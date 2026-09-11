# Bruno collection — Payment Processing System (OpenCollection YAML)

Manual / CLI API tests for every HTTP surface defined in the design.

## Prerequisites

- App running: `docker compose up --build -d`
- One of:
  - [Bruno desktop](https://www.usebruno.com/) ≥ 3.0 (YAML / OpenCollection), or
  - Node.js 20+ (provides `npx`) for `@usebruno/cli`

## Open in Bruno

1. Bruno → **Open Collection** → select this `bruno/` folder
2. Environment: **Local** (`baseUrl` = `http://localhost:8080`)

## CLI

Requires Node/`npx`. If `npx: command not found`, install Node 20+ (e.g. `brew install node`) or use the desktop app / curl below.

```bash
cd bruno
npx @usebruno/cli run --env Local
npx @usebruno/cli run health --env Local
npx @usebruno/cli run user --env Local
npx @usebruno/cli run transaction --env Local
```

Or run CLI via Docker (no local Node):

```bash
docker run --rm -v "$PWD/bruno:/collection" -w /collection --network host \
  node:20-alpine sh -c "npm i -g @usebruno/cli && bru run --env Local"
```

## Phase 0 health without Bruno

```bash
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/actuator/health/liveness
curl -s http://localhost:8080/actuator/health/readiness
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/prometheus
# expect UP / UP / UP / 200
```

## Folders

| Folder | Phase | Endpoints |
| ------ | ----- | --------- |
| `health/` | 0 | Actuator health / liveness / readiness / prometheus |
| `user/` | 1 | `POST/GET/PATCH /api/v1/users` + 404 |
| `transaction/` | 3–5 / 9 | Process, decline, get, idempotency, validation, list, audit-logs |

Each request has **docs** (contract) and **runtime tests** (status + envelope).

## Variables

| Var | Set by |
| --- | ------ |
| `userId` | Create user |
| `transactionId` | Process transaction |
| `idempotencyKey` | Process transaction (when empty) |

## Design source

[docs/low-level-design.md](../docs/low-level-design.md) — API Design & Observability.
