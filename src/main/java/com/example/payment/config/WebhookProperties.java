package com.example.payment.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional webhook subscribers (YAML). When disabled or empty, payments enqueue only AUDIT outbox
 * rows.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "payment.webhooks")
public class WebhookProperties {

    /** Master switch — false skips enqueueing WEBHOOK outbox rows. */
    private boolean enabled = false;

    private long connectTimeoutMs = 2000;

    private long readTimeoutMs = 3000;

    private List<Subscriber> subscribers = new ArrayList<>();

    @Getter
    @Setter
    public static class Subscriber {
        private String id;
        /** Merchant scope, or {@code *} for all merchants. */
        private String merchantId = "*";
        private String targetUrl;
        private String secret;
        /** Empty = all transaction decision events. */
        private List<String> events = new ArrayList<>();
        private boolean active = true;
    }
}
