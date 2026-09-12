package com.example.payment.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.common.exception.UserNotFoundException;
import com.example.payment.data.postgres.entity.UserEntity;
import com.example.payment.data.postgres.repository.UserJpaRepository;
import com.example.payment.data.redis.UserQueryCache;
import com.example.payment.domain.user.KycStatus;
import com.example.payment.domain.user.User;
import com.example.payment.service.mapper.UserMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetUserHandlerTest {

    @Mock
    private UserJpaRepository userRepository;

    @Mock
    private UserQueryCache userQueryCache;

    private GetUserHandler handler;
    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        handler = new GetUserHandler(userRepository, new UserMapper(), userQueryCache);
    }

    @Test
    void returnsUserFromCacheWithoutDb() {
        Instant now = Instant.parse("2026-09-12T00:00:00Z");
        User cached = new User(userId, "alice@example.com", KycStatus.PENDING, null, now, now);
        when(userQueryCache.get(userId)).thenReturn(Optional.of(cached));

        User user = handler.handle(userId);

        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        verify(userRepository, never()).findById(any());
        verify(userQueryCache, never()).put(any(), any());
    }

    @Test
    void returnsUserFromDbAndCaches() {
        Instant now = Instant.parse("2026-09-12T00:00:00Z");
        when(userQueryCache.get(userId)).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                new UserEntity(userId, "alice@example.com", KycStatus.PENDING, null, now, now)));

        User user = handler.handle(userId);

        assertThat(user.getId()).isEqualTo(userId);
        verify(userQueryCache).put(eq(userId), any(User.class));
    }

    @Test
    void unknownUserThrows() {
        when(userQueryCache.get(userId)).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(userId)).isInstanceOf(UserNotFoundException.class);
    }
}
