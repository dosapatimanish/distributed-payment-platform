# fx-rate-service — Implementation Notes

Second microservice in the platform, built the same way wallet-service was: read the design
doc section for it (6.1.2, 6.2.2, 6.3.2), scaffold, verify manually with `curl`.

## What this step built

The FX Rate Service, as a standalone Spring Boot 4.1.1 (Java 25) module on port `:8082`, backed
by its own PostgreSQL database (`fxrate_db`):

- `GET /api/v1/fx/rates/{base}/{quote}` — current rate, served from an in-memory cache.
- `POST /api/v1/fx/rate-lock` — create a short-lived (10s default) locked rate for a transaction.
- `POST /api/v1/fx/rate-lock/{lockId}/consume` — mark a lock used (ACTIVE → CONSUMED).
- `DELETE /api/v1/fx/rate-lock/{lockId}` — release a lock; idempotent (design doc §6.4).
- A simulated rate feed (`RateRefreshScheduler`, `@Scheduled` every 1s) generating a small
  random walk around each configured pair's seed rate — no external provider wired in yet.
- The in-memory cache pattern from design doc §6.2.2: `ConcurrentHashMap`-backed snapshot
  guarded by a `ReadWriteLock`, atomically swapped in whole on every refresh tick.
- The consistent `ErrorResponse` JSON shape, same as wallet-service.

### Deliberately deferred (and why)

| Deferred | Why |
|---|---|
| Real Redisson `RLock` for rate-lock creation | Single-instance service has no cross-JVM lock contention yet. `DistributedLockManager` is an in-memory placeholder with the exact same two-method contract (`acquireLock`/`releaseLock`), so swapping in real Redisson later is a class-body change, not a call-site change. Same category of deferral as wallet-service's Kafka/Redis-idempotency pieces. |
| Real external FX rate provider | `RateRefreshScheduler` fakes a fluctuating rate instead — no API key/rate-limit/downtime handling to build against yet, and nothing downstream (Conversion Orchestrator) consumes real rates yet either. |
| Expired-lock sweep | A lock past `expiresAt` is only marked `EXPIRED` lazily, the next time something tries to consume or release it — nothing proactively sweeps `ACTIVE` locks whose TTL has silently passed. Same gap as wallet-service's un-swept expired reservations. |
| Kafka `rate.locked` / `rate.lock.failed` events | Nothing consumes these yet — same reasoning as wallet-service's deferred event publishing. |
| Automated tests | Verified manually via `curl` for this step, same as wallet-service so far. |
| Flyway/Liquibase | `ddl-auto=update`, same deliberate temporary choice as wallet-service. |

## Package layout

```
com.paymentplatform.fxrate
├── ping/          toolchain-check endpoint
├── domain/        FxRate, FxRateLock, RateLockStatus
├── repository/    FxRateRepository, FxRateLockRepository
├── service/       FxRateCache, DistributedLockManager, RateRefreshScheduler, FxRateService
├── web/           FxRateController + request/response DTO records
└── exception/     custom exceptions + GlobalExceptionHandler
```

## Local run

```
docker compose -f backend/docker-compose.yml up -d fxrate-postgres
cd backend/fx-rate-service && ./mvnw spring-boot:run
```

fxrate-postgres publishes on host port **5435** (5432/5433/5434 were already taken locally),
container port stays 5432 internally — see `backend/docker-compose.yml`.

## Manually verified (this step)

- `GET /api/v1/fx/rates/USD/INR` → live-fluctuating rate from the simulated feed.
- `GET /api/v1/fx/rates/XXX/YYY` → 404 `UNSUPPORTED_CURRENCY_PAIR`.
- `POST /api/v1/fx/rate-lock` → 201, `ACTIVE` lock.
- Same `transactionId` locked twice → 409 `RATE_LOCK_CONFLICT` (unique constraint).
- `.../consume` on an `ACTIVE` lock → 200, `CONSUMED`.
- `DELETE` on a `CONSUMED` lock → 409 `RATE_LOCK_NOT_ACTIVE` (can't un-consume).
- `DELETE` on an unknown lock → 404 `RATE_LOCK_NOT_FOUND`.
- `DELETE` on an `ACTIVE` lock, then `DELETE` again → both 200 (idempotent release).
- Invalid request body (2-char currency, negative amount, blank `transactionId`) → 400
  `VALIDATION_FAILED` listing all three field errors.

## Next candidates

- Conversion Orchestrator (port `:8083`) — the first service that actually calls both
  wallet-service and fx-rate-service, per design doc §6.3.3.
- Or round out fx-rate-service/wallet-service gaps (tests, Kafka events, Idempotency-Key) before
  taking on the orchestrator's added complexity.
