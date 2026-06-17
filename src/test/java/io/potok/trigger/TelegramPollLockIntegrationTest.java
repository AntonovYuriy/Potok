package io.potok.trigger;

import io.potok.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two {@link TelegramPollLock} instances sharing the same Postgres database:
 * the first {@code tryAcquire} wins, the second is rejected until the first
 * releases. This pins the M6 "single getUpdates consumer across replicas"
 * guarantee — a second app instance attached to the same DB cannot race the
 * offset.
 *
 * The poller bean is disabled here (the lock is exercised directly) so the
 * production {@code TelegramPollLock} bean is not the one we contend with.
 */
class TelegramPollLockIntegrationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void disablePoller(DynamicPropertyRegistry registry) {
        registry.add("potok.telegram.poll-updates", () -> "false");
    }

    @Autowired
    private DataSource dataSource;

    private TelegramPollLock a;
    private TelegramPollLock b;

    @AfterEach
    void releaseBoth() {
        if (a != null) { a.release(); }
        if (b != null) { b.release(); }
    }

    @Test
    void onlyOneAcquiresUntilTheOwnerReleases() {
        a = new TelegramPollLock(dataSource);
        b = new TelegramPollLock(dataSource);

        assertThat(a.tryAcquire()).as("first acquire").isTrue();
        assertThat(b.tryAcquire()).as("second contender while held").isFalse();

        a.release();

        assertThat(b.tryAcquire()).as("acquire after release").isTrue();
        // and a second tryAcquire on the new owner is idempotent (no-op true)
        assertThat(b.tryAcquire()).as("idempotent on owner").isTrue();
    }

    @Test
    void releaseIsIdempotentEvenWithoutAcquire() {
        a = new TelegramPollLock(dataSource);
        // release without acquire — must not blow up
        a.release();
        assertThat(a.tryAcquire()).isTrue();
    }
}
