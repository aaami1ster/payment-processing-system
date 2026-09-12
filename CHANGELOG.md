# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0] - 2026-09-12

MR title: `chore(release): 1.1.0`

### Added

- Wire Sonar-ready JaCoCo XML reporting and the Sonar Maven plugin for optional `sonar:sonar` CI uploads
- Run SpotBugs (High), PMD, and Checkstyle on `mvn verify` with lean configs under `config/`
- Add a sample GitHub Actions workflow that runs verify and optionally uploads to Sonar when secrets exist
- Document Code quality commands and gate expectations in the README
- Enqueue optional WEBHOOK outbox rows beside AUDIT on payment commit for YAML subscribers
- Deliver HMAC-signed HTTP POSTs asynchronously via `WebhookClient` (`X-Signature: sha256=…`)
- Retry webhook delivery with the same outbox backoff when subscribers return 5xx or are unreachable
- Cover signed delivery, 5xx retry, and subscriber-down paths with `WebhookOutboxIT`
- Document webhook subscription config and at-least-once semantics in the README
- List transactions via `GET /api/v1/transactions` with required `userId` and cursor pagination
- Filter by optional `from`/`to`/`status` and enforce limit default 50 / max 200
- Cover handler and API pagination (including empty pages) with unit and Testcontainers tests
- Document bulk export in README/Swagger and harden Bruno `09-list-transactions` for Phase 9
- Cache `GET /users/{id}` via optional Redis `user:{id}` read-through with TTL on `GetUserHandler`
- Evict the cache key from `UpdateUserHandler` so PATCH results stay fresh
- Fall back to PostgreSQL when Redis is disabled or errors; keep readiness independent of Redis
- Add Compose `redis` profile and Testcontainers coverage (`UserCacheIT`) proving authorize path skips Redis
- Cap `/api/v1/**` traffic with an in-process Bucket4j filter keyed by userId, merchantId, and IP
- Return `429 RATE_LIMIT_EXCEEDED` with the ApiResponse envelope and optional `Retry-After`
- Cover over-limit (no DB writes) and Rule 2 independence with Testcontainers
- Document rate-limit defaults, config (`payment.rate-limit.*`), and burst demo in the README

### Changed

- Clear SpotBugs/PMD findings that blocked the new verify gate (Locale case conversion, `serialVersionUID`, velocity threshold constant)
- Mark Phase 11 plan tasks T11.1–T11.2 done
- Extend `audit_outbox` with `destination` / delivery columns so AUDIT and WEBHOOK share the publisher
- Mark Phase 10 plan tasks T10.1–T10.2 done
- Mark Phase 9 plan tasks T9.1–T9.2 done
- Mark Phase 8 plan tasks T8.1–T8.2 done
- Raise JaCoCo branch coverage gate to 80% on `mvn verify`
- Mark Phase 7 plan tasks T7.1–T7.2 done

## [1.0.0] - 2026-09-12

MR title: `chore(release): 1.0.0`

### Added

- Cover Rule 2 races and concurrent idempotency with Testcontainers (`ConcurrencyIT`)
- Enforce JaCoCo line coverage ≥ 75% on `mvn verify`
- Include `*IT` suites in Surefire so outbox/concurrency Testcontainers tests run on verify
- Add domain UML (`docs/uml-domain.md`) and end-to-end `scripts/demo.sh`
- Complete root README to the challenge template (architecture, API, tests, coverage, design decisions)
- Fetch stored decisions via `GET /api/v1/transactions/{id}` with envelope `200` / `404 NOT_FOUND`
- Publish OpenAPI docs and Swagger UI with springdoc for users and transactions
- Put `X-Request-Id` into MDC so JSON logs correlate with response headers and `meta.requestId`
- Emit Micrometer metrics for transaction outcomes, processing duration, fraud rules, and outbox backlog/age
- Document Swagger, Prometheus metrics, and Logback JSON + MDC in the README
- Strengthen Bruno GET-transaction and prometheus probes for Phase 5 exit checks
- Project audit logs to MongoDB via scheduled `OutboxPublisher` (`FOR UPDATE SKIP LOCKED`)
- Insert immutable `audit_logs` documents with `_id = transactionId`; treat duplicate key as delivered
- Retry with exponential backoff and Resilience4j circuit breaker around Mongo publish
- Cover success, simulated Mongo outage + recovery, and duplicate delivery with Testcontainers
- Document why outbox / why Mongo in the README
- Process payments via `POST /api/v1/transactions` with fraud evaluation under user-row `FOR UPDATE`
- Persist transaction + audit outbox atomically; acknowledge decisions only after PostgreSQL commit
- Support optional `Idempotency-Key` with SHA-256 fingerprint replay and `409 IDEMPOTENCY_CONFLICT`
- Cover handler and API with unit/Testcontainers tests; document Phase 3 exit demo in the README
- Introduce an in-process fraud engine with four LLD rules (amount, velocity, high-risk category, new-user flag)
- Add configurable high-risk categories and thresholds via `FraudProperties`
- Cover rule aggregation and Rule 2/4 time boundaries with exhaustive unit tests (no Spring/DB)
- Document fraud rule semantics and how to run domain tests in the README
- Expose create, get, patch, and cursor-paginated list endpoints under `/api/v1/users`
- Return a shared `ApiResponse` envelope with request IDs and structured error codes
- Enforce layered validation via Bean Validation on DTOs and business guards in handlers
- Add Bruno list-users coverage and README examples for the Users API
- Cover user handlers and HTTP API with unit and Testcontainers-backed tests
- Start a Spring Boot payment service with Docker Compose for PostgreSQL 18 and MongoDB 8
- Apply Liquibase migrations for users, transactions, and the audit outbox
- Expose Actuator liveness and readiness (and Prometheus) for local health checks
- Add a Bruno OpenCollection for health, user, and transaction API scenarios
- Document Phase 0+ implementation plan and local run/debug setup in the README

### Changed

- Mark Phase 6 plan tasks T6.1–T6.4 done
- Soften Bruno list/audit requests so Phase 9-only endpoints do not fail the transaction folder
- Bind Mongo via Spring Boot 4 `spring.mongodb.uri` and disable auto-index-creation so Mongo downtime does not block app startup
- Align Bruno transaction POST categories with domain `GROCERIES` enum values
- Scope `.gitignore` `data/` to repo-root `/data/` so `com.example.payment.data` sources are tracked
- Adopt Lombok project-wide to cut boilerplate on handlers, domain user, exceptions, and JPA entities
- Document layered request validation in the HLD and LLD for reuse on later endpoints
- Target Spring Boot 4.1.1 so High/Critical Spring Framework CVEs have an OSS fix path

### Fixed

- Set Mongo `GLIBC_TUNABLES` so containers start on current Docker Desktop LinuxKit kernels

### Security

- Add OWASP Dependency-Check and Docker Scout scripts for phase validation gates
- Suppress known OWASP CPE false positives and drop unfixed GNU wget from the app image

[unreleased]: https://github.com/aaami1ster/payment-processing-system/compare/main...HEAD
[1.1.0]: https://github.com/aaami1ster/payment-processing-system/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/aaami1ster/payment-processing-system/releases/tag/v1.0.0
