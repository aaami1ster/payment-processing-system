package com.example.payment.common.exception;

import java.io.Serial;
import java.util.UUID;
import lombok.Getter;

@Getter
public class UserNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;

    public UserNotFoundException(UUID userId) {
        super("User not found: " + userId);
        this.userId = userId;
    }
}
