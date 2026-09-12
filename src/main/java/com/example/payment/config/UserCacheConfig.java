package com.example.payment.config;

import com.example.payment.data.redis.NoOpUserQueryCache;
import com.example.payment.data.redis.RedisUserQueryCache;
import com.example.payment.data.redis.UserQueryCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(UserCacheProperties.class)
public class UserCacheConfig {

    @Bean
    @ConditionalOnProperty(prefix = "payment.cache.user", name = "enabled", havingValue = "true")
    UserQueryCache redisUserQueryCache(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            UserCacheProperties properties,
            MeterRegistry meterRegistry) {
        return new RedisUserQueryCache(stringRedisTemplate, objectMapper, properties, meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(UserQueryCache.class)
    UserQueryCache noOpUserQueryCache() {
        return new NoOpUserQueryCache();
    }

    @Bean
    @ConditionalOnProperty(prefix = "payment.cache.user", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(StringRedisTemplate.class)
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
