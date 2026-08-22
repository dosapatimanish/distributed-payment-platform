# Release Reservation

`POST /api/v1/wallets/reservations/{reservationId}/release`

## Purpose

Cancels a `HELD` reservation without moving any money — the wallet's balance was never touched
at reserve time, so releasing is a pure status flip. Terminal, one-way: `HELD` → `RELEASED`.

## Request

Header: `Idempotency-Key` (required) — see [01-create-wallet.md](01-create-wallet.md)'s Features. Path param: `reservationId` (UUID string). No body.

## Response — `200 OK`

Returns the wallet, unchanged balance:

```json
{
  "walletId": "b3b3c1b0-1e2a-4b3a-9c1a-0f7e2b6a1234",
  "userId": "user-123",
  "currency": "USD",
  "balance": 100.0000,
  "status": "ACTIVE",
  "highContention": false,
  "createdAt": "2026-08-22T10:00:00Z",
  "updatedAt": "2026-08-22T10:10:00Z"
}
```

Note `updatedAt` here is unchanged from reserve time — releasing does not write to the `wallet`
row at all, only to the `wallet_reservation` row.

## Error responses

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | missing `Idempotency-Key` header |
| 404 | `RESERVATION_NOT_FOUND` | no reservation with that id |
| 409 | `INVALID_RESERVATION_STATE` | reservation already `CAPTURED` or `RELEASED` |
| 409 | `IDEMPOTENCY_KEY_IN_PROGRESS` | another request with this same key is still processing |

## Flow (file by file)

1. [WalletController.releaseReservation](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/web/WalletController.java) — `@RequestHeader("Idempotency-Key")` (required) + `@PathVariable reservationId`, no request DTO.
2. [IdempotencyGuard.runIdempotent](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/idempotency/IdempotencyGuard.java) — a completed key short-circuits to the cached response, skipping every step below.
3. [WalletService.releaseReservation](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/service/WalletService.java):
   - `requireHeldReservation` — same gate as capture (`ReservationNotFoundException` / `InvalidReservationStateException`).
   - flips status to `RELEASED`, saves.
   - re-fetches the wallet with a plain `findById` purely to return it in the response — **no lock, no mutation, no `applyMutation` dispatch**, because there is nothing to write on the wallet side.
4. [WalletReservationRepository](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/repository/WalletReservationRepository.java) — `findById`, `save`.
5. [WalletRepository](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/repository/WalletRepository.java) — plain `findById` only.
6. `WalletResponse.from(wallet)`. On success, `IdempotencyGuard` caches it; on failure, it releases the key.

## Features

- **No balance mutation, no locking needed**: unlike every other write endpoint in this service, release doesn't go through `applyMutation`/optimistic-retry/pessimistic-lock at all — there's nothing contended, since it only ever writes the reservation row.
- **Same state-machine gate as capture**: `requireHeldReservation` guarantees a reservation can be captured *or* released, but never both, and never twice.
- **Note this endpoint is *not* naturally idempotent at the business layer** the way fx-rate-service's equivalent release is (see `fx-rate-service-api/04-release-lock.md`) — releasing an already-`RELEASED` reservation here throws `INVALID_RESERVATION_STATE` rather than succeeding as a no-op. `Idempotency-Key` is what actually makes a retried release safe; without it, a retry after a slow-but-successful first attempt would 409.
