package com.example.payment.data.postgres.repository;

import com.example.payment.data.postgres.entity.TransactionEntity;
import com.example.payment.domain.transaction.TransactionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionJpaRepository extends JpaRepository<TransactionEntity, UUID> {

    Optional<TransactionEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    @Query("""
            SELECT COUNT(t) FROM TransactionEntity t
            WHERE t.userId = :userId
              AND t.status IN :statuses
              AND t.createdAt >= :windowStart
              AND t.createdAt <= :now
            """)
    long countByUserIdAndStatusInAndCreatedAtBetween(
            @Param("userId") UUID userId,
            @Param("statuses") Collection<TransactionStatus> statuses,
            @Param("windowStart") Instant windowStart,
            @Param("now") Instant now);

    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE t.userId = :userId
              AND t.createdAt >= :from
              AND t.createdAt <= :to
              AND (:status IS NULL OR t.status = :status)
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    List<TransactionEntity> findFirstPage(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("status") TransactionStatus status,
            Pageable pageable);

    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE t.userId = :userId
              AND t.createdAt >= :from
              AND t.createdAt <= :to
              AND (:status IS NULL OR t.status = :status)
              AND (
                    t.createdAt < :cursorCreatedAt
                    OR (t.createdAt = :cursorCreatedAt AND t.id < :cursorId)
                  )
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    List<TransactionEntity> findPageAfter(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("status") TransactionStatus status,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);
}
