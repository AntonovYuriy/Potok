package io.potok.trigger;

import io.potok.definition.Workflow;
import io.potok.definition.WorkflowDefinition;
import io.potok.definition.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/**
 * Schedules poll/rss trigger ticks from the database, mirroring
 * CronTriggerScheduler: refresh on workflow change events plus a periodic
 * re-read. Tasks stop with the TaskScheduler on context close (graceful
 * shutdown); fire/state writes are transactional in PollerService, so a
 * restart never double-fires.
 */
@Component
public class PollerScheduler {

    private static final Logger log = LoggerFactory.getLogger(PollerScheduler.class);

    private final WorkflowRepository workflows;
    private final PollerService pollerService;
    private final TaskScheduler taskScheduler;

    private record Registration(Duration interval, String kind, ScheduledFuture<?> future) {
    }

    private final Map<UUID, Registration> registrations = new HashMap<>();

    public PollerScheduler(WorkflowRepository workflows,
                           PollerService pollerService,
                           TaskScheduler taskScheduler) {
        this.workflows = workflows;
        this.pollerService = pollerService;
        this.taskScheduler = taskScheduler;
    }

    /**
     * AFTER_COMMIT: the event is published inside the create/update transaction;
     * refreshing earlier lets the poller's immediate first tick read the database
     * before the workflow row is committed (baseline silently skipped — flaked on CI).
     */
    @TransactionalEventListener(value = WorkflowsChangedEvent.class,
            phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public synchronized void onWorkflowsChanged() {
        refresh();
    }

    @Scheduled(initialDelayString = "PT3S", fixedDelayString = "${potok.cron.refresh-interval}")
    public synchronized void refresh() {
        Map<UUID, Workflow> current = new HashMap<>();
        for (Workflow workflow : workflows.findEnabledWithPollers()) {
            current.put(workflow.id(), workflow);
        }

        Set<UUID> stale = new HashSet<>(registrations.keySet());
        stale.removeAll(current.keySet());
        for (UUID id : stale) {
            registrations.remove(id).future().cancel(false);
            log.info("poller_unscheduled workflowId={}", id);
        }

        for (Workflow workflow : current.values()) {
            WorkflowDefinition.Trigger trigger = workflow.definition().trigger();
            Duration interval = trigger.poll() != null ? trigger.poll().interval() : trigger.rss().interval();
            String kind = trigger.poll() != null ? "poll" : "rss";
            Registration existing = registrations.get(workflow.id());
            if (existing != null) {
                if (existing.interval().equals(interval) && existing.kind().equals(kind)) {
                    continue;
                }
                existing.future().cancel(false);
            }
            ScheduledFuture<?> future = taskScheduler.scheduleWithFixedDelay(
                    () -> tick(workflow.id()), interval);
            registrations.put(workflow.id(), new Registration(interval, kind, future));
            log.info("poller_scheduled workflowId={} workflow={} kind={} interval={}",
                    workflow.id(), workflow.name(), kind, interval);
        }
    }

    /** Re-reads the workflow at tick time so disable/update take effect immediately. */
    private void tick(UUID workflowId) {
        try {
            workflows.findById(workflowId)
                    .filter(Workflow::enabled)
                    .ifPresent(workflow -> {
                        if (workflow.definition().trigger().poll() != null) {
                            pollerService.pollHttp(workflow);
                        } else if (workflow.definition().trigger().rss() != null) {
                            pollerService.pollRss(workflow);
                        }
                    });
        } catch (Exception e) {
            // never kill the scheduled task; next tick retries
            log.error("poller_tick_failed workflowId={}", workflowId, e);
        }
    }
}
