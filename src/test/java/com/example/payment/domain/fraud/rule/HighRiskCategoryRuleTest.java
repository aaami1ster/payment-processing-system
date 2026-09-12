package com.example.payment.domain.fraud.rule;

import static com.example.payment.domain.fraud.FraudTestSupport.NOW;
import static com.example.payment.domain.fraud.FraudTestSupport.context;
import static com.example.payment.domain.fraud.FraudTestSupport.matureUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.domain.fraud.Category;
import com.example.payment.domain.fraud.RuleId;
import com.example.payment.domain.fraud.Severity;
import java.math.BigDecimal;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class HighRiskCategoryRuleTest {

    private final HighRiskCategoryRule rule = new HighRiskCategoryRule(
            EnumSet.of(Category.GAMBLING, Category.CRYPTO, Category.CASH_ADVANCE, Category.ADULT),
            new BigDecimal("5000"));

    @Test
    void declinesHighRiskCategoryAboveThreshold() {
        var result = rule.evaluate(context(matureUser(), "5000.01", Category.CRYPTO, 0, NOW));

        assertThat(result).isPresent();
        assertThat(result.get().ruleId()).isEqualTo(RuleId.HIGH_RISK_CATEGORY);
        assertThat(result.get().severity()).isEqualTo(Severity.DECLINE);
    }

    @Test
    void allowsHighRiskCategoryAtThreshold() {
        var result = rule.evaluate(context(matureUser(), "5000", Category.GAMBLING, 0, NOW));

        assertThat(result).isEmpty();
    }

    @Test
    void allowsHighRiskCategoryBelowThreshold() {
        var result = rule.evaluate(context(matureUser(), "4999.99", Category.ADULT, 0, NOW));

        assertThat(result).isEmpty();
    }

    @Test
    void allowsLowRiskCategoryAboveThreshold() {
        var result = rule.evaluate(context(matureUser(), "9000", Category.GROCERIES, 0, NOW));

        assertThat(result).isEmpty();
    }

    @Test
    void declinesEachConfiguredHighRiskCategory() {
        for (Category category : EnumSet.of(
                Category.GAMBLING, Category.CRYPTO, Category.CASH_ADVANCE, Category.ADULT)) {
            assertThat(rule.evaluate(context(matureUser(), "5000.01", category, 0, NOW)))
                    .as("category %s", category)
                    .isPresent();
        }
    }

    @Test
    void idIsHighRiskCategory() {
        assertThat(rule.id()).isEqualTo(RuleId.HIGH_RISK_CATEGORY);
    }
}
