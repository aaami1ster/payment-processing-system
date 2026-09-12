package com.example.payment.service.query;

import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.common.util.CreatedAtIdCursor;
import com.example.payment.data.postgres.entity.TransactionEntity;
import com.example.payment.data.postgres.repository.TransactionJpaRepository;
import com.example.payment.domain.transaction.Transaction;
import com.example.payment.domain.transaction.TransactionStatus;
import com.example.payment.service.mapper.TransactionMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ListTransactionsHandler {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    /** Sentinel lower bound when {@code from} is omitted (LLD COALESCE '-infinity'). */
    static final Instant FROM_UNBOUNDED = Instant.EPOCH;

    /** Sentinel upper bound when {@code to} is omitted (LLD COALESCE 'infinity'). */
    static final Instant TO_UNBOUNDED = Instant.parse("9999-12-31T23:59:59.999999999Z");

    public record Result(List<Transaction> items, String nextCursor, boolean hasMore) {}

    private final TransactionJpaRepository transactionRepository;
    private final TransactionMapper transactionMapper;

    @Transactional(readOnly = true)
    public Result handle(
            UUID userId,
            Instant from,
            Instant to,
            TransactionStatus status,
            String cursor,
            Integer limit) {
        if (userId == null) {
            throw new InvalidRequestException("userId", "userId is required");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("from", "from must be before or equal to to");
        }

        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw new InvalidRequestException("limit", "limit must be between 1 and " + MAX_LIMIT);
        }

        // Bind concrete Instant values — PostgreSQL cannot type NULL Instant in IS NULL checks.
        Instant fromBound = from != null ? from : FROM_UNBOUNDED;
        Instant toBound = to != null ? to : TO_UNBOUNDED;

        CreatedAtIdCursor.Value cursorValue = CreatedAtIdCursor.decode(cursor);
        PageRequest pageRequest = PageRequest.of(0, pageSize + 1);
        // Split first page vs after-cursor so PostgreSQL never sees typed NULLs in IS NULL checks.
        List<TransactionEntity> rows = cursorValue == null
                ? transactionRepository.findFirstPage(userId, fromBound, toBound, status, pageRequest)
                : transactionRepository.findPageAfter(
                        userId,
                        fromBound,
                        toBound,
                        status,
                        cursorValue.createdAt(),
                        cursorValue.id(),
                        pageRequest);

        boolean hasMore = rows.size() > pageSize;
        List<TransactionEntity> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<Transaction> items = page.stream().map(transactionMapper::toDomain).toList();

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            TransactionEntity last = page.get(page.size() - 1);
            nextCursor = CreatedAtIdCursor.encode(last.getCreatedAt(), last.getId());
        }
        return new Result(items, nextCursor, hasMore);
    }
}
