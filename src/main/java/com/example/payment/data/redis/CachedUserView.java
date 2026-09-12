package com.example.payment.data.redis;

import com.example.payment.domain.user.KycStatus;
import com.example.payment.domain.user.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** JSON shape stored under {@code user:{id}} — maps to/from domain {@link User}. */
record CachedUserView(
        UUID id,
        String email,
        KycStatus kycStatus,
        BigDecimal preApprovedTransactionLimit,
        Instant createdAt,
        Instant updatedAt) {

    static CachedUserView from(User user) {
        return new CachedUserView(
                user.getId(),
                user.getEmail(),
                user.getKycStatus(),
                user.getPreApprovedTransactionLimit(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    User toDomain() {
        return new User(id, email, kycStatus, preApprovedTransactionLimit, createdAt, updatedAt);
    }
}
