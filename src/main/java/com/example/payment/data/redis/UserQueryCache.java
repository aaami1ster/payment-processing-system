package com.example.payment.data.redis;

import com.example.payment.domain.user.User;
import java.util.Optional;
import java.util.UUID;

/**
 * Optional query-side cache for {@code GetUserHandler}. Never used on the authorize path.
 */
public interface UserQueryCache {

    Optional<User> get(UUID userId);

    void put(UUID userId, User user);

    void evict(UUID userId);
}
