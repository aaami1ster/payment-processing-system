package com.example.payment.common.exception;

import java.io.Serial;
import lombok.Getter;

@Getter
public class DuplicateEmailException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String email;

    public DuplicateEmailException(String email) {
        super("Email already registered: " + email);
        this.email = email;
    }
}
