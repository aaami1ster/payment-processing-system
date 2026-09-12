package com.example.payment.data.postgres.repository;

import com.example.payment.data.postgres.entity.TransactionEntity;
import com.example.payment.domain.transaction.TransactionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
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
}
