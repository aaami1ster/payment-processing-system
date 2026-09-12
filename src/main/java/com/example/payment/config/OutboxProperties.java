package com.example.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public long getMongoTimeoutMs() {
        return mongoTimeoutMs;
    }

    public void setMongoTimeoutMs(long mongoTimeoutMs) {
        this.mongoTimeoutMs = mongoTimeoutMs;
    }

    public int getCircuitFailureRateThreshold() {
        return circuitFailureRateThreshold;
    }

    public void setCircuitFailureRateThreshold(int circuitFailureRateThreshold) {
        this.circuitFailureRateThreshold = circuitFailureRateThreshold;
    }

    public int getCircuitSlidingWindowSize() {
        return circuitSlidingWindowSize;
    }

    public void setCircuitSlidingWindowSize(int circuitSlidingWindowSize) {
        this.circuitSlidingWindowSize = circuitSlidingWindowSize;
    }

    public long getCircuitWaitOpenMs() {
        return circuitWaitOpenMs;
    }

    public void setCircuitWaitOpenMs(long circuitWaitOpenMs) {
        this.circuitWaitOpenMs = circuitWaitOpenMs;
    }
}
