package com.example.payment.domain.fraud;

import java.util.Optional;

/**
 * Strategy for one fraud policy. Implementations must be pure (no I/O).
 */
public interface FraudRule {

    RuleId id();

    /**
     * @return triggered result, or empty if the rule does not apply
     */
    Optional<RuleResult> evaluate(FraudContext context);
}
