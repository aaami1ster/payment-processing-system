package com.example.payment.domain.fraud;

import java.util.Objects;

/**
 * Result of a single rule that fired. Empty {@link java.util.Optional} from a rule means it did not trigger.
 */
public record RuleResult(RuleId ruleId, Severity severity, String reason) {

    public RuleResult {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(reason, "reason");
    }

    public static RuleResult decline(RuleId ruleId, String reason) {
        return new RuleResult(ruleId, Severity.DECLINE, reason);
    }

    public static RuleResult flag(RuleId ruleId, String reason) {
        return new RuleResult(ruleId, Severity.FLAG, reason);
    }
}
