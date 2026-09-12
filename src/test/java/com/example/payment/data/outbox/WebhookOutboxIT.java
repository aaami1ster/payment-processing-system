package com.example.payment.data.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.payment.integration.webhook.WebhookClient;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WebhookOutboxIT {

    private static final String WEBHOOK_SECRET = "phase10-test-secret";

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("payments")
            .withUsername("payments")
            .withPassword("payments");

    @Container
    @SuppressWarnings("resource")
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:8"))
            .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1");

    private static HttpServer subscriber;
    private static final List<ReceivedWebhook> received = new CopyOnWriteArrayList<>();
    private static final AtomicInteger remainingFailures = new AtomicInteger(0);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        try {
            subscriber = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            subscriber.createContext("/hooks", exchange -> {
                byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
                String body = new String(bodyBytes, StandardCharsets.UTF_8);
                String signature = exchange.getRequestHeaders().getFirst("X-Signature");
                String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
                received.add(new ReceivedWebhook(body, signature, requestId));

                int code = 200;
                while (true) {
                    int current = remainingFailures.get();
                    if (current <= 0) {
                        break;
                    }
                    if (remainingFailures.compareAndSet(current, current - 1)) {
                        code = 500;
                        break;
                    }
                }

                byte[] resp = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            });
            subscriber.start();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to start webhook test subscriber", ex);
        }

        String hookUrl = "http://127.0.0.1:" + subscriber.getAddress().getPort() + "/hooks";

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add(
                "spring.data.mongodb.uri",
                () -> mongo.getConnectionString()
                        + "/payments_audit?serverSelectionTimeoutMS=2000&connectTimeoutMS=2000&socketTimeoutMS=2000");
        registry.add("payment.outbox.publisher.enabled", () -> "true");
        registry.add("payment.outbox.publisher.poll-interval-ms", () -> "600000");
        registry.add("payment.outbox.publisher.initial-delay-ms", () -> "600000");
        registry.add("payment.webhooks.enabled", () -> "true");
        registry.add("payment.webhooks.subscribers[0].id", () -> "test-sub");
        registry.add("payment.webhooks.subscribers[0].merchant-id", () -> "*");
        registry.add("payment.webhooks.subscribers[0].target-url", () -> hookUrl);
        registry.add("payment.webhooks.subscribers[0].secret", () -> WEBHOOK_SECRET);
        registry.add("payment.webhooks.subscribers[0].active", () -> "true");
        registry.add("payment.rate-limit.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void clean() {
        received.clear();
        remainingFailures.set(0);
        jdbcTemplate.update("DELETE FROM audit_outbox");
        jdbcTemplate.update("DELETE FROM transactions");
    }

    @Test
    void deliversSignedWebhookAfterCommitAndDoesNotBlockAuthorize() throws Exception {
        String userId = createUser("wh-ok+" + System.currentTimeMillis() + "@example.com");
        long started = System.nanoTime();
        String transactionId = processApproved(userId);
        long authorizeMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(authorizeMs).isLessThan(5_000L);
        assertThat(outboxStatus(transactionId, "AUDIT")).isEqualTo("PENDING");
        assertThat(outboxStatus(transactionId, "WEBHOOK")).isEqualTo("PENDING");
        assertThat(received).isEmpty();

        assertThat(outboxPublisher.publishBatch()).isEqualTo(2);

        assertThat(outboxStatus(transactionId, "AUDIT")).isEqualTo("PUBLISHED");
        assertThat(outboxStatus(transactionId, "WEBHOOK")).isEqualTo("PUBLISHED");
        assertThat(received).hasSize(1);

        ReceivedWebhook hook = received.getFirst();
        assertThat(hook.requestId()).isEqualTo(transactionId);
        assertThat(hook.signature()).isEqualTo("sha256=" + WebhookClient.sign(WEBHOOK_SECRET, hook.body()));
        JsonNode payload = objectMapper.readTree(hook.body());
        assertThat(payload.get("event").asText()).isEqualTo("TRANSACTION_APPROVED");
        assertThat(payload.get("transactionId").asText()).isEqualTo(transactionId);
        assertThat(payload.get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void retriesWebhookOn5xxThenPublishes() throws Exception {
        remainingFailures.set(1);
        String userId = createUser("wh-retry+" + System.currentTimeMillis() + "@example.com");
        String transactionId = processApproved(userId);

        assertThat(outboxPublisher.publishBatch()).isEqualTo(1);
        assertThat(outboxStatus(transactionId, "AUDIT")).isEqualTo("PUBLISHED");
        assertThat(outboxStatus(transactionId, "WEBHOOK")).isEqualTo("PENDING");
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT attempts FROM audit_outbox WHERE transaction_id = ?::uuid AND destination = 'WEBHOOK'",
                Integer.class,
                transactionId);
        assertThat(attempts).isGreaterThanOrEqualTo(1);

        jdbcTemplate.update(
                "UPDATE audit_outbox SET next_attempt_at = now() - interval '1 second' WHERE destination = 'WEBHOOK'");
        assertThat(outboxPublisher.publishBatch()).isEqualTo(1);
        assertThat(outboxStatus(transactionId, "WEBHOOK")).isEqualTo("PUBLISHED");
        assertThat(received).isNotEmpty();
        ReceivedWebhook last = received.getLast();
        assertThat(last.signature()).isEqualTo("sha256=" + WebhookClient.sign(WEBHOOK_SECRET, last.body()));
    }

    @Test
    void paymentSucceedsWhenWebhookEndpointDown() throws Exception {
        String userId = createUser("wh-down+" + System.currentTimeMillis() + "@example.com");
        String transactionId = processApproved(userId);
        assertThat(outboxStatus(transactionId, "AUDIT")).isEqualTo("PENDING");
        assertThat(outboxStatus(transactionId, "WEBHOOK")).isEqualTo("PENDING");

        jdbcTemplate.update(
                "UPDATE audit_outbox SET target_url = ? WHERE destination = 'WEBHOOK'",
                "http://127.0.0.1:1/unreachable");

        assertThat(outboxPublisher.publishBatch()).isEqualTo(1);
        assertThat(outboxStatus(transactionId, "AUDIT")).isEqualTo("PUBLISHED");
        assertThat(outboxStatus(transactionId, "WEBHOOK")).isEqualTo("PENDING");
    }

    private String processApproved(String userId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "amount": 100.00,
                                  "userId": "%s",
                                  "merchantId": "mch_webhook",
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
                .asText();
    }

    private String createUser(String email) throws Exception {
        MvcResult create = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode root = objectMapper.readTree(create.getResponse().getContentAsString());
        return root.get("data").get("id").asText();
    }

    private String outboxStatus(String transactionId, String destination) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM audit_outbox WHERE transaction_id = ?::uuid AND destination = ?",
                String.class,
                transactionId,
                destination);
    }

    private record ReceivedWebhook(String body, String signature, String requestId) {}
}
