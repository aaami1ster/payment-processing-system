package com.example.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Phase 7: HTTP rate limit is independent of fraud Rule 2; over-limit never persists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.mongo.MongoHealthContributorAutoConfiguration",
})
class RateLimitIT {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("payments")
            .withUsername("payments")
            .withPassword("payments");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/unused");
        registry.add("payment.outbox.publisher.enabled", () -> "false");
        registry.add("payment.rate-limit.enabled", () -> "true");
        // Tight user bucket so burst is easy to demonstrate
        registry.add("payment.rate-limit.user.capacity", () -> "3");
        registry.add("payment.rate-limit.user.window-seconds", () -> "60");
        registry.add("payment.rate-limit.merchant.capacity", () -> "1000");
        registry.add("payment.rate-limit.ip.capacity", () -> "1000");
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
    void overLimitReturns429WithoutPersisting() throws Exception {
        String userId = createUser("rl+" + System.currentTimeMillis() + "@example.com");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(txnBody(userId, "mch_rl", "10.00")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status", equalTo("APPROVED")));
        }

        Integer beforeTxn = countTransactions(userId);
        Integer beforeOutbox = countOutboxForUser(userId);
        assertThat(beforeTxn).isEqualTo(3);
        assertThat(beforeOutbox).isEqualTo(3);

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txnBody(userId, "mch_rl", "10.00")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.data", nullValue()))
                .andExpect(jsonPath("$.message", equalTo("Rate limit exceeded")))
                .andExpect(jsonPath("$.errors[0].code", equalTo("RATE_LIMIT_EXCEEDED")))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());

        assertThat(countTransactions(userId)).isEqualTo(beforeTxn);
        assertThat(countOutboxForUser(userId)).isEqualTo(beforeOutbox);
    }

    @Test
    void underLimitStillReachesFraudEngine() throws Exception {
        String userId = createUser("rl-ok+" + System.currentTimeMillis() + "@example.com");

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txnBody(userId, "mch_ok", "25.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status", equalTo("APPROVED")));

        assertThat(countTransactions(userId)).isEqualTo(1);
        assertThat(countOutboxForUser(userId)).isEqualTo(1);
    }

    private Integer countTransactions(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE user_id = ?::uuid", Integer.class, userId);
    }

    private Integer countOutboxForUser(String userId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM audit_outbox o
                JOIN transactions t ON t.id = o.transaction_id
                WHERE t.user_id = ?::uuid
                """,
                Integer.class,
                userId);
    }

    private static String txnBody(String userId, String merchantId, String amount) {
        return """
                {
                  "amount": %s,
                  "userId": "%s",
                  "merchantId": "%s",
                  "category": "GROCERIES"
                }
                """
                .formatted(amount, userId, merchantId);
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
