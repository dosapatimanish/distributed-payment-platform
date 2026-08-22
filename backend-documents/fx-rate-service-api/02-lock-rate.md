# Lock Rate

`POST /api/v1/fx/rate-lock`

## Purpose

Freezes the current rate for a specific pair against a specific `transactionId`, so a
multi-step conversion (debit source wallet → credit destination wallet → pay merchant) uses
one fixed rate for its whole lifetime, immune to the feed refreshing underneath it every second.

## Request

Header: `Idempotency-Key` (required) — see Features.

```json
{
  "baseCurrency": "USD",
  "quoteCurrency": "INR",
  "amount": 100.00,
  "transactionId": "txn-fx-001"
}
```

| Field | Type | Rule |
|---|---|---|
| `baseCurrency` | string | `@NotBlank`, exactly 3 chars |
| `quoteCurrency` | string | `@NotBlank`, exactly 3 chars |
| `amount` | decimal | `@NotNull`, `@DecimalMin("0.0001")` |
| `transactionId` | string | `@NotBlank` — the SAGA/business transaction this lock belongs to; UNIQUE, one active lock per transaction |

## Response — `201 Created`

Header `Location: /api/v1/fx/rate-lock/{lockId}`

```json
{
  "lockId": "9a41e9c2-6504-4af1-8292-798ebc71acba",
  "transactionId": "txn-fx-001",
  "baseCurrency": "USD",
  "quoteCurrency": "INR",
  "lockedRate": 82.75822683,
  "amount": 100.00,
  "status": "ACTIVE",
  "createdAt": "2026-08-22T13:45:03.391429600Z",
  "expiresAt": "2026-08-22T13:45:13.387858700Z"
}
```

`expiresAt` = `createdAt` + `fx.rate.lock.ttl-seconds` (10s default).

## Error responses

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | missing `Idempotency-Key` header, bad currency code length, non-positive `amount`, blank `transactionId` |
| 404 | `UNSUPPORTED_CURRENCY_PAIR` | no current rate cached for this pair |
| 409 | `RATE_LOCK_CONFLICT` | a lock already exists for this `transactionId` (a duplicate call with a *different* `Idempotency-Key`), the per-pair lock-creation mutex couldn't be acquired after 3 attempts, or this `Idempotency-Key` is still in progress |

## Flow (file by file)

1. [FxRateController.lockRate](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/web/FxRateController.java) — `@RequestHeader("Idempotency-Key")` (required) + binds `RateLockRequest`.
2. [IdempotencyGuard.runIdempotent](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/idempotency/IdempotencyGuard.java) — a completed key short-circuits to the cached `RateLockResponse`, skipping every step below.
3. [FxRateService.lockRate](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/service/FxRateService.java):
   - `DistributedLockManager.acquireLock(pairKey, 5s lease)` — a short mutex keyed by `base/quote`, held only for this method's own critical section (read the rate, build the row, save it), not for the lock's full 10s business TTL. Retries up to 3 times with a tiny backoff if the mutex is momentarily busy, else throws `RateLockConflictException`.
   - inside the mutex: `getCurrentRate` reads the cache, builds a new `FxRateLock` (`ACTIVE`, `expiresAt = now + ttl`).
   - `lockRepository.save(lock)` — the `transaction_id` UNIQUE constraint catches a duplicate/retried request for the same transaction; caught as `DataIntegrityViolationException`, rethrown as `RateLockConflictException`.
   - mutex released in a `finally` regardless of outcome.
4. [DistributedLockManager](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/service/DistributedLockManager.java) — in-memory placeholder for the design doc's Redisson `RLock`; same method contract, so a real distributed lock drops in later without touching `FxRateService`.
5. [FxRateLockRepository](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/repository/FxRateLockRepository.java) — plain `save`.
6. `RateLockResponse.from(lock)`. On success, `IdempotencyGuard` caches this response; on failure, it releases the key.

## Features

- **Mutex protects lock-*creation*, not the lock's lifetime**: contrast with the wallet's pessimistic `SELECT ... FOR UPDATE`, which is held for an entire mutation. Here the per-pair mutex is released the instant the `fx_rate_lock` row is written — the 10s TTL that follows is enforced purely by `expiresAt`, no lock held.
- **One active lock per transaction**: the DB unique constraint on `transaction_id`, not just an app-level check — a genuinely concurrent duplicate request still can't create two locks for the same transaction.
- **Fail-fast on contention**: 3 bounded attempts, then a 409 telling the caller to retry the whole request — same philosophy as wallet-service's `WalletConflictException`.
- **`Idempotency-Key` fixes what the unique constraint alone can't**: the `transaction_id` constraint stops a duplicate from creating a *second* lock, but a plain retry with no key still hits that constraint as an *error* (`RATE_LOCK_CONFLICT`), not a replay. `Idempotency-Key` turns a legitimate retry into the original success response instead — same `lockId` and `lockedRate` back, not a 409. See [idempotency.md](../idempotency.md) for the full what/why/how (same mechanism on every write endpoint in both services; this service's `IdempotencyGuard` is a deliberate copy of wallet-service's).
