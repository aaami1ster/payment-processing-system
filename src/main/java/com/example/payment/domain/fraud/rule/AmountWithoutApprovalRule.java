package com.example.payment.domain.fraud.rule;

import com.example.payment.domain.fraud.FraudContext;
import com.example.payment.domain.fraud.FraudRule;
import com.example.payment.domain.fraud.RuleId;
import com.example.payment.domain.fraud.RuleResult;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Rule 1: decline when amount exceeds 10_000 and exceeds the user's pre-approved limit.
 * {@code null} or {@code 0} limit means no prior approval.
 */
@RequiredArgsConstructor
public final class AmountWithoutApprovalRule implements FraudRule {

    private final @NonNull BigDecimal threshold;

    @Override
    public RuleId id() {
        return RuleId.AMOUNT_WITHOUT_APPROVAL;
    }

    @Override
    public Optional<RuleResult> evaluate(FraudContext context) {
        BigDecimal amount = context.amount();
        BigDecimal limit = effectiveLimit(context.user().getPreApprovedTransactionLimit());
        if (amount.compareTo(threshold) > 0 && amount.compareTo(limit) > 0) {
            return Optional.of(RuleResult.decline(
                    id(),
                    "Amount exceeds threshold without sufficient pre-approved limit"));
        }
        return Optional.empty();
    }

    private static BigDecimal effectiveLimit(BigDecimal preApproved) {
        if (preApproved == null || preApproved.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return preApproved;
    }
}
