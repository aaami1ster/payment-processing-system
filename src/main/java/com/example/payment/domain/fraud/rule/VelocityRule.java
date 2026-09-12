package com.example.payment.domain.fraud.rule;

import com.example.payment.domain.fraud.FraudContext;
import com.example.payment.domain.fraud.FraudRule;
import com.example.payment.domain.fraud.RuleId;
import com.example.payment.domain.fraud.RuleResult;
import java.util.Optional;

/**
 * Rule 2: decline when the caller-supplied count of APPROVED/FLAGGED transactions
 * in the inclusive {@code [now - 60s, now]} window is already {@code >=} threshold (default 3).
 *
 * <p>Counting and window boundaries are applied by the service when building
 * {@link FraudContext#recentTxnCount()}; see {@link com.example.payment.domain.fraud.VelocityWindow}.
 */
public final class VelocityRule implements FraudRule {

    private static final int MIN_THRESHOLD = 1;

    private final int threshold;

    public VelocityRule(int threshold) {
        if (threshold < MIN_THRESHOLD) {
            throw new IllegalArgumentException("velocity threshold must be >= " + MIN_THRESHOLD);
        }
        this.threshold = threshold;
    }

    @Override
    public RuleId id() {
        return RuleId.VELOCITY;
    }

    @Override
    public Optional<RuleResult> evaluate(FraudContext context) {
        if (context.recentTxnCount() >= threshold) {
            return Optional.of(RuleResult.decline(
                    id(),
                    "Too many successful authorizations in the velocity window"));
        }
        return Optional.empty();
    }
}
