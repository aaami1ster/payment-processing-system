package com.example.payment.domain.fraud;

import com.example.payment.domain.transaction.TransactionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Authoritative helpers for Rule 2 (velocity): inclusive sliding window and which statuses count.
 *
 * <p>Window: {@code [now - window, now]} inclusive. Count only {@link TransactionStatus#APPROVED}
 * and {@link TransactionStatus#FLAGGED}. The service supplies {@link FraudContext#recentTxnCount()};
 * this type documents and tests boundary semantics without I/O.
 */
public final class VelocityWindow {

    private VelocityWindow() {}

    /**
     * Inclusive check: {@code createdAt >= now - window && createdAt <= now}.
     */
    public static boolean isWithinWindow(Instant createdAt, Instant now, Duration window) {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(window, "window");
        Instant windowStart = now.minus(window);
        return !createdAt.isBefore(windowStart) && !createdAt.isAfter(now);
    }

    /**
     * Only successful authorization outcomes extend the velocity count.
     */
    public static boolean countsTowardVelocity(TransactionStatus status) {
        Objects.requireNonNull(status, "status");
        return status == TransactionStatus.APPROVED || status == TransactionStatus.FLAGGED;
    }
}
