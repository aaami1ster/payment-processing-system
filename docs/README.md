# Payment Processing System — Design Docs

> **Docs index:** [README.md](README.md) · [requirements-and-assumptions.md](requirements-and-assumptions.md) · [high-level-design.md](high-level-design.md) · [low-level-design.md](low-level-design.md)

This folder is the **entry point** for the design. Detailed content lives in the linked docs below.

The design is sized for a single Spring Boot service with PostgreSQL and MongoDB, runnable via Docker Compose. Choices favor correctness, testability, and explainability over architectural decoration.

**Core guarantee:** No transaction decision is acknowledged to the client unless the transaction and its audit intent have been durably committed to PostgreSQL.

```text
APPROVED response  → durable APPROVED transaction exists
FLAGGED response   → durable FLAGGED transaction exists
DECLINED response  → durable DECLINED transaction exists
Persistence unavailable → no business decision acknowledged → 503 Service Unavailable
```

## Document map

| Document | Contents |
| -------- | -------- |
| [requirements-and-assumptions.md](requirements-and-assumptions.md) | Goals, constraints, explicit assumptions, optional enhancements, out-of-scope choices |
| [high-level-design.md](high-level-design.md) | Architecture, packages / CQRS-lite, request validation (DTO + handler), request flow, design decisions, resilience, deployment |
| [low-level-design.md](low-level-design.md) | Fraud engine & Rule 2, domain & data models, API envelope, layered validation, concurrency, observability, testing, implementation sequence |

## Challenge brief

The original challenge statement is in [Payment_Processing_Code_Challenge.md](Payment_Processing_Code_Challenge.md).

## Implementation plan

Phased build order, deliverables, checkboxes, and copy-paste AI prompts: [../plan.md](../plan.md).

## API testing (Bruno)

Runnable HTTP collection (docs + tests per endpoint): [../bruno/README.md](../bruno/README.md).

## Project identity

| Item | Value |
| ---- | ----- |
| Application name | `payment-processing-system` |
| Maven `groupId` | `com.example` |
| Maven `artifactId` | `payment-processing-system` |
| Base Java package | `com.example.payment` |
| Main class | `com.example.payment.PaymentProcessingApplication` |

Full package layout: see [high-level-design.md — Layered Design](high-level-design.md#layered-design-inside-the-service).
