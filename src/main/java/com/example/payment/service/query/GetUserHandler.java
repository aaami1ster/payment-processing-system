package com.example.payment.service.query;

import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.common.exception.UserNotFoundException;
import com.example.payment.common.logging.LogFactory;
import com.example.payment.data.postgres.repository.UserJpaRepository;
import com.example.payment.data.redis.UserQueryCache;
import com.example.payment.domain.user.User;
import com.example.payment.service.mapper.UserMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetUserHandler {

    private static final Logger log = LogFactory.getLogger(GetUserHandler.class);

    private final UserJpaRepository userRepository;
    private final UserMapper userMapper;
    private final UserQueryCache userQueryCache;

    @Transactional(readOnly = true)
    public User handle(UUID userId) {
        if (userId == null) {
            throw new InvalidRequestException("id", "user id is required");
        }

        return userQueryCache
                .get(userId)
                .orElseGet(() -> loadFromDbAndCache(userId));
    }

    private User loadFromDbAndCache(UUID userId) {
        User user = userRepository
                .findById(userId)
                .map(userMapper::toDomain)
                .orElseThrow(() -> new UserNotFoundException(userId));
        userQueryCache.put(userId, user);
        log.debug("user.cache.miss_loaded userId={}", userId);
        return user;
    }
}
