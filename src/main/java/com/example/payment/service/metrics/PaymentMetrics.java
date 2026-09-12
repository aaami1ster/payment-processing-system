package com.example.payment.service.metrics;

import com.example.payment.domain.fraud.RuleId;
import com.example.payment.domain.transaction.TransactionStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Micrometer meters aligned with LLD Observability names.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry meterRegistry;
    private final Timer processingDuration;

    public PaymentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.processingDuration = Timer.builder("payment.processing.duration")
                .description("End-to-end payment processing latency")
                .register(meterRegistry);
    }

    public void recordProcessed(TransactionStatus status, Duration duration, List<RuleId> rulesTriggered) {
        meterRegistry.counter("payment.transactions.total", "status", status.name()).increment();
        processingDuration.record(duration);
        if (rulesTriggered != null) {
            for (RuleId rule : rulesTriggered) {
                meterRegistry.counter("fraud.rule.triggered", "rule", rule.name()).increment();
            }
        }
    }
}
