# Requirements and Assumptions

> **Docs index:** [README.md](README.md) · [requirements-and-assumptions.md](requirements-and-assumptions.md) · [high-level-design.md](high-level-design.md) · [low-level-design.md](low-level-design.md)

This document captures functional and non-functional goals, explicit assumptions, scope boundaries, and optional enhancements for the payment processing system.

**Core guarantee:** No transaction decision is acknowledged to the client unless the transaction and its audit intent have been durably committed to PostgreSQL.

```text
APPROVED response  → durable APPROVED transaction exists
FLAGGED response   → durable FLAGGED transaction exists
DECLINED response  → durable DECLINED transaction exists
Persistence unavailable → no business decision acknowledged → 503 Service Unavailable
```

---

## Goals and Constraints



### Functional

- Accept a transaction (`amount`, `userId`, `merchantId`, `category`), assign an ID and timestamp, and return `APPROVED`, `FLAGGED`, or `DECLINED`.
- Evaluate four fraud rules on every request (all rules run; results are aggregated).
- Persist an audit record for every decision (rules triggered, timestamp, user context). Audit data must survive restarts.
- Manage users: creation date, KYC status, pre-approved transaction limits; CRUD-style REST APIs.



### Non-functional

- Concurrent requests for the same user must not bypass velocity limits.
- Downstream slowness or outage must not lose transaction data or audit intent.
- Layered, testable Java (Spring Boot 3.x, Maven, JUnit 5, Testcontainers, JaCoCo ≥ 75%).
- Structured logging (SLF4J / Logback). Observable HTTP API (OpenAPI).



### Explicit assumptions

These are not specified in the brief; they are called out so behavior is deterministic:


| Topic                  | Assumption                                                                                                                                            |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| Currency               | Single currency (`SAR`). Amounts stored as `NUMERIC(19,4)`.                                                                                           |
| Isolation              | PostgreSQL `READ COMMITTED` plus targeted `SELECT … FOR UPDATE` on the user row. No need for global `SERIALIZABLE`.                                   |
| Velocity window        | **Sliding** 60-second window: `[now - 60s, now]` inclusive. Not fixed clock-minute buckets.                                                           |
| Velocity counting      | Count only `APPROVED` and `FLAGGED`. **Declined** attempts are **not** counted.                                                                       |
| Velocity threshold     | Before processing: if `recentCount >= 3`, decline. The incoming request would be the 4th authorized transaction.                                      |
| Rule composition       | All rules run. Any `DECLINE` wins over `FLAG`. Otherwise `FLAG` wins over `APPROVED`.                                                                 |
| High-risk categories   | `GAMBLING`, `CRYPTO`, `CASH_ADVANCE`, `ADULT`. Extensible via config.                                                                                 |
| KYC                    | Stored and included in audit context. **No** transaction decision is based on KYC — the challenge defines no KYC-specific fraud rule.                 |
| Missing user           | Unknown `userId` is a client error (`404`), not a fraud decline.                                                                                      |
| PostgreSQL unavailable | Return `503 Service Unavailable`. Do **not** approve and do **not** fabricate a fraud `DECLINED`. Infrastructure failure ≠ business decline.          |
| Idempotency            | Optional `Idempotency-Key` header. Same key + same request fingerprint → original result. Same key + different fingerprint → `409`.                   |
| Declined HTTP status   | `DECLINED` returns `201 Created` with `ApiResponse.data.status = DECLINED` and empty `errors`. Business outcome is in `data`, not an API error. |

---

## Optional Enhancements (how they fit)

Designed as additive; not required for the MVP path except where already adopted as core. Each item below states **status**, **intent**, **fit**, and **non-goals**. Architecture detail lives in [high-level-design.md](high-level-design.md); contracts and schemas in [low-level-design.md](low-level-design.md).


| Enhancement | Status | Intent | Fit | Non-goals |
| ----------- | ------ | ------ | --- | --------- |
| **Idempotency keys** | **Core** (already designed) | Prevent duplicate transactions on client retry | Optional `Idempotency-Key` header + request fingerprint + partial unique index on `(user_id, idempotency_key)` | Not a substitute for the Rule 2 user-row lock |
| **Webhooks / notifications** | Optional | Notify merchants/ops of transaction events after a durable decision | Extend transactional outbox with a `WEBHOOK` destination; same publisher pattern as Mongo audit (`SKIP LOCKED`, retry/backoff); client under `integration` | No sync HTTP fan-out on the authorize path; no Kafka for this scope |
| **Rate limiting** | Optional | Protect the API from abuse / burst traffic per user or merchant | HTTP filter or gateway (e.g. Bucket4j) **in addition to** Rule 2; returns `429` | Not a fraud rule — Rule 2 remains the velocity fraud control |
| **Bulk transaction export** | Optional | Let clients/ops pull historical transactions with pagination | CQRS-lite query: `GET /transactions` with filters + cursor against PostgreSQL; audit export from Mongo if needed | Not on the authorize write path; no full-table dump without pagination |
| **Redis caching** | Optional | Speed up read-heavy user profile (and optionally fraud config) lookups | Read-through cache on `GetUserHandler` (+ TTL for fraud properties); invalidate on `UpdateUserHandler` | **Never** cache the authorize-path user load; never move velocity counts or `FOR UPDATE` locking to Redis |
| **SonarQube / static analysis** | Optional | Continuous code-quality gate in CI | Maven plugin / pipeline step; quality gate alongside JaCoCo | No runtime dependency; not part of `docker-compose` app topology |


**Priority if implemented later:** idempotency (done) → rate limiting → Redis query cache → bulk export → webhooks → SonarQube.

---

## What we would not do (for this scope)

- **Two-phase commit** between PG and Mongo.
- **Approve when the user record cannot be read** — or fabricate a fraud `DECLINED` for infrastructure failure (`503` instead).
- **Acknowledge a decision before PostgreSQL commit.**
- **In-memory-only audit.** It dies with the process.
- **Overwrite Mongo audit documents** (upsert rewriting history).
- **Terminal outbox** `FAILED` **that silently stops delivery.**
- **Kafka, Redis distributed locks, Drools, microservices, event sourcing** for this challenge size.
- **Caching the authorize-path user load** (or routing it through `GetUserHandler`) — Rule 2 requires `SELECT … FOR UPDATE` on the primary.
- **A separate read database for CQRS** — CQRS-lite is application-level only.
- **Global synchronized lock** or raising the whole DB to `SERIALIZABLE`.
- **A separate microservice per rule.**

---

