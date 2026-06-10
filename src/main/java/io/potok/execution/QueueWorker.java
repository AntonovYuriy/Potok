package io.potok.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

/**
 * Pool of virtual-thread pollers over the Postgres job queue.
 * Each worker loops: claim one due job (SKIP LOCKED), process it, repeat;
 * an empty poll sleeps for poll-interval. Crash recovery needs no startup
 * sweep — expired locked_until leases simply become claimable again.
 */
@Component
public class QueueWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);

    private final JobQueueRepository jobQueue;
    private final JobProcessor processor;
    private final QueueProperties properties;
    private final List<Thread> workers = new ArrayList<>();
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
                    processor.process(job.get());
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
        workers.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
