package io.potok.trigger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Single-consumer guard for the Telegram updates long-poll. Telegram delivers
 * each update exactly once, so two replicas long-polling the same bot would
 * race and tear the offset; we claim a session-scoped {@code pg_advisory_lock}
 * on a dedicated connection that is held for the lifetime of the poller, then
 * released when the bean shuts down. Single-instance behavior is unchanged.
 */
@Component
public class TelegramPollLock {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollLock.class);
    private static final int NAMESPACE = "potok-telegram-updates".hashCode();
    private static final int KEY = 1;

    private final DataSource dataSource;
    private Connection connection;

    public TelegramPollLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * @return true when this caller now owns the lock. The connection is held
     * until {@link #release()}. Repeated calls without release are no-ops once
     * we own the lock.
     */
    public synchronized boolean tryAcquire() {
        if (connection != null) {
            return true;
        }
        Connection candidate = null;
        try {
            candidate = dataSource.getConnection();
            candidate.setAutoCommit(true);
            try (PreparedStatement stmt = candidate.prepareStatement(
                    "select pg_try_advisory_lock(?, ?)")) {
                stmt.setInt(1, NAMESPACE);
                stmt.setInt(2, KEY);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        connection = candidate;
                        return true;
                    }
                }
            }
            candidate.close();
            return false;
        } catch (SQLException e) {
            log.warn("telegram_poll_lock_acquire_failed error={}", e.getMessage());
            if (candidate != null) {
                try { candidate.close(); } catch (SQLException ignored) {}
            }
            return false;
        }
    }

    public synchronized void release() {
        if (connection == null) {
            return;
        }
        try (PreparedStatement stmt = connection.prepareStatement(
                "select pg_advisory_unlock(?, ?)")) {
            stmt.setInt(1, NAMESPACE);
            stmt.setInt(2, KEY);
            stmt.execute();
        } catch (SQLException e) {
            log.warn("telegram_poll_lock_release_failed error={}", e.getMessage());
        } finally {
            try { connection.close(); } catch (SQLException ignored) {}
            connection = null;
        }
    }
}
