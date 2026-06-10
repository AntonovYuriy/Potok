package io.potok.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "potok.queue")
public record QueueProperties(
        int workers,
        Duration pollInterval,
        Duration lockTimeout,
        Duration retryBackoff,
        int defaultMaxAttempts) {
}
