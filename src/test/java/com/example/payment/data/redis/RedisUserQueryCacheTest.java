package com.example.payment.data.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.config.UserCacheProperties;
import com.example.payment.domain.user.KycStatus;
import com.example.payment.domain.user.User;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisUserQueryCacheTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> values;

    private RedisUserQueryCache cache;
    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(values);
        UserCacheProperties properties = new UserCacheProperties();
        properties.setTtlSeconds(30);
        properties.setKeyPrefix("user:");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        cache = new RedisUserQueryCache(redisTemplate, mapper, properties, new SimpleMeterRegistry());
    }

    @Test
    void getPutEvictHappyPath() {
        Instant now = Instant.parse("2026-09-12T00:00:00Z");
        User user = new User(userId, "a@b.com", KycStatus.PENDING, null, now, now);
        cache.put(userId, user);
        verify(values).set(eq("user:" + userId), anyString(), eq(Duration.ofSeconds(30)));

        when(values.get("user:" + userId))
                .thenReturn(
                        "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"email\":\"a@b.com\",\"kycStatus\":\"PENDING\",\"preApprovedTransactionLimit\":null,\"createdAt\":\"2026-09-12T00:00:00Z\",\"updatedAt\":\"2026-09-12T00:00:00Z\"}");
        assertThat(cache.get(userId)).isPresent();

        when(values.get("user:" + userId)).thenReturn(null);
        assertThat(cache.get(userId)).isEmpty();

        cache.evict(userId);
        verify(redisTemplate).delete("user:" + userId);
    }

    @Test
    void softFailsOnRedisErrors() {
        when(values.get(anyString())).thenThrow(new RuntimeException("down"));
        assertThat(cache.get(userId)).isEmpty();

        doThrow(new RuntimeException("down")).when(values).set(anyString(), anyString(), any(Duration.class));
        Instant now = Instant.parse("2026-09-12T00:00:00Z");
        cache.put(userId, new User(userId, "a@b.com", KycStatus.PENDING, null, now, now));

        doThrow(new RuntimeException("down")).when(redisTemplate).delete(anyString());
        cache.evict(userId);
    }
}
