package com.example.payment.config;

import lombok.Getter;
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
    public static class Limit {
        /** Max requests in the window. */
        private int capacity = 60;
        /** Window length in seconds. */
        private int windowSeconds = 60;

        public Limit() {}

        public Limit(int capacity, int windowSeconds) {
            this.capacity = capacity;
            this.windowSeconds = windowSeconds;
        }
    }
}
