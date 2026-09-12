package com.example.payment.common.exception;

import java.io.Serial;

/**
 * Persistence or infrastructure failure that prevents acknowledging a business decision.
 * Mapped to HTTP 503 {@code SERVICE_UNAVAILABLE}.
 */
public class ServiceUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
