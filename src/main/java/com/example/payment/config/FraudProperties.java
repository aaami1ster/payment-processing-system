package com.example.payment.config;

import com.example.payment.domain.fraud.Category;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurable fraud thresholds and high-risk categories (LLD / requirements defaults).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "fraud")
public class FraudProperties {

    private Set<Category> highRiskCategories =
            EnumSet.of(Category.GAMBLING, Category.CRYPTO, Category.CASH_ADVANCE, Category.ADULT);

    private BigDecimal amountWithoutApprovalThreshold = new BigDecimal("10000");

    private BigDecimal highRiskAmountThreshold = new BigDecimal("5000");

    private BigDecimal newUserAmountThreshold = new BigDecimal("5000");

    private int velocityThreshold = 3;

    private int velocityWindowSeconds = 60;

    private int newUserMaxAgeDays = 30;

    public void setHighRiskCategories(Set<Category> highRiskCategories) {
        this.highRiskCategories = highRiskCategories == null
                ? EnumSet.noneOf(Category.class)
                : EnumSet.copyOf(highRiskCategories);
    }
}
