package com.example.payment.domain.fraud.rule;

import static com.example.payment.domain.fraud.FraudTestSupport.NOW;
import static com.example.payment.domain.fraud.FraudTestSupport.context;
import static com.example.payment.domain.fraud.FraudTestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.domain.fraud.Category;
import com.example.payment.domain.fraud.RuleId;
import com.example.payment.domain.fraud.Severity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NewUserHighAmountRuleTest {

    private static final Duration MAX_AGE = Duration.ofDays(30);

    private final NewUserHighAmountRule rule = new NewUserHighAmountRule(new BigDecimal("5000"), MAX_AGE);

    @Test
    void flagsYoungUserAboveAmountThreshold() {
        Instant createdAt = NOW.minus(Duration.ofDays(10));
        var result = rule.evaluate(context(user(createdAt, null), "5000.01", Category.ELECTRONICS, 0, NOW));

        assertThat(result).isPresent();
        assertThat(result.get().ruleId()).isEqualTo(RuleId.NEW_USER_HIGH_AMOUNT);
        assertThat(result.get().severity()).isEqualTo(Severity.FLAG);
    }

    @Test
    void doesNotFlagWhenUserExactlyThirtyDaysOld() {
        Instant createdAt = NOW.minus(Duration.ofDays(30));
        var result = rule.evaluate(context(user(createdAt, null), "6000", Category.ELECTRONICS, 0, NOW));

        assertThat(result).isEmpty();
    }

    @Test
    void flagsWhenUserOneMillisecondYoungerThanThirtyDays() {
        Instant createdAt = NOW.minus(Duration.ofDays(30)).plusMillis(1);
        var result = rule.evaluate(context(user(createdAt, null), "6000", Category.ELECTRONICS, 0, NOW));

        assertThat(result).isPresent();
        assertThat(result.get().severity()).isEqualTo(Severity.FLAG);
    }

    @Test
    void doesNotFlagWhenUserOlderThanThirtyDays() {
        Instant createdAt = NOW.minus(Duration.ofDays(30)).minusMillis(1);
        var result = rule.evaluate(context(user(createdAt, null), "6000", Category.ELECTRONICS, 0, NOW));

        assertThat(result).isEmpty();
    }

    @Test
    void doesNotFlagYoungUserAtAmountThreshold() {
        Instant createdAt = NOW.minus(Duration.ofDays(5));
        var result = rule.evaluate(context(user(createdAt, null), "5000", Category.ELECTRONICS, 0, NOW));

        assertThat(result).isEmpty();
    }

    @Test
    void doesNotFlagYoungUserBelowAmountThreshold() {
        Instant createdAt = NOW.minus(Duration.ofDays(5));
        var result = rule.evaluate(context(user(createdAt, null), "4999.99", Category.ELECTRONICS, 0, NOW));

        assertThat(result).isEmpty();
    }

    @Test
    void idIsNewUserHighAmount() {
        assertThat(rule.id()).isEqualTo(RuleId.NEW_USER_HIGH_AMOUNT);
    }
}
