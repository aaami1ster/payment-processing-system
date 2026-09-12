package com.example.payment.api.request;

import com.example.payment.domain.user.KycStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/**
 * Partial update: non-null fields are applied. Omitted / null fields are left unchanged.
 * Transport constraints use Bean Validation; business rules stay in {@code UpdateUserHandler}.
 */
public record UpdateUserRequest(
        KycStatus kycStatus,
        @DecimalMin(value = "0.0001", inclusive = true, message = "preApprovedTransactionLimit must be greater than 0")
        @Digits(integer = 15, fraction = 4, message = "preApprovedTransactionLimit format is invalid")
        BigDecimal preApprovedTransactionLimit
) {

    public boolean hasKycStatus() {
        return kycStatus != null;
    }

    public boolean hasPreApprovedTransactionLimit() {
        return preApprovedTransactionLimit != null;
    }
}
