package com.example.payment.config;

import com.example.payment.integration.webhook.WebhookClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfig {

    @Bean
    HttpClient webhookHttpClient(WebhookProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    WebhookClient webhookClient(HttpClient webhookHttpClient, WebhookProperties properties) {
        return new WebhookClient(webhookHttpClient, properties);
    }
}
