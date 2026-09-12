package com.example.payment.domain.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.domain.transaction.TransactionStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VelocityWindowTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:01:10Z");
    private static final Duration WINDOW = Duration.ofSeconds(60);

    @Test
    void includesTransactionExactlyAtWindowStart() {
        Instant exactlySixtySecondsAgo = NOW.minusSeconds(60);

        assertThat(VelocityWindow.isWithinWindow(exactlySixtySecondsAgo, NOW, WINDOW)).isTrue();
    }

    @Test
    void excludesTransactionOneMillisecondBeforeWindowStart() {
        Instant justOutside = NOW.minusSeconds(60).minusMillis(1);

        assertThat(VelocityWindow.isWithinWindow(justOutside, NOW, WINDOW)).isFalse();
    }

    @Test
    void includesTransactionAtNow() {
        assertThat(VelocityWindow.isWithinWindow(NOW, NOW, WINDOW)).isTrue();
    }

    @Test
    void excludesTransactionAfterNow() {
        assertThat(VelocityWindow.isWithinWindow(NOW.plusMillis(1), NOW, WINDOW)).isFalse();
    }

    @Test
    void includesTransactionInsideWindow() {
        Instant mid = NOW.minusSeconds(30);

        assertThat(VelocityWindow.isWithinWindow(mid, NOW, WINDOW)).isTrue();
    }

    @Test
    void countsApprovedAndFlaggedOnly() {
        assertThat(VelocityWindow.countsTowardVelocity(TransactionStatus.APPROVED)).isTrue();
        assertThat(VelocityWindow.countsTowardVelocity(TransactionStatus.FLAGGED)).isTrue();
        assertThat(VelocityWindow.countsTowardVelocity(TransactionStatus.DECLINED)).isFalse();
    }

    @Test
    void exampleHistory_declinedDoesNotExtendVelocityBlock() {
        // APPROVED, APPROVED, DECLINED, FLAGGED → count is 3, not 4
        int count = 0;
        for (TransactionStatus status : new TransactionStatus[] {
            TransactionStatus.APPROVED,
            TransactionStatus.APPROVED,
            TransactionStatus.DECLINED,
            TransactionStatus.FLAGGED
        }) {
            if (VelocityWindow.countsTowardVelocity(status)) {
                count++;
            }
        }
        assertThat(count).isEqualTo(3);
    }
}
