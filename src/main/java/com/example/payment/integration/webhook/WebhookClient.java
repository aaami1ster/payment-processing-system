package com.example.payment.integration.webhook;

import com.example.payment.common.logging.LogFactory;
import com.example.payment.config.WebhookProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;

/**
 * POSTs signed webhook payloads. Callers (outbox publisher) own retry/backoff — this client never
 * runs on the authorize request path.
 */
@RequiredArgsConstructor
public class WebhookClient {

    private static final Logger log = LogFactory.getLogger(WebhookClient.class);

    private static final String HMAC_ALG = "HmacSHA256";

    private final HttpClient httpClient;
    private final WebhookProperties properties;

    /**
     * Deliver raw JSON body with HMAC signature.
     *
     * @return HTTP status code
     * @throws WebhookDeliveryException on transport failure or non-success after response
     */
    public int deliver(String targetUrl, String secret, String rawBody, String requestId) {
        String signature = sign(secret, rawBody);
        HttpRequest request = HttpRequest.newBuilder(URI.create(targetUrl))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", requestId)
                .header("X-Signature", "sha256=" + signature)
                .POST(HttpRequest.BodyPublishers.ofString(rawBody, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            log.info(
                    "webhook.delivered targetHost={} status={} requestId={}",
                    URI.create(targetUrl).getHost(),
                    status,
                    requestId);
            return status;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new WebhookDeliveryException("Webhook delivery interrupted", ex);
        } catch (Exception ex) {
            throw new WebhookDeliveryException("Webhook delivery failed: " + ex.getMessage(), ex);
        }
    }

    /** HMAC-SHA256 hex digest of {@code rawBody} using {@code secret}. */
    public static String sign(String secret, String rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            byte[] digest = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute webhook HMAC", ex);
        }
    }
}
