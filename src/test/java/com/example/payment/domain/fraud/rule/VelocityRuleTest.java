package com.example.payment.domain.fraud.rule;

import static com.example.payment.domain.fraud.FraudTestSupport.NOW;
import static com.example.payment.domain.fraud.FraudTestSupport.context;
import static com.example.payment.domain.fraud.FraudTestSupport.matureUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.domain.fraud.Category;
import com.example.payment.domain.fraud.RuleId;
import com.example.payment.domain.fraud.Severity;
import org.junit.jupiter.api.Test;

class VelocityRuleTest {

    private final VelocityRule rule = new VelocityRule(3);

    @Test
    void declinesWhenRecentCountAlreadyAtThreshold() {
        var result = rule.evaluate(context(matureUser(), "100", Category.GROCERIES, 3, NOW));

        assertThat(result).isPresent();
        assertThat(result.get().ruleId()).isEqualTo(RuleId.VELOCITY);
        assertThat(result.get().severity()).isEqualTo(Severity.DECLINE);
    }

    @Test
    void declinesWhenRecentCountAboveThreshold() {
        var result = rule.evaluate(context(matureUser(), "100", Category.GROCERIES, 4, NOW));

        assertThat(result).isPresent();
    }

    @Test
    void allowsWhenRecentCountBelowThreshold() {
        var result = rule.evaluate(context(matureUser(), "100", Category.GROCERIES, 2, NOW));

        assertThat(result).isEmpty();
    }

    @Test
    void allowsWhenNoRecentTransactions() {
        var result = rule.evaluate(context(matureUser(), "100", Category.GROCERIES, 0, NOW));

        assertThat(result).isEmpty();
    }

    @Test
    void idIsVelocity() {
        assertThat(rule.id()).isEqualTo(RuleId.VELOCITY);
    }
}
