package com.example.payment.integration.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookClientTest {

    @Test
    void signProducesDeterministicHmacSha256Hex() {
        String body = "{\"event\":\"TRANSACTION_APPROVED\",\"transactionId\":\"abc\"}";
        String secret = "test-secret";

        String first = WebhookClient.sign(secret, body);
        String second = WebhookClient.sign(secret, body);

        assertThat(first).isEqualTo(second);
        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(WebhookClient.sign("other-secret", body)).isNotEqualTo(first);
        assertThat(WebhookClient.sign(secret, body + "x")).isNotEqualTo(first);
    }
}
