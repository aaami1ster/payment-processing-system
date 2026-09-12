package com.example.payment.common.util;

import com.example.payment.domain.fraud.Category;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic SHA-256 fingerprint of canonical authorize payload fields (LLD Idempotency).
 */
public final class RequestFingerprint {

    private RequestFingerprint() {}

    public static String sha256(UUID userId, String merchantId, BigDecimal amount, Category category) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(category, "category");
        String canonical = userId
                + "|"
                + merchantId
                + "|"
                + amount.stripTrailingZeros().toPlainString()
                + "|"
                + category.name();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
