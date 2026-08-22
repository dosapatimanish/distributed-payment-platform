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
| Real Redisson `RLock` for rate-lock creation | Single-instance service has no cross-JVM lock contention yet. `DistributedLockManager` is an in-memory placeholder with the exact same two-method contract (`acquireLock`/`releaseLock`), so swapping in real Redisson later is a class-body change, not a call-site change. Same category of deferral as wallet-service's Kafka piece. Note: this is a *different* Redis than the one now backing Idempotency-Key below - that one really is real Redis, just not yet used for distributed locking here. |
| Real external FX rate provider | `RateRefreshScheduler` fakes a fluctuating rate instead — no API key/rate-limit/downtime handling to build against yet, and nothing downstream (Conversion Orchestrator) consumes real rates yet either. |
| Expired-lock sweep | A lock past `expiresAt` is only marked `EXPIRED` lazily, the next time something tries to consume or release it — nothing proactively sweeps `ACTIVE` locks whose TTL has silently passed. Same gap as wallet-service's un-swept expired reservations. |
| Kafka `rate.locked` / `rate.lock.failed` events | Nothing consumes these yet — same reasoning as wallet-service's deferred event publishing. |
| Testcontainers integration tests | Same scope decision as wallet-service — unit tests only for this pass, see [Automated tests](#automated-tests) below. |
| Flyway/Liquibase | `ddl-auto=update`, same deliberate temporary choice as wallet-service. |

## Package layout

```
com.paymentplatform.fxrate
├── ping/          toolchain-check endpoint
├── domain/        FxRate, FxRateLock, RateLockStatus
├── repository/    FxRateRepository, FxRateLockRepository
├── service/       FxRateCache, DistributedLockManager, RateRefreshScheduler, FxRateService
├── web/           FxRateController + request/response DTO records
├── exception/     custom exceptions + GlobalExceptionHandler
└── idempotency/   IdempotencyGuard, IdempotencyKeyInProgressException
```

## Idempotency-Key

See [idempotency.md](idempotency.md) for the full concept writeup (what/why/how, shared across
both services) — this section covers only fx-rate-service-specific detail.

`lockRate` and `consumeLock` require an `Idempotency-Key` header (design doc §6.2.3);
`getCurrentRate` is read-only and `releaseLock` is already idempotent by design at the business
layer (see `FxRateService.releaseLock`), so neither needs one. Identical mechanics and the same
only-cache-success simplification as wallet-service's guard — see its implementation-notes doc
for the full reasoning; `IdempotencyGuard` here is a deliberate independent copy, not a shared
library, consistent with how domain/exception/etc are mirrored rather than extracted across
these two services so far.

**Where this actually improves on the business layer alone**: both `lockRate` and `consumeLock`
already had *some* protection against a retry - a duplicate `transactionId` on `lockRate` hits
the DB unique constraint (409 `RATE_LOCK_CONFLICT`), and consuming an already-`CONSUMED` lock
hits the state check (409 `RATE_LOCK_NOT_ACTIVE`). Neither of those is a true idempotent
replay, though - both return an *error* on a legitimate retry of a call that already succeeded.
The `Idempotency-Key` header fixes that: a retried `lockRate`/`consumeLock` call with the same
key now gets the original success response back, not an error.

Same shared Redis instance as wallet-service (`backend/docker-compose.yml`'s `redis` service),
namespaced under `fxrate:idem:` so the two services' keys can't collide with wallet-service's
`wallet:idem:`.

## Automated tests

Unit tests only for this pass (same scope decision as wallet-service): 46 tests total, all
passing (`./mvnw test`).

- **`FxRateCacheTest`** (3), **`DistributedLockManagerTest`** (5) — pure unit tests, no Spring,
  no mocks; both classes are simple in-memory collections so they're cheap to exercise directly,
  including the lease-expiry and wrong-lock-id-can't-steal-the-real-holder's-lock cases.
- **`RateRefreshSchedulerTest`** (3) — real `FxRateCache`, mocked `FxRateRepository`, a real
  pairs-config string. Checks the cache gets seeded, one row is persisted per configured pair,
  the random walk stays within a generous bound of the previous value (not flaky, just proves
  it's a small step not an arbitrary jump), and a malformed `fx.rate.pairs` entry fails fast at
  construction.
- **`FxRateServiceTest`** (15) — real `FxRateCache` and `DistributedLockManager` (both simple
  enough to use as-is), mocked `FxRateLockRepository`. Covers the full lock state machine
  including the two lazy-expiry branches that differ from each other: consuming an
  expired-but-still-`ACTIVE` lock throws (and records the `EXPIRED` transition as a side
  effect), while releasing the same kind of lock succeeds and marks it `EXPIRED` - see
  `04-release-lock.md` for why that asymmetry is deliberate.
- **`FxRateControllerTest`** (12) — `@WebMvcTest(FxRateController.class)`, `FxRateService` and
  `IdempotencyGuard` both mocked with `@MockitoBean` (the guard stubbed as a passthrough by
  default, same pattern as wallet-service's `WalletControllerTest`). Request validation and HTTP
  status/error-code mapping for all 4 endpoints, plus the missing-header and key-in-progress
  paths on `lockRate`.
- **`IdempotencyGuardTest`** (8) — identical structure to wallet-service's own
  `IdempotencyGuardTest` (mocked `StringRedisTemplate`/`ValueOperations`, real `ObjectMapper`),
  since the two `IdempotencyGuard` classes are deliberate copies of each other.

## Local run

```
docker compose -f backend/docker-compose.yml up -d fxrate-postgres redis
cd backend/fx-rate-service && ./mvnw spring-boot:run
```

fxrate-postgres publishes on host port **5435** (5432/5433/5434 were already taken locally),
container port stays 5432 internally — see `backend/docker-compose.yml`. If port 8082 is
already in use on startup, see wallet-service-implementation.md's "How to run it locally" note
on finding and stopping the specific orphaned PID rather than killing all java processes.

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
- **Idempotency-Key, against real Redis**: missing header on `lockRate` → 400; `lockRate`
  replayed with the same key → identical `lockId`/`lockedRate` back, not the `RATE_LOCK_CONFLICT`
  a plain retry would otherwise hit; `consumeLock` replayed with the same key → identical
  `CONSUMED` response back, not the `RATE_LOCK_NOT_ACTIVE` a plain retry would otherwise hit.

## Next candidates

- Conversion Orchestrator (port `:8083`) — the first service that actually calls both
  wallet-service and fx-rate-service, per design doc §6.3.3.
- Or round out fx-rate-service/wallet-service gaps (Kafka events, Testcontainers integration
  tests) before taking on the orchestrator's added complexity.
