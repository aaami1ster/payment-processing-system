package com.example.payment.common.util;

import com.example.payment.common.exception.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque cursor for stable DESC pages keyed by {@code (createdAt, id)}.
 */
public final class CreatedAtIdCursor {

    private CreatedAtIdCursor() {}

    public record Value(Instant createdAt, UUID id) {}

    public static String encode(Instant createdAt, UUID id) {
        String raw = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Value decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.lastIndexOf('|');
            if (sep <= 0 || sep == raw.length() - 1) {
                throw new IllegalArgumentException("missing separator");
            }
            Instant createdAt = Instant.parse(raw.substring(0, sep));
            UUID id = UUID.fromString(raw.substring(sep + 1));
            return new Value(createdAt, id);
        } catch (RuntimeException ex) {
            throw new InvalidRequestException("cursor", "cursor is invalid");
        }
    }
}
