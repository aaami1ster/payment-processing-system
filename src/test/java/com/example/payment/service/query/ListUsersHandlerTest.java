package com.example.payment.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.common.util.CreatedAtIdCursor;
import com.example.payment.data.postgres.entity.UserEntity;
import com.example.payment.data.postgres.repository.UserJpaRepository;
import com.example.payment.domain.user.KycStatus;
import com.example.payment.service.mapper.UserMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class ListUsersHandlerTest {

    @Mock
    private UserJpaRepository userRepository;

    private ListUsersHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ListUsersHandler(userRepository, new UserMapper());
    }

    @Test
    void returnsPageWithNextCursorWhenHasMore() {
        Instant t1 = Instant.parse("2026-09-12T02:00:00Z");
        Instant t2 = Instant.parse("2026-09-12T01:00:00Z");
        Instant t3 = Instant.parse("2026-09-12T00:00:00Z");
        UUID id1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID id2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID id3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

        when(userRepository.findFirstPage(isNull(), eq(PageRequest.of(0, 3))))
                .thenReturn(List.of(
                        new UserEntity(id1, "a@example.com", KycStatus.PENDING, null, t1, t1),
                        new UserEntity(id2, "b@example.com", KycStatus.VERIFIED, null, t2, t2),
                        new UserEntity(id3, "c@example.com", KycStatus.PENDING, null, t3, t3)));

        ListUsersHandler.Result result = handler.handle(null, 2, null);

        assertThat(result.items()).hasSize(2);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursor()).isEqualTo(CreatedAtIdCursor.encode(t2, id2));
    }

    @Test
    void rejectsInvalidLimit() {
        assertThatThrownBy(() -> handler.handle(null, 0, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> handler.handle(null, 999, null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void passesDecodedCursorAndKycFilter() {
        Instant cursorAt = Instant.parse("2026-09-12T01:00:00Z");
        UUID cursorId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String cursor = CreatedAtIdCursor.encode(cursorAt, cursorId);

        when(userRepository.findPageAfter(eq(KycStatus.PENDING), eq(cursorAt), eq(cursorId), any()))
                .thenReturn(List.of());

        ListUsersHandler.Result result = handler.handle(cursor, 10, KycStatus.PENDING);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
        verify(userRepository).findPageAfter(
                eq(KycStatus.PENDING), eq(cursorAt), eq(cursorId), eq(PageRequest.of(0, 11)));
    }
}
