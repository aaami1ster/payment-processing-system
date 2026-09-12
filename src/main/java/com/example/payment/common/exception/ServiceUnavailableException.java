package com.example.payment.common.exception;

/**
 * Persistence or infrastructure failure that prevents acknowledging a business decision.
 * Mapped to HTTP 503 {@code SERVICE_UNAVAILABLE}.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
