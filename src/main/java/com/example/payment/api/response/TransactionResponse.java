package com.example.payment.api.response;

import com.example.payment.domain.fraud.RuleId;
import com.example.payment.domain.transaction.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        TransactionStatus status,
        BigDecimal amount,
        List<RuleId> rulesTriggered,
        Instant createdAt
) {}
