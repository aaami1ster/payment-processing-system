package com.example.payment.domain.fraud;

import com.example.payment.domain.user.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot for fraud evaluation. No I/O — callers gather data (including velocity count).
 */
public record FraudContext(
        User user, BigDecimal amount, Category category, int recentTxnCount, Instant now) {

    public FraudContext {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(category, "category");
        if (recentTxnCount < 0) {
            throw new IllegalArgumentException("recentTxnCount must be >= 0");
        }
        Objects.requireNonNull(now, "now");
    }
}
