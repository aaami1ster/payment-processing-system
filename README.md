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

`FLAGGED` is a **successful** authorization that needs review (HTTP `201` once transactions are wired). Business `DECLINED` is not an infrastructure failure — Postgres outages map to `503`, not a fabricated decline. High-risk categories and thresholds live in `fraud.*` (`FraudProperties`). Details: [docs/low-level-design.md](docs/low-level-design.md) (Fraud Detection Engine, Rule 2).

```bash
mvn -q -Dtest='*Fraud*,*Rule*,*VelocityWindow*' test
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
