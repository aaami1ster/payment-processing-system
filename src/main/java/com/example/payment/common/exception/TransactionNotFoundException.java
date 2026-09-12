package com.example.payment.common.exception;

import java.util.UUID;
import lombok.Getter;

@Getter
public class TransactionNotFoundException extends RuntimeException {

    private final UUID transactionId;

    public TransactionNotFoundException(UUID transactionId) {
        super("Transaction not found: " + transactionId);
        this.transactionId = transactionId;
    }
}
