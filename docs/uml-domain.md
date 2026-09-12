# Domain UML — Payment Processing System

Canonical domain model from [low-level-design.md](low-level-design.md). Also embedded in the root [README](../README.md).

```mermaid
classDiagram
    class User {
        +UUID id
        +String email
        +KycStatus kycStatus
        +BigDecimal preApprovedTransactionLimit
        +Instant createdAt
        +Instant updatedAt
    }
    class Transaction {
        +UUID id
        +UUID userId
        +String merchantId
        +BigDecimal amount
        +Category category
        +TransactionStatus status
        +List~RuleId~ rulesTriggered
        +String idempotencyKey
        +String requestFingerprint
        +Instant createdAt
    }
    class AuditLog {
        +String id
        +UUID transactionId
        +UUID userId
        +TransactionStatus decision
        +List~RuleResult~ rulesTriggered
        +UserSnapshot userContext
        +Instant timestamp
    }
    class UserSnapshot {
        +KycStatus kycStatus
        +BigDecimal preApprovedTransactionLimit
        +Instant userCreatedAt
    }
    class FraudRule {
        <<interface>>
        +evaluate(FraudContext) Optional~RuleResult~
    }

    User "1" --> "*" Transaction : places
    Transaction "1" --> "1" AuditLog : audited by
    AuditLog --> UserSnapshot
    Transaction --> FraudRule : evaluated by
```

**Enumerations:** `TransactionStatus` (`APPROVED` | `FLAGGED` | `DECLINED`), `KycStatus`, `Category`, `Severity`, `OutboxStatus`.

`UserSnapshot` is copied into the Mongo audit document at decision time so later KYC/limit changes do not rewrite history.
