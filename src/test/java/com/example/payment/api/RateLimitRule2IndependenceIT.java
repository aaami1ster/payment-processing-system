package com.example.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Rate limiting must not replace Rule 2: with room in the bucket, the 4th authorization is DECLINED by VELOCITY.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.mongo.MongoHealthContributorAutoConfiguration",
        "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "org.springframework.boot.data.mongo.autoconfigure.DataMongoAutoConfiguration",
        "org.springframework.boot.data.mongo.autoconfigure.DataMongoRepositoriesAutoConfiguration",
        "org.springframework.boot.data.mongo.autoconfigure.DataMongoReactiveAutoConfiguration",
        "org.springframework.boot.data.mongo.autoconfigure.DataMongoReactiveRepositoriesAutoConfiguration",
        "org.springframework.boot.mongodb.health.autoconfigure.MongoHealthContributorAutoConfiguration"
})
class RateLimitRule2IndependenceIT {

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
        registry.add("spring.mongodb.uri", () -> "mongodb://localhost:27017/unused");
        registry.add("payment.outbox.publisher.enabled", () -> "false");
        registry.add("payment.rate-limit.enabled", () -> "true");
        registry.add("payment.rate-limit.user.capacity", () -> "100");
        registry.add("payment.rate-limit.merchant.capacity", () -> "1000");
        registry.add("payment.rate-limit.ip.capacity", () -> "1000");
        registry.add("spring.autoconfigure.exclude", () -> String.join(",",
                "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.mongo.MongoHealthContributorAutoConfiguration",
                "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
                "org.springframework.boot.data.mongo.autoconfigure.DataMongoAutoConfiguration",
                "org.springframework.boot.data.mongo.autoconfigure.DataMongoRepositoriesAutoConfiguration",
                "org.springframework.boot.data.mongo.autoconfigure.DataMongoReactiveAutoConfiguration",
                "org.springframework.boot.data.mongo.autoconfigure.DataMongoReactiveRepositoriesAutoConfiguration",
                "org.springframework.boot.mongodb.health.autoconfigure.MongoHealthContributorAutoConfiguration"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void fourthAuthorizationDeclinedByVelocityNotRateLimit() throws Exception {
        String userId = createUser("rl-v+" + System.currentTimeMillis() + "@example.com");

        process(userId, "mch_a", "10.00");
        process(userId, "mch_b", "10.00");

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(userId, "mch_c", "10.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status", equalTo("APPROVED")));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(userId, "mch_d", "10.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status", equalTo("DECLINED")))
                .andExpect(jsonPath("$.data.rulesTriggered", hasItem("VELOCITY")));

        Integer authorized = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM transactions
                WHERE user_id = ?::uuid AND status IN ('APPROVED','FLAGGED')
                """,
                Integer.class,
                userId);
        assertThat(authorized).isEqualTo(3);

        Integer velocityDeclines = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM transactions
                WHERE user_id = ?::uuid
                  AND status = 'DECLINED'
                  AND 'VELOCITY' = ANY(rules_triggered)
                """,
                Integer.class,
                userId);
        assertThat(velocityDeclines).isEqualTo(1);
    }

    private void process(String userId, String merchantId, String amount) throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(userId, merchantId, amount)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status", equalTo("APPROVED")));
    }

    private static String body(String userId, String merchantId, String amount) {
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
        return root.get("data").get("id").asString();
    }
}
