# Consume Lock

`POST /api/v1/fx/rate-lock/{lockId}/consume`

## Purpose

Marks a rate lock as actually used — the terminal "success" transition. Called once the locked
rate has actually been applied to a real money movement (e.g. once the orchestrator's
source-wallet debit succeeds using `lockedRate`). Not in the design doc's REST contract table,
but required to complete the lock's state machine — see Features.

## Request

Path param only: `lockId` (UUID string). No body.

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
| 404 | `RATE_LOCK_NOT_FOUND` | no lock with that id |
| 409 | `RATE_LOCK_NOT_ACTIVE` | lock is already `CONSUMED`/`RELEASED`, or its `expiresAt` has passed (lazily flipped to `EXPIRED` right here, then rejected) |

## Flow (file by file)

1. [FxRateController.consumeLock](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/web/FxRateController.java) — `@PathVariable lockId`, no request DTO.
2. [FxRateService.consumeLock](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/service/FxRateService.java) → `requireActiveLock`:
   - loads the lock (`RateLockNotFoundException` if missing).
   - rejects if not `ACTIVE` (`RateLockNotActiveException`).
   - **lazy expiry check**: if `ACTIVE` but `expiresAt` has already passed, flips it to `EXPIRED`, saves that, then still rejects the call as `RateLockNotActiveException` — nothing sweeps expired locks proactively, so this is the one place staleness actually gets caught and recorded.
   - on success: status → `CONSUMED`, saved.
3. [FxRateLockRepository](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/repository/FxRateLockRepository.java) — `findById`, `save`.
4. `RateLockResponse.from(lock)`.

## Features

- **Fills a gap the design doc's contract table leaves open**: that table only lists lock-creation and release (`DELETE`); without an explicit consume step, `CONSUMED` — the success path — would be unreachable, and a lock would only ever end at `RELEASED` or a silently-stale `ACTIVE`.
- **Terminal, one-way**: once `CONSUMED`, a lock can never be released (see [04-release-lock.md](04-release-lock.md)) or re-consumed — the rate was used, that's final.
