package com.example.payment.data.redis;

import com.example.payment.common.logging.LogFactory;
import com.example.payment.config.UserCacheProperties;
import com.example.payment.domain.user.User;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-through cache for user query views. Failures fall through to PostgreSQL (never throw to callers).
 */
public final class RedisUserQueryCache implements UserQueryCache {

    private static final Logger log = LogFactory.getLogger(RedisUserQueryCache.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserCacheProperties properties;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    public RedisUserQueryCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            UserCacheProperties properties,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.hits = Counter.builder("user.cache.hits").description("User query cache hits").register(meterRegistry);
        this.misses =
                Counter.builder("user.cache.misses").description("User query cache misses").register(meterRegistry);
        this.errors =
                Counter.builder("user.cache.errors").description("User query cache errors").register(meterRegistry);
    }

    @Override
    public Optional<User> get(UUID userId) {
        try {
            String json = redisTemplate.opsForValue().get(key(userId));
            if (json == null || json.isBlank()) {
                misses.increment();
                return Optional.empty();
            }
            hits.increment();
            log.info("user.cache.hit userId={}", userId);
            CachedUserView view = objectMapper.readValue(json, CachedUserView.class);
            return Optional.of(view.toDomain());
        } catch (Exception ex) {
            errors.increment();
            log.warn("user.cache.get_failed userId={} error={}", userId, ex.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(UUID userId, User user) {
        try {
            String json = objectMapper.writeValueAsString(CachedUserView.from(user));
            Duration ttl = Duration.ofSeconds(Math.max(1L, properties.getTtlSeconds()));
            redisTemplate.opsForValue().set(key(userId), json, ttl);
            log.info("user.cache.put userId={} ttlSeconds={}", userId, ttl.toSeconds());
        } catch (Exception ex) {
            errors.increment();
            log.warn("user.cache.put_failed userId={} error={}", userId, ex.toString());
        }
    }

    @Override
    public void evict(UUID userId) {
        try {
            redisTemplate.delete(key(userId));
            log.info("user.cache.evict userId={}", userId);
        } catch (Exception ex) {
            errors.increment();
            log.warn("user.cache.evict_failed userId={} error={}", userId, ex.toString());
        }
    }

    private String key(UUID userId) {
        String prefix = properties.getKeyPrefix() == null ? "user:" : properties.getKeyPrefix();
        return prefix + userId;
    }
}
