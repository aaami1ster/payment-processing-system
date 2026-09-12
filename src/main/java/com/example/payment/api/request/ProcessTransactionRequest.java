package com.example.payment.api.request;

import com.example.payment.domain.fraud.Category;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record ProcessTransactionRequest(
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0001", inclusive = true, message = "must be greater than 0")
        @Digits(integer = 15, fraction = 4, message = "amount must have at most 15 integer and 4 fraction digits")
        BigDecimal amount,

        @NotNull(message = "userId is required")
        UUID userId,

        @NotBlank(message = "merchantId is required")
        @Size(max = 128, message = "merchantId must be at most 128 characters")
        String merchantId,

        @NotNull(message = "category is required")
        Category category
) {}
