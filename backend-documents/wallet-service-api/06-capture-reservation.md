# Capture Reservation

`POST /api/v1/wallets/reservations/{reservationId}/capture`

## Purpose

Converts a `HELD` reservation into an actual debit — the money promised at reserve-time
now really leaves the wallet. Terminal, one-way transition: `HELD` → `CAPTURED`.

## Request

Path param only: `reservationId` (UUID string). No body.

## Response — `200 OK`

Returns the **wallet**, post-debit (not the reservation):

```json
{
  "walletId": "b3b3c1b0-1e2a-4b3a-9c1a-0f7e2b6a1234",
  "userId": "user-123",
  "currency": "USD",
  "balance": 75.0000,
  "status": "ACTIVE",
  "highContention": false,
  "createdAt": "2026-08-22T10:00:00Z",
  "updatedAt": "2026-08-22T10:15:00Z"
}
```

## Error responses

| Status | Code | When |
|---|---|---|
| 404 | `RESERVATION_NOT_FOUND` | no reservation with that id |
| 409 | `INVALID_RESERVATION_STATE` | reservation already `CAPTURED` or `RELEASED` (not currently `HELD`) |
| 409 | `WALLET_CONFLICT` / `WALLET_LOCK_TIMEOUT` | same concurrency failure modes as a direct debit |
| 422 | `INSUFFICIENT_FUNDS` | balance has since dropped below the reserved amount (rare — balance wasn't touched at reserve time, so it can move between reserve and capture) |

## Flow (file by file)

1. [WalletController.captureReservation](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/web/WalletController.java) — `@PathVariable reservationId`, no request DTO.
2. [WalletService.captureReservation](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/service/WalletService.java):
   - `requireHeldReservation` — loads the reservation (`ReservationNotFoundException` if missing), asserts `status == HELD` (`InvalidReservationStateException` otherwise).
   - calls `debit(reservation.getWalletId(), reservation.getAmount(), reservation.getTransactionId())` — **reuses the exact same debit path as the direct-debit endpoint**, including its optimistic/pessimistic dispatch and all its status/funds checks.
   - on success, flips the reservation's status to `CAPTURED` and saves it.
3. [WalletReservationRepository](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/repository/WalletReservationRepository.java) — `findById`, `save`.
4. [WalletRepository](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/repository/WalletRepository.java) — via the reused `debit()` call, same as [03-debit-wallet.md](03-debit-wallet.md).
5. `WalletResponse.from(debitedWallet)` — note this returns the wallet, not a `ReservationResponse`; the caller already has the reservation details from the reserve call.

## Features

- **Capture = debit, reused**: no separate money-movement logic exists for capture — it calls `WalletService.debit` directly, so it inherits every guarantee (precision, locking, status checks) documented for the debit endpoint.
- **State machine enforcement**: `requireHeldReservation` is the single gate that makes `HELD → CAPTURED` and `HELD → RELEASED` the only legal transitions — capturing an already-captured or already-released reservation is rejected, not silently a no-op.
