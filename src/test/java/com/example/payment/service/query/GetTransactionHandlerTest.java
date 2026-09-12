package com.example.payment.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.payment.common.exception.TransactionNotFoundException;
import com.example.payment.data.postgres.entity.TransactionEntity;
import com.example.payment.data.postgres.repository.TransactionJpaRepository;
import com.example.payment.domain.fraud.Category;
import com.example.payment.domain.transaction.Transaction;
import com.example.payment.domain.transaction.TransactionStatus;
import com.example.payment.service.mapper.TransactionMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetTransactionHandlerTest {

    @Mock
    private TransactionJpaRepository transactionRepository;

    private GetTransactionHandler handler;
    private final UUID transactionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        handler = new GetTransactionHandler(transactionRepository, new TransactionMapper());
    }

    @Test
    void returnsTransaction() {
        Instant now = Instant.parse("2026-09-12T00:00:00Z");
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(
                new TransactionEntity(
                        transactionId,
                        userId,
                        "mch_demo",
                        new BigDecimal("100.00"),
                        Category.GROCERIES,
                        TransactionStatus.APPROVED,
                        new String[0],
                        null,
                        null,
                        now)));

        Transaction transaction = handler.handle(transactionId);

        assertThat(transaction.getId()).isEqualTo(transactionId);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.APPROVED);
    }

    @Test
    void unknownTransactionThrows() {
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(transactionId))
                .isInstanceOf(TransactionNotFoundException.class);
    }
}
