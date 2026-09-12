package com.example.payment.api;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
        "org.springframework.boot.data.mongo.autoconfigure.DataMongoRepositoriesAutoConfiguration",
        "org.springframework.boot.data.mongo.autoconfigure.DataMongoReactiveAutoConfiguration",
        "org.springframework.boot.data.mongo.autoconfigure.DataMongoReactiveRepositoriesAutoConfiguration",
        "org.springframework.boot.mongodb.health.autoconfigure.MongoHealthContributorAutoConfiguration"
})
class UserApiTest {

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
        registry.add("spring.mongodb.uri", () -> "mongodb://localhost:27017/unused");
        registry.add("payment.outbox.publisher.enabled", () -> "false");
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

    @Test
    void createGetPatchAndNotFound() throws Exception {
        String email = "alice+" + System.currentTimeMillis() + "@example.com";

        MvcResult create = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.data.email", equalTo(email)))
                .andExpect(jsonPath("$.data.kycStatus", equalTo("PENDING")))
                .andExpect(jsonPath("$.data.preApprovedTransactionLimit", nullValue()))
                .andExpect(jsonPath("$.errors", empty()))
                .andExpect(jsonPath("$.meta.requestId", notNullValue()))
                .andReturn();

        JsonNode root = objectMapper.readTree(create.getResponse().getContentAsString());
        String userId = root.get("data").get("id").asString();

        mockMvc.perform(get("/api/v1/users/{id}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", equalTo(userId)))
                .andExpect(jsonPath("$.errors", empty()));

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kycStatus":"VERIFIED","preApprovedTransactionLimit":15000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kycStatus", equalTo("VERIFIED")))
                .andExpect(jsonPath("$.data.preApprovedTransactionLimit", equalTo(15000)));

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data", nullValue()))
                .andExpect(jsonPath("$.errors[0].code", equalTo("EMAIL_ALREADY_EXISTS")));

        mockMvc.perform(get("/api/v1/users/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data", nullValue()))
                .andExpect(jsonPath("$.errors[0].code", equalTo("USER_NOT_FOUND")));
    }

    @Test
    void createRejectsInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data", nullValue()))
                .andExpect(jsonPath("$.errors[0].code", equalTo("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.errors[0].field", equalTo("email")));
    }

    @Test
    void patchRejectsNonPositiveLimit() throws Exception {
        String email = "bob+" + System.currentTimeMillis() + "@example.com";
        MvcResult create = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        String userId = objectMapper.readTree(create.getResponse().getContentAsString())
                .get("data")
                .get("id")
                .asString();

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preApprovedTransactionLimit":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", equalTo("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.errors[0].field", equalTo("preApprovedTransactionLimit")));
    }

    @Test
    void listUsersReturnsPage() throws Exception {
        String email = "list+" + System.currentTimeMillis() + "@example.com";
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/users").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.hasMore").isBoolean())
                .andExpect(jsonPath("$.errors", empty()));

        mockMvc.perform(get("/api/v1/users/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());

        mockMvc.perform(get("/api/v1/users").param("limit", "999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", equalTo("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.errors[0].field", equalTo("limit")));
    }
}
