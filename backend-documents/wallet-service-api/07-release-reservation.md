# Release Reservation

`POST /api/v1/wallets/reservations/{reservationId}/release`

## Purpose

Cancels a `HELD` reservation without moving any money — the wallet's balance was never touched
at reserve time, so releasing is a pure status flip. Terminal, one-way: `HELD` → `RELEASED`.

## Request

Path param only: `reservationId` (UUID string). No body.

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
| 404 | `RESERVATION_NOT_FOUND` | no reservation with that id |
| 409 | `INVALID_RESERVATION_STATE` | reservation already `CAPTURED` or `RELEASED` |

## Flow (file by file)

1. [WalletController.releaseReservation](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/web/WalletController.java) — `@PathVariable reservationId`, no request DTO.
2. [WalletService.releaseReservation](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/service/WalletService.java):
   - `requireHeldReservation` — same gate as capture (`ReservationNotFoundException` / `InvalidReservationStateException`).
   - flips status to `RELEASED`, saves.
   - re-fetches the wallet with a plain `findById` purely to return it in the response — **no lock, no mutation, no `applyMutation` dispatch**, because there is nothing to write on the wallet side.
3. [WalletReservationRepository](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/repository/WalletReservationRepository.java) — `findById`, `save`.
4. [WalletRepository](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/repository/WalletRepository.java) — plain `findById` only.
5. `WalletResponse.from(wallet)`.

## Features

- **No balance mutation, no locking needed**: unlike every other write endpoint in this service, release doesn't go through `applyMutation`/optimistic-retry/pessimistic-lock at all — there's nothing contended, since it only ever writes the reservation row.
- **Same state-machine gate as capture**: `requireHeldReservation` guarantees a reservation can be captured *or* released, but never both, and never twice.
