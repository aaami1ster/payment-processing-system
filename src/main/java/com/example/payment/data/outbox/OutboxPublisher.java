package com.example.payment.data.outbox;

import com.example.payment.common.logging.LogFactory;
import com.example.payment.config.OutboxProperties;
import com.example.payment.data.mongo.document.AuditLogDocument;
import com.example.payment.data.postgres.entity.AuditOutboxEntity;
import com.example.payment.domain.outbox.OutboxStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Claims PENDING outbox rows with {@code FOR UPDATE SKIP LOCKED}, projects audit docs to Mongo,
 * and marks {@code PUBLISHED}. At-least-once: {@link DuplicateKeyException} means already delivered.
 */
@Component
@ConditionalOnProperty(
        prefix = "payment.outbox.publisher",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LogFactory.getLogger(OutboxPublisher.class);

    private static final long[] BACKOFF_SECONDS = {0, 2, 5, 10, 30, 60, 120, 300};

    @PersistenceContext
    private EntityManager entityManager;

    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final CircuitBreaker mongoCircuitBreaker;
    private final Counter publishFailures;
    private final Timer publishDuration;
    private final AtomicLong pendingCount = new AtomicLong(0);
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong(0);
    private final AtomicLong indexesEnsured = new AtomicLong(0);

    public OutboxPublisher(
            MongoTemplate mongoTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            OutboxProperties properties,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.mongoCircuitBreaker = CircuitBreaker.of(
                "mongoAudit",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(properties.getCircuitFailureRateThreshold())
                        .slidingWindowSize(properties.getCircuitSlidingWindowSize())
                        .waitDurationInOpenState(Duration.ofMillis(properties.getCircuitWaitOpenMs()))
                        .build());
        this.publishFailures = Counter.builder("outbox.publish.failures")
                .description("Outbox publish failures")
                .register(meterRegistry);
        this.publishDuration = Timer.builder("mongo.publish.duration")
                .description("Mongo audit publish latency")
                .register(meterRegistry);
        Gauge.builder("outbox.pending.count", pendingCount, AtomicLong::get)
                .description("Approximate PENDING outbox backlog")
                .register(meterRegistry);
        Gauge.builder("outbox.oldest.pending.age", oldestPendingAgeSeconds, AtomicLong::get)
                .description("Age in seconds of the oldest PENDING outbox row")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${payment.outbox.publisher.poll-interval-ms:1000}")
    public void scheduledPublish() {
        try {
            publishBatch();
        } catch (Exception ex) {
            log.warn("outbox.publish.batch_error error={}", ex.toString());
        }
    }

    /**
     * Process one batch of due PENDING rows. Safe to call from tests.
     *
     * @return number of rows marked PUBLISHED in this batch
     */
    public int publishBatch() {
        ensureIndexesBestEffort();
        Integer published = transactionTemplate.execute(status -> doPublishBatch());
        return published == null ? 0 : published;
    }

    private void ensureIndexesBestEffort() {
        if (indexesEnsured.get() > 0) {
            return;
        }
        try {
            IndexOperations ops = mongoTemplate.indexOps(AuditLogDocument.class);
            ops.ensureIndex(new Index().on("userId", Sort.Direction.ASC).on("timestamp", Sort.Direction.DESC));
            ops.ensureIndex(new Index().on("decision", Sort.Direction.ASC).on("timestamp", Sort.Direction.DESC));
            indexesEnsured.set(1);
            log.info("outbox.mongo_indexes_ensured");
        } catch (Exception ex) {
            log.warn("outbox.mongo_indexes_deferred error={}", ex.toString());
        }
    }

    private int doPublishBatch() {
        if (mongoCircuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            log.warn("outbox.publish.skipped reason=circuit_open");
            return 0;
        }

        Instant now = Instant.now(clock);
        List<AuditOutboxEntity> claimed = claimPending(now, properties.getBatchSize());
        refreshPendingGauge();

        int published = 0;
        for (AuditOutboxEntity row : claimed) {
            try {
                publishOne(row, now);
                published++;
            } catch (CallNotPermittedException ex) {
                publishFailures.increment();
                log.warn(
                        "outbox.publish.circuit_open transactionId={} outboxId={}",
                        row.getTransactionId(),
                        row.getId());
                scheduleRetry(row, now, "circuit open");
                break;
            } catch (Exception ex) {
                publishFailures.increment();
                log.warn(
                        "outbox.publish.failed transactionId={} outboxId={} attempts={} error={}",
                        row.getTransactionId(),
                        row.getId(),
                        row.getAttempts() + 1,
                        ex.toString());
                scheduleRetry(row, now, truncate(ex.getMessage()));
            }
        }
        if (published > 0) {
            refreshPendingGauge();
        }
        return published;
    }

    private void publishOne(AuditOutboxEntity row, Instant now) {
        Timer.Sample sample = Timer.start();
        try {
            mongoCircuitBreaker.executeRunnable(() -> insertAudit(row));
            row.setStatus(OutboxStatus.PUBLISHED);
            row.setLastError(null);
            row.setNextAttemptAt(now);
            entityManager.merge(row);
            log.info(
                    "outbox.published transactionId={} outboxId={}",
                    row.getTransactionId(),
                    row.getId());
        } finally {
            sample.stop(publishDuration);
        }
    }

    private void insertAudit(AuditOutboxEntity row) {
        try {
            AuditLogDocument document = objectMapper.readValue(row.getPayload(), AuditLogDocument.class);
            document.setId(row.getTransactionId().toString());
            if (document.getTransactionId() == null) {
                document.setTransactionId(row.getTransactionId().toString());
            }
            mongoTemplate.insert(document);
        } catch (DuplicateKeyException ex) {
            log.info(
                    "outbox.duplicate_key_treated_success transactionId={}",
                    row.getTransactionId());
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize audit payload", ex);
        }
    }

    private void scheduleRetry(AuditOutboxEntity row, Instant now, String error) {
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        row.setLastError(error);
        row.setStatus(OutboxStatus.PENDING);
        row.setNextAttemptAt(now.plus(backoff(attempts)));
        entityManager.merge(row);
    }

    @SuppressWarnings("unchecked")
    private List<AuditOutboxEntity> claimPending(Instant now, int batchSize) {
        return entityManager
                .createNativeQuery(
                        """
                        SELECT id, transaction_id, payload, status, attempts, last_error, next_attempt_at, created_at
                        FROM audit_outbox
                        WHERE status = 'PENDING'
                          AND next_attempt_at <= :now
                        ORDER BY id
                        FOR UPDATE SKIP LOCKED
                        LIMIT :batchSize
                        """,
                        AuditOutboxEntity.class)
                .setParameter("now", now)
                .setParameter("batchSize", batchSize)
                .getResultList();
    }

    private void refreshPendingGauge() {
        Instant now = Instant.now(clock);
        Number count = (Number) entityManager
                .createNativeQuery(
                        "SELECT COUNT(*) FROM audit_outbox WHERE status = 'PENDING'")
                .getSingleResult();
        pendingCount.set(count == null ? 0L : count.longValue());

        Object oldestCreated = entityManager
                .createNativeQuery(
                        "SELECT MIN(created_at) FROM audit_outbox WHERE status = 'PENDING'")
                .getSingleResult();
        if (oldestCreated instanceof Instant createdAt) {
            oldestPendingAgeSeconds.set(Math.max(0L, Duration.between(createdAt, now).getSeconds()));
        } else if (oldestCreated instanceof java.sql.Timestamp ts) {
            oldestPendingAgeSeconds.set(
                    Math.max(0L, Duration.between(ts.toInstant(), now).getSeconds()));
        } else {
            oldestPendingAgeSeconds.set(0L);
        }
    }

    static Duration backoff(int attemptsAfterFailure) {
        int index = Math.min(Math.max(attemptsAfterFailure, 1), BACKOFF_SECONDS.length) - 1;
        return Duration.ofSeconds(BACKOFF_SECONDS[index]);
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
