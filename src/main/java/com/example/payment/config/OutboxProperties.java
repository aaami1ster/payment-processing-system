package com.example.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "payment.outbox.publisher")
public class OutboxProperties {

    /**
     * When false, the scheduled publisher bean is not registered (e.g. API tests without Mongo).
     */
    private boolean enabled = true;

    private int batchSize = 50;

    private long pollIntervalMs = 1000;

    /** Socket/server selection timeouts applied to Mongo publish path (milliseconds). */
    private long mongoTimeoutMs = 2000;

    private int circuitFailureRateThreshold = 50;

    private int circuitSlidingWindowSize = 10;

    private long circuitWaitOpenMs = 10_000;

}
