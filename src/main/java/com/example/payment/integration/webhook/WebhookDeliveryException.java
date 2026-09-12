package com.example.payment.integration.webhook;

import java.io.Serial;

/** Transport or protocol failure delivering a webhook (publisher will backoff). */
public class WebhookDeliveryException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public WebhookDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public WebhookDeliveryException(String message) {
        super(message);
    }
}
