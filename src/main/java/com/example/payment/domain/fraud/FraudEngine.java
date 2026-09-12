package com.example.payment.domain.fraud;

import com.example.payment.domain.transaction.TransactionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs all registered {@link FraudRule}s and aggregates by severity.
 * Precedence: any DECLINE → {@link TransactionStatus#DECLINED}; else any FLAG →
 * {@link TransactionStatus#FLAGGED}; else {@link TransactionStatus#APPROVED}.
 * All rules always evaluate; triggered results are retained for audit even when a decline wins.
 */
public final class FraudEngine {

    private final List<FraudRule> rules;

    public FraudEngine(List<FraudRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    public FraudDecision evaluate(FraudContext context) {
        Objects.requireNonNull(context, "context");
        List<RuleResult> triggered = new ArrayList<>();
        for (FraudRule rule : rules) {
            rule.evaluate(context).ifPresent(triggered::add);
        }
        return new FraudDecision(aggregate(triggered), List.copyOf(triggered));
    }

    private static TransactionStatus aggregate(List<RuleResult> triggered) {
        boolean anyFlag = false;
        for (RuleResult result : triggered) {
            if (result.severity() == Severity.DECLINE) {
                return TransactionStatus.DECLINED;
            }
            if (result.severity() == Severity.FLAG) {
                anyFlag = true;
            }
        }
        return anyFlag ? TransactionStatus.FLAGGED : TransactionStatus.APPROVED;
    }
}
