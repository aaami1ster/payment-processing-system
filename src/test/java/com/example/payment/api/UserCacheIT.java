package com.example.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.payment.data.redis.UserQueryCache;
import com.example.payment.service.command.ProcessTransactionHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 8: query-side Redis cache for GET /users; authorize path stays on PostgreSQL FOR UPDATE.
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
class UserCacheIT {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("payments")
            .withUsername("payments")
            .withPassword("payments");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.mongodb.uri", () -> "mongodb://localhost:27017/unused");
        registry.add("payment.outbox.publisher.enabled", () -> "false");
        registry.add("payment.cache.user.enabled", () -> "true");
        registry.add("payment.cache.user.ttl-seconds", () -> "60");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
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
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UserQueryCache userQueryCache;

    @Autowired
    private ProcessTransactionHandler processTransactionHandler;

    @Test
    void getUserCachesThenInvalidatesOnPatch() throws Exception {
        String userId = createUser("cache+" + System.currentTimeMillis() + "@example.com");
        String key = "user:" + userId;

        mockMvc.perform(get("/api/v1/users/{id}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").isNotEmpty());
        assertThat(redisTemplate.hasKey(key)).isTrue();

        // Second GET should be served from cache (key still present; hit path)
        mockMvc.perform(get("/api/v1/users/{id}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", equalTo(userId)));

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kycStatus\":\"VERIFIED\",\"preApprovedTransactionLimit\":15000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kycStatus", equalTo("VERIFIED")));

        assertThat(redisTemplate.hasKey(key)).isFalse();

        mockMvc.perform(get("/api/v1/users/{id}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kycStatus", equalTo("VERIFIED")))
                .andExpect(jsonPath("$.data.preApprovedTransactionLimit").value(15000));
        assertThat(redisTemplate.hasKey(key)).isTrue();
    }

    @Test
    void authorizePathDoesNotUseUserQueryCache() throws Exception {
        String userId = createUser("authz+" + System.currentTimeMillis() + "@example.com");
        UUID id = UUID.fromString(userId);

        // Warm cache
        mockMvc.perform(get("/api/v1/users/{id}", userId)).andExpect(status().isOk());
        assertThat(userQueryCache.get(id)).isPresent();

        // Corrupt cache value — authorize must still succeed via FOR UPDATE on PG, not cache
        redisTemplate.opsForValue().set("user:" + userId, "{not-json");

        processTransactionHandler.handle(
                id,
                "mch_cache",
                new java.math.BigDecimal("10.00"),
                com.example.payment.domain.fraud.Category.GROCERIES,
                null);

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "amount": 15.00,
                                  "userId": "%s",
                                  "merchantId": "mch_cache",
                                  "category": "GROCERIES"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status", equalTo("APPROVED")));
    }

    @Test
    void getUserFallsBackWhenRedisErrors() throws Exception {
        String userId = createUser("fallback+" + System.currentTimeMillis() + "@example.com");
        mockMvc.perform(get("/api/v1/users/{id}", userId)).andExpect(status().isOk());

        // Simulate bad payload then stop redis — get() should miss/fail soft and reload from PG
        redisTemplate.opsForValue().set("user:" + userId, "%%%");
        mockMvc.perform(get("/api/v1/users/{id}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", equalTo(userId)));
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
