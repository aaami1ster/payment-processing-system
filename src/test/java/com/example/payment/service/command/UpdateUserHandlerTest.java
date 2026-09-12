package com.example.payment.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.payment.common.exception.UserNotFoundException;
import com.example.payment.data.postgres.entity.UserEntity;
import com.example.payment.data.postgres.repository.UserJpaRepository;
import com.example.payment.domain.user.KycStatus;
import com.example.payment.domain.user.User;
import com.example.payment.service.mapper.UserMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpdateUserHandlerTest {

    @Mock
    private UserJpaRepository userRepository;

    private UpdateUserHandler handler;
    private final Instant created = Instant.parse("2026-09-01T00:00:00Z");
    private final Instant updated = Instant.parse("2026-09-12T00:00:00Z");
    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        handler = new UpdateUserHandler(
                userRepository, new UserMapper(), Clock.fixed(updated, ZoneOffset.UTC));
    }

    @Test
    void patchesKycAndLimit() {
        UserEntity entity = new UserEntity(
                userId, "alice@example.com", KycStatus.PENDING, null, created, created);
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));
        when(userRepository.save(entity)).thenReturn(entity);

        User user = handler.handle(userId, KycStatus.VERIFIED, new BigDecimal("15000"), true);

        assertThat(user.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        assertThat(user.getPreApprovedTransactionLimit()).isEqualByComparingTo("15000");
        assertThat(entity.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        assertThat(entity.getPreApprovedTransactionLimit()).isEqualByComparingTo("15000");
        assertThat(entity.getUpdatedAt()).isEqualTo(updated);
    }

    @Test
    void unknownUserThrows() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(userId, KycStatus.VERIFIED, null, false))
                .isInstanceOf(UserNotFoundException.class);
    }
}
