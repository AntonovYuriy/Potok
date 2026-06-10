package io.potok.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** telegramNotify maps to POTOK_DLQ_TELEGRAM; notifyInterval is the rate-limit window. */
@ConfigurationProperties(prefix = "potok.dlq")
public record DlqProperties(boolean telegramNotify, Duration notifyInterval) {
}
