package com.example.payment.api.response;

import com.example.payment.domain.user.KycStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        KycStatus kycStatus,
        BigDecimal preApprovedTransactionLimit,
        Instant createdAt,
        Instant updatedAt
) {}
