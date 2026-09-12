package com.example.payment.domain.transaction;

import com.example.payment.domain.fraud.Category;
import com.example.payment.domain.fraud.FraudDecision;
import com.example.payment.domain.fraud.RuleId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.Value;

/**
 * Domain payment authorization. Persistence mapping lives under {@code data.postgres}.
 */
@Value
public class Transaction {

    @NonNull UUID id;
    @NonNull UUID userId;
    @NonNull String merchantId;
    @NonNull BigDecimal amount;
    @NonNull Category category;
    @NonNull TransactionStatus status;
    @NonNull List<RuleId> rulesTriggered;
    String idempotencyKey;
    String requestFingerprint;
    @NonNull Instant createdAt;

    public static Transaction create(
            UUID id,
            UUID userId,
            String merchantId,
            BigDecimal amount,
            Category category,
            FraudDecision decision,
            String idempotencyKey,
            String requestFingerprint,
            Instant createdAt) {
        List<RuleId> rules = decision.triggered().stream().map(r -> r.ruleId()).toList();
        return new Transaction(
                id,
                userId,
                merchantId,
                amount,
                category,
                decision.status(),
                List.copyOf(rules),
                idempotencyKey,
                requestFingerprint,
                createdAt);
    }
}
