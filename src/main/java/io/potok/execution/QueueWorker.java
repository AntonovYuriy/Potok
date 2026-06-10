package io.potok.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Pool of virtual-thread pollers over the Postgres job queue.
 * Each worker loops: claim one due job (SKIP LOCKED), process it, repeat;
 * an empty poll sleeps for poll-interval.
 *
 * Graceful shutdown (SIGTERM → context close → stop()): polling stops at
 * once, in-flight steps get up to shutdown-grace to finish, then the leases
 * of whatever is still running are released (locked_until = now) so another
 * instance picks those jobs up immediately instead of waiting out the lease.
 * Crash (no shutdown): expired leases become claimable again on their own.
 */
@Component
public class QueueWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);

    private final JobQueueRepository jobQueue;
    private final JobProcessor processor;
    private final QueueProperties properties;
    private final List<Thread> workers = new ArrayList<>();
    private final Map<Thread, QueuedJob> inFlight = new ConcurrentHashMap<>();
    private volatile boolean running;

    public QueueWorker(JobQueueRepository jobQueue, JobProcessor processor, QueueProperties properties) {
        this.jobQueue = jobQueue;
        this.processor = processor;
        this.properties = properties;
    }

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < properties.workers(); i++) {
            Thread worker = Thread.ofVirtual()
                    .name("potok-worker-" + i)
                    .start(this::pollLoop);
            workers.add(worker);
        }
        log.info("queue_workers_started count={} pollInterval={} lockTimeout={}",
                properties.workers(), properties.pollInterval(), properties.lockTimeout());
    }

    private void pollLoop() {
        while (running) {
            try {
                Optional<QueuedJob> job = jobQueue.pollAndLock(properties.lockTimeout());
                if (job.isPresent()) {
                    inFlight.put(Thread.currentThread(), job.get());
                    try {
                        processor.process(job.get());
                    } finally {
                        inFlight.remove(Thread.currentThread());
                    }
                } else {
                    LockSupport.parkNanos(properties.pollInterval().toNanos());
                }
            } catch (Exception e) {
                // Never let a poison job kill the worker; its lease will expire and it will retry.
                log.error("worker_iteration_failed", e);
                LockSupport.parkNanos(properties.pollInterval().toNanos());
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        workers.forEach(LockSupport::unpark);

        Duration grace = properties.shutdownGrace();
        Instant deadline = Instant.now().plus(grace);
        log.info("queue_workers_stopping inFlight={} grace={}", inFlight.size(), grace);
        for (Thread worker : workers) {
            long remaining = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
            try {
                worker.join(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Whatever is still running will not finish in this JVM — hand its jobs over now.
        for (QueuedJob job : inFlight.values()) {
            try {
                jobQueue.releaseLock(job.id());
                log.warn("job_lock_released_on_shutdown jobId={} executionId={} step={}",
                        job.id(), job.executionId(), job.stepName());
            } catch (Exception e) {
                log.error("job_lock_release_failed jobId={}", job.id(), e);
            }
        }
        inFlight.clear();
        workers.clear();
        log.info("queue_workers_stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
