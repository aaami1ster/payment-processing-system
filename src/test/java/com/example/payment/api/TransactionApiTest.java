package com.example.payment.api;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.mongo.MongoHealthContributorAutoConfiguration",
        "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "org.springframework.boot.data.mongo.autoconfigure.DataMongoAutoConfiguration",
        "org.springframework.boot.mongodb.health.autoconfigure.MongoHealthContributorAutoConfiguration"
})
class TransactionApiTest {

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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/unused");
        registry.add("spring.autoconfigure.exclude", () -> String.join(",",
                "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.mongo.MongoHealthContributorAutoConfiguration",
                "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
                "org.springframework.boot.data.mongo.autoconfigure.DataMongoAutoConfiguration",
                "org.springframework.boot.mongodb.health.autoconfigure.MongoHealthContributorAutoConfiguration"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void processDeclineIdempotencyAndOutbox() throws Exception {
        String userId = createUser("txn+" + System.currentTimeMillis() + "@example.com");
        String idemKey = "idem-" + UUID.randomUUID();

        MvcResult created = mockMvc.perform(post("/api/v1/transactions")
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 100.00,
                                  "userId": "%s",
                                  "merchantId": "mch_demo",
                                  "category": "GROCERIES"
                                }
                                """.formatted(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status", equalTo("APPROVED")))
                .andExpect(jsonPath("$.data.transactionId", notNullValue()))
                .andExpect(jsonPath("$.errors", empty()))
                .andReturn();

        String transactionId = objectMapper
                .readTree(created.getResponse().getContentAsString())
                .get("data")
                .get("transactionId")
                .asString();

        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_outbox WHERE transaction_id = ?::uuid AND status = 'PENDING'",
                Integer.class,
                transactionId);
        assert outboxCount != null && outboxCount == 1;

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 100.00,
                                  "userId": "%s",
                                  "merchantId": "mch_demo",
                                  "category": "GROCERIES"
                                }
                                """.formatted(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.transactionId", equalTo(transactionId)));

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 99.00,
                                  "userId": "%s",
                                  "merchantId": "mch_demo",
                                  "category": "GROCERIES"
                                }
                                """.formatted(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data", nullValue()))
                .andExpect(jsonPath("$.errors[0].code", equalTo("IDEMPOTENCY_CONFLICT")));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 12000.00,
                                  "userId": "%s",
                                  "merchantId": "mch_demo",
                                  "category": "GROCERIES"
                                }
                                """.formatted(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status", equalTo("DECLINED")))
                .andExpect(jsonPath("$.data.rulesTriggered", hasItem("AMOUNT_WITHOUT_APPROVAL")))
                .andExpect(jsonPath("$.errors", empty()));

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preApprovedTransactionLimit":15000}
                                """))
                .andExpect(status().isOk());

        // New user + amount > 5000 → FLAGGED (Rule 4) once Rule 1 no longer declines
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 12000.00,
                                  "userId": "%s",
                                  "merchantId": "mch_demo",
                                  "category": "GROCERIES"
                                }
                                """.formatted(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status", equalTo("FLAGGED")))
                .andExpect(jsonPath("$.errors", empty()));

        // Age the user past Rule 4 window → same high amount APPROVED
        jdbcTemplate.update(
                "UPDATE users SET created_at = created_at - interval '60 days' WHERE id = ?::uuid",
                userId);

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 12000.00,
                                  "userId": "%s",
                                  "merchantId": "mch_demo",
                                  "category": "GROCERIES"
                                }
                                """.formatted(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status", equalTo("APPROVED")))
                .andExpect(jsonPath("$.errors", empty()));
    }

    @Test
    void unknownUserAndValidationErrors() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 100.00,
                                  "userId": "00000000-0000-0000-0000-000000000000",
                                  "merchantId": "mch_demo",
                                  "category": "GROCERIES"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", equalTo("USER_NOT_FOUND")));

        String userId = createUser("val+" + System.currentTimeMillis() + "@example.com");
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": -1,
                                  "userId": "%s",
                                  "merchantId": "mch_demo",
                                  "category": "GROCERIES"
                                }
                                """.formatted(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", equalTo("VALIDATION_ERROR")));
    }

    private String createUser(String email) throws Exception {
        MvcResult create = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode root = objectMapper.readTree(create.getResponse().getContentAsString());
        return root.get("data").get("id").asString();
    }
}
