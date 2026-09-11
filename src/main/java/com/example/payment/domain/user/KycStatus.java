package com.example.payment.domain.user;

/**
 * Know-your-customer status stored on the user profile (audit context only — not used by fraud rules).
 */
public enum KycStatus {
    PENDING,
    VERIFIED,
    REJECTED
}
