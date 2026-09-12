package com.example.payment.domain.user;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain user profile. Persistence mapping lives under {@code data.postgres}.
 */
public final class User {

    private final UUID id;
    private final String email;
    private final KycStatus kycStatus;
    private final BigDecimal preApprovedTransactionLimit;
    private final Instant createdAt;
    private final Instant updatedAt;

    public User(
            UUID id,
            String email,
            KycStatus kycStatus,
            BigDecimal preApprovedTransactionLimit,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = Objects.requireNonNull(email, "email");
        this.kycStatus = Objects.requireNonNull(kycStatus, "kycStatus");
        this.preApprovedTransactionLimit = preApprovedTransactionLimit;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static User createNew(UUID id, String email, KycStatus kycStatus, Instant now) {
        return new User(id, email, kycStatus, null, now, now);
    }

    public User withKycStatus(KycStatus status, Instant updatedAt) {
        return new User(id, email, status, preApprovedTransactionLimit, createdAt, updatedAt);
    }

    public User withPreApprovedTransactionLimit(BigDecimal limit, Instant updatedAt) {
        return new User(id, email, kycStatus, limit, createdAt, updatedAt);
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public KycStatus getKycStatus() {
        return kycStatus;
    }

    public BigDecimal getPreApprovedTransactionLimit() {
        return preApprovedTransactionLimit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
