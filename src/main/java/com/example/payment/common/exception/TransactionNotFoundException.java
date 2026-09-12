package com.example.payment.common.exception;

import java.io.Serial;
import java.util.UUID;
import lombok.Getter;

@Getter
public class TransactionNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID transactionId;

    public TransactionNotFoundException(UUID transactionId) {
        super("Transaction not found: " + transactionId);
        this.transactionId = transactionId;
    }
}
