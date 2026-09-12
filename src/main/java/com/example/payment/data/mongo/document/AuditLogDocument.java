package com.example.payment.data.mongo.document;

import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Append-only audit projection. {@code _id} equals {@code transactionId}; never upsert/overwrite.
 * Indexes are created by {@link com.example.payment.data.outbox.OutboxPublisher} (not at app startup).
 */
@Document(collection = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class AuditLogDocument {

    @Id
    private String id;

    private String transactionId;
    private String userId;
    private String decision;
    private List<RuleTriggeredEntry> rulesTriggered;
    private UserContextSnapshot userContext;
    private String amount;
    private String merchantId;
    private String category;
    private Instant timestamp;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class RuleTriggeredEntry {
        private String ruleId;
        private String severity;
        private String reason;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class UserContextSnapshot {
        private String kycStatus;
        private String preApprovedTransactionLimit;
        private Instant userCreatedAt;
    }
}
