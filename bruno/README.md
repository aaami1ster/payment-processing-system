# Bruno collection — Payment Processing System (OpenCollection YAML)

Manual / CLI API tests for every HTTP surface defined in the design.

## Prerequisites

- App running: `docker compose up --build -d`
- [Bruno](https://www.usebruno.com/) ≥ 3.0 (YAML / OpenCollection), or `@usebruno/cli`

## Open in Bruno

1. Bruno → **Open Collection** → select this `bruno/` folder
2. Environment: **Local** (`baseUrl` = `http://localhost:8080`)

## CLI

```bash
cd bruno
npx @usebruno/cli run --env Local
npx @usebruno/cli run health --env Local
npx @usebruno/cli run user --env Local
npx @usebruno/cli run transaction --env Local
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
