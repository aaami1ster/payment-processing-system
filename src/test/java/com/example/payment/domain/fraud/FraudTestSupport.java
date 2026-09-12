package com.example.payment.domain.fraud;

import com.example.payment.domain.user.KycStatus;
import com.example.payment.domain.user.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class FraudTestSupport {

    public static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");

    private FraudTestSupport() {}

    public static User user(Instant createdAt, BigDecimal preApprovedLimit) {
        return new User(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "user@example.com",
                KycStatus.VERIFIED,
                preApprovedLimit,
                createdAt,
                createdAt);
    }

    public static User matureUser() {
        return user(NOW.minusSeconds(60L * 60 * 24 * 100), null);
    }

    public static FraudContext context(
            User user, String amount, Category category, int recentTxnCount, Instant now) {
        return new FraudContext(user, new BigDecimal(amount), category, recentTxnCount, now);
    }
}
