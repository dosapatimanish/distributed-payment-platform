# Consume Lock

`POST /api/v1/fx/rate-lock/{lockId}/consume`

## Purpose

Marks a rate lock as actually used — the terminal "success" transition. Called once the locked
rate has actually been applied to a real money movement (e.g. once the orchestrator's
source-wallet debit succeeds using `lockedRate`). Not in the design doc's REST contract table,
but required to complete the lock's state machine — see Features.

## Request

Header: `Idempotency-Key` (required) — see Features. Path param: `lockId` (UUID string). No body.

## Response — `200 OK`

```json
{
  "lockId": "9a41e9c2-6504-4af1-8292-798ebc71acba",
  "transactionId": "txn-fx-001",
  "baseCurrency": "USD",
  "quoteCurrency": "INR",
  "lockedRate": 82.75822683,
  "amount": 100.0000,
  "status": "CONSUMED",
  "createdAt": "2026-08-22T13:45:03.391430Z",
  "expiresAt": "2026-08-22T13:45:13.387859Z"
}
```

## Error responses

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | missing `Idempotency-Key` header |
| 404 | `RATE_LOCK_NOT_FOUND` | no lock with that id |
| 409 | `RATE_LOCK_NOT_ACTIVE` | lock is already `CONSUMED`/`RELEASED`, its `expiresAt` has passed (lazily flipped to `EXPIRED` right here, then rejected), or this `Idempotency-Key` is still in progress |

## Flow (file by file)

1. [FxRateController.consumeLock](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/web/FxRateController.java) — `@RequestHeader("Idempotency-Key")` (required) + `@PathVariable lockId`, no request DTO.
2. [IdempotencyGuard.runIdempotent](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/idempotency/IdempotencyGuard.java) — a completed key short-circuits to the cached `RateLockResponse`, skipping every step below.
3. [FxRateService.consumeLock](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/service/FxRateService.java) → `requireActiveLock`:
   - loads the lock (`RateLockNotFoundException` if missing).
   - rejects if not `ACTIVE` (`RateLockNotActiveException`).
   - **lazy expiry check**: if `ACTIVE` but `expiresAt` has already passed, flips it to `EXPIRED`, saves that, then still rejects the call as `RateLockNotActiveException` — nothing sweeps expired locks proactively, so this is the one place staleness actually gets caught and recorded.
   - on success: status → `CONSUMED`, saved.
4. [FxRateLockRepository](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/repository/FxRateLockRepository.java) — `findById`, `save`.
5. `RateLockResponse.from(lock)`. On success, `IdempotencyGuard` caches it; on failure, it releases the key.

## Features

- **Fills a gap the design doc's contract table leaves open**: that table only lists lock-creation and release (`DELETE`); without an explicit consume step, `CONSUMED` — the success path — would be unreachable, and a lock would only ever end at `RELEASED` or a silently-stale `ACTIVE`.
- **Terminal, one-way**: once `CONSUMED`, a lock can never be released (see [04-release-lock.md](04-release-lock.md)) or re-consumed — the rate was used, that's final.
- **`Idempotency-Key` turns a retry-after-success from an error into a replay**: without it, a retried consume call after the first one already succeeded would hit `RATE_LOCK_NOT_ACTIVE` (the lock is now `CONSUMED`, not `ACTIVE`) — a spurious error for a call that already worked. With it, the retry gets the original `CONSUMED` response back instead.
