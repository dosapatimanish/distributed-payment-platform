# Reserve Funds (Hold)

`POST /api/v1/wallets/{walletId}/reserve`

## Purpose

Places a hold against a wallet — records that `amount` is promised, without moving it yet. The
first step of the two-step reserve → capture/release flow (contrast with the one-step debit).
Creates a row in a separate `wallet_reservation` table; **does not touch `wallet.balance`**.

## Request

```json
{
  "amount": 25.00,
  "transactionId": "txn-003"
}
```

| Field | Type | Rule |
|---|---|---|
| `amount` | decimal | `@NotNull`, `@DecimalMin("0.0001")` |
| `transactionId` | string | `@NotBlank` — the SAGA/business transaction this hold belongs to |

## Response — `201 Created`

```json
{
  "reservationId": "9f1c2b0a-3d4e-4f5a-8b6c-7d8e9f0a1b2c",
  "walletId": "b3b3c1b0-1e2a-4b3a-9c1a-0f7e2b6a1234",
  "transactionId": "txn-003",
  "amount": 25.0000,
  "status": "HELD",
  "createdAt": "2026-08-22T10:10:00Z",
  "expiresAt": "2026-08-22T10:25:00Z"
}
```

`expiresAt` = `createdAt` + `wallet.reservation.ttl-minutes` (15 min, in `application.properties`).

## Error responses

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | `amount` missing/≤0, or blank `transactionId` |
| 404 | `WALLET_NOT_FOUND` | no wallet with that id |
| 409 | `WALLET_NOT_ACTIVE` | wallet not `ACTIVE` |
| 422 | `INSUFFICIENT_FUNDS` | `amount` > current balance |

## Flow (file by file)

1. [WalletController.reserve](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/web/WalletController.java) — binds `ReserveRequest`.
2. [WalletService.reserveFunds](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/service/WalletService.java):
   - probes the wallet with a plain `findById` to check `highContention`.
   - **`highContention=true`**: opens a transaction, re-reads the wallet via `findByIdForUpdate` (row lock held for the duration), then `createReservation` — serializes the balance check + insert so two concurrent holds on a hot wallet can't both pass a check against balance that only covers one of them.
   - **normal wallet**: `createReservation` runs straight off the unlocked probe read — a plain read-then-insert. Documented gap: two concurrent reserves on the same low-contention wallet can both pass the balance check, because balance itself is never decremented at hold time. Accepted simplification for now; closing it needs a separate "held total" tracked per wallet.
   - `createReservation` — `requireActive`, `requireSufficientFunds` (checked against `wallet.balance`, not balance-minus-other-holds), builds a `WalletReservation` with status `HELD` and `expiresAt`, saves it.
3. [WalletReservationRepository](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/repository/WalletReservationRepository.java) — plain `JpaRepository`, just `save`.
4. [WalletReservation entity](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/domain/WalletReservation.java) — `walletId` kept as a plain string column, not a JPA `@ManyToOne`, on purpose (see its javadoc) — the service always loads the target `Wallet` explicitly under its chosen lock strategy, not implicitly through a relation.
5. `ReservationResponse.from(reservation)`.

## Features

- **Two-phase money movement**: reserve (hold, no balance change) → capture (real debit, see doc 06) or release (drop the hold, see doc 07). Useful when authorization and settlement are separate steps (e.g. checkout auth vs. later capture).
- **TTL-bound holds**: every reservation carries `expiresAt`; nothing currently sweeps expired `HELD` reservations automatically — that's a follow-up piece, not implemented in this endpoint.
- **Contention-aware locking**, same dispatch rule as debit/credit, applied here to the balance-check-then-insert sequence instead of a balance mutation.
