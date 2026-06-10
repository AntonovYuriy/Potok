package io.potok.execution;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Nightly retention: finished executions (and their steps) older than
 * POTOK_RETENTION_DAYS are deleted. Executions still referenced from the DLQ
 * are kept until the DLQ entry is handled; poll/rss dedupe state is untouched.
 */
@Component
public class RetentionPurger {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurger.class);

    private final JdbcClient jdbc;
    private final int retentionDays;
    private final Counter purged;

    public RetentionPurger(JdbcClient jdbc,
                           @Value("${potok.retention.days:30}") int retentionDays,
                           MeterRegistry registry) {
        this.jdbc = jdbc;
        this.retentionDays = retentionDays;
        this.purged = Counter.builder("potok.purged")
                .description("Finished executions removed by retention")
                .register(registry);
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void nightly() {
        purge();
    }

    @Transactional
    public int purge() {
        OffsetDateTime cutoff = cutoff(OffsetDateTime.now());
        jdbc.sql("""
                        delete from step_execution se
                        using workflow_execution we
                        where se.execution_id = we.id
                          and we.status in ('SUCCEEDED', 'FAILED')
                          and we.finished_at < :cutoff
                          and not exists (select 1 from dead_letter dl where dl.execution_id = we.id)
                        """)
                .param("cutoff", cutoff)
                .update();
        int executions = jdbc.sql("""
                        delete from workflow_execution we
                        where we.status in ('SUCCEEDED', 'FAILED')
                          and we.finished_at < :cutoff
                          and not exists (select 1 from dead_letter dl where dl.execution_id = we.id)
                        """)
                .param("cutoff", cutoff)
                .update();
        if (executions > 0) {
            purged.increment(executions);
            log.info("retention_purged executions={} olderThanDays={}", executions, retentionDays);
        }
        return executions;
    }

    public OffsetDateTime cutoff(OffsetDateTime now) {
        return now.minusDays(retentionDays);
    }
}
