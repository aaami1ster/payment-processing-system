package com.example.payment.service.mapper;

import com.example.payment.api.response.TransactionResponse;
import com.example.payment.data.postgres.entity.TransactionEntity;
import com.example.payment.domain.fraud.RuleId;
import com.example.payment.domain.transaction.Transaction;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TransactionMapper {

    public Transaction toDomain(TransactionEntity entity) {
        List<RuleId> rules = entity.getRulesTriggered() == null
                ? List.of()
                : Arrays.stream(entity.getRulesTriggered()).map(RuleId::valueOf).toList();
        return new Transaction(
                entity.getId(),
                entity.getUserId(),
                entity.getMerchantId(),
                entity.getAmount(),
                entity.getCategory(),
                entity.getStatus(),
                rules,
                entity.getIdempotencyKey(),
                entity.getRequestFingerprint(),
                entity.getCreatedAt());
    }

    public TransactionEntity toEntity(Transaction transaction) {
        String[] rules = transaction.getRulesTriggered().stream().map(Enum::name).toArray(String[]::new);
        return new TransactionEntity(
                transaction.getId(),
                transaction.getUserId(),
                transaction.getMerchantId(),
                transaction.getAmount(),
                transaction.getCategory(),
                transaction.getStatus(),
                rules,
                transaction.getIdempotencyKey(),
                transaction.getRequestFingerprint(),
                transaction.getCreatedAt());
    }

    public TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getStatus(),
                transaction.getAmount(),
                transaction.getRulesTriggered(),
                transaction.getCreatedAt());
    }
}
