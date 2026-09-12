package com.example.payment.integration.webhook;

/** Transport or protocol failure delivering a webhook (publisher will backoff). */
public class WebhookDeliveryException extends RuntimeException {

    public WebhookDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public WebhookDeliveryException(String message) {
        super(message);
    }
}
