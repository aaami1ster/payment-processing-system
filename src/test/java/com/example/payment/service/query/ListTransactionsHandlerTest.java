package com.example.payment.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.common.util.CreatedAtIdCursor;
import com.example.payment.data.postgres.entity.TransactionEntity;
import com.example.payment.data.postgres.repository.TransactionJpaRepository;
import com.example.payment.domain.fraud.Category;
import com.example.payment.domain.transaction.TransactionStatus;
import com.example.payment.service.mapper.TransactionMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class ListTransactionsHandlerTest {

    @Mock
    private TransactionJpaRepository transactionRepository;

    private ListTransactionsHandler handler;

    private final UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @BeforeEach
    void setUp() {
        handler = new ListTransactionsHandler(transactionRepository, new TransactionMapper());
    }

    @Test
    void returnsPageWithNextCursorWhenHasMore() {
        Instant t1 = Instant.parse("2026-09-12T02:00:00Z");
        Instant t2 = Instant.parse("2026-09-12T01:00:00Z");
        Instant t3 = Instant.parse("2026-09-12T00:00:00Z");
        UUID id1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID id2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID id3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

        when(transactionRepository.findFirstPage(
                        eq(userId),
                        eq(ListTransactionsHandler.FROM_UNBOUNDED),
                        eq(ListTransactionsHandler.TO_UNBOUNDED),
                        isNull(),
                        eq(PageRequest.of(0, 3))))
                .thenReturn(List.of(entity(id1, t1), entity(id2, t2), entity(id3, t3)));

        ListTransactionsHandler.Result result =
                handler.handle(userId, null, null, null, null, 2);

        assertThat(result.items()).hasSize(2);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursor()).isEqualTo(CreatedAtIdCursor.encode(t2, id2));
    }

    @Test
    void rejectsMissingUserIdAndInvalidLimit() {
        assertThatThrownBy(() -> handler.handle(null, null, null, null, null, 10))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("userId");
        assertThatThrownBy(() -> handler.handle(userId, null, null, null, null, 0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> handler.handle(userId, null, null, null, null, 999))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsFromAfterTo() {
        Instant from = Instant.parse("2026-09-12T12:00:00Z");
        Instant to = Instant.parse("2026-09-12T10:00:00Z");
        assertThatThrownBy(() -> handler.handle(userId, from, to, null, null, 10))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("from");
    }

    @Test
    void passesDecodedCursorAndFilters() {
        Instant cursorAt = Instant.parse("2026-09-12T01:00:00Z");
        UUID cursorId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String cursor = CreatedAtIdCursor.encode(cursorAt, cursorId);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-12-31T23:59:59Z");

        when(transactionRepository.findPageAfter(
                        eq(userId),
                        eq(from),
                        eq(to),
                        eq(TransactionStatus.APPROVED),
                        eq(cursorAt),
                        eq(cursorId),
                        any()))
                .thenReturn(List.of());

        ListTransactionsHandler.Result result =
                handler.handle(userId, from, to, TransactionStatus.APPROVED, cursor, 10);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
        verify(transactionRepository).findPageAfter(
                eq(userId),
                eq(from),
                eq(to),
                eq(TransactionStatus.APPROVED),
                eq(cursorAt),
                eq(cursorId),
                eq(PageRequest.of(0, 11)));
    }

    private TransactionEntity entity(UUID id, Instant createdAt) {
        return new TransactionEntity(
                id,
                userId,
                "mch_demo",
                new BigDecimal("10.00"),
                Category.GROCERIES,
                TransactionStatus.APPROVED,
                new String[0],
                null,
                null,
                createdAt);
    }
}
