package com.example.payment.domain.transaction;

/**
 * Authorization outcome for a processed payment.
 * {@link #FLAGGED} is a successful authorization that requires review.
 */
public enum TransactionStatus {
    APPROVED,
    FLAGGED,
    DECLINED
}
