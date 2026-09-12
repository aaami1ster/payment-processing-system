package com.example.payment.service.command;

import com.example.payment.common.exception.IdempotencyConflictException;
import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.common.exception.ServiceUnavailableException;
import com.example.payment.common.exception.UserNotFoundException;
import com.example.payment.common.logging.LogFactory;
import com.example.payment.common.util.RequestFingerprint;
import com.example.payment.config.FraudProperties;
import com.example.payment.data.postgres.entity.AuditOutboxEntity;
import com.example.payment.data.postgres.repository.AuditOutboxJpaRepository;
import com.example.payment.data.postgres.repository.TransactionJpaRepository;
import com.example.payment.data.postgres.repository.UserJpaRepository;
import com.example.payment.domain.fraud.Category;
import com.example.payment.domain.fraud.FraudContext;
import com.example.payment.domain.fraud.FraudDecision;
import com.example.payment.domain.fraud.FraudEngine;
import com.example.payment.domain.fraud.RuleResult;
import com.example.payment.domain.outbox.OutboxStatus;
import com.example.payment.domain.transaction.Transaction;
import com.example.payment.domain.transaction.TransactionStatus;
import com.example.payment.domain.user.User;
import com.example.payment.service.mapper.TransactionMapper;
import com.example.payment.service.mapper.UserMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ProcessTransactionHandler {

    private static final Logger log = LogFactory.getLogger(ProcessTransactionHandler.class);

    private static final EnumSet<TransactionStatus> VELOCITY_STATUSES =
            EnumSet.of(TransactionStatus.APPROVED, TransactionStatus.FLAGGED);

    private final UserJpaRepository userRepository;
    private final TransactionJpaRepository transactionRepository;
    private final AuditOutboxJpaRepository outboxRepository;
    private final FraudEngine fraudEngine;
    private final FraudProperties fraudProperties;
    private final UserMapper userMapper;
    private final TransactionMapper transactionMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public Transaction handle(
            UUID userId,
            String merchantId,
            BigDecimal amount,
            Category category,
            String idempotencyKey) {
        validate(userId, merchantId, amount, category);

        String normalizedMerchant = merchantId.trim();
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String fingerprint = RequestFingerprint.sha256(userId, normalizedMerchant, amount, category);

        try {
            if (normalizedKey != null) {
                Optional<Transaction> preCheck = findByIdempotency(userId, normalizedKey);
                if (preCheck.isPresent()) {
                    return resolveIdempotent(preCheck.get(), fingerprint);
                }
            }

            User user = userRepository
                    .findByIdForUpdate(userId)
                    .map(userMapper::toDomain)
                    .orElseThrow(() -> new UserNotFoundException(userId));

            if (normalizedKey != null) {
                Optional<Transaction> underLock = findByIdempotency(userId, normalizedKey);
                if (underLock.isPresent()) {
                    return resolveIdempotent(underLock.get(), fingerprint);
                }
            }

            Instant now = Instant.now(clock);
            Instant windowStart = now.minus(Duration.ofSeconds(fraudProperties.getVelocityWindowSeconds()));
            long recentCount = transactionRepository.countByUserIdAndStatusInAndCreatedAtBetween(
                    userId, VELOCITY_STATUSES, windowStart, now);

            FraudContext context = new FraudContext(user, amount, category, (int) recentCount, now);
            FraudDecision decision = fraudEngine.evaluate(context);

            Transaction transaction = Transaction.create(
                    UUID.randomUUID(),
                    userId,
                    normalizedMerchant,
                    amount,
                    category,
                    decision,
                    normalizedKey,
                    fingerprint,
                    now);

            persistTransactionAndOutbox(transaction, user, decision);

            log.info(
                    "transaction.processed status={} transactionId={} userId={} rulesTriggered={}",
                    transaction.getStatus(),
                    transaction.getId(),
                    userId,
                    transaction.getRulesTriggered());
            return transaction;
        } catch (UserNotFoundException | IdempotencyConflictException | InvalidRequestException ex) {
            throw ex;
        } catch (DataIntegrityViolationException ex) {
            if (normalizedKey != null) {
                Optional<Transaction> winner = findByIdempotency(userId, normalizedKey);
                if (winner.isPresent()) {
                    return resolveIdempotent(winner.get(), fingerprint);
                }
            }
            throw new ServiceUnavailableException("Unable to persist transaction", ex);
        } catch (DataAccessException ex) {
            throw new ServiceUnavailableException("Unable to persist transaction", ex);
        }
    }

    private void persistTransactionAndOutbox(Transaction transaction, User user, FraudDecision decision) {
        transactionRepository.save(transactionMapper.toEntity(transaction));
        outboxRepository.save(new AuditOutboxEntity(
                transaction.getId(),
                buildAuditPayload(transaction, user, decision),
                OutboxStatus.PENDING,
                0,
                null,
                transaction.getCreatedAt(),
                transaction.getCreatedAt()));
    }

    private String buildAuditPayload(Transaction transaction, User user, FraudDecision decision) {
        Map<String, Object> userContext = new LinkedHashMap<>();
        userContext.put("kycStatus", user.getKycStatus().name());
        userContext.put(
                "preApprovedTransactionLimit",
                user.getPreApprovedTransactionLimit() == null
                        ? null
                        : user.getPreApprovedTransactionLimit().toPlainString());
        userContext.put("userCreatedAt", user.getCreatedAt().toString());

        List<Map<String, String>> rules = decision.triggered().stream()
                .map(this::ruleToMap)
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", transaction.getId().toString());
        payload.put("userId", transaction.getUserId().toString());
        payload.put("decision", transaction.getStatus().name());
        payload.put("rulesTriggered", rules);
        payload.put("userContext", userContext);
        payload.put("amount", transaction.getAmount().toPlainString());
        payload.put("merchantId", transaction.getMerchantId());
        payload.put("category", transaction.getCategory().name());
        payload.put("timestamp", transaction.getCreatedAt().toString());

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new ServiceUnavailableException("Unable to serialize audit payload", ex);
        }
    }

    private Map<String, String> ruleToMap(RuleResult result) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("ruleId", result.ruleId().name());
        map.put("severity", result.severity().name());
        map.put("reason", result.reason());
        return map;
    }

    private Optional<Transaction> findByIdempotency(UUID userId, String idempotencyKey) {
        return transactionRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(transactionMapper::toDomain);
    }

    private Transaction resolveIdempotent(Transaction existing, String fingerprint) {
        if (!Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key was reused with a different request payload");
        }
        log.info(
                "transaction.idempotent_replay transactionId={} userId={}",
                existing.getId(),
                existing.getUserId());
        return existing;
    }

    private static void validate(UUID userId, String merchantId, BigDecimal amount, Category category) {
        if (userId == null) {
            throw new InvalidRequestException("userId", "userId is required");
        }
        if (merchantId == null || merchantId.isBlank()) {
            throw new InvalidRequestException("merchantId", "merchantId is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidRequestException("amount", "must be greater than 0");
        }
        if (category == null) {
            throw new InvalidRequestException("category", "category is required");
        }
    }

    private static String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return idempotencyKey.trim();
    }
}
