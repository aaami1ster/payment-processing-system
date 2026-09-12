package com.example.payment.common.exception;

import java.io.Serial;

/**
 * Same Idempotency-Key reused with a different request fingerprint.
 * Mapped to HTTP 409 {@code IDEMPOTENCY_CONFLICT}.
 */
public class IdempotencyConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
