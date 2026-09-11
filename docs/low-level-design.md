# Low-Level Design

> **Docs index:** [README.md](README.md) · [requirements-and-assumptions.md](requirements-and-assumptions.md) · [high-level-design.md](high-level-design.md) · [low-level-design.md](low-level-design.md)

This document covers fraud rules, domain and data models, API contracts, concurrency/idempotency details, observability, testing, implementation sequence, and optional enhancement contracts.

See [high-level-design.md](high-level-design.md) for architecture and trade-offs, and [requirements-and-assumptions.md](requirements-and-assumptions.md) for goals and assumptions.

---

## Fraud Detection Engine



### Why a dedicated engine (not inline `if`s in the service)

Putting rules in `ProcessTransactionHandler` would mix orchestration with policy and make Rule 4 (flag-but-allow) easy to get wrong when combined with declines.

Rules live in a **small in-process engine**:

- `FraudRule` — one class per rule, independently unit-tested.
- `FraudEngine` — runs all rules, aggregates by severity.
- `FraudContext` — immutable snapshot (user, amount, category, velocity count, clock).
- No I/O inside a rule. The service gathers data; the engine only decides.

Do **not** introduce Drools or an external rules engine for four fixed rules. Adding a fifth rule is a new class plus registration.

```mermaid
classDiagram
    class FraudRule {
        <<interface>>
        +id() RuleId
        +evaluate(FraudContext) Optional~RuleResult~
    }
    class FraudEngine {
        -List~FraudRule~ rules
        +evaluate(FraudContext) FraudDecision
    }
    class FraudContext {
        +user User
        +amount BigDecimal
        +category Category
        +recentTxnCount int
        +now Instant
    }
    class FraudDecision {
        +status TransactionStatus
        +triggered List~RuleResult~
    }
    class RuleResult {
        +ruleId RuleId
        +severity Severity
        +reason String
    }
    class AmountWithoutApprovalRule
    class VelocityRule
    class HighRiskCategoryRule
    class NewUserHighAmountRule

    FraudEngine --> FraudRule
    FraudEngine --> FraudDecision
    FraudRule <|.. AmountWithoutApprovalRule
    FraudRule <|.. VelocityRule
    FraudRule <|.. HighRiskCategoryRule
    FraudRule <|.. NewUserHighAmountRule
    AmountWithoutApprovalRule ..> FraudContext
    FraudDecision --> RuleResult
```





### Aggregation / precedence

```
severity(DECLINE) > severity(FLAG) > severity(ALLOW)

if any rule returns DECLINE → TransactionStatus.DECLINED
else if any rule returns FLAG    → TransactionStatus.FLAGGED
else                             → TransactionStatus.APPROVED
```

All rules always run. Example: amount `12000`, category `CRYPTO`, user age 10 days can trigger `AMOUNT_WITHOUT_APPROVAL` (DECLINE), `HIGH_RISK_CATEGORY` (DECLINE), and `NEW_USER_HIGH_AMOUNT` (FLAG). Final decision is `DECLINED`; audit still contains all three triggered rules.

`FLAGGED` is a **successful** authorization that requires review. The transaction is stored and the client receives `201`.

### Rules


| ID                        | Condition                                                                                                  | Outcome   |
| ------------------------- | ---------------------------------------------------------------------------------------------------------- | --------- |
| `AMOUNT_WITHOUT_APPROVAL` | `amount > 10_000` **and** `amount > user.preApprovedTransactionLimit` (`null` or `0` = no prior approval)  | `DECLINE` |
| `VELOCITY`                | count of this user's `APPROVED`**/**`FLAGGED` rows with `created_at` in `[now - 60s, now]` **already ≥ 3** | `DECLINE` |
| `HIGH_RISK_CATEGORY`      | category ∈ high-risk set **and** `amount > 5_000`                                                          | `DECLINE` |
| `NEW_USER_HIGH_AMOUNT`    | `amount > 5_000` **and** `user.createdAt > now - 30 days` (user younger than 30 days)                      | `FLAG`    |


`preApprovedTransactionLimit` is the “prior user approval” from Rule 1. Operations raises it via `PATCH /users/{id}` after an offline approval. A limit of `15000` allows a `12000` payment and still declines `16000`.

High-risk categories are a Spring `@ConfigurationProperties` set so they can change without a code edit.

### Clock

Rules use a `Clock` bean (`Clock.systemUTC()` in prod, fixed clock in tests) so 30-day and 60-second windows are deterministic.

---

## Rule 2 — Velocity (authoritative definition)

> **For each incoming transaction, evaluate a sliding 60-second window from** `now - 60 seconds` **through** `now`**, inclusive. Count only previously persisted transactions for the same user whose status is** `APPROVED` **or** `FLAGGED`**. If three or more qualifying transactions already exist, the incoming transaction is declined. Concurrent requests for the same user are serialized using** `SELECT … FOR UPDATE` **on the user row, with the lock, velocity query, fraud evaluation, transaction insert, and audit-outbox insert occurring inside the same PostgreSQL transaction.**



### Sliding window (not fixed buckets)

```text
windowStart = currentTransactionTime - 60 seconds
evaluate transactions in [windowStart, now]
```

Example: new transaction at `12:01:10` → window `12:00:10` … `12:01:10`.

Not clock-minute buckets such as `12:00:00 → 12:00:59`.

### Inclusive time boundary

```sql
created_at >= :windowStart   -- windowStart = now - 60 seconds
AND created_at <= :now
```

A row exactly at `now - 60s` **is** counted. A row at `now - 60s - 1ms` is **not**.

### Counting semantics

Count only successful authorization outcomes:

```text
APPROVED, FLAGGED   → counted
DECLINED            → not counted
```

Example history in-window: `APPROVED`, `APPROVED`, `DECLINED`, `FLAGGED` → Rule 2 count is **3**, not 4. A declined attempt does not extend the velocity block.

### Reference query

```sql
SELECT COUNT(*)
FROM transactions
WHERE user_id = :userId
  AND status IN ('APPROVED', 'FLAGGED')
  AND created_at >= :windowStart
  AND created_at <= :now;
```



### Threshold

```java
if (recentCount >= 3) {
    return RuleResult.decline(RuleId.VELOCITY);
}
```

Example: `T1=12:00:20`, `T2=12:00:35`, `T3=12:00:50`, new request `12:01:00` → `recentCount = 3` → incoming would be #4 → `DECLINE`.

### Concurrency protection

```sql
SELECT *
FROM users
WHERE id = :userId
FOR UPDATE;
```

```text
Request A                         Request B
SELECT USER FOR UPDATE
acquires lock
                                  SELECT USER FOR UPDATE
                                  waits
count recent = 2
process transaction #3
commit / release lock
                                  acquires lock
                                  count recent = 3
                                  transaction #4 DECLINED
                                  commit
```

Different users lock different rows and proceed concurrently.

---

## Domain Model

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





### Enumerations

- `TransactionStatus`: `APPROVED` | `FLAGGED` | `DECLINED`
- `KycStatus`: `PENDING` | `VERIFIED` | `REJECTED`
- `Category`: `GROCERIES`, `TRAVEL`, `ELECTRONICS`, `GAMBLING`, `CRYPTO`, `CASH_ADVANCE`, `MONEY_TRANSFER`, `ADULT`, `OTHER`
- `Severity`: `ALLOW` | `FLAG` | `DECLINE`
- `OutboxStatus`: `PENDING` | `PUBLISHED`

`UserSnapshot` is copied into the audit document at decision time so later KYC or limit changes do not rewrite history. KYC is audit context only — no fraud rule reads it.

---

## Data Design



### PostgreSQL (system of record)

Isolation level: `READ COMMITTED`. Per-user invariants are protected by `SELECT … FOR UPDATE` on the user row, not by raising isolation globally.

`users`


| Column                           | Type                   | Notes                                 |
| -------------------------------- | ---------------------- | ------------------------------------- |
| `id`                             | `UUID PK`              |                                       |
| `email`                          | `TEXT UNIQUE NOT NULL` |                                       |
| `kyc_status`                     | `TEXT NOT NULL`        | Audit / profile only                  |
| `pre_approved_transaction_limit` | `NUMERIC(19,4)`        | `NULL` = no high-value prior approval |
| `created_at`                     | `TIMESTAMPTZ NOT NULL` | Used by Rule 4                        |
| `updated_at`                     | `TIMESTAMPTZ NOT NULL` |                                       |


`transactions`


| Column                | Type                       | Notes                                               |
| --------------------- | -------------------------- | --------------------------------------------------- |
| `id`                  | `UUID PK`                  | Returned to the client                              |
| `user_id`             | `UUID NOT NULL FK → users` |                                                     |
| `merchant_id`         | `TEXT NOT NULL`            | Opaque merchant identifier                          |
| `amount`              | `NUMERIC(19,4) NOT NULL`   | `CHECK (amount > 0)`                                |
| `category`            | `TEXT NOT NULL`            |                                                     |
| `status`              | `TEXT NOT NULL`            | APPROVED / FLAGGED / DECLINED                       |
| `rules_triggered`     | `TEXT[]`                   | Rule IDs                                            |
| `idempotency_key`     | `TEXT`                     | Nullable                                            |
| `request_fingerprint` | `TEXT`                     | SHA-256 of canonical payload; used with idempotency |
| `created_at`          | `TIMESTAMPTZ NOT NULL`     | Decision time; velocity window                      |


Indexes:

```sql
CREATE INDEX idx_transaction_user_created
ON transactions(user_id, created_at DESC);

-- Optional; helpful when filtering by status for Rule 2
CREATE INDEX idx_transaction_user_status_created
ON transactions(user_id, status, created_at DESC);

CREATE UNIQUE INDEX idx_transaction_user_idempotency
ON transactions(user_id, idempotency_key)
WHERE idempotency_key IS NOT NULL;
```

The simpler `(user_id, created_at)` index is sufficient at challenge scale. The unique partial index is the final idempotency invariant.

`audit_outbox`


| Column            | Type                     | Notes                        |
| ----------------- | ------------------------ | ---------------------------- |
| `id`              | `BIGSERIAL PK`           |                              |
| `transaction_id`  | `UUID NOT NULL UNIQUE`   |                              |
| `payload`         | `JSONB NOT NULL`         | Full audit document          |
| `status`          | `TEXT NOT NULL`          | `PENDING` / `PUBLISHED` only |
| `attempts`        | `INT NOT NULL DEFAULT 0` |                              |
| `last_error`      | `TEXT`                   | Last publish failure message |
| `next_attempt_at` | `TIMESTAMPTZ NOT NULL`   | Exponential backoff          |
| `created_at`      | `TIMESTAMPTZ NOT NULL`   |                              |


```sql
CREATE INDEX idx_audit_outbox_pending
ON audit_outbox(status, next_attempt_at);
```

No terminal `FAILED` state that silently stops delivery. Events are retained; backoff grows; metrics and alerts fire. If a dead-letter path is added later, the original payload remains durable and recoverable.

The outbox row is inserted in the **same** database transaction as the payment row (transactional outbox).

### Database constraints (defense in depth)

API validation gives good client errors; constraints protect invariants:

- `CHECK (amount > 0)`
- `NOT NULL` on required fields
- `UNIQUE users.email`
- `UNIQUE (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL`
- `UNIQUE audit_outbox.transaction_id`



### MongoDB (audit projection)

Collection `audit_logs` — **append-only**. Existing audit records are **never overwritten**. Corrections, if ever needed, are additional events.

```json
{
  "_id": "txn-uuid",
  "transactionId": "txn-uuid",
  "userId": "user-uuid",
  "decision": "FLAGGED",
  "rulesTriggered": [
    { "ruleId": "NEW_USER_HIGH_AMOUNT", "severity": "FLAG", "reason": "..." }
  ],
  "userContext": {
    "kycStatus": "VERIFIED",
    "preApprovedTransactionLimit": 0,
    "userCreatedAt": "2026-08-20T10:00:00Z"
  },
  "amount": "6200.00",
  "merchantId": "m_123",
  "category": "ELECTRONICS",
  "timestamp": "2026-09-10T16:01:02Z"
}
```

Publishing uses **immutable insert** with `_id = transactionId`. On retry, `DuplicateKeyException` means the audit was already persisted — treat as success and mark outbox `PUBLISHED`. Do not upsert/rewrite history.

Indexes: `{ userId: 1, timestamp: -1 }`, `{ decision: 1, timestamp: -1 }`.

### Schema migrations

**Liquibase** only (not Flyway). Target runtime: **PostgreSQL 18** (Compose: `postgres:18-alpine`). No Mongo schema migrations — audit indexes are created by the app/repository layer when that phase lands.

```text
db/changelog/
├── db.changelog-master.yaml
└── changes/
    ├── 001-create-users.yaml
    ├── 002-create-transactions.yaml
    └── 003-create-audit-outbox.yaml
```

DDL uses portable Postgres features (`UUID`, `TEXT[]`, `JSONB`, `TIMESTAMPTZ`, partial unique indexes). No version-specific syntax is required for 18.
---

## API Design

Base path: `/api/v1`. JSON. `X-Request-Id` is accepted or generated, echoed as a response header, and included in every body via `meta.requestId`.

Controllers return `ResponseEntity<ApiResponse<T>>` so the **HTTP status line** and the envelope stay in sync. Do **not** put an HTTP `statusCode` field in the body (HTTP is authoritative).

### Standard response envelope

Every endpoint uses the same shape:

```java
public record ApiResponse<T>(
        T data,
        String message,
        List<ApiError> errors,
        ApiMeta meta
) {}

public record ApiError(
        String code,
        String field,
        String message
) {}

public record ApiMeta(
        String requestId
) {}
```

| Field | Success | Error | Notes |
| ----- | ------- | ----- | ----- |
| `data` | object or array | `null` | The resource(s) |
| `message` | short human summary | short human summary | Not for client branching |
| `errors` | `[]` | one or more items | Stable `code` for clients; optional `field` for validation |
| `meta.requestId` | always | always | Correlate with logs / `X-Request-Id` |

**Factory helpers** (e.g. `ApiResponse.ok`, `ApiResponse.created`, `ApiResponse.failure`) keep controllers thin. `GlobalExceptionHandler` maps domain/API exceptions to the same envelope.

**Business vs API errors:** fraud `DECLINED` / `FLAGGED` are **success** payloads inside `data.status`. They must **never** appear in `errors[]`. `errors[]` is only for request, authz, conflict, and infrastructure failures.

### Transactions

`POST /api/v1/transactions`

Headers: optional `Idempotency-Key`.

Request body:

```json
{
  "amount": 6200.00,
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "merchantId": "mch_9f2",
  "category": "ELECTRONICS"
}
```


| Outcome                                 | HTTP          | `data`                         | `errors` |
| --------------------------------------- | ------------- | ------------------------------ | -------- |
| Processed (any business outcome)        | `201 Created` | transaction (`APPROVED` / `FLAGGED` / `DECLINED`) | `[]` |
| Unknown user                            | `404`         | `null`                         | `USER_NOT_FOUND` |
| Same idempotency key, different payload | `409`         | `null`                         | `IDEMPOTENCY_CONFLICT` |
| Malformed / invalid request             | `400`         | `null`                         | `VALIDATION_ERROR` (per field) |
| Resource not found (`GET`)              | `404`         | `null`                         | `NOT_FOUND` |
| PostgreSQL unavailable / commit failed  | `503`         | `null`                         | `SERVICE_UNAVAILABLE` |


All three business statuses return `201` because each represents a successfully evaluated and **persisted** transaction resource. The authorization outcome lives in `data.status`. `DECLINED` means the **business** rejected the payment — not that the HTTP request failed.

Success example (`DECLINED`):

```json
{
  "data": {
    "transactionId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "status": "DECLINED",
    "amount": 12000.00,
    "rulesTriggered": ["HIGH_RISK_CATEGORY"],
    "createdAt": "2026-09-10T16:01:02Z"
  },
  "message": "Transaction processed",
  "errors": [],
  "meta": {
    "requestId": "req_abc123"
  }
}
```

Success example (`FLAGGED`):

```json
{
  "data": {
    "transactionId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "status": "FLAGGED",
    "amount": 6200.00,
    "rulesTriggered": ["NEW_USER_HIGH_AMOUNT"],
    "createdAt": "2026-09-10T16:01:02Z"
  },
  "message": "Transaction processed",
  "errors": [],
  "meta": {
    "requestId": "req_abc123"
  }
}
```

Validation error example:

```json
{
  "data": null,
  "message": "Validation failed",
  "errors": [
    {
      "code": "VALIDATION_ERROR",
      "field": "amount",
      "message": "must be greater than 0"
    }
  ],
  "meta": {
    "requestId": "req_abc123"
  }
}
```

Infrastructure error example:

```json
{
  "data": null,
  "message": "Service temporarily unavailable",
  "errors": [
    {
      "code": "SERVICE_UNAVAILABLE",
      "field": null,
      "message": "Unable to persist transaction"
    }
  ],
  "meta": {
    "requestId": "req_abc123"
  }
}
```

`GET /api/v1/transactions/{id}` — fetch a stored decision (useful for idempotent clients and ops). HTTP `200` + envelope with transaction in `data`, or `404` + `NOT_FOUND`.

Optional list/export: `GET /api/v1/transactions` with filters and cursor pagination — see [Bulk transaction export](#4-bulk-transaction-export-with-pagination).

### Infrastructure failure vs business decline

**Business decline**

```text
Authoritative data loaded successfully
        ↓
Fraud engine evaluated transaction
        ↓
A DECLINE rule triggered
        ↓
Transaction persisted as DECLINED
        ↓
201 + ApiResponse.data.status = DECLINED  (errors = [])
```

**Infrastructure failure**

```text
PostgreSQL unavailable or commit fails
        ↓
Transaction could not be safely evaluated/persisted
        ↓
503 + ApiResponse.data = null, errors = [SERVICE_UNAVAILABLE]
```

`DECLINED` = the business rejected the transaction. `503` = the system could not safely reach or durably store a business decision. Never fabricate a fraud decline for an infrastructure outage.

### Users


| Method  | Path                 | HTTP success | Purpose                                         |
| ------- | -------------------- | ------------ | ----------------------------------------------- |
| `POST`  | `/api/v1/users`      | `201`        | Create user (sets `createdAt`)                  |
| `GET`   | `/api/v1/users/{id}` | `200`        | Query profile                                   |
| `PATCH` | `/api/v1/users/{id}` | `200`        | Update KYC and/or `preApprovedTransactionLimit` |


Create body: `{ "email", "kycStatus"? }`. Default KYC `PENDING`, `preApprovedTransactionLimit` null.

All user endpoints use the same `ApiResponse` envelope (`data` = user resource on success).

### Idempotency

Do **not** compare raw JSON strings. Store a deterministic request fingerprint from canonical business fields:

```text
SHA-256(canonical(userId, merchantId, amount, category))
```

| Same key + same fingerprint | Return original transaction (`201` + same `data`) |
| Same key + different fingerprint | `409` + `IDEMPOTENCY_CONFLICT` |

Application checks (before and after acquiring the user lock) provide friendly handling. The unique DB constraint is the final correctness guarantee:

> Application logic provides friendly handling; database constraints protect invariants.

Recommended sequence:

```text
1. Lookup existing transaction using userId + Idempotency-Key
2. If found and fingerprint matches → return original result
3. If found and fingerprint differs → 409
4. Acquire user row with SELECT … FOR UPDATE
5. Check idempotency again
6. If another request created it while waiting → return it
7. Otherwise process normally
```

---

## Concurrency, Idempotency, and Time

```mermaid
flowchart TD
    A[Request] --> B{Idempotency key?}
    B -->|yes, same fingerprint| C[Return stored transaction]
    B -->|yes, different fingerprint| D[409 Conflict]
    B -->|no / miss| E[BEGIN + SELECT user FOR UPDATE]
    E --> F[Re-check idempotency]
    F --> G[Velocity count + FraudEngine]
    G --> H[Insert txn + outbox]
    H --> I[COMMIT]
    I -->|ok| J[201 + status]
    I -->|fail| K[503]
```



- **Velocity** uses inclusive `[now - 60s, now]` on `APPROVED`/`FLAGGED` only, under the same user-row lock as the insert.
- **Time** is `Instant.now(clock)` applied as `created_at` for both the row and the 60s/30d windows — one timestamp per request.
- **Idempotency** unique index prevents double-insert if two retries overlap; the loser re-reads the winner’s row.

---

## Observability

### Structured logging (SLF4J + Logback)

**Requirement:** SLF4J API + Logback implementation. **Never** `System.out.println` / `System.err.println`.

All application classes obtain loggers through a single factory so usage stays consistent and easy to review:

```text
common/logging/LogFactory.java
```

```java
package com.example.payment.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LogFactory {

    private LogFactory() {}

    public static Logger getLogger(Class<?> type) {
        return LoggerFactory.getLogger(type);
    }
}
```

Usage in every class:

```java
private static final Logger log = LogFactory.getLogger(ProcessTransactionHandler.class);

log.info("transaction.processed status={} transactionId={} durationMs={}",
        status, transactionId, durationMs);
```

**Conventions:**

- One `static final Logger` per class via `LogFactory.getLogger(Class)`.
- Prefer parameterized messages (`{}`), not string concatenation.
- JSON layout via Logback (e.g. Logstash encoder); include MDC fields where useful: `requestId`, `transactionId`, `userId` (privacy permitting), `status`, `rulesTriggered`, `durationMs`.
- Filter / interceptor sets `requestId` (from `X-Request-Id` or generated) into MDC at request start and clears it at end.
- Do **not** log credentials, tokens, or full sensitive financial payloads.

Metrics:


| Metric                               | Purpose                           |
| ------------------------------------ | --------------------------------- |
| `payment.transactions.total{status}` | Throughput by outcome             |
| `fraud.rule.triggered{rule}`         | Which rules fire                  |
| `payment.processing.duration`        | End-to-end latency                |
| `velocity.lock.wait`                 | User-row lock contention          |
| `outbox.pending.count`               | Backlog size                      |
| `outbox.oldest.pending.age`          | How far Mongo audit lag has grown |
| `outbox.publish.failures`            | Publish errors                    |
| `mongo.publish.duration`             | Publisher latency                 |


Health (Spring Actuator):

- **Liveness** = process healthy.
- **Readiness** = PostgreSQL up (required to safely process payments).
- Mongo down → still **READY** but degraded (async audit projection only).

---

## Testing Strategy (mapped to this design)

Use **Testcontainers** (PostgreSQL + MongoDB), not H2 — the design depends on `FOR UPDATE`, `SKIP LOCKED`, partial indexes, and `TIMESTAMPTZ`.


| Layer   | What                                                                                                                                                                  | How                                 |
| ------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| Domain  | Each `FraudRule`; aggregation (no rules → APPROVED, FLAG only, DECLINE only, FLAG+DECLINE → DECLINED, multiple DECLINEs); Rule 2 inclusive 60s edge; Rule 4 age edges | JUnit 5, fixed `Clock`, no Spring   |
| Command | Lock → re-check idempotency → engine → persist outbox; `503` on PG failure; fingerprint conflict                                                                      | Mockito on repositories / out-ports |
| Query   | `GetUser` / `GetTransaction` return stored views; 404 when missing                                                                                                    | Mockito or `@DataJpaTest`           |
| Data    | Unique idempotency, velocity query, outbox insert with txn rollback                                                                                                   | Testcontainers PostgreSQL           |
| Audit   | Insert + DuplicateKey = already delivered; Mongo outage → PENDING → recover → PUBLISHED                                                                               | Testcontainers Mongo (+ PG)         |
| API     | Envelope on all statuses; `201` (APPROVED/FLAGGED/DECLINED) / `404` / `409` / `503`; validation `errors[]`                                 | `@SpringBootTest` + Testcontainers  |




### Concurrency regression (Rule 2 + lock)

**Incorrect:** three existing qualifying transactions + two concurrent requests (both already violate Rule 2).

**Correct:**

```text
2 existing APPROVED/FLAGGED transactions
+
2 simultaneous requests
```

Expected: Request A sees `recentCount = 2` → may `APPROVED`/`FLAGGED` as #3. Request B then sees `recentCount = 3` → `DECLINED` as #4.

Assertion: exactly one may be `APPROVED`/`FLAGGED`; exactly one is `DECLINED` by Rule 2.

### Idempotency tests

- Same key + same fingerprint → original `T1`; no second transaction, no second outbox, no extra Rule 2 count.
- Same key + different payload → `409`.
- Concurrent duplicates with same key → exactly one transaction, one outbox event, both resolve to the same result.



### Time-boundary tests (injected `Clock`)

- Rule 2: transaction exactly 60 seconds old → **counted** (`created_at >= now - 60s`).
- Rule 2: 60 seconds + 1 ms old → **not** counted.
- Rule 4: user exactly 30 days old, younger than 30 days, older than 30 days — document inclusive/exclusive consistently with `user.createdAt > now - 30 days` (exactly 30 days old → **not** flagged).



### Outbox tests

- Mongo available → audit inserted → outbox `PUBLISHED`.
- Mongo unavailable → transaction still commits → outbox `PENDING` → Mongo restored → publisher retries → insert → `PUBLISHED`.
- Ambiguous retry: Mongo insert succeeded but outbox not marked → retry → duplicate `_id` → treat as delivered → mark `PUBLISHED`.

---

## Implementation Sequence

Aligned with the challenge’s suggested order:

1. Docker Compose + Liquibase schema (users, transactions, outbox) + Mongo collection.
2. Domain model + `FraudEngine` and four rules with unit tests (including aggregation and time boundaries).
3. `ProcessTransactionHandler` with `SELECT … FOR UPDATE`, double idempotency check, and outbox write inside one PG transaction.
4. User command/query handlers (`CreateUser`, `UpdateUser` for `preApprovedTransactionLimit`/KYC, `GetUser`).
5. Outbox publisher (`SKIP LOCKED`, insert-or-duplicate-key, exponential backoff) + Mongo.
6. API layer (`ApiResponse` envelope, `201` for all business statuses, `503` for PG failures), OpenAPI, structured logging via `LogFactory` + Logback JSON, metrics.
7. Integration tests (Testcontainers), JaCoCo, README (include the core guarantee and Mongo justification).

This order keeps the fraud policy correct before HTTP and storage adapters accumulate around it.

---

## Optional Enhancements (contracts & details)

Additive designs for the challenge’s optional list. **Idempotency is already specified above** and is treated as core. The rest are not required for MVP. Architecture fit: [high-level-design.md](high-level-design.md); status table: [requirements-and-assumptions.md](requirements-and-assumptions.md).

### 1. Idempotency keys (core — see above)

Already covered under [API Design → Idempotency](#idempotency) and [Concurrency, Idempotency, and Time](#concurrency-idempotency-and-time):

- Header: optional `Idempotency-Key`
- Fingerprint: `SHA-256(canonical(userId, merchantId, amount, category))`
- Storage: `transactions.idempotency_key` + `request_fingerprint`
- Invariant: partial unique index on `(user_id, idempotency_key)`
- Conflict: same key + different fingerprint → `409` + `IDEMPOTENCY_CONFLICT`

No additional schema or endpoints are required for this enhancement.

### 2. Webhook / notification system

**Goal:** Push transaction events to subscriber URLs after a durable decision, without blocking the authorize response.

#### Subscription model (optional table or config)

| Field | Type | Notes |
| ----- | ---- | ----- |
| `id` | UUID | |
| `merchant_id` | TEXT | Scope delivery (or `*` for global/ops) |
| `target_url` | TEXT | HTTPS endpoint |
| `secret` | TEXT | HMAC signing key |
| `events` | TEXT[] | e.g. `TRANSACTION_APPROVED`, `TRANSACTION_FLAGGED`, `TRANSACTION_DECLINED` |
| `active` | BOOLEAN | |
| `created_at` | TIMESTAMPTZ | |

MVP alternative: static YAML/`application.yml` subscribers — no CRUD API required.

#### Outbox extension

Extend `audit_outbox` (or introduce a parallel `notification_outbox`) with a destination:

| Column | Notes |
| ------ | ----- |
| `destination` | `AUDIT` \| `WEBHOOK` |
| `payload` | JSONB — for `WEBHOOK`, the HTTP body to POST |
| `status` / `attempts` / `next_attempt_at` | Same retry semantics as today |

On payment commit, insert:

1. One `AUDIT` row (Mongo projection) — **always** (MVP).
2. Zero or more `WEBHOOK` rows — one per matching active subscription (optional).

Publisher claim query remains `FOR UPDATE SKIP LOCKED`, filtered by `status = PENDING` and `next_attempt_at <= now()`. Branch by `destination`.

#### Delivery sequence

```text
1. Claim PENDING WEBHOOK outbox row
2. POST payload to target_url
   Headers:
     Content-Type: application/json
     X-Request-Id: <id>
     X-Signature: sha256=<HMAC-SHA256(secret, rawBody)>
3. 2xx → mark PUBLISHED
4. 5xx / timeout → attempts++, schedule next_attempt_at (exponential backoff)
5. 4xx (except 429) → retain + alert (poison); do not silent-drop
```

#### Example webhook payload

```json
{
  "event": "TRANSACTION_FLAGGED",
  "transactionId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "merchantId": "mch_9f2",
  "status": "FLAGGED",
  "amount": "6200.00",
  "category": "ELECTRONICS",
  "rulesTriggered": ["NEW_USER_HIGH_AMOUNT"],
  "createdAt": "2026-09-10T16:01:02Z"
}
```

Subscribers must treat delivery as **at-least-once** and dedupe on `transactionId` (+ `event`).

#### Package touchpoints

- `integration.webhook.WebhookClient` — HTTP + HMAC
- `data.outbox.OutboxPublisher` — destination switch
- No change to `FraudEngine` or authorize HTTP latency budget

### 3. Rate limiting per user or merchant

**Goal:** Cap request rate before handlers run. Distinct from Rule 2 (fraud velocity on successful authorizations).

#### Placement

```text
HTTP request
  → RateLimitFilter (or gateway)
  → Controller
  → Command / Query handler
```

#### Suggested defaults (configurable)

| Key | Limit | Window |
| --- | ----- | ------ |
| `userId` (from body/path when present) | 60 requests | 1 minute |
| `merchantId` (from body when present) | 300 requests | 1 minute |
| IP (fallback for routes without ids) | 120 requests | 1 minute |

Implementation sketch: Bucket4j (in-process for single instance; Redis-backed buckets if multi-instance).

#### API response when limited

HTTP `429 Too Many Requests` with the standard envelope:

```json
{
  "data": null,
  "message": "Rate limit exceeded",
  "errors": [
    {
      "code": "RATE_LIMIT_EXCEEDED",
      "field": null,
      "message": "Too many requests; retry later"
    }
  ],
  "meta": {
    "requestId": "req_abc123"
  }
}
```

Optional response header: `Retry-After: <seconds>`.

#### Tests

- Under limit → request reaches handler.
- Over limit → `429` + `RATE_LIMIT_EXCEEDED`; no transaction insert, no outbox row.
- Rule 2 still declines the 4th **successful** authorization even when rate limit allows the call through.

### 4. Bulk transaction export with pagination

**Goal:** List/export transactions for a user (and optional time range) without loading the full table.

#### Endpoint

`GET /api/v1/transactions`

| Query param | Required | Notes |
| ----------- | -------- | ----- |
| `userId` | yes | Scope to one user |
| `from` | no | Inclusive lower bound on `created_at` (ISO-8601) |
| `to` | no | Inclusive upper bound on `created_at` |
| `status` | no | `APPROVED` \| `FLAGGED` \| `DECLINED` |
| `cursor` | no | Opaque; encode `(created_at, id)` of last row |
| `limit` | no | Default 50, max 200 |

#### Handler

`ListTransactionsHandler` (query) — read-only; uses `idx_transaction_user_created` (and status variant when filtering).

Cursor page (stable under inserts):

```sql
SELECT *
FROM transactions
WHERE user_id = :userId
  AND created_at >= COALESCE(:from, '-infinity')
  AND created_at <= COALESCE(:to, 'infinity')
  AND (created_at, id) < (:cursorCreatedAt, :cursorId)  -- for DESC pages
ORDER BY created_at DESC, id DESC
LIMIT :limit;
```

#### Success response shape

```json
{
  "data": {
    "items": [ /* TransactionResponse… */ ],
    "nextCursor": "eyJjcmVhdGVkQXQiOiIyMDI2LTA5LTEwVC4uLiIsImlkIjoiLi4uIn0",
    "hasMore": true
  },
  "message": "Transactions listed",
  "errors": [],
  "meta": { "requestId": "req_abc123" }
}
```

HTTP `200`. Empty page → `items: []`, `hasMore: false`, `nextCursor: null`.

#### Audit export (optional companion)

`GET /api/v1/audit-logs?userId=&from=&to=&cursor=` against Mongo `{ userId: 1, timestamp: -1 }` when consumers need rule detail / `userContext` snapshots. Same cursor pattern; eventually consistent with PG.

### 5. Redis caching for user data or fraud rules

**Goal:** Reduce PG load on hot reads. Authorize path unchanged.

#### Keys and TTL

| Key | Value | TTL | Written by | Invalidated by |
| --- | ----- | --- | ---------- | -------------- |
| `user:{id}` | serialized user view | e.g. 60s | `GetUserHandler` on miss | `UpdateUserHandler` (DEL); optional on create |
| `fraud:config` | high-risk categories + thresholds snapshot | e.g. 30s | first read of `FraudProperties` / config adapter | deploy/config refresh; short TTL is enough |

#### Rules

```text
GetUserHandler:
  GET user:{id} → hit → return
                → miss → PG → SET user:{id} EX 60 → return

UpdateUserHandler:
  PG update → DEL user:{id}

ProcessTransactionHandler:
  findByIdForUpdate only — never read or write Redis for the user row
```

Fraud **rule classes** remain in-process. Redis may cache **configuration**, not replace `FraudEngine`.

#### Resilience

- Redis down → queries fall through to PostgreSQL (degraded latency, still correct).
- Readiness: Redis optional (like Mongo) — app stays READY if PG is up.
- Tests: Testcontainers Redis only when the cache profile is enabled; core suite must pass without Redis.

#### Compose (when enabled)

Add `redis` service; Spring Data Redis / Lettuce with short timeouts and circuit breaker around cache get/set.

### 6. SonarQube / static code analysis

**Goal:** CI quality gate without runtime impact.

#### Integration sketch

```text
mvn -B verify          # tests + JaCoCo (≥ 75%)
mvn -B sonar:sonar     # CI only; SONAR_HOST_URL + token from secrets
```

| Check | Example gate |
| ----- | ------------ |
| Coverage | JaCoCo line ≥ 75% (align with challenge) |
| Bugs / Vulnerabilities | 0 new blocker/critical |
| Code smells | threshold per team preference |
| Duplications | e.g. < 5% new code |

Local alternative without a Sonar server: SpotBugs + PMD + Checkstyle Maven plugins on `verify`. Prefer one static-analysis path in CI docs so reviewers know how to run it.

#### Non-impact

- No Sonar container required in app `docker-compose`.
- No production dependency.
- Does not change API contracts or fraud semantics.

### Suggested implementation order (if taken on)

1. Idempotency — **done in core design**
2. Rate limiting — small filter, high demo value
3. Redis query cache — builds on CQRS-lite read path
4. Bulk export — new query + cursor tests
5. Webhooks — outbox destination + integration client
6. SonarQube — CI wiring + README note

---

