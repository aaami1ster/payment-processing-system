package com.example.payment.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.domain.fraud.Category;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void sameCanonicalInputsProduceSameHash() {
        UUID userId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        String a = RequestFingerprint.sha256(userId, "mch", new BigDecimal("100.00"), Category.GROCERIES);
        String b = RequestFingerprint.sha256(userId, "mch", new BigDecimal("100.0"), Category.GROCERIES);
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void differentAmountChangesFingerprint() {
        UUID userId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        String a = RequestFingerprint.sha256(userId, "mch", new BigDecimal("100.00"), Category.GROCERIES);
        String b = RequestFingerprint.sha256(userId, "mch", new BigDecimal("99.00"), Category.GROCERIES);
        assertThat(a).isNotEqualTo(b);
    }
}
