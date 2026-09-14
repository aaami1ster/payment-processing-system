package com.example.payment.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "payment.rate-limit")
public class RateLimitProperties {

    /** When false, {@link com.example.payment.api.filter.RateLimitFilter} is a no-op. */
    private boolean enabled = true;

    private Limit user = new Limit(60, 60);
    private Limit merchant = new Limit(300, 60);
    private Limit ip = new Limit(120, 60);

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Limit {
        /** Max requests in the window. */
        private int capacity = 60;
        /** Window length in seconds. */
        private int windowSeconds = 60;
    }
}
