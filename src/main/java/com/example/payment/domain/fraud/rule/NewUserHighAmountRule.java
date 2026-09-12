package com.example.payment.domain.fraud.rule;

import com.example.payment.domain.fraud.FraudContext;
import com.example.payment.domain.fraud.FraudRule;
import com.example.payment.domain.fraud.RuleId;
import com.example.payment.domain.fraud.RuleResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Rule 4: flag (but allow) when amount exceeds threshold and the user is younger than max age
 * ({@code user.createdAt > now - maxAge}). Exactly {@code maxAge} old does not flag.
 */
@RequiredArgsConstructor
public final class NewUserHighAmountRule implements FraudRule {

    private final @NonNull BigDecimal amountThreshold;
    private final @NonNull Duration maxAge;

    @Override
    public RuleId id() {
        return RuleId.NEW_USER_HIGH_AMOUNT;
    }

    @Override
    public Optional<RuleResult> evaluate(FraudContext context) {
        Instant cutoff = context.now().minus(maxAge);
        boolean youngUser = context.user().getCreatedAt().isAfter(cutoff);
        if (context.amount().compareTo(amountThreshold) > 0 && youngUser) {
            return Optional.of(RuleResult.flag(
                    id(),
                    "High amount from a recently created user"));
        }
        return Optional.empty();
    }
}
