package com.example.payment.domain.fraud.rule;

import com.example.payment.domain.fraud.Category;
import com.example.payment.domain.fraud.FraudContext;
import com.example.payment.domain.fraud.FraudRule;
import com.example.payment.domain.fraud.RuleId;
import com.example.payment.domain.fraud.RuleResult;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Rule 3: decline when category is high-risk and amount exceeds the configured threshold.
 */
public final class HighRiskCategoryRule implements FraudRule {

    private final Set<Category> highRiskCategories;
    private final BigDecimal amountThreshold;

    public HighRiskCategoryRule(Set<Category> highRiskCategories, BigDecimal amountThreshold) {
        this.highRiskCategories = Set.copyOf(Objects.requireNonNull(highRiskCategories, "highRiskCategories"));
        this.amountThreshold = Objects.requireNonNull(amountThreshold, "amountThreshold");
    }

    @Override
    public RuleId id() {
        return RuleId.HIGH_RISK_CATEGORY;
    }

    @Override
    public Optional<RuleResult> evaluate(FraudContext context) {
        if (highRiskCategories.contains(context.category())
                && context.amount().compareTo(amountThreshold) > 0) {
            return Optional.of(RuleResult.decline(
                    id(),
                    "High-risk category with amount above threshold"));
        }
        return Optional.empty();
    }
}
