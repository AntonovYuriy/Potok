package io.potok.trigger;

import io.potok.definition.Workflow;
import io.potok.definition.WorkflowRepository;
import io.potok.definition.YamlDefinitionParser;
import io.potok.execution.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/**
 * Schedules cron-triggered workflows from the database. The schedule registry
 * refreshes on workflow changes (in-process event) and on a fixed interval,
 * so external DB edits are picked up without a restart.
 */
@Component
public class CronTriggerScheduler {

    private static final Logger log = LoggerFactory.getLogger(CronTriggerScheduler.class);

    private final WorkflowRepository workflows;
    private final CronFireService fireService;
    private final TaskScheduler taskScheduler;

    private record Registration(String cron, ScheduledFuture<?> future) {
    }

    private final Map<UUID, Registration> registrations = new HashMap<>();

    public CronTriggerScheduler(WorkflowRepository workflows,
                                CronFireService fireService,
                                TaskScheduler taskScheduler) {
        this.workflows = workflows;
        this.fireService = fireService;
        this.taskScheduler = taskScheduler;
    }

    /** AFTER_COMMIT — see PollerScheduler: schedules must only see committed workflows. */
    @TransactionalEventListener(value = WorkflowsChangedEvent.class,
            phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public synchronized void onWorkflowsChanged() {
        refresh();
    }

    @Scheduled(initialDelayString = "PT3S", fixedDelayString = "${potok.cron.refresh-interval}")
    public synchronized void refresh() {
        Map<UUID, Workflow> current = new HashMap<>();
        for (Workflow workflow : workflows.findEnabledWithCron()) {
            current.put(workflow.id(), workflow);
        }

        Set<UUID> stale = new HashSet<>(registrations.keySet());
        stale.removeAll(current.keySet());
        for (UUID id : stale) {
            registrations.remove(id).future().cancel(false);
            log.info("cron_unscheduled workflowId={}", id);
        }

        for (Workflow workflow : current.values()) {
            String cron = workflow.definition().trigger().cron();
            Registration existing = registrations.get(workflow.id());
            if (existing != null) {
                if (existing.cron().equals(cron)) {
                    continue;
                }
                existing.future().cancel(false);
            }
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> fire(workflow.id()),
                    new CronTrigger(YamlDefinitionParser.normalizeCron(cron)));
            registrations.put(workflow.id(), new Registration(cron, future));
            log.info("cron_scheduled workflowId={} workflow={} cron='{}'", workflow.id(), workflow.name(), cron);
        }
    }

    /** Re-reads the workflow at fire time: a disable between refreshes must not start executions. */
    private void fire(UUID workflowId) {
        workflows.findById(workflowId)
                .filter(Workflow::enabled)
                .ifPresent(fireService::fire);
    }
}
