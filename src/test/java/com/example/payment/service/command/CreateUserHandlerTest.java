package com.example.payment.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.common.exception.DuplicateEmailException;
import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.data.postgres.entity.UserEntity;
import com.example.payment.data.postgres.repository.UserJpaRepository;
import com.example.payment.domain.user.KycStatus;
import com.example.payment.domain.user.User;
import com.example.payment.service.mapper.UserMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class CreateUserHandlerTest {

    @Mock
    private UserJpaRepository userRepository;

    private CreateUserHandler handler;
    private final Instant fixed = Instant.parse("2026-09-12T00:00:00Z");

    @BeforeEach
    void setUp() {
        handler = new CreateUserHandler(
                userRepository, new UserMapper(), Clock.fixed(fixed, ZoneOffset.UTC));
    }

    @Test
    void createsUserWithPendingKycAndNullLimit() {
        when(userRepository.existsByEmailIgnoreCase("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = handler.handle("alice@example.com", null);

        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.PENDING);
        assertThat(user.getPreApprovedTransactionLimit()).isNull();
        assertThat(user.getCreatedAt()).isEqualTo(fixed);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getKycStatus()).isEqualTo(KycStatus.PENDING);
        assertThat(captor.getValue().getPreApprovedTransactionLimit()).isNull();
    }

    @Test
    void rejectsDuplicateEmail() {
        when(userRepository.existsByEmailIgnoreCase("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> handler.handle("alice@example.com", KycStatus.PENDING))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void rejectsBlankEmail() {
        assertThatThrownBy(() -> handler.handle("  ", null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> handler.handle(null, KycStatus.PENDING))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void mapsSaveRaceToDuplicateEmail() {
        when(userRepository.existsByEmailIgnoreCase("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException("unique_email"));

        assertThatThrownBy(() -> handler.handle("alice@example.com", KycStatus.VERIFIED))
                .isInstanceOf(DuplicateEmailException.class);
    }
}
