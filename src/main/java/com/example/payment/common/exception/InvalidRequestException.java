package com.example.payment.common.exception;

import java.io.Serial;
import lombok.Getter;

/**
 * Input/use-case validation that is not covered (or not solely covered) by Bean Validation on API DTOs.
 * Mapped to HTTP 400 with {@code VALIDATION_ERROR}.
 */
@Getter
public class InvalidRequestException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

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
}
