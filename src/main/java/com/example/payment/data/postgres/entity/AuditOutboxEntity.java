package com.example.payment.data.postgres.entity;

import com.example.payment.domain.outbox.OutboxDestination;
import com.example.payment.domain.outbox.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditOutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination", nullable = false)
    private OutboxDestination destination;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** Set for {@link OutboxDestination#WEBHOOK} only. */
    @Column(name = "target_url")
    private String targetUrl;

    /** Set for {@link OutboxDestination#WEBHOOK} only — never logged. */
    @Column(name = "signing_secret")
    private String signingSecret;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status;

    @Setter
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Setter
    @Column(name = "last_error")
    private String lastError;

    @Setter
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AuditOutboxEntity(
            UUID transactionId,
            OutboxDestination destination,
            String payload,
            String targetUrl,
            String signingSecret,
            OutboxStatus status,
            int attempts,
            String lastError,
            Instant nextAttemptAt,
            Instant createdAt) {
        this.transactionId = transactionId;
        this.destination = destination;
        this.payload = payload;
        this.targetUrl = targetUrl;
        this.signingSecret = signingSecret;
        this.status = status;
        this.attempts = attempts;
        this.lastError = lastError;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
    }

    /** Convenience for AUDIT Mongo projection rows. */
    public static AuditOutboxEntity audit(
            UUID transactionId, String payload, Instant createdAt) {
        return new AuditOutboxEntity(
                transactionId,
                OutboxDestination.AUDIT,
                payload,
                null,
                null,
                OutboxStatus.PENDING,
                0,
                null,
                createdAt,
                createdAt);
    }

    /** Convenience for WEBHOOK delivery rows. */
    public static AuditOutboxEntity webhook(
            UUID transactionId,
            String payload,
            String targetUrl,
            String signingSecret,
            Instant createdAt) {
        return new AuditOutboxEntity(
                transactionId,
                OutboxDestination.WEBHOOK,
                payload,
                targetUrl,
                signingSecret,
                OutboxStatus.PENDING,
                0,
                null,
                createdAt,
                createdAt);
    }
}
