package com.example.payment.domain.fraud;

/**
 * Stable identifiers for fraud rules (persisted on transactions / audit).
 */
public enum RuleId {
    AMOUNT_WITHOUT_APPROVAL,
    VELOCITY,
    HIGH_RISK_CATEGORY,
    NEW_USER_HIGH_AMOUNT
}
