package com.example.payment.domain.user;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.NonNull;
import lombok.Value;

/**
 * Domain user profile. Persistence mapping lives under {@code data.postgres}.
 */
@Value
public class User {

    @NonNull UUID id;
    @NonNull String email;
    @NonNull KycStatus kycStatus;
    BigDecimal preApprovedTransactionLimit;
    @NonNull Instant createdAt;
    @NonNull Instant updatedAt;

    public static User createNew(UUID id, String email, KycStatus kycStatus, Instant now) {
        return new User(id, email, kycStatus, null, now, now);
    }

    public User withKycStatus(KycStatus status, Instant updatedAt) {
        return new User(id, email, status, preApprovedTransactionLimit, createdAt, updatedAt);
    }

    public User withPreApprovedTransactionLimit(BigDecimal limit, Instant updatedAt) {
        return new User(id, email, kycStatus, limit, createdAt, updatedAt);
    }
}
