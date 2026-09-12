package com.example.payment.domain.outbox;

/**
 * Delivery state for transactional outbox rows.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED
}
