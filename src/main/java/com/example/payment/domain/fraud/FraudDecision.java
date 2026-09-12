package com.example.payment.domain.fraud;

import com.example.payment.domain.transaction.TransactionStatus;
import java.util.List;
import java.util.Objects;

/**
 * Aggregated outcome of evaluating all fraud rules for one payment attempt.
 */
public record FraudDecision(TransactionStatus status, List<RuleResult> triggered) {

    public FraudDecision {
        Objects.requireNonNull(status, "status");
        triggered = List.copyOf(Objects.requireNonNull(triggered, "triggered"));
    }
}
