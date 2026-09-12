package com.example.payment.data.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.payment.data.mongo.document.AuditLogDocument;
import com.example.payment.data.postgres.entity.AuditOutboxEntity;
import com.example.payment.data.postgres.repository.AuditOutboxJpaRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OutboxPublisherIT {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("payments")
            .withUsername("payments")
            .withPassword("payments");

    @Container
    @SuppressWarnings("resource")
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:8"))
            // Same workaround as docker-compose (MongoDB 8 + Linux kernel 6.19+ / SERVER-121912)
            .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add(
                "spring.mongodb.uri",
                () -> mongo.getConnectionString()
                        + "/payments_audit?serverSelectionTimeoutMS=2000&connectTimeoutMS=2000&socketTimeoutMS=2000");
        registry.add("payment.outbox.publisher.enabled", () -> "true");
        // Avoid racing the scheduler; tests call publishBatch() explicitly
        registry.add("payment.outbox.publisher.poll-interval-ms", () -> "600000");
        registry.add("payment.outbox.publisher.initial-delay-ms", () -> "600000");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @MockitoSpyBean
    private MongoTemplate mongoTemplate;

    @Autowired
    private AuditOutboxJpaRepository outboxRepository;

    @BeforeEach
    void cleanAuditProjection() {
        reset(mongoTemplate);
        mongoTemplate.dropCollection(AuditLogDocument.class);
        jdbcTemplate.update("DELETE FROM audit_outbox");
        jdbcTemplate.update("DELETE FROM transactions");
    }

    @Test
    void publishesAuditLogAndMarksOutboxPublished() throws Exception {
        String userId = createUser("audit-ok+" + System.currentTimeMillis() + "@example.com");
        String transactionId = processApproved(userId);

        assertThat(outboxStatus(transactionId)).isEqualTo("PENDING");
        assertThat(outboxPublisher.publishBatch()).isEqualTo(1);

        assertThat(outboxStatus(transactionId)).isEqualTo("PUBLISHED");
        AuditLogDocument doc = mongoTemplate.findById(transactionId, AuditLogDocument.class);
        assertThat(doc).isNotNull();
        assertThat(doc.getId()).isEqualTo(transactionId);
        assertThat(doc.getTransactionId()).isEqualTo(transactionId);
        assertThat(doc.getUserId()).isEqualTo(userId);
        assertThat(doc.getDecision()).isEqualTo("APPROVED");
        assertThat(doc.getMerchantId()).isEqualTo("mch_audit");
        assertThat(doc.getAmount()).startsWith("100");
    }

    @Test
    void paymentSucceedsWhenMongoDownThenRecovers() throws Exception {
        String userId = createUser("audit-down+" + System.currentTimeMillis() + "@example.com");

        // Simulate Mongo unavailable before authorize — POST must still succeed (HLD: Mongo not on request path)
        doThrow(new DataAccessResourceFailureException("simulated mongo outage"))
                .when(mongoTemplate)
                .insert(any(AuditLogDocument.class));

        String transactionId = processApproved(userId);
        assertThat(outboxStatus(transactionId)).isEqualTo("PENDING");

        outboxPublisher.publishBatch();
        assertThat(outboxStatus(transactionId)).isEqualTo("PENDING");
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT attempts FROM audit_outbox WHERE transaction_id = ?::uuid AND destination = 'AUDIT'",
                Integer.class,
                transactionId);
        assertThat(attempts).isGreaterThanOrEqualTo(1);
        assertThat(mongoTemplate.findById(transactionId, AuditLogDocument.class)).isNull();

        // Another payment while Mongo is still down must also return 201 with PENDING outbox
        String secondTxn = processApproved(userId);
        assertThat(outboxStatus(secondTxn)).isEqualTo("PENDING");

        reset(mongoTemplate);
        jdbcTemplate.update(
                "UPDATE audit_outbox SET next_attempt_at = now() - interval '1 second' WHERE status = 'PENDING'");

        assertThat(outboxPublisher.publishBatch()).isEqualTo(2);
        assertThat(outboxStatus(transactionId)).isEqualTo("PUBLISHED");
        assertThat(outboxStatus(secondTxn)).isEqualTo("PUBLISHED");
        assertThat(mongoTemplate.findById(transactionId, AuditLogDocument.class)).isNotNull();
        assertThat(mongoTemplate.findById(secondTxn, AuditLogDocument.class)).isNotNull();
    }

    @Test
    void duplicateMongoInsertIsTreatedAsSuccess() {
        UUID transactionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-10T16:01:02Z");
        String originalPayload =
                """
                {
                  "transactionId":"%s",
                  "userId":"%s",
                  "decision":"APPROVED",
                  "rulesTriggered":[],
                  "userContext":{"kycStatus":"PENDING","preApprovedTransactionLimit":null,"userCreatedAt":"%s"},
                  "amount":"50.00",
                  "merchantId":"mch_original",
                  "category":"GROCERIES",
                  "timestamp":"%s"
                }
                """
                        .formatted(transactionId, UUID.randomUUID(), now, now);

        AuditLogDocument existing = objectMapper.readValue(originalPayload, AuditLogDocument.class);
        existing.setId(transactionId.toString());
        mongoTemplate.insert(existing);

        // Different merchant in outbox payload — insert must not upsert/overwrite the original
        String retryPayload = originalPayload.replace("mch_original", "mch_should_not_overwrite");
        outboxRepository.save(AuditOutboxEntity.audit(transactionId, retryPayload, now.minusSeconds(1)));

        int published = outboxPublisher.publishBatch();
        assertThat(published).isEqualTo(1);
        assertThat(outboxStatus(transactionId.toString())).isEqualTo("PUBLISHED");
        assertThat(mongoTemplate.findAll(AuditLogDocument.class)).hasSize(1);
        AuditLogDocument kept = mongoTemplate.findById(transactionId.toString(), AuditLogDocument.class);
        assertThat(kept).isNotNull();
        assertThat(kept.getMerchantId()).isEqualTo("mch_original");
    }

    @Test
    void backoffScheduleMatchesDesign() {
        assertThat(OutboxPublisher.backoff(1)).isEqualTo(Duration.ZERO);
        assertThat(OutboxPublisher.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxPublisher.backoff(3)).isEqualTo(Duration.ofSeconds(5));
        assertThat(OutboxPublisher.backoff(4)).isEqualTo(Duration.ofSeconds(10));
        assertThat(OutboxPublisher.backoff(5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(OutboxPublisher.backoff(8)).isEqualTo(Duration.ofSeconds(300));
    }

    private String processApproved(String userId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "amount": 100.00,
                                  "userId": "%s",
                                  "merchantId": "mch_audit",
                                  "category": "GROCERIES"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andReturn();
        return objectMapper
                .readTree(created.getResponse().getContentAsString())
                .get("data")
                .get("transactionId")
                .asString();
    }

    private String createUser(String email) throws Exception {
        MvcResult create = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode root = objectMapper.readTree(create.getResponse().getContentAsString());
        return root.get("data").get("id").asString();
    }

    private String outboxStatus(String transactionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM audit_outbox WHERE transaction_id = ?::uuid AND destination = 'AUDIT'",
                String.class,
                transactionId);
    }
}
