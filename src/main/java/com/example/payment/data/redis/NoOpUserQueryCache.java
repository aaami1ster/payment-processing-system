package com.example.payment.data.redis;

import com.example.payment.domain.user.User;
import java.util.Optional;
import java.util.UUID;

/** Default when {@code payment.cache.user.enabled=false} — no Redis dependency at runtime. */
public final class NoOpUserQueryCache implements UserQueryCache {

    @Override
    public Optional<User> get(UUID userId) {
        return Optional.empty();
    }

    @Override
    public void put(UUID userId, User user) {
        // no-op
    }

    @Override
    public void evict(UUID userId) {
        // no-op
    }
}
