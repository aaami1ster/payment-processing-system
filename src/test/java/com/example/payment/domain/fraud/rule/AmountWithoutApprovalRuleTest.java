package com.example.payment.domain.fraud.rule;

import static com.example.payment.domain.fraud.FraudTestSupport.NOW;
import static com.example.payment.domain.fraud.FraudTestSupport.context;
import static com.example.payment.domain.fraud.FraudTestSupport.matureUser;
import static com.example.payment.domain.fraud.FraudTestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.domain.fraud.Category;
import com.example.payment.domain.fraud.RuleId;
import com.example.payment.domain.fraud.Severity;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AmountWithoutApprovalRuleTest {

    private final AmountWithoutApprovalRule rule = new AmountWithoutApprovalRule(new BigDecimal("10000"));

    @Test
    void declinesWhenAmountExceedsThresholdAndNoPriorApproval_nullLimit() {
        var result = rule.evaluate(context(matureUser(), "10000.01", Category.GROCERIES, 0, NOW));

        assertThat(result).isPresent();
        assertThat(result.get().ruleId()).isEqualTo(RuleId.AMOUNT_WITHOUT_APPROVAL);
        assertThat(result.get().severity()).isEqualTo(Severity.DECLINE);
    }

    @Test
    void declinesWhenAmountExceedsThresholdAndZeroLimit() {
        var result = rule.evaluate(context(
                user(NOW.minusSeconds(86_400L * 100), BigDecimal.ZERO),
                "12000",
                Category.TRAVEL,
                0,
                NOW));

        assertThat(result).isPresent();
        assertThat(result.get().ruleId()).isEqualTo(RuleId.AMOUNT_WITHOUT_APPROVAL);
    }

    @Test
    void allowsWhenAmountExceedsThresholdButWithinPreApprovedLimit() {
        var result = rule.evaluate(context(
                user(NOW.minusSeconds(86_400L * 100), new BigDecimal("15000")),
                "12000",
                Category.ELECTRONICS,
                0,
                NOW));

        assertThat(result).isEmpty();
    }

    @Test
    void declinesWhenAmountExceedsBothThresholdAndPreApprovedLimit() {
        var result = rule.evaluate(context(
                user(NOW.minusSeconds(86_400L * 100), new BigDecimal("15000")),
                "16000",
                Category.ELECTRONICS,
                0,
                NOW));

        assertThat(result).isPresent();
        assertThat(result.get().severity()).isEqualTo(Severity.DECLINE);
    }

    @Test
    void allowsWhenAmountEqualsThreshold() {
        var result = rule.evaluate(context(matureUser(), "10000", Category.OTHER, 0, NOW));

        assertThat(result).isEmpty();
    }

    @Test
    void allowsWhenAmountBelowThresholdRegardlessOfLimit() {
        var result = rule.evaluate(context(matureUser(), "9999.99", Category.OTHER, 0, NOW));

        assertThat(result).isEmpty();
    }

    @Test
    void idIsAmountWithoutApproval() {
        assertThat(rule.id()).isEqualTo(RuleId.AMOUNT_WITHOUT_APPROVAL);
    }
}
