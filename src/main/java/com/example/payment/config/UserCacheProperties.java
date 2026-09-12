package com.example.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "payment.cache.user")
public class UserCacheProperties {

    /**
     * When false (default), {@link com.example.payment.data.redis.NoOpUserQueryCache} is used and
     * Redis is not required.
     */
    private boolean enabled = false;

    /** TTL for {@code user:{id}} entries. */
    private long ttlSeconds = 60;

    private String keyPrefix = "user:";
}
