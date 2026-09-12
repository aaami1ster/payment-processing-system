# Payment Processing System

Spring Boot **4.1.1** payment service with fraud detection (challenge MVP; Boot 4 only because Spring Framework High/Critical CVEs have no OSS 6.2.x fix — see `docs/plan.md`). Design docs live in [`docs/`](docs/).

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker + Docker Compose

## Runtime stack (Compose)

| Service | Image |
| ------- | ----- |
| App | built from `Dockerfile` (Temurin 21) |
| PostgreSQL | `postgres:18-alpine` |
| MongoDB | `mongo:8` (official image; no alpine tag) |

## Run with Docker Compose

```bash
cp .env.example .env   # first time only; .env is gitignored
docker compose up --build -d
```

Configuration lives in `.env` (ports, DB credentials, Mongo URI / `GLIBC_TUNABLES`). See `.env.example`.

If you previously ran an older Postgres major (≤17) with this project, wipe volumes once so PG 18 can init a fresh data dir:

```bash
docker compose down -v
docker compose up --build -d
```

Wait until the app is healthy, then check (port from `APP_PORT`, default `8080`):

```bash
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/actuator/health/liveness
curl -s http://localhost:8080/actuator/health/readiness
```

Expect `"status":"UP"`. Readiness requires PostgreSQL; MongoDB can be down without failing readiness (app still waits for a healthy Mongo on first compose start).

Verify Liquibase tables:

```bash
docker compose exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c '\dt'
# or defaults: -U payments -d payments
# expect: users, transactions, audit_outbox (+ Liquibase tables)
```

Stop:

```bash
docker compose down
```

## Manual API tests (Bruno)

Open [`bruno/`](bruno/) in [Bruno](https://www.usebruno.com/) (YAML / OpenCollection) with env **Local**, or:

```bash
cd bruno && npx @usebruno/cli run health --env Local
cd bruno && npx @usebruno/cli run user --env Local
cd bruno && npx @usebruno/cli run transaction --env Local
```

## Users API (Phase 1)

Base path: `/api/v1/users`. Responses use the `ApiResponse` envelope (`data`, `message`, `errors[]`, `meta.requestId`).

```bash
# Create (KYC defaults to PENDING; preApprovedTransactionLimit is null)
curl -s -X POST http://localhost:8080/api/v1/users \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com"}'

# Get
curl -s http://localhost:8080/api/v1/users/<userId>

# Patch KYC and/or pre-approved limit (needed later for fraud Rule 1)
curl -s -X PATCH http://localhost:8080/api/v1/users/<userId> \
  -H 'Content-Type: application/json' \
  -d '{"kycStatus":"VERIFIED","preApprovedTransactionLimit":15000}'
```

| Method | Path | Success | Notes |
| ------ | ---- | ------- | ----- |
| `POST` | `/api/v1/users` | `201` | Body: `{ "email", "kycStatus"? }` |
| `GET` | `/api/v1/users` | `200` | Cursor page: `items`, `nextCursor`, `hasMore` (`limit`, `cursor`, optional `kycStatus`) |
| `GET` | `/api/v1/users/{id}` | `200` | `404` + `USER_NOT_FOUND` if missing |
| `PATCH` | `/api/v1/users/{id}` | `200` | Partial update of KYC and/or limit |
| `POST` duplicate email | | `409` | `EMAIL_ALREADY_EXISTS` |
| Invalid body / query | | `400` | `VALIDATION_ERROR` (+ `field` when known) |

```bash
# List (newest first; optional cursor / kycStatus / limit)
curl -s 'http://localhost:8080/api/v1/users?limit=50'
```

**Validation:** Bean Validation on request DTOs (`@Valid`) for formats/ranges; handlers enforce business rules (duplicate email, missing user, non-HTTP callers). See `docs/low-level-design.md` — *Request validation (layered)*.

## Fraud rules (Phase 2)

In-process strategy objects under `domain/fraud` (no Drools). The service gathers data; `FraudEngine` runs **all** rules and aggregates:

`DECLINE` → `DECLINED` · else `FLAG` → `FLAGGED` · else `APPROVED`

| Rule | Condition | Outcome |
| ---- | --------- | ------- |
| `AMOUNT_WITHOUT_APPROVAL` | `amount > 10_000` and `amount > preApprovedTransactionLimit` (`null`/`0` = no approval) | `DECLINE` |
| `VELOCITY` | ≥ 3 prior `APPROVED`/`FLAGGED` in inclusive `[now-60s, now]` | `DECLINE` |
| `HIGH_RISK_CATEGORY` | category ∈ high-risk set (`GAMBLING`, `CRYPTO`, `CASH_ADVANCE`, `ADULT`) and `amount > 5_000` | `DECLINE` |
| `NEW_USER_HIGH_AMOUNT` | `amount > 5_000` and user younger than 30 days | `FLAG` |

`FLAGGED` is a **successful** authorization that needs review (HTTP `201`). Business `DECLINED` is not an infrastructure failure — Postgres outages map to `503`, not a fabricated decline. High-risk categories and thresholds live in `fraud.*` (`FraudProperties`). Details: [docs/low-level-design.md](docs/low-level-design.md) (Fraud Detection Engine, Rule 2).

```bash
mvn -q -Dtest='*Fraud*,*Rule*,*VelocityWindow*' test
```

## Process transaction (Phase 3)

**Core guarantee:** no `APPROVED` / `FLAGGED` / `DECLINED` is returned unless the transaction **and** its audit outbox row were committed in PostgreSQL. Mongo publish is async (Phase 4); the client never waits on Mongo.

Base path: `/api/v1/transactions`. Optional header: `Idempotency-Key` (fingerprint = SHA-256 of `userId|merchantId|amount|category`).

```bash
# 1) Create user
USER_ID=$(curl -s -X POST http://localhost:8080/api/v1/users \
  -H 'Content-Type: application/json' \
  -d '{"email":"payer@example.com"}' | jq -r '.data.id')

# 2) Small amount → APPROVED (HTTP 201)
curl -s -X POST http://localhost:8080/api/v1/transactions \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-key-1" \
  -d "{\"amount\":100.00,\"userId\":\"$USER_ID\",\"merchantId\":\"mch_demo\",\"category\":\"GROCERIES\"}"

# 3) High amount without limit → DECLINED (still HTTP 201; errors=[])
curl -s -X POST http://localhost:8080/api/v1/transactions \
  -H 'Content-Type: application/json' \
  -d "{\"amount\":12000.00,\"userId\":\"$USER_ID\",\"merchantId\":\"mch_demo\",\"category\":\"GROCERIES\"}"

# 4) Raise pre-approved limit, retry high amount → FLAGGED for new users (Rule 4), still 201
curl -s -X PATCH "http://localhost:8080/api/v1/users/$USER_ID" \
  -H 'Content-Type: application/json' \
  -d '{"preApprovedTransactionLimit":15000}'

curl -s -X POST http://localhost:8080/api/v1/transactions \
  -H 'Content-Type: application/json' \
  -d "{\"amount\":12000.00,\"userId\":\"$USER_ID\",\"merchantId\":\"mch_demo\",\"category\":\"GROCERIES\"}"

# 5) Replay same Idempotency-Key + body → same transactionId
curl -s -X POST http://localhost:8080/api/v1/transactions \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-key-1" \
  -d "{\"amount\":100.00,\"userId\":\"$USER_ID\",\"merchantId\":\"mch_demo\",\"category\":\"GROCERIES\"}"

# 6) Verify durable rows (outbox becomes PUBLISHED once the Phase 4 publisher drains)
docker compose exec postgres psql -U payments -d payments \
  -c "SELECT id, status, amount FROM transactions ORDER BY created_at DESC LIMIT 5;"
docker compose exec postgres psql -U payments -d payments \
  -c "SELECT transaction_id, status FROM audit_outbox ORDER BY id DESC LIMIT 5;"
```

| Outcome | HTTP | Notes |
| ------- | ---- | ----- |
| Processed (`APPROVED`/`FLAGGED`/`DECLINED`) | `201` | Business status in `data.status`; `errors` = `[]` |
| Unknown user | `404` | `USER_NOT_FOUND` |
| Same key, different payload | `409` | `IDEMPOTENCY_CONFLICT` |
| Invalid body | `400` | `VALIDATION_ERROR` |
| PostgreSQL / commit failure | `503` | `SERVICE_UNAVAILABLE` |

Authorize path loads the user with `SELECT … FOR UPDATE` inside `ProcessTransactionHandler` — never via `GetUserHandler`.

## Async audit (Phase 4)

**Why an outbox (not sync dual-write)?** The authorize path must never wait on Mongo. PostgreSQL commits the transaction **and** an `audit_outbox` row in one DB transaction; that is the durable decision. A background `OutboxPublisher` claims `PENDING` rows with `FOR UPDATE SKIP LOCKED`, inserts an immutable Mongo `audit_logs` document (`_id = transactionId`), and marks `PUBLISHED`. Retries use exponential backoff (`next_attempt_at` / `attempts`). Duplicate `_id` on retry is treated as success (at-least-once).

**Why Mongo at all?** PostgreSQL alone could store audit rows atomically and would be simpler. Mongo is a separate **audit projection** to demonstrate resilience across heterogeneous stores. PostgreSQL remains the system of record; Mongo may lag without blocking payments.

```bash
# After POST /transactions, wait a moment then inspect Mongo
docker compose exec mongo mongosh payments_audit --quiet --eval 'db.audit_logs.find().limit(3).toArray()'

# Outbox should move PENDING → PUBLISHED
docker compose exec postgres psql -U payments -d payments \
  -c "SELECT transaction_id, status, attempts FROM audit_outbox ORDER BY id DESC LIMIT 5;"

# Mongo down: payments still return 201; outbox stays PENDING until Mongo recovers
docker compose stop mongo
# POST /api/v1/transactions → 201, outbox PENDING
docker compose start mongo
# publisher drains → audit document appears, outbox PUBLISHED
```

```bash
mvn -q -Dtest='OutboxPublisherIT' test
```

```bash
cd bruno && npx @usebruno/cli run user --env Local
cd bruno && npx @usebruno/cli run transaction --env Local
# GET / list / audit requests may 404 until later phases — POST + idempotency + error cases are Phase 3
```

## Local Maven build

```bash
mvn -q -DskipTests package
```

Requires PostgreSQL + Mongo reachable at the URLs in `src/main/resources/application.yml` (or override via env).

**Lombok:** optional compile-time dependency (`@Value`, `@Getter`/`@Setter`, `@RequiredArgsConstructor`). Enable annotation processing in the IDE. Prefer Java **records** for API DTOs; keep `LogFactory` (do not use `@Slf4j`).

## Security vulnerability scan

Run after each phase (and before marking validation done):

```bash
./scripts/check-security-docker-scout.sh   # fast (Docker Scout; docker login once)
BUILD_APP_IMAGE=1 ./scripts/check-security-docker-scout.sh  # rebuild app then scan
./scripts/check-security-owasp.sh          # Maven deps (OWASP; needs NVD_API_KEY in .env)
# or:
./scripts/check-security-vulnerabilities.sh
```

Gate = project sources + app image (Temurin base ignored by default). Official `postgres`/`mongo` images are scanned as warnings (`FAIL_ON_VENDOR=1` to enforce). Set `NVD_API_KEY` in `.env` (see `.env.example`). Reports: `target/security/scout/` and `target/security/owasp/`.

## IntelliJ: debug with `.env`

To load project `.env` variables when running/debugging the app from IntelliJ:

1. **IntelliJ → Settings → Plugins**
2. Search for **EnvFile** by **Borys Pierov**, install it, then **restart** the IDE
3. Open the run/debug configuration for `PaymentProcessingApplication`
4. Follow the plugin **Overview** steps to enable EnvFile and point it at the project root `.env` (copy from `.env.example` if needed)
5. Start the configuration in **Debug** mode

Ensure Postgres and Mongo are up (e.g. `docker compose up -d postgres mongo`) before debugging locally.

## Docs

| Doc | Purpose |
| --- | ------- |
| [docs/README.md](docs/README.md) | Design index |
| [docs/plan.md](docs/plan.md) | Phased implementation plan |
| [docs/high-level-design.md](docs/high-level-design.md) | Architecture |
| [docs/low-level-design.md](docs/low-level-design.md) | Schema, API, fraud rules |
