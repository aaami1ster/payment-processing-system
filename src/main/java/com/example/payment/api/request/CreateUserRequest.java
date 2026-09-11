package com.example.payment.api.request;

import com.example.payment.domain.user.KycStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        @Size(max = 320, message = "email must be at most 320 characters")
        String email,
        KycStatus kycStatus
) {}
