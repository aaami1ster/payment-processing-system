# Changelog (develop)

Changes merged to `develop` but not yet released to `main`. Each MR is a separate section under **Unreleased**.

When cutting a release, flatten these sections into [`CHANGELOG.md`](CHANGELOG.md) and reset this file (see [`docs/update-changelog-prompt.md`](docs/update-changelog-prompt.md)).

## [Unreleased]

### MR: `feat: add phase 4 async mongo audit outbox`

#### Added

- Project audit logs to MongoDB via scheduled `OutboxPublisher` (`FOR UPDATE SKIP LOCKED`)
- Insert immutable `audit_logs` documents with `_id = transactionId`; treat duplicate key as delivered
- Retry with exponential backoff and Resilience4j circuit breaker around Mongo publish
- Cover success, simulated Mongo outage + recovery, and duplicate delivery with Testcontainers
- Document why outbox / why Mongo in the README

#### Changed

- Bind Mongo via Spring Boot 4 `spring.mongodb.uri` and disable auto-index-creation so Mongo downtime does not block app startup

### MR: `feat: add phase 3 process transaction MVP`

#### Added

- Process payments via `POST /api/v1/transactions` with fraud evaluation under user-row `FOR UPDATE`
- Persist transaction + audit outbox atomically; acknowledge decisions only after PostgreSQL commit
- Support optional `Idempotency-Key` with SHA-256 fingerprint replay and `409 IDEMPOTENCY_CONFLICT`
- Cover handler and API with unit/Testcontainers tests; document Phase 3 exit demo in the README

#### Changed

- Align Bruno transaction POST categories with domain `GROCERIES` enum values
- Scope `.gitignore` `data/` to repo-root `/data/` so `com.example.payment.data` sources are tracked

### MR: `feat: add phase 2 fraud detection domain`

#### Added

- Introduce an in-process fraud engine with four LLD rules (amount, velocity, high-risk category, new-user flag)
- Add configurable high-risk categories and thresholds via `FraudProperties`
- Cover rule aggregation and Rule 2/4 time boundaries with exhaustive unit tests (no Spring/DB)
- Document fraud rule semantics and how to run domain tests in the README

#### Changed

- Adopt Lombok project-wide to cut boilerplate on handlers, domain user, exceptions, and JPA entities

### MR: `feat: add phase 1 user management API`

#### Added

- Expose create, get, patch, and cursor-paginated list endpoints under `/api/v1/users`
- Return a shared `ApiResponse` envelope with request IDs and structured error codes
- Enforce layered validation via Bean Validation on DTOs and business guards in handlers
- Add Bruno list-users coverage and README examples for the Users API
- Cover user handlers and HTTP API with unit and Testcontainers-backed tests

#### Changed

- Document layered request validation in the HLD and LLD for reuse on later endpoints

### MR: `feat: bootstrap phase 0 runtime foundation`

#### Added

- Start a Spring Boot payment service with Docker Compose for PostgreSQL 18 and MongoDB 8
- Apply Liquibase migrations for users, transactions, and the audit outbox
- Expose Actuator liveness and readiness (and Prometheus) for local health checks
- Add a Bruno OpenCollection for health, user, and transaction API scenarios
- Document Phase 0+ implementation plan and local run/debug setup in the README

#### Changed

- Target Spring Boot 4.1.1 so High/Critical Spring Framework CVEs have an OSS fix path

#### Fixed

- Set Mongo `GLIBC_TUNABLES` so containers start on current Docker Desktop LinuxKit kernels

#### Security

- Add OWASP Dependency-Check and Docker Scout scripts for phase validation gates
- Suppress known OWASP CPE false positives and drop unfixed GNU wget from the app image

[unreleased]: https://github.com/aaami1ster/payment-processing-system/compare/develop...HEAD
