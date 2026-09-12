package com.example.payment.data.postgres.repository;

import com.example.payment.data.postgres.entity.AuditOutboxEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditOutboxJpaRepository extends JpaRepository<AuditOutboxEntity, Long> {

    boolean existsByTransactionId(UUID transactionId);
}
