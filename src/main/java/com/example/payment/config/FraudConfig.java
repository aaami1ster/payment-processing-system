package com.example.payment.config;

import com.example.payment.domain.fraud.FraudEngine;
import com.example.payment.domain.fraud.FraudRule;
import com.example.payment.domain.fraud.rule.AmountWithoutApprovalRule;
import com.example.payment.domain.fraud.rule.HighRiskCategoryRule;
import com.example.payment.domain.fraud.rule.NewUserHighAmountRule;
import com.example.payment.domain.fraud.rule.VelocityRule;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FraudProperties.class)
public class FraudConfig {

    @Bean
    public FraudEngine fraudEngine(FraudProperties properties) {
        List<FraudRule> rules = List.of(
                new AmountWithoutApprovalRule(properties.getAmountWithoutApprovalThreshold()),
                new VelocityRule(properties.getVelocityThreshold()),
                new HighRiskCategoryRule(
                        properties.getHighRiskCategories(),
                        properties.getHighRiskAmountThreshold()),
                new NewUserHighAmountRule(
                        properties.getNewUserAmountThreshold(),
                        Duration.ofDays(properties.getNewUserMaxAgeDays())));
        return new FraudEngine(rules);
    }
}
