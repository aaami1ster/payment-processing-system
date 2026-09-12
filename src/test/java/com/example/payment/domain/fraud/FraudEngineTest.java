package com.example.payment.domain.fraud;

import static com.example.payment.domain.fraud.FraudTestSupport.NOW;
import static com.example.payment.domain.fraud.FraudTestSupport.context;
import static com.example.payment.domain.fraud.FraudTestSupport.matureUser;
import static com.example.payment.domain.fraud.FraudTestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.domain.fraud.rule.AmountWithoutApprovalRule;
import com.example.payment.domain.fraud.rule.HighRiskCategoryRule;
import com.example.payment.domain.fraud.rule.NewUserHighAmountRule;
import com.example.payment.domain.fraud.rule.VelocityRule;
import com.example.payment.domain.transaction.TransactionStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FraudEngineTest {

    private final FraudEngine engine = new FraudEngine(List.of(
            new AmountWithoutApprovalRule(new BigDecimal("10000")),
            new VelocityRule(3),
            new HighRiskCategoryRule(
                    EnumSet.of(Category.CRYPTO, Category.CASH_ADVANCE),
                    new BigDecimal("5000")),
            new NewUserHighAmountRule(new BigDecimal("5000"), Duration.ofDays(30))));

    @Test
    void noRulesTriggered_approved() {
        FraudDecision decision =
                engine.evaluate(context(matureUser(), "100", Category.GROCERIES, 0, NOW));

        assertThat(decision.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(decision.triggered()).isEmpty();
    }

    @Test
    void emptyRuleList_approved() {
        FraudDecision decision =
                new FraudEngine(List.of()).evaluate(context(matureUser(), "99999", Category.CRYPTO, 10, NOW));

        assertThat(decision.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(decision.triggered()).isEmpty();
    }

    @Test
    void flagOnly_flagged() {
        FraudDecision decision = engine.evaluate(context(
                user(NOW.minus(Duration.ofDays(10)), null), "6000", Category.ELECTRONICS, 0, NOW));

        assertThat(decision.status()).isEqualTo(TransactionStatus.FLAGGED);
        assertThat(decision.triggered()).extracting(RuleResult::ruleId).containsExactly(RuleId.NEW_USER_HIGH_AMOUNT);
    }

    @Test
    void declineOnly_declined() {
        FraudDecision decision =
                engine.evaluate(context(matureUser(), "100", Category.GROCERIES, 3, NOW));

        assertThat(decision.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(decision.triggered()).extracting(RuleResult::ruleId).containsExactly(RuleId.VELOCITY);
    }

    @Test
    void flagPlusDecline_declinedButRetainsAllTriggered() {
        // amount 12000, CRYPTO, user age 10 days → Rules 1, 3, 4
        FraudDecision decision = engine.evaluate(context(
                user(NOW.minus(Duration.ofDays(10)), null), "12000", Category.CRYPTO, 0, NOW));

        assertThat(decision.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(decision.triggered())
                .extracting(RuleResult::ruleId)
                .containsExactlyInAnyOrder(
                        RuleId.AMOUNT_WITHOUT_APPROVAL,
                        RuleId.HIGH_RISK_CATEGORY,
                        RuleId.NEW_USER_HIGH_AMOUNT);
    }

    @Test
    void multipleDeclines_declinedAndAllPresent() {
        FraudDecision decision = engine.evaluate(context(
                matureUser(), "12000", Category.CRYPTO, 3, NOW));

        assertThat(decision.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(decision.triggered())
                .extracting(RuleResult::ruleId)
                .containsExactlyInAnyOrder(
                        RuleId.AMOUNT_WITHOUT_APPROVAL,
                        RuleId.VELOCITY,
                        RuleId.HIGH_RISK_CATEGORY);
    }

    @Test
    void allRulesAlwaysEvaluate_evenAfterDecline() {
        FraudRule first = new FraudRule() {
            @Override
            public RuleId id() {
                return RuleId.VELOCITY;
            }

            @Override
            public Optional<RuleResult> evaluate(FraudContext context) {
                return Optional.of(RuleResult.decline(id(), "first"));
            }
        };
        FraudRule second = new FraudRule() {
            @Override
            public RuleId id() {
                return RuleId.NEW_USER_HIGH_AMOUNT;
            }

            @Override
            public Optional<RuleResult> evaluate(FraudContext context) {
                return Optional.of(RuleResult.flag(id(), "second"));
            }
        };
        FraudEngine custom = new FraudEngine(List.of(first, second));

        FraudDecision decision =
                custom.evaluate(context(matureUser(), "1", Category.OTHER, 0, NOW));

        assertThat(decision.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(decision.triggered()).hasSize(2);
    }

    @Test
    void preApprovedLimitAllowsHighAmountThatWouldOtherwiseDecline() {
        FraudDecision decision = engine.evaluate(context(
                user(NOW.minus(Duration.ofDays(100)), new BigDecimal("15000")),
                "12000",
                Category.ELECTRONICS,
                0,
                NOW));

        assertThat(decision.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(decision.triggered()).isEmpty();
    }
}
