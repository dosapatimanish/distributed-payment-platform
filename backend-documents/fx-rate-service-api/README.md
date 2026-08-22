# FX Rate Service — API Docs

4 endpoints, base URL `http://localhost:8082`, all under `/api/v1/fx`. `lockRate` and
`consumeLock` require an `Idempotency-Key` header; `getCurrentRate` (read-only) and `releaseLock`
(already idempotent by design) don't — see [02-lock-rate.md](02-lock-rate.md)'s Features.

| # | Method | Path | Doc |
|---|---|---|---|
| 1 | GET | `/api/v1/fx/rates/{base}/{quote}` | [01-get-current-rate.md](01-get-current-rate.md) |
| 2 | POST | `/api/v1/fx/rate-lock` | [02-lock-rate.md](02-lock-rate.md) |
| 3 | POST | `/api/v1/fx/rate-lock/{lockId}/consume` | [03-consume-lock.md](03-consume-lock.md) |
| 4 | DELETE | `/api/v1/fx/rate-lock/{lockId}` | [04-release-lock.md](04-release-lock.md) |

## Other files here

- [fx-rate-service.openapi.yaml](fx-rate-service.openapi.yaml) — OpenAPI 3.0 spec. Import into Swagger UI / Swagger Editor to browse or generate a client.
- [fx-rate-service.postman_collection.json](fx-rate-service.postman_collection.json) — Postman collection v2.1. Import directly; `Lock Rate` auto-captures `lockId` into a collection variable, so `Consume Lock` / `Release Lock` chain off it without manual copy-paste.

## Typical flows

- **Successful conversion step**: 1 (read rate) → 2 (lock) → 3 (consume, once the locked rate was actually used).
- **Compensated/failed conversion**: 1 → 2 (lock) → 4 (release, saga rolls back before the rate was used).
- Calling 4 twice on the same lock, or after it already expired, is safe — it's idempotent, always `200`. Calling 3 or 4 on an already-`CONSUMED` lock is not — `409`.

See [../fx-rate-service-implementation.md](../fx-rate-service-implementation.md) for the concurrency-control design behind rate locking (in-memory `DistributedLockManager` standing in for the design doc's Redisson `RLock`, and the `ReadWriteLock`-guarded rate cache).
