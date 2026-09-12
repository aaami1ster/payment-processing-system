package com.example.payment.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payment.common.exception.InvalidRequestException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreatedAtIdCursorTest {

    @Test
    void roundTripAndNullDecode() {
        Instant at = Instant.parse("2026-09-12T00:00:00Z");
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String encoded = CreatedAtIdCursor.encode(at, id);
        CreatedAtIdCursor.Value value = CreatedAtIdCursor.decode(encoded);
        assertThat(value.createdAt()).isEqualTo(at);
        assertThat(value.id()).isEqualTo(id);
        assertThat(CreatedAtIdCursor.decode(null)).isNull();
        assertThat(CreatedAtIdCursor.decode("  ")).isNull();
    }

    @Test
    void invalidCursorThrows() {
        assertThatThrownBy(() -> CreatedAtIdCursor.decode("%%%"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> CreatedAtIdCursor.decode(
                        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("nosep".getBytes())))
                .isInstanceOf(InvalidRequestException.class);
    }
}
