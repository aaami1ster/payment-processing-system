package com.example.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LLD concurrency regressions: Rule 2 velocity under {@code FOR UPDATE}, and concurrent
 * idempotency (exactly one transaction + one outbox).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.mongo.MongoHealthContributorAutoConfiguration",
})
class ConcurrencyIT {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("payments")
            .withUsername("payments")
            .withPassword("payments");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/unused");
        registry.add("payment.outbox.publisher.enabled", () -> "false");
        registry.add("spring.autoconfigure.exclude", () -> String.join(",",
                "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.mongo.MongoHealthContributorAutoConfiguration"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void rule2_twoExistingPlusTwoParallel_oneSuccessOneVelocityDecline() throws Exception {
        String userId = createUser("vel+" + System.currentTimeMillis() + "@example.com");

        // Seed exactly 2 APPROVED transactions in the velocity window
        processPayment(userId, "mch_seed_1", "25.00", null);
        processPayment(userId, "mch_seed_2", "25.00", null);

        Integer seeded = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM transactions
                WHERE user_id = ?::uuid AND status IN ('APPROVED','FLAGGED')
                """,
                Integer.class,
                userId);
        assertThat(seeded).isEqualTo(2);

        List<JsonNode> results = runParallel(2, i -> () -> processPayment(
                userId, "mch_race_" + i, "30.00", null));

        assertThat(results).hasSize(2);
        long successPaths = results.stream()
                .filter(n -> {
                    String status = n.get("data").get("status").asText();
                    return "APPROVED".equals(status) || "FLAGGED".equals(status);
                })
                .count();
        long velocityDeclines = results.stream()
                .filter(n -> {
                    JsonNode data = n.get("data");
                    if (!"DECLINED".equals(data.get("status").asText())) {
                        return false;
                    }
                    for (JsonNode rule : data.get("rulesTriggered")) {
                        if ("VELOCITY".equals(rule.asText())) {
                            return true;
                        }
                    }
                    return false;
                })
                .count();

        assertThat(successPaths)
                .as("exactly one additional non-DECLINED-by-velocity success path (#3)")
                .isEqualTo(1);
        assertThat(velocityDeclines)
                .as("exactly one DECLINED by Rule 2 (VELOCITY) as #4")
                .isEqualTo(1);

        Integer authorized = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM transactions
                WHERE user_id = ?::uuid AND status IN ('APPROVED','FLAGGED')
                """,
                Integer.class,
                userId);
        assertThat(authorized).isEqualTo(3);

        Integer declinedVelocity = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM transactions
                WHERE user_id = ?::uuid
                  AND status = 'DECLINED'
                  AND 'VELOCITY' = ANY(rules_triggered)
                """,
                Integer.class,
                userId);
        assertThat(declinedVelocity).isEqualTo(1);
    }

    @Test
    void concurrentIdempotency_oneTransactionOneOutbox() throws Exception {
        String userId = createUser("idem+" + System.currentTimeMillis() + "@example.com");
        String idemKey = "concurrent-" + UUID.randomUUID();

        List<JsonNode> results = runParallel(2, i -> () -> processPayment(
                userId, "mch_idem", "40.00", idemKey));

        assertThat(results).hasSize(2);
        Set<String> transactionIds = results.stream()
                .map(n -> n.get("data").get("transactionId").asText())
                .collect(Collectors.toSet());
        assertThat(transactionIds).hasSize(1);
        String transactionId = transactionIds.iterator().next();

        assertThat(results)
                .allSatisfy(n -> assertThat(n.get("data").get("status").asText())
                        .isIn("APPROVED", "FLAGGED", "DECLINED"));

        Integer txnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM transactions
                WHERE user_id = ?::uuid AND idempotency_key = ?
                """,
                Integer.class,
                userId,
                idemKey);
        assertThat(txnCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM audit_outbox
                WHERE transaction_id = ?::uuid
                """,
                Integer.class,
                transactionId);
        assertThat(outboxCount).isEqualTo(1);
    }

    private List<JsonNode> runParallel(int n, java.util.function.IntFunction<Callable<JsonNode>> taskFactory)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<JsonNode>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Callable<JsonNode> body = taskFactory.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting for start gate");
                    }
                    return body.call();
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers did not become ready");
            }
            start.countDown();

            List<JsonNode> results = new ArrayList<>(n);
            for (Future<JsonNode> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private JsonNode processPayment(String userId, String merchantId, String amount, String idempotencyKey)
            throws Exception {
        var request = post("/api/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        {
                          "amount": %s,
                          "userId": "%s",
                          "merchantId": "%s",
                          "category": "GROCERIES"
                        }
                        """
                                .formatted(amount, userId, merchantId));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
}
