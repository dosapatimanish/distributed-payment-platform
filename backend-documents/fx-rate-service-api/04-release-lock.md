# Release Lock

`DELETE /api/v1/fx/rate-lock/{lockId}`

## Purpose

Gives up a rate lock without using it — e.g. a SAGA compensation step when a later stage of
the conversion fails and everything already done needs undoing, including this lock.

## Request

Path param only: `lockId` (UUID string). No body.

## Response — `200 OK`

```json
{
  "lockId": "df77d789-4757-4304-b667-8a7cb0e520cb",
  "transactionId": "txn-fx-002",
  "baseCurrency": "USD",
  "quoteCurrency": "EUR",
  "lockedRate": 0.92506189,
  "amount": 50.0000,
  "status": "RELEASED",
  "createdAt": "2026-08-22T13:45:10.570236Z",
  "expiresAt": "2026-08-22T13:45:20.568373Z"
}
```

## Error responses

| Status | Code | When |
|---|---|---|
| 404 | `RATE_LOCK_NOT_FOUND` | no lock with that id |
| 409 | `RATE_LOCK_NOT_ACTIVE` | lock is `CONSUMED` — the rate was already used, nothing to give back |

Note there is **no error** for releasing an already-`RELEASED` or already-`EXPIRED` lock — see
Features.

## Flow (file by file)

1. [FxRateController.releaseLock](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/web/FxRateController.java) — `@PathVariable lockId`, no request DTO. Mapped `DELETE`, matching the design doc's REST contract table exactly (§6.4).
2. [FxRateService.releaseLock](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/service/FxRateService.java):
   - loads the lock (`RateLockNotFoundException` if missing).
   - **already `RELEASED` or `EXPIRED`** → returns it as-is, no state change, still `200`.
   - **`CONSUMED`** → rejected, `RateLockNotActiveException`.
   - **`ACTIVE`** → flips to `EXPIRED` if `expiresAt` has already passed, otherwise `RELEASED`; saves either way.
3. [FxRateLockRepository](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/repository/FxRateLockRepository.java) — `findById`, `save`.
4. `RateLockResponse.from(lock)`.

## Features

- **Idempotent by design** (design doc §6.4 calls this out explicitly): a saga compensation step or a network-retried release call must never fail just because the release already happened once — calling this twice on the same lock returns `200` both times, second call a pure no-op.
- **Contrast with wallet-service's reservation release**: wallet's `releaseReservation` is a *strict* one-shot state machine (release an already-released reservation → error). This endpoint is deliberately looser on the "already terminal, no-op fine" side, but just as strict as wallet's about not undoing a completed transition (`CONSUMED`, like wallet's `CAPTURED`, can never be reversed here).
- **No `Idempotency-Key` here, unlike lock/consume**: this is the one write endpoint in either service that skips it deliberately — the business layer's own idempotent-release logic (above) already gives a retry the exact same safe behavior an `Idempotency-Key` would add, so the header would be pure overhead. Contrast with wallet's `releaseReservation` ([07-release-reservation.md](../wallet-service-api/07-release-reservation.md)), which *isn't* idempotent at the business layer and so needs the header for the same safety.
