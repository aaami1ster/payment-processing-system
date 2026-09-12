# Implementation Plan — Payment Processing System

> **Docs index:** [docs/README.md](docs/README.md) · [requirements](docs/requirements-and-assumptions.md) · [HLD](docs/high-level-design.md) · [LLD](docs/low-level-design.md) · [challenge brief](docs/Payment_Processing_Code_Challenge.md)

This plan turns the design into a working service in **vertical phases**. Each phase ends with something you can **run and demonstrate**. Core payment/fraud features come first; optional enhancements are last.

**Core guarantee (never break this):**

```text
APPROVED / FLAGGED / DECLINED response
  → durable row exists in PostgreSQL (transaction + audit outbox)
Persistence unavailable → 503 — never fabricate a business decision
```

---



## How to use this plan

1. Finish phases **in order** (0 → 6 = challenge MVP; 7+ = optional).
2. For each phase: copy the **AI implementation prompt** → let the agent work → copy the **AI validation prompt** → confirm all checkboxes.
3. Do not start the next phase until the current phase’s **Phase exit demo** passes.
4. Agents must follow the design docs; if something conflicts, **design docs win** over improvisation.
5. After each phase that exposes HTTP, run the matching **Bruno** folder (see below) in addition to automated tests / curl demos.
6. **Every phase validation** must run the security vulnerability scan (see below) and fix High/Critical findings before marking the phase done.


| Item               | Value                                                                                                                                           |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| Base package       | `com.example.payment`                                                                                                                           |
| Main class         | `com.example.payment.PaymentProcessingApplication`                                                                                              |
| Stack              | Java 21+, Spring Boot **4.1.1** (prefer 3.x; see note), Maven, PostgreSQL, MongoDB, Liquibase, Testcontainers, JUnit 5, JaCoCo                  |
| Logging            | SLF4J + Logback via `LogFactory` only — never `System.out`                                                                                      |
| Manual API tests   | Bruno collection in `[bruno/](bruno/)`                                                                                                          |
| Request validation | Bean Validation on API DTOs + business guards in CQRS handlers (see LLD)                                                                        |
| Security scan      | `[check-security-docker-scout.sh](../scripts/check-security-docker-scout.sh)` + `[check-security-owasp.sh](../scripts/check-security-owasp.sh)` |


**Progress legend:** `- [ ]` not done · `- [x]` done

**Spring Boot version:** Challenge asks for 3.x. We evaluated **3.5.16** (last OSS 3.x) plus dependency overrides (Tomcat, Jackson, PostgreSQL JDBC). That still leaves High/Critical **Spring Framework** CVEs (e.g. CVE-2026-47884, CVE-2026-59313): NVD requires Framework **6.2.20+**, which is **not on Maven Central** (6.2 ends at 6.2.19). The available OSS fix is Framework **7.0.9** via Boot **4.1.1** — so 4.x is used only because 3.x cannot clear the OWASP gate.

---



## Bruno API collection (manual / CLI testing)

Repo folder `[bruno/](bruno/)` is an **OpenCollection YAML** suite (Bruno ≥ 3). Every designed endpoint has a `.yml` request with **docs** and **runtime tests** (status + `ApiResponse` envelope).


| Folder               | Phase gate | What it covers                                                      |
| -------------------- | ---------- | ------------------------------------------------------------------- |
| `bruno/health/`      | 0          | `/actuator/health`, liveness, readiness (+ prometheus when enabled) |
| `bruno/user/`        | 1          | `POST/GET/PATCH /api/v1/users`, list, 404                           |
| `bruno/transaction/` | 3–5 / 9    | Process, decline, get, idempotency, validation, list, audit-logs    |


**How to run**

```bash
docker compose up --build -d
# Desktop: Bruno → Open Collection → select bruno/ → env Local
# CLI:
cd bruno && npx @usebruno/cli run --env Local
npx @usebruno/cli run health --env Local
npx @usebruno/cli run user --env Local
npx @usebruno/cli run transaction --env Local
```

Details: `[bruno/README.md](bruno/README.md)`. Keep Bruno requests in sync when API contracts change (LLD wins).

---



## Security vulnerability scan (every phase validation)

Before marking **any** phase done, run both scanners (or the wrapper):

```bash
./scripts/check-security-docker-scout.sh   # fast — Docker Scout (needs docker login)
./scripts/check-security-owasp.sh          # Maven deps — OWASP + NVD_API_KEY from .env
# or both:
./scripts/check-security-vulnerabilities.sh
```


| Script                                      | Tool                   | Notes                                                                                   |
| ------------------------------------------- | ---------------------- | --------------------------------------------------------------------------------------- |
| `scripts/check-security-docker-scout.sh`    | Docker Scout           | App `pom`+`src` + app image (gate); vendor PG/Mongo warn-only unless `FAIL_ON_VENDOR=1` |
| `scripts/check-security-owasp.sh`           | OWASP Dependency-Check | Uses `NVD_API_KEY` from `.env`; report under `target/security/owasp/`                   |
| `scripts/check-security-vulnerabilities.sh` | both                   | `SKIP_SCOUT=1` / `SKIP_OWASP=1` to run one                                              |


```bash
SKIP_IMAGES=1 ./scripts/check-security-docker-scout.sh
BUILD_APP_IMAGE=1 ./scripts/check-security-docker-scout.sh
FAIL_ON_VENDOR=1 ./scripts/check-security-docker-scout.sh   # also fail on postgres/mongo image CVEs
IGNORE_BASE=0 ./scripts/check-security-docker-scout.sh       # include Temurin/Alpine base CVEs
FAIL_CVSS=8 ./scripts/check-security-owasp.sh
```

Put your NVD key in `.env` only (`NVD_API_KEY=…`) — never commit it. Template: `.env.example`.

Complements (does not replace) Phase 11 SonarQube / static analysis.

---



## Phase map (demo after each)


| Phase  | Name                                   | What you can demo when done                                                          |
| ------ | -------------------------------------- | ------------------------------------------------------------------------------------ |
| **0**  | Bootstrap & runtime foundation         | `docker compose up` → app healthy, DBs up, schema migrated                           |
| **1**  | User management API                    | Create / get / update users over HTTP                                                |
| **2**  | Fraud domain (unit-tested)             | All 4 rules + aggregation proven by unit tests                                       |
| **3**  | Process transaction (core MVP)         | `POST /transactions` → APPROVED/FLAGGED/DECLINED persisted with outbox + idempotency |
| **4**  | Async audit to Mongo                   | Audit documents appear in Mongo; Mongo down does not block payments                  |
| **5**  | API polish & observability             | OpenAPI/Swagger, GET transaction, metrics, structured logs                           |
| **6**  | Hardening, coverage & delivery README  | Concurrency tests, JaCoCo ≥ 75%, submission-ready README                             |
| **7**  | Optional — rate limiting               | `429` under burst traffic                                                            |
| **8**  | Optional — Redis query cache           | Faster `GET /users`; authorize path still hits PG                                    |
| **9**  | Optional — bulk export                 | Cursor-paginated transaction list                                                    |
| **10** | Optional — webhooks                    | Signed async notifications after commit                                              |
| **11** | Optional — SonarQube / static analysis | CI quality gate                                                                      |


**Challenge MVP complete after Phase 6.** Phases 7–11 are optional enhancements from the brief.

```text
Phase 0 ──► runnable empty service
Phase 1 ──► users work
Phase 2 ──► fraud rules correct (tests)
Phase 3 ──► payments + fraud + durability  ← first full product demo
Phase 4 ──► audit projection
Phase 5 ──► polished API / ops
Phase 6 ──► submission quality
Phase 7+ ──► optional extras
```

---



## Phase 0 — Bootstrap & runtime foundation

**Goal:** A Spring Boot app that starts with PostgreSQL + MongoDB via Docker Compose, applies Liquibase migrations, exposes health, and uses the agreed package layout.

**Phase MVP / deliverables**

- Maven project (`com.example:payment-processing-system`) with Spring Boot 4.x
- `docker-compose.yml`: app, PostgreSQL, MongoDB + healthchecks
- Liquibase changelogs: `users`, `transactions`, `audit_outbox` (+ indexes from LLD)
- Package skeleton under `com.example.payment` (api / service / domain / data / config / common)
- `LogFactory` wired; Actuator liveness/readiness (readiness requires PostgreSQL)
- Minimal root `README.md` (clone, prerequisites, compose up)

**Documentation:** Update root README with setup/run. Link to `docs/`. Point to `bruno/` for health probes.

**Testing:** Context loads (`@SpringBootTest` smoke) or compose health check; **Bruno** `bruno/health/` (aggregated + liveness + readiness).

**Deployment:** `docker compose up --build` brings everything up.

**Phase exit demo**

```bash
docker compose up --build -d
curl -s http://localhost:8080/actuator/health
# expect UP (or readiness UP with postgres)
docker compose exec postgres psql -U <user> -d <db> -c '\dt'
# expect users, transactions, audit_outbox
cd bruno && npx @usebruno/cli run health --env Local
# expect health + liveness + readiness tests green (prometheus may wait until Phase 5)
```



### Tasks

- [x] **T0.1 — Maven / Spring Boot skeleton**  
  **Description:** Create `pom.xml`, main application class, `application.yml` (datasources placeholders, actuator).  
  **Acceptance:** `mvn -q -DskipTests package` succeeds; main class is `PaymentProcessingApplication`.

- [x] **T0.2 — Package layout + LogFactory**  
  **Description:** Create empty package dirs per HLD; keep/ensure `common.logging.LogFactory`.  
  **Acceptance:** Layout matches HLD; no `System.out` usage introduced.

- [x] **T0.3 — Liquibase schema**  
  **Description:** Changelogs for users, transactions (incl. idempotency partial unique index), audit_outbox per LLD.  
  **Acceptance:** Fresh Postgres applies all changelogs; indexes exist as designed.

- [x] **T0.4 — Docker Compose**  
  **Description:** Postgres, Mongo, app; app waits for Postgres; Liquibase runs on startup.  
  **Acceptance:** Single `docker compose up --build` starts healthy stack.

- [x] **T0.5 — Health + README stub**  
  **Description:** Actuator health; README with Java/Maven/Docker prerequisites and run steps.  
  **Acceptance:** Health endpoint reachable; README steps work on a clean machine.

- [x] **T0.6 — Bruno health collection**  
  **Description:** Keep `bruno/health/` docs/tests aligned with actuator paths (liveness/readiness semantics per LLD).  
  **Acceptance:** `bru run health --env Local` passes against a healthy compose stack.

- [x] **T0.7 — Security vulnerability scan script**  
  **Description:** `check-security-docker-scout.sh` + `check-security-owasp.sh` (NVD key in `.env`); wired into every phase validation.  
  **Acceptance:** Both scripts documented; High/Critical CVEs fixed or gated.



### AI implementation prompt (Phase 0)

```text
You are implementing Phase 0 of the payment-processing-system.

Read and follow:
- docs/high-level-design.md (project identity, package layout, deployment topology)
- docs/low-level-design.md (PostgreSQL tables, indexes, Liquibase layout)
- plan.md Phase 0 tasks T0.1–T0.7

Implement ONLY Phase 0:
1. Maven Spring Boot 4.x project (groupId com.example, artifactId payment-processing-system).
2. Main class com.example.payment.PaymentProcessingApplication.
3. Package skeleton: api, service.command, service.query, domain, data.postgres, data.mongo, data.outbox, integration, config, common.logging.
4. Keep LogFactory; configure SLF4J/Logback (no System.out).
5. Liquibase: users, transactions, audit_outbox exactly as LLD (types, CHECKs, partial unique idempotency index, outbox indexes).
6. docker-compose.yml with postgres, mongo, app; healthchecks; app depends on healthy postgres.
7. Actuator health; readiness should require PostgreSQL.
8. Minimal root README: prerequisites, docker compose up, health check.
9. Verify Bruno `bruno/health/` against the running stack (adjust only if actuator paths differ from design).
10. Add OWASP + Docker Scout security scripts; document in README/plan; NVD key only in `.env`.

Do NOT implement business APIs, fraud rules, or Mongo publishers yet.
When done, list files changed and how to verify Phase 0 exit demo from plan.md.
```



### AI validation prompt (Phase 0)

```text
Validate Phase 0 of payment-processing-system against plan.md and the design docs.

Checklist:
1. pom.xml builds; main class package is correct.
2. Liquibase matches LLD column types and indexes (especially partial unique idempotency index).
3. docker compose brings up app + postgres + mongo; health is UP.
4. Tables users, transactions, audit_outbox exist after startup.
5. No business endpoints required yet; no System.out.
6. README stub documents how to run.
7. Bruno health folder runs successfully (liveness/readiness semantics).
8. Run ./scripts/check-security-docker-scout.sh and ./scripts/check-security-owasp.sh; fix High/Critical CVEs; cite target/security/scout/ and owasp/.

Run the Phase 0 exit demo commands. Report PASS/FAIL per task T0.1–T0.7 with evidence.
Fix only Phase 0 gaps; do not start Phase 1.
```

---



## Phase 1 — User management API

**Goal:** Working user CRUD-style API so transactions have a real user to lock and evaluate.

**Phase MVP / deliverables**

- Domain `User`, `KycStatus`
- Postgres entity/repository; `CreateUserHandler`, `GetUserHandler`, `UpdateUserHandler`, `ListUsersHandler`
- REST: `POST /api/v1/users`, `GET /api/v1/users` (cursor list), `GET /api/v1/users/{id}`, `PATCH /api/v1/users/{id}`
- Standard `ApiResponse` envelope + `GlobalExceptionHandler` (at least for users)
- Layered input validation: Bean Validation on request DTOs + business guards in handlers
- Unit/API tests for create/get/update/list + 404

**Documentation:** README API section for user endpoints (examples). Bruno `bruno/user/` docs stay in sync with the envelope.

**Testing:** Handler unit tests; `@SpringBootTest` or MockMvc/Testcontainers for user API; **Bruno** `bruno/user/` (create / list / get / patch / 404).

**Deployment:** Same compose stack; no new services.

**Phase exit demo**

```bash
# create user
curl -s -X POST http://localhost:8080/api/v1/users \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com"}'
# get + patch preApprovedTransactionLimit / kycStatus
cd bruno && npx @usebruno/cli run user --env Local
```



### Tasks

- [x] **T1.1 — User domain + persistence**  
  **Description:** `User`, `KycStatus`; JPA entity/repo matching Liquibase.  
  **Acceptance:** Can save/load user with email unique, KYC default PENDING, null limit.

- [x] **T1.2 — User command/query handlers**  
  **Description:** Create / Get / List (cursor) / Update (KYC + `preApprovedTransactionLimit`).  
  **Acceptance:** Update is partial; unknown id → domain not-found; list returns `items`/`nextCursor`/`hasMore`; no fraud logic here.

- [x] **T1.3 — User REST + ApiResponse envelope**  
  **Description:** Controllers return `ResponseEntity<ApiResponse<T>>`; errors use `errors[]` codes; `@Valid` on request DTOs.  
  **Acceptance:** 201 create, 200 get/patch, 404 `USER_NOT_FOUND`/`NOT_FOUND`; envelope matches LLD; invalid body → 400 `VALIDATION_ERROR`.

- [x] **T1.4 — User tests + README examples**  
  **Description:** Tests for happy path + duplicate email + 404; README curl examples.  
  **Acceptance:** Tests green; README examples match running API.

- [x] **T1.5 — Bruno user collection**  
  **Description:** Ensure `bruno/user/` requests match live paths/body/error codes; create sets `userId` for later phases.  
  **Acceptance:** `bru run user --env Local` green (create → list → get → patch → not-found).



### AI implementation prompt (Phase 1)

```text
You are implementing Phase 1 (User management API) of payment-processing-system.

Prerequisites: Phase 0 is complete (compose + Liquibase + skeleton).

Read and follow:
- docs/low-level-design.md (User model, Users API, ApiResponse envelope)
- docs/high-level-design.md (CQRS-lite: CreateUserHandler, UpdateUserHandler, GetUserHandler)
- plan.md Phase 1 tasks T1.1–T1.5

Implement:
1. Domain User + KycStatus.
2. Postgres mapping + repository.
3. CreateUserHandler, GetUserHandler, UpdateUserHandler (PATCH KYC and/or preApprovedTransactionLimit).
4. REST under /api/v1/users with ApiResponse / ApiError / ApiMeta and GlobalExceptionHandler.
5. Bean Validation on request DTOs (@Valid); business guards in handlers (duplicate email, etc.).
6. Default KYC PENDING; preApprovedTransactionLimit null on create.
7. Tests + README curl examples for users.
8. Align/verify Bruno `bruno/user/` (docs + tests) with the live API; create request must set env userId.

Do NOT implement transaction processing or fraud engine yet.
Logging only via LogFactory.
When done, show Phase 1 exit demo commands and results.
```



### AI validation prompt (Phase 1)

```text
Validate Phase 1 against plan.md and LLD user/API sections.

Verify:
1. POST/GET/PATCH /api/v1/users behave as designed with ApiResponse envelope.
2. Duplicate email rejected; unknown user → 404 with stable error code.
3. Invalid create/patch bodies → 400 VALIDATION_ERROR with field when applicable.
4. PATCH can raise preApprovedTransactionLimit (needed later for Rule 1).
5. Tests pass; README examples work.
6. Bruno user folder passes (create/get/patch/404).
7. No transaction/fraud code required yet.

Run Phase 1 exit demo. PASS/FAIL per T1.1–T1.5. Fix only Phase 1 gaps.

Also run: ./scripts/check-security-docker-scout.sh and ./scripts/check-security-owasp.sh — fix High/Critical findings before PASS; cite target/security/scout/ and target/security/owasp/.
```

---



## Phase 2 — Fraud domain (unit-tested)

**Goal:** Correct, isolated fraud policy before wiring HTTP payments. Demo is **test-driven proof** of all four rules + aggregation.

**Phase MVP / deliverables**

- `FraudRule`, `FraudEngine`, `FraudContext`, `FraudDecision`, `RuleResult`, enums
- Four rules per LLD; `FraudProperties` for high-risk categories / thresholds
- Injectable `Clock`
- Comprehensive unit tests (no Spring, no DB)

**Documentation:** Short “Fraud rules” subsection in README pointing to LLD table.

**Testing:** Domain unit tests only (this phase’s primary deliverable).

**Deployment:** Unchanged runtime (no new endpoints required).

**Phase exit demo**

```bash
mvn -q -Dtest='*Fraud*,*Rule*' test
# all fraud/rule tests pass; show sample test names covering Rules 1–4 + aggregation
```



### Tasks

- [x] **T2.1 — Fraud types + engine**  
  **Description:** Interfaces/records/enums; engine runs all rules; DECLINE > FLAG > APPROVED.  
  **Acceptance:** Aggregation matches LLD; all rules always evaluate.

- [x] **T2.2 — Four rule implementations**  
  **Description:** AmountWithoutApproval, Velocity, HighRiskCategory, NewUserHighAmount per LLD exact conditions.  
  **Acceptance:** Thresholds/categories from `FraudProperties` / config where specified; Clock used for windows.

- [x] **T2.3 — Domain unit tests**  
  **Description:** Each rule; aggregation combinations; Rule 2 inclusive 60s edge; Rule 4 age edges.  
  **Acceptance:** Fixed Clock tests; no Spring context; failures clearly name the rule.

- [x] **T2.4 — README fraud blurb**  
  **Description:** Document the four rules and that FLAGGED is a successful auth.  
  **Acceptance:** README matches LLD semantics (DECLINED vs 503 distinction mentioned).



### AI implementation prompt (Phase 2)

```text
You are implementing Phase 2 (Fraud domain) of payment-processing-system.

Prerequisites: Phases 0–1 complete.

Read and follow strictly:
- docs/low-level-design.md sections: Fraud Detection Engine, Rules table, Rule 2 authoritative definition, Clock
- docs/high-level-design.md: domain package layout, why rules are strategy objects
- plan.md Phase 2 tasks T2.1–T2.4

Implement ONLY domain fraud:
1. FraudRule, FraudEngine, FraudContext, FraudDecision, RuleResult, RuleId, Severity, Category, TransactionStatus as needed by domain.
2. Four rule classes with exact LLD conditions (velocity counts APPROVED/FLAGGED only; inclusive [now-60s, now]; Rule 1 uses preApprovedTransactionLimit).
3. FraudProperties for high-risk category set / thresholds.
4. Clock bean config for later; unit tests use fixed Clock.
5. Exhaustive unit tests — no Spring, no DB.
6. Brief README note on fraud rules.

Do NOT wire ProcessTransactionHandler or HTTP transactions yet.
When done, paste mvn test output summary for fraud tests.
```



### AI validation prompt (Phase 2)

```text
Validate Phase 2 fraud domain against LLD Rule definitions and plan.md.

Must verify:
1. All four rules match LLD conditions exactly (especially Rule 2 sliding window + count semantics).
2. Aggregation: any DECLINE wins; else FLAG; else APPROVED; all rules always run.
3. Time-boundary tests exist for Rule 2 and Rule 4.
4. No I/O inside rules.
5. Unit tests pass without Spring.

PASS/FAIL T2.1–T2.4 with evidence. Fix only Phase 2 gaps.

Also run: ./scripts/check-security-docker-scout.sh and ./scripts/check-security-owasp.sh — fix High/Critical findings before PASS; cite target/security/scout/ and target/security/owasp/.
```

---



## Phase 3 — Process transaction (core product MVP)

**Goal:** First **full product demo**: accept a payment, evaluate fraud under user-row lock, persist transaction + audit outbox atomically, support idempotency, return 201 with business status (or 503/404/409).

**Phase MVP / deliverables**

- `ProcessTransactionHandler` with `SELECT … FOR UPDATE`, velocity count, fraud evaluate, insert txn + outbox, commit-then-respond
- Idempotency key + fingerprint + unique constraint handling
- `POST /api/v1/transactions` (+ keep users API)
- Postgres transaction entity/repo; outbox insert (publisher can still be stub/no-op or unused)
- Tests: happy paths per rule outcome, 404 user, idempotency replay/conflict, 503 path mocked

**Documentation:** README transaction examples; restate core guarantee. Keep `bruno/transaction/` docs aligned.

**Testing:** Unit tests for handler; data tests for idempotency unique index; at least one integration test with Testcontainers PG; **Bruno** `bruno/transaction/` POST cases.

**Deployment:** Same compose; demo via curl or Bruno.

**Phase exit demo**

```bash
# 1) create user
# 2) POST /api/v1/transactions → APPROVED
# 3) POST amount > 10000 without limit → DECLINED (still HTTP 201)
# 4) PATCH user limit, retry high amount → APPROVED
# 5) replay same Idempotency-Key → same transactionId
# 6) verify rows in transactions + audit_outbox (PENDING ok)
cd bruno && npx @usebruno/cli run user --env Local
npx @usebruno/cli run transaction --env Local
# skip GET / list / audit requests until their phases if they 404
```



### Tasks

- [x] **T3.1 — Transaction + outbox persistence**  
  **Description:** Entities/repos; save transaction and outbox in one `@Transactional`.  
  **Acceptance:** Rollback removes both; constraints enforce amount > 0 and idempotency uniqueness.

- [x] **T3.2 — ProcessTransactionHandler**  
  **Description:** Idempotency pre-check → BEGIN → FOR UPDATE → re-check → velocity → FraudEngine → persist → commit → return.  
  **Acceptance:** Matches HLD sequence; authorize path does not use GetUserHandler/cache.

- [x] **T3.3 — POST /api/v1/transactions API**  
  **Description:** Optional `Idempotency-Key`; 201 for APPROVED/FLAGGED/DECLINED; 404/409/400/503 mapping.  
  **Acceptance:** Envelope rules: business status in `data.status`, never in `errors[]` for declines.

- [x] **T3.4 — Idempotency fingerprint**  
  **Description:** Canonical SHA-256 of userId, merchantId, amount, category.  
  **Acceptance:** Same key+fingerprint replays; different fingerprint → 409 `IDEMPOTENCY_CONFLICT`.

- [x] **T3.5 — Tests + README demo script**  
  **Description:** Rule outcomes, idempotency, missing user; README demo steps for Phase 3 exit.  
  **Acceptance:** Tests green; demo script reproducible on compose stack.

- [x] **T3.6 — Bruno transaction collection**  
  **Description:** Align `bruno/transaction/` POST/idempotency/error cases with live API.  
  **Acceptance:** Bruno transaction POST cases pass; DECLINED returns 201 with empty `errors[]`; replay/conflict behave as LLD.



### AI implementation prompt (Phase 3)

```text
You are implementing Phase 3 — the core payment MVP — for payment-processing-system.

Prerequisites: Phases 0–2 complete (users API + fraud unit tests).

Read and follow STRICTLY:
- docs/high-level-design.md: Request Flow, transaction boundary, core guarantee, CQRS-lite rules
- docs/low-level-design.md: API transactions, Idempotency, Rule 2 concurrency, outbox table
- plan.md Phase 3 tasks T3.1–T3.6

Implement:
1. Transaction domain + postgres mapping including idempotency_key + request_fingerprint.
2. ProcessTransactionHandler:
   - optional idempotency lookup + fingerprint compare
   - SELECT user FOR UPDATE
   - re-check idempotency under lock
   - count APPROVED/FLAGGED in [now-60s, now]
   - FraudEngine.evaluate
   - insert transaction + audit_outbox in SAME DB transaction
   - return result only after COMMIT; on commit failure → 503
3. POST /api/v1/transactions with ApiResponse; DECLINED/FLAGGED/APPROVED all HTTP 201.
4. Do NOT publish to Mongo yet (outbox rows may stay PENDING).
5. Never route authorize user load through GetUserHandler.
6. Tests + README demo for Phase 3 exit.
7. Verify Bruno `bruno/transaction/` POST cases against the live API.

Logging via LogFactory only.
When done, run/describe the Phase 3 exit demo end-to-end.
```



### AI validation prompt (Phase 3)

```text
Validate Phase 3 (process transaction MVP) against HLD/LLD and plan.md.

Critical checks:
1. Core guarantee: no APPROVED/FLAGGED/DECLINED without PG commit of txn+outbox.
2. FOR UPDATE held across velocity + insert.
3. Idempotency replay and 409 conflict behave as LLD.
4. HTTP: business decline is 201 + data.status=DECLINED, not errors[]; PG failure is 503.
5. Unknown user is 404, not fraud decline.
6. Outbox row created with transaction (Mongo publish not required yet).
7. Bruno transaction POST suite passes where endpoints exist.

Run Phase 3 exit demo. PASS/FAIL T3.1–T3.6. Fix only Phase 3 gaps.

Also run: ./scripts/check-security-docker-scout.sh and ./scripts/check-security-owasp.sh — fix High/Critical findings before PASS; cite target/security/scout/ and target/security/owasp/.
```

---



## Phase 4 — Async audit projection to MongoDB

**Goal:** Outbox publisher delivers audit logs to Mongo with retries; Mongo outage does not block payments.

**Phase MVP / deliverables**

- Mongo `audit_logs` document model (`_id = transactionId`)
- `OutboxPublisher` with `FOR UPDATE SKIP LOCKED`, insert-or-duplicate-key, exponential backoff
- Resilience4j (or equivalent) timeout/circuit around Mongo
- Tests: publish success; Mongo down → PENDING → recover → PUBLISHED; duplicate key = success

**Documentation:** README explains why Mongo + outbox (not sync dual-write).

**Testing:** Testcontainers Mongo + PG publisher tests.

**Deployment:** Same compose; demonstrate audit document after payment.

**Phase exit demo**

```bash
# POST a transaction → wait briefly → check Mongo audit_logs for transactionId
# Stop mongo → POST another transaction still 201 → outbox PENDING
# Start mongo → publisher drains → document appears, outbox PUBLISHED
```



### Tasks

- [x] **T4.1 — Mongo audit document + repository**  
  **Description:** Append-only insert; payload matches LLD JSON shape including userContext snapshot.  
  **Acceptance:** `_id = transactionId`; no upsert overwrite.

- [x] **T4.2 — OutboxPublisher worker**  
  **Description:** Scheduled poll; SKIP LOCKED; mark PUBLISHED; backoff on failure.  
  **Acceptance:** Multi-instance safe claim; DuplicateKeyException treated as delivered.

- [x] **T4.3 — Resilience + metrics hooks**  
  **Description:** Timeouts/circuit on Mongo publish; basic counters for pending/failures if easy.  
  **Acceptance:** Mongo down does not fail POST /transactions.

- [x] **T4.4 — Publisher tests + README resilience note**  
  **Description:** Testcontainers flows for success, outage, duplicate retry.  
  **Acceptance:** Tests green; README states PG is SoR and Mongo is projection.



### AI implementation prompt (Phase 4)

```text
You are implementing Phase 4 (async Mongo audit via outbox) for payment-processing-system.

Prerequisites: Phase 3 works (txn + PENDING outbox on commit).

Read:
- docs/high-level-design.md: Audit sync vs async, outbox publisher, consistency, resilience table
- docs/low-level-design.md: Mongo audit schema, outbox tests, publisher SKIP LOCKED SQL
- plan.md Phase 4 tasks T4.1–T4.4

Implement:
1. Mongo audit_logs document with _id=transactionId; immutable insert.
2. OutboxPublisher: claim PENDING with FOR UPDATE SKIP LOCKED; insert Mongo; on DuplicateKey treat success; mark PUBLISHED; exponential backoff via next_attempt_at/attempts.
3. Ensure POST /transactions never waits on Mongo.
4. Testcontainers tests for success, Mongo outage + recovery, duplicate delivery.
5. README note: why outbox / why Mongo.

Do not add webhooks/Kafka/Redis.
When done, demonstrate Phase 4 exit demo.
```



### AI validation prompt (Phase 4)

```text
Validate Phase 4 against HLD outbox/Mongo design and plan.md.

Verify:
1. Payment succeeds when Mongo is down; outbox stays PENDING.
2. Publisher eventually inserts audit and marks PUBLISHED.
3. Retry after successful Mongo insert but failed mark → DuplicateKey → PUBLISHED.
4. Audit documents are not overwritten (no upsert rewrite).
5. Phase 3 API behavior unchanged.

Run Phase 4 exit demo. PASS/FAIL T4.1–T4.4. Fix only Phase 4 gaps.

Also run: ./scripts/check-security-docker-scout.sh and ./scripts/check-security-owasp.sh — fix High/Critical findings before PASS; cite target/security/scout/ and target/security/owasp/.
```

---



## Phase 5 — API polish & observability

**Goal:** Demo-ready HTTP surface: fetch transaction, OpenAPI/Swagger, structured JSON logs, metrics, consistent errors.

**Phase MVP / deliverables**

- `GET /api/v1/transactions/{id}`
- OpenAPI / Swagger UI
- Logback JSON + MDC `requestId` (`X-Request-Id`)
- Micrometer metrics from LLD list (at least transaction totals + processing duration + outbox pending if practical)
- Error mapping completeness

**Documentation:** OpenAPI is the API doc; README links to `/swagger-ui`. Bruno remains the runnable contract tests (`bruno/`).

**Testing:** API tests for GET 200/404; requestId echoed in meta/header; **Bruno** GET transaction + prometheus when enabled.

**Deployment:** Same compose; open Swagger in browser.

**Phase exit demo**

```bash
open http://localhost:8080/swagger-ui.html   # or /swagger-ui/index.html
# walk: create user → pay → get transaction → show logs contain requestId
cd bruno && npx @usebruno/cli run transaction --env Local
```



### Tasks

- [x] **T5.1 — GET transaction query**  
  **Description:** `GetTransactionHandler` + controller.  
  **Acceptance:** 200 envelope / 404 `NOT_FOUND`.

- [x] **T5.2 — OpenAPI**  
  **Description:** springdoc (or equivalent); document endpoints and envelope.  
  **Acceptance:** Swagger UI lists users + transactions operations.

- [x] **T5.3 — Structured logging + requestId**  
  **Description:** Filter sets MDC; JSON encoder; LogFactory only.  
  **Acceptance:** Response `meta.requestId` and header correlate; no sensitive payload dumps.

- [x] **T5.4 — Metrics + README observability**  
  **Description:** Key Micrometer metrics; document actuator/prometheus if enabled.  
  **Acceptance:** At least status counters and timer visible; README updated.

- [x] **T5.5 — Bruno GET + observability probes**  
  **Description:** Ensure `bruno/transaction/` GET cases and `bruno/health/04-prometheus` match enabled endpoints.  
  **Acceptance:** `bru run transaction` passes (including GET); requestId assertions hold.



### AI implementation prompt (Phase 5)

```text
You are implementing Phase 5 (API polish & observability) for payment-processing-system.

Prerequisites: Phases 0–4 complete.

Read:
- docs/low-level-design.md: API Design, Observability (logging + metrics + health)
- docs/high-level-design.md: api package responsibilities
- plan.md Phase 5 tasks T5.1–T5.5

Implement:
1. GET /api/v1/transactions/{id} via GetTransactionHandler (query-only).
2. OpenAPI/Swagger with springdoc.
3. X-Request-Id accept-or-generate; echo header + ApiMeta.requestId; MDC; Logback JSON.
4. Micrometer metrics aligned with LLD (transaction totals by status, duration; outbox metrics if feasible).
5. Update README with Swagger URL and logging notes.
6. Verify Bruno GET transaction + prometheus probe.

Do not start optional enhancements (rate limit/redis/webhooks).
When done, show Phase 5 exit demo.
```



### AI validation prompt (Phase 5)

```text
Validate Phase 5 against LLD Observability/API and plan.md.

Checks:
1. GET transaction 200/404 with envelope.
2. Swagger UI available and accurate.
3. requestId in header + meta + logs.
4. No System.out; LogFactory used.
5. Metrics present for processed transactions.
6. Bruno user + transaction folders pass for available endpoints.

PASS/FAIL T5.1–T5.5. Fix only Phase 5 gaps.

Also run: ./scripts/check-security-docker-scout.sh and ./scripts/check-security-owasp.sh — fix High/Critical findings before PASS; cite target/security/scout/ and target/security/owasp/.
```

---



## Phase 6 — Hardening, coverage & submission delivery

**Goal:** Challenge-complete artifact: concurrency correctness, Testcontainers suite, JaCoCo ≥ 75%, polished README + domain UML reference.

**Phase MVP / deliverables**

- Rule 2 concurrency regression (2 existing + 2 parallel → one success path, one DECLINED by velocity)
- Idempotency concurrency test
- JaCoCo report ≥ 75% on `mvn verify`
- Root README complete (challenge template sections)
- UML class diagram (Mermaid in README or `docs/uml-domain.md`)

**Documentation:** Full README (architecture, setup, API, tests, coverage, design decisions, link to `bruno/`).

**Testing:** Full `mvn verify` green; Bruno core folders green (`health`, `user`, `transaction`).

**Deployment:** Documented one-command compose demo script.

**Phase exit demo**

```bash
mvn verify
# open target/site/jacoco/index.html — coverage ≥ 75%
docker compose up --build
# run end-to-end script: users + payments + audit in Mongo + swagger
cd bruno && npx @usebruno/cli run --env Local
# list/audit requests in transaction/ need Phases 9+
```



### Tasks

- [x] **T6.1 — Concurrency + idempotency integration tests**  
  **Description:** Exact LLD scenarios for Rule 2 race and concurrent same idempotency key.  
  **Acceptance:** Assertions match LLD expected outcomes.

- [x] **T6.2 — JaCoCo ≥ 75%**  
  **Description:** Enforce on verify; fill coverage gaps with meaningful tests.  
  **Acceptance:** `mvn verify` fails if under threshold; report generated.

- [x] **T6.3 — README completion + UML**  
  **Description:** Fill challenge README template; include core guarantee, Mongo justification, how to test/coverage; link Bruno collection.  
  **Acceptance:** New reader can run system from README alone.

- [x] **T6.4 — End-to-end demo script**  
  **Description:** `scripts/demo.sh` or README section with ordered curls; optionally wrap `bru run`.  
  **Acceptance:** Script succeeds against local compose.



### AI implementation prompt (Phase 6)

```text
You are implementing Phase 6 (hardening & submission delivery) for payment-processing-system.

Prerequisites: Phases 0–5 complete — full vertical service works.

Read:
- docs/low-level-design.md: Testing Strategy, concurrency regression, idempotency tests, Implementation Sequence item 7
- docs/Payment_Processing_Code_Challenge.md: README template, deliverables checklist
- plan.md Phase 6 tasks T6.1–T6.4

Implement:
1. Testcontainers concurrency test: 2 existing APPROVED/FLAGGED + 2 parallel requests → exactly one additional non-DECLINED-by-velocity success path as designed, one DECLINED by Rule 2.
2. Concurrent idempotency: one transaction, one outbox.
3. JaCoCo ≥ 75% enforced on mvn verify.
4. Complete root README per challenge template + design decision highlights from HLD.
5. Domain UML (Mermaid) for User, Transaction, FraudRule, AuditLog.
6. Simple demo script or documented curl sequence.

Do not implement optional phases 7–11 unless explicitly asked.
When done, paste mvn verify summary and coverage %.
```



### AI validation prompt (Phase 6)

```text
Validate Phase 6 — challenge MVP readiness.

Checklist against challenge deliverables:
1. docker-compose runs app+postgres+mongo.
2. All 4 fraud rules work via API.
3. Audit in Mongo via outbox.
4. User management endpoints.
5. Unit + integration tests; JaCoCo ≥ 75%.
6. README + OpenAPI + UML.
7. Concurrency test exists and passes.
8. Core guarantee upheld.

Run mvn verify and Phase 6 exit demo. PASS/FAIL T6.1–T6.4.
If gaps remain in Phases 0–5, list them separately; fix MVP blockers.

Also run: ./scripts/check-security-docker-scout.sh and ./scripts/check-security-owasp.sh — fix High/Critical findings before PASS; cite target/security/scout/ and target/security/owasp/.
```



### Challenge MVP gate

After Phase 6, you should be able to check the challenge deliverables:

- [ ] Compose stack
- [ ] Four fraud rules
- [ ] Audit logging (Mongo)
- [ ] User APIs
- [ ] Transaction processing
- [ ] Tests + JaCoCo ≥ 75%
- [ ] README + OpenAPI + UML
- [ ] Idempotency (core design)

---



## Phase 7 — Optional: Rate limiting

**Goal:** Demonstrate HTTP rate limits per user/merchant without changing fraud semantics.

**Phase MVP / deliverables:** Filter (Bucket4j or equivalent); `429` + `RATE_LIMIT_EXCEEDED`; config limits; tests; README note.

**Phase exit demo:** Burst requests → some `429`; allowed request still evaluates fraud normally.

### Tasks

- [x] **T7.1 — Rate limit filter + config**  
  **Description:** Key by userId and/or merchantId; configurable windows.  
  **Acceptance:** Over-limit never inserts transaction/outbox.

- [x] **T7.2 — 429 envelope + tests + README**  
  **Description:** Match LLD optional contract; optional `Retry-After`.  
  **Acceptance:** Tests prove 429 vs Rule 2 independence.



### AI implementation prompt (Phase 7)

```text
Implement Phase 7 (optional rate limiting) per docs/low-level-design.md “Rate limiting” and plan.md T7.1–T7.2.
Add HTTP filter before controllers; return 429 RATE_LIMIT_EXCEEDED with ApiResponse envelope.
Do not change FraudEngine or Rule 2.
Include tests and README note. Demo burst → 429.
```



### AI validation prompt (Phase 7)

```text
Validate Phase 7: over-limit returns 429 with no DB writes; under-limit unchanged; Rule 2 still works. PASS/FAIL T7.1–T7.2.

Also run: ./scripts/check-security-docker-scout.sh and ./scripts/check-security-owasp.sh — fix High/Critical findings before PASS; cite target/security/scout/ and target/security/owasp/.
```

---



## Phase 8 — Optional: Redis query cache

**Goal:** Cache `GetUserHandler` reads; invalidate on update; never cache authorize-path user load.

**Phase MVP / deliverables:** Optional Redis service; cache `user:{id}`; TTL; invalidation; fallback if Redis down; tests; compose profile or optional service.

**Phase exit demo:** Repeated GET /users hits cache (logs/metrics); POST /transactions still uses FOR UPDATE on PG.

### Tasks

- [ ] **T8.1 — Redis cache on GetUserHandler**  
  **Description:** Read-through + TTL; UpdateUserHandler deletes key.  
  **Acceptance:** ProcessTransactionHandler never reads Redis for user.

- [ ] **T8.2 — Resilience + tests + README**  
  **Description:** Redis down → PG fallback; document optional compose service.  
  **Acceptance:** Core tests pass without Redis; cache tests use Testcontainers Redis or similar.



### AI implementation prompt (Phase 8)

```text
Implement Phase 8 (optional Redis user cache) per HLD/LLD Redis sections and plan.md T8.1–T8.2.
Cache only GetUserHandler; invalidate on UpdateUserHandler; ProcessTransactionHandler must keep findByIdForUpdate on PostgreSQL only.
Redis optional: app works if Redis absent/down. Add tests + README.
```



### AI validation prompt (Phase 8)

```text
Validate Phase 8: GET users can cache; update invalidates; authorize path never uses Redis; payments work with Redis down. PASS/FAIL T8.1–T8.2.

Also run: ./scripts/check-security-docker-scout.sh and ./scripts/check-security-owasp.sh — fix High/Critical findings before PASS; cite target/security/scout/ and target/security/owasp/.
```

---



## Phase 9 — Optional: Bulk transaction export

**Goal:** Cursor-paginated `GET /api/v1/transactions` list/export.

**Phase MVP / deliverables:** `ListTransactionsHandler`; query params; `nextCursor`/`hasMore`; tests; OpenAPI + README + Bruno `bruno/transaction/09-list-transactions.yml`.

**Phase exit demo:** Create several txns → list with limit=2 → follow cursor → stable pages → `bru run transaction --env Local` (list request).

### Tasks

- [ ] **T9.1 — List endpoint + cursor query**  
  **Description:** Filters userId/from/to/status/limit per LLD.  
  **Acceptance:** Deterministic order `created_at DESC, id DESC`; max limit enforced.

- [ ] **T9.2 — Tests + OpenAPI/README/Bruno**  
  **Description:** Pagination + empty page cases; keep Bruno list request docs/tests accurate.  
  **Acceptance:** Documented in Swagger, README, and `bruno/transaction/` list request.



### AI implementation prompt (Phase 9)

```text
Implement Phase 9 (bulk export with cursor pagination) per LLD “Bulk transaction export” and plan.md T9.1–T9.2.
Add GET /api/v1/transactions as query handler only (no locks/outbox).
Include tests, OpenAPI, README examples, and verify Bruno transaction list request.
```



### AI validation prompt (Phase 9)

```text
Validate Phase 9: cursor pages are stable; limit caps work; authorize POST unchanged; Bruno `09-list-transactions` passes. PASS/FAIL T9.1–T9.2.

Also run: ./scripts/check-security-docker-scout.sh and ./scripts/check-security-owasp.sh — fix High/Critical findings before PASS; cite target/security/scout/ and target/security/owasp/.
```

---



## Phase 10 — Optional: Webhooks

**Goal:** After commit, enqueue WEBHOOK outbox rows; publisher POSTs signed payloads asynchronously.

**Phase MVP / deliverables:** Destination on outbox (or parallel table); config/YAML subscribers; HMAC client; retries; tests with WireMock/MockWebServer; README.

**Phase exit demo:** Configure subscriber URL → pay → subscriber receives signed event; payment latency unaffected.

### Tasks

- [ ] **T10.1 — Webhook outbox + client**  
  **Description:** Enqueue on commit; publisher branch WEBHOOK; HMAC signature header.  
  **Acceptance:** At-least-once; failures backoff; POST /transactions does not call webhook inline.

- [ ] **T10.2 — Tests + README**  
  **Description:** Delivery success/retry; signature verified in test.  
  **Acceptance:** Documented subscription config.



### AI implementation prompt (Phase 10)

```text
Implement Phase 10 (webhooks) per LLD Webhook section and plan.md T10.1–T10.2.
Extend outbox with WEBHOOK destination; async signed HTTP delivery; never block authorize path.
Use test double for subscriber. Update README. No Kafka.
```



### AI validation prompt (Phase 10)

```text
Validate Phase 10: webhook after commit only; retries work; payment succeeds if webhook endpoint down; HMAC present. PASS/FAIL T10.1–T10.2.

Also run: ./scripts/check-security-docker-scout.sh and ./scripts/check-security-owasp.sh — fix High/Critical findings before PASS; cite target/security/scout/ and target/security/owasp/.
```

---



## Phase 11 — Optional: SonarQube / static analysis

**Goal:** CI-oriented static analysis alongside JaCoCo; no runtime impact.

**Phase MVP / deliverables:** Sonar Maven config and/or SpotBugs+PMD+Checkstyle on `verify`; README “Code quality” section; optional sample GitHub Actions workflow.

**Phase exit demo:** `mvn verify` (and `sonar:sonar` if server available) runs analysis.

### Tasks

- [ ] **T11.1 — Static analysis tooling**  
  **Description:** Prefer Sonar-ready JaCoCo + document `sonar:sonar`; and/or SpotBugs/PMD on verify.  
  **Acceptance:** Documented commands; no compose Sonar required for app runtime.

- [ ] **T11.2 — README / CI notes**  
  **Description:** How to run locally/CI; quality gate expectations.  
  **Acceptance:** New contributor can run analysis from docs.



### AI implementation prompt (Phase 11)

```text
Implement Phase 11 (SonarQube/static analysis) per LLD SonarQube section and plan.md T11.1–T11.2.
CI-only: wire Maven for analysis reports; do not add Sonar to app docker-compose runtime.
Update README with commands. Keep JaCoCo ≥ 75%.
```



### AI validation prompt (Phase 11)

```text
Validate Phase 11: analysis runs via Maven docs; app runtime unchanged; JaCoCo still enforced. PASS/FAIL T11.1–T11.2.

Also run: ./scripts/check-security-docker-scout.sh and ./scripts/check-security-owasp.sh — fix High/Critical findings before PASS; cite target/security/scout/ and target/security/owasp/.
```

---



## Cross-cutting rules for every AI session

Paste this when starting any phase if the agent lacks chat history:

```text
Project: payment-processing-system at repo root.
Design authority: docs/requirements-and-assumptions.md, docs/high-level-design.md, docs/low-level-design.md.
Plan authority: plan.md — implement only the requested phase/tasks.
Hard rules:
- Core guarantee: never acknowledge APPROVED/FLAGGED/DECLINED unless PG committed transaction + audit outbox.
- DECLINED is HTTP 201 with data.status=DECLINED; infrastructure failure is 503.
- Authorize path: SELECT user FOR UPDATE on primary; never Redis/GetUserHandler for that load.
- Logging: LogFactory / SLF4J only — no System.out.
- Prefer smallest change set; match existing packages and ApiResponse envelope.
- Validate request DTOs with Bean Validation (`@Valid`); keep business invariants in CQRS handlers — do not duplicate the same check in both layers.
- After implementation, run the phase exit demo and mark what was verified.
```

---



## Suggested cadence


| When        | Do                                                                          |
| ----------- | --------------------------------------------------------------------------- |
| Day slice A | Phase 0 + 1 (runnable users)                                                |
| Day slice B | Phase 2 + 3 (fraud + payments MVP demo)                                     |
| Day slice C | Phase 4 + 5 (audit + polish)                                                |
| Day slice D | Phase 6 (tests, coverage, README)                                           |
| Stretch     | Phases 7–11 as time allows (rate limit → cache → export → webhooks → Sonar) |


---



## Progress tracker (roll-up)

- [x] Phase 0 — Bootstrap & runtime
- [x] Phase 1 — Users API
- [x] Phase 2 — Fraud domain
- [x] Phase 3 — Process transaction MVP
- [x] Phase 4 — Mongo audit publisher
- [x] Phase 5 — API polish & observability
- [x] Phase 6 — Hardening & submission README
- [x] Phase 7 — Rate limiting (optional)
- [ ] Phase 8 — Redis cache (optional)
- [ ] Phase 9 — Bulk export (optional)
- [ ] Phase 10 — Webhooks (optional)
- [ ] Phase 11 — Static analysis (optional)

**First impressive demo:** end of **Phase 3**.  
**Challenge-complete demo:** end of **Phase 6**.