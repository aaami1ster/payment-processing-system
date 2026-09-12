package com.example.payment.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Payment Processing System API")
                        .description(
                                "Fraud-aware payment authorization. All responses use the ApiResponse envelope "
                                        + "(data, message, errors[], meta.requestId). Correlate with X-Request-Id.")
                        .version("v1")
                        .license(new License().name("Apache 2.0")));
    }
}
