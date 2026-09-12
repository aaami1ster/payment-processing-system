package com.example.payment.common.exception;

/**
 * Input/use-case validation that is not covered (or not solely covered) by Bean Validation on API DTOs.
 * Mapped to HTTP 400 with {@code VALIDATION_ERROR}.
 */
public class InvalidRequestException extends RuntimeException {

    private final String field;
    private final String code;

    public InvalidRequestException(String field, String message) {
        this(field, "VALIDATION_ERROR", message);
    }

    public InvalidRequestException(String field, String code, String message) {
        super(message);
        this.field = field;
        this.code = code != null ? code : "VALIDATION_ERROR";
    }

    public String getField() {
        return field;
    }

    public String getCode() {
        return code;
    }
}
