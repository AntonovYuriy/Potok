package io.potok.trigger;

import io.potok.definition.Workflow;
import io.potok.execution.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Cron fire with multi-instance dedupe: the replica that wins the
 * (workflow, scheduled minute) claim starts the execution; others no-op.
 * Cron granularity is minute-level (5-field specs get second 0).
 */
@Service
public class CronFireService {

    private static final Logger log = LoggerFactory.getLogger(CronFireService.class);

    private final TriggerLocks locks;
    private final ExecutionService executions;

    public CronFireService(TriggerLocks locks, ExecutionService executions) {
        this.locks = locks;
        this.executions = executions;
    }

    @Transactional
    public void fire(Workflow workflow) {
        if (!locks.claimCronFire(workflow.id(), Instant.now())) {
            log.info("cron_fire_deduped workflowId={} (another instance fired this minute)", workflow.id());
            return;
        }
        executions.start(workflow, Map.of(
                "type", "cron",
                "cron", workflow.definition().trigger().cron(),
                "payload", Map.of()));
    }
}
