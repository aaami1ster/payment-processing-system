package com.example.payment.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.common.exception.IdempotencyConflictException;
import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.common.exception.ServiceUnavailableException;
import com.example.payment.common.exception.UserNotFoundException;
import com.example.payment.common.util.RequestFingerprint;
import com.example.payment.config.FraudProperties;
import com.example.payment.config.WebhookProperties;
import com.example.payment.data.postgres.entity.AuditOutboxEntity;
import com.example.payment.data.postgres.entity.TransactionEntity;
import com.example.payment.data.postgres.entity.UserEntity;
import com.example.payment.data.postgres.repository.AuditOutboxJpaRepository;
import com.example.payment.data.postgres.repository.TransactionJpaRepository;
import com.example.payment.data.postgres.repository.UserJpaRepository;
import com.example.payment.domain.fraud.Category;
import com.example.payment.domain.fraud.FraudEngine;
import com.example.payment.domain.fraud.rule.AmountWithoutApprovalRule;
import com.example.payment.domain.fraud.rule.HighRiskCategoryRule;
import com.example.payment.domain.fraud.rule.NewUserHighAmountRule;
import com.example.payment.domain.fraud.rule.VelocityRule;
import com.example.payment.domain.transaction.Transaction;
import com.example.payment.domain.transaction.TransactionStatus;
import com.example.payment.domain.user.KycStatus;
import com.example.payment.service.mapper.TransactionMapper;
import com.example.payment.service.mapper.UserMapper;
import com.example.payment.service.metrics.PaymentMetrics;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@ExtendWith(MockitoExtension.class)
class ProcessTransactionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final UUID USER_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    @Mock
    private UserJpaRepository userRepository;

    @Mock
    private TransactionJpaRepository transactionRepository;

    @Mock
    private AuditOutboxJpaRepository outboxRepository;

    @Mock
    private PaymentMetrics paymentMetrics;

    private ProcessTransactionHandler handler;

    @BeforeEach
    void setUp() {
        FraudProperties properties = new FraudProperties();
        FraudEngine engine = new FraudEngine(List.of(
                new AmountWithoutApprovalRule(properties.getAmountWithoutApprovalThreshold()),
                new VelocityRule(properties.getVelocityThreshold()),
                new HighRiskCategoryRule(
                        properties.getHighRiskCategories(),
                        properties.getHighRiskAmountThreshold()),
                new NewUserHighAmountRule(
                        properties.getNewUserAmountThreshold(),
                        Duration.ofDays(properties.getNewUserMaxAgeDays()))));

        handler = new ProcessTransactionHandler(
                userRepository,
                transactionRepository,
                outboxRepository,
                engine,
                properties,
                new UserMapper(),
                new TransactionMapper(),
                new ObjectMapper().registerModule(new JavaTimeModule()),
                Clock.fixed(NOW, ZoneOffset.UTC),
                paymentMetrics,
                new WebhookProperties());
    }

    @Test
    void flagsNewUserHighAmountAndWritesOutbox() {
        stubUserForUpdate(youngUser());
        when(transactionRepository.countByUserIdAndStatusInAndCreatedAtBetween(
                        eq(USER_ID), any(), any(), any()))
                .thenReturn(0L);
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any(AuditOutboxEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = handler.handle(
                USER_ID, "mch_9f2", new BigDecimal("6200.00"), Category.ELECTRONICS, null);

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.FLAGGED);
        assertThat(result.getRulesTriggered()).isNotEmpty();

        ArgumentCaptor<AuditOutboxEntity> outboxCaptor = ArgumentCaptor.forClass(AuditOutboxEntity.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getTransactionId()).isEqualTo(result.getId());
        assertThat(outboxCaptor.getValue().getPayload()).contains("FLAGGED");
    }

    @Test
    void declinesAmountWithoutApproval() {
        stubUserForUpdate(matureUser(false));
        when(transactionRepository.countByUserIdAndStatusInAndCreatedAtBetween(
                        eq(USER_ID), any(), any(), any()))
                .thenReturn(0L);
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any(AuditOutboxEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = handler.handle(
                USER_ID, "mch_9f2", new BigDecimal("12000.00"), Category.GROCERIES, null);

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.DECLINED);
        verify(transactionRepository).save(any(TransactionEntity.class));
        verify(outboxRepository).save(any(AuditOutboxEntity.class));
    }

    @Test
    void approvesHighAmountWhenMatureUserHasLimit() {
        stubUserForUpdate(matureUser(true));
        when(transactionRepository.countByUserIdAndStatusInAndCreatedAtBetween(
                        eq(USER_ID), any(), any(), any()))
                .thenReturn(0L);
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any(AuditOutboxEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = handler.handle(
                USER_ID, "mch_9f2", new BigDecimal("12000.00"), Category.GROCERIES, null);

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.APPROVED);
    }

    @Test
    void unknownUserReturns404DomainException() {
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(
                        USER_ID, "mch_9f2", new BigDecimal("100.00"), Category.GROCERIES, null))
                .isInstanceOf(UserNotFoundException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void idempotencyReplayReturnsOriginal() {
        String key = "idem-1";
        String fingerprint = RequestFingerprint.sha256(
                USER_ID, "mch_9f2", new BigDecimal("100.00"), Category.GROCERIES);
        TransactionEntity existing = existingEntity(key, fingerprint, TransactionStatus.APPROVED);

        when(transactionRepository.findByUserIdAndIdempotencyKey(USER_ID, key))
                .thenReturn(Optional.of(existing));

        Transaction result = handler.handle(
                USER_ID, "mch_9f2", new BigDecimal("100.00"), Category.GROCERIES, key);

        assertThat(result.getId()).isEqualTo(existing.getId());
        verify(userRepository, never()).findByIdForUpdate(any());
        verify(transactionRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void idempotencyConflictWhenFingerprintDiffers() {
        String key = "idem-1";
        String fingerprint = RequestFingerprint.sha256(
                USER_ID, "mch_9f2", new BigDecimal("100.00"), Category.GROCERIES);
        TransactionEntity existing = existingEntity(key, fingerprint, TransactionStatus.APPROVED);

        when(transactionRepository.findByUserIdAndIdempotencyKey(USER_ID, key))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> handler.handle(
                        USER_ID, "mch_9f2", new BigDecimal("99.00"), Category.GROCERIES, key))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void postgresFailureMapsToServiceUnavailable() {
        when(userRepository.findByIdForUpdate(USER_ID))
                .thenThrow(new DataAccessResourceFailureException("pg down"));

        assertThatThrownBy(() -> handler.handle(
                        USER_ID, "mch_9f2", new BigDecimal("100.00"), Category.GROCERIES, null))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> handler.handle(
                        null, "mch", BigDecimal.ONE, Category.GROCERIES, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> handler.handle(
                        USER_ID, "  ", BigDecimal.ONE, Category.GROCERIES, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> handler.handle(
                        USER_ID, "mch", null, Category.GROCERIES, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> handler.handle(
                        USER_ID, "mch", BigDecimal.ZERO, Category.GROCERIES, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> handler.handle(
                        USER_ID, "mch", BigDecimal.ONE, null, null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void blankIdempotencyKeyTreatedAsAbsent() {
        stubUserForUpdate(matureUser(true));
        when(transactionRepository.countByUserIdAndStatusInAndCreatedAtBetween(
                        eq(USER_ID), any(), any(), any()))
                .thenReturn(0L);
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any(AuditOutboxEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = handler.handle(
                USER_ID, "mch_9f2", new BigDecimal("50.00"), Category.GROCERIES, "  ");

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.APPROVED);
        verify(transactionRepository, never()).findByUserIdAndIdempotencyKey(any(), any());
    }

    @Test
    void dataIntegrityViolationRecoversViaIdempotencyLookup() {
        String key = "idem-race";
        String fingerprint = RequestFingerprint.sha256(
                USER_ID, "mch_9f2", new BigDecimal("100.00"), Category.GROCERIES);
        TransactionEntity existing = existingEntity(key, fingerprint, TransactionStatus.APPROVED);

        stubUserForUpdate(matureUser(true));
        when(transactionRepository.findByUserIdAndIdempotencyKey(USER_ID, key))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(transactionRepository.countByUserIdAndStatusInAndCreatedAtBetween(
                        eq(USER_ID), any(), any(), any()))
                .thenReturn(0L);
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenThrow(new DataIntegrityViolationException("unique"));

        Transaction result = handler.handle(
                USER_ID, "mch_9f2", new BigDecimal("100.00"), Category.GROCERIES, key);

        assertThat(result.getId()).isEqualTo(existing.getId());
    }

    @Test
    void dataIntegrityViolationWithoutWinnerIsServiceUnavailable() {
        stubUserForUpdate(matureUser(true));
        when(transactionRepository.countByUserIdAndStatusInAndCreatedAtBetween(
                        eq(USER_ID), any(), any(), any()))
                .thenReturn(0L);
        when(transactionRepository.save(any(TransactionEntity.class)))
                .thenThrow(new DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> handler.handle(
                        USER_ID, "mch_9f2", new BigDecimal("100.00"), Category.GROCERIES, null))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    private void stubUserForUpdate(UserEntity entity) {
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(entity));
    }

    private UserEntity youngUser() {
        Instant created = NOW.minus(Duration.ofDays(10));
        return new UserEntity(
                USER_ID,
                "alice@example.com",
                KycStatus.VERIFIED,
                null,
                created,
                created);
    }

    private UserEntity matureUser(boolean withLimit) {
        Instant created = NOW.minus(Duration.ofDays(60));
        return new UserEntity(
                USER_ID,
                "alice@example.com",
                KycStatus.VERIFIED,
                withLimit ? new BigDecimal("15000") : null,
                created,
                created);
    }

    private TransactionEntity existingEntity(String key, String fingerprint, TransactionStatus status) {
        return new TransactionEntity(
                UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7"),
                USER_ID,
                "mch_9f2",
                new BigDecimal("100.00"),
                Category.GROCERIES,
                status,
                new String[0],
                key,
                fingerprint,
                NOW);
    }
}
