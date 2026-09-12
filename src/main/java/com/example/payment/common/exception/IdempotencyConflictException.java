package com.example.payment.common.exception;

/**
 * Same Idempotency-Key reused with a different request fingerprint.
 * Mapped to HTTP 409 {@code IDEMPOTENCY_CONFLICT}.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
