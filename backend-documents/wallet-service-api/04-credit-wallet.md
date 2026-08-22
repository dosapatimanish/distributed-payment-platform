# Credit Wallet

`POST /api/v1/wallets/{walletId}/credit`

## Purpose

Adds `amount` to a wallet's balance. Mirror of debit, but with a looser status rule (see below)
and no funds check.

## Request

Header: `Idempotency-Key` (required) — see [01-create-wallet.md](01-create-wallet.md)'s Features.

```json
{
  "amount": 100.00,
  "transactionId": "txn-002"
}
```

| Field | Type | Rule |
|---|---|---|
| `amount` | decimal | `@NotNull`, `@DecimalMin("0.0001")` |
| `transactionId` | string | `@NotBlank` |

## Response — `200 OK`

```json
{
  "walletId": "b3b3c1b0-1e2a-4b3a-9c1a-0f7e2b6a1234",
  "userId": "user-123",
  "currency": "USD",
  "balance": 100.0000,
  "status": "ACTIVE",
  "highContention": false,
  "createdAt": "2026-08-22T10:00:00Z",
  "updatedAt": "2026-08-22T10:02:00Z"
}
```

## Error responses

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | missing `Idempotency-Key` header, `amount` missing/≤0, or blank `transactionId` |
| 404 | `WALLET_NOT_FOUND` | no wallet with that id |
| 409 | `WALLET_NOT_ACTIVE` | wallet is `CLOSED` (note: `FROZEN` wallets **can** still be credited) |
| 409 | `WALLET_CONFLICT` / `WALLET_LOCK_TIMEOUT` | same concurrency failure modes as debit |
| 409 | `IDEMPOTENCY_KEY_IN_PROGRESS` | another request with this same key is still processing |

## Flow (file by file)

1. [WalletController.credit](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/web/WalletController.java) — `@RequestHeader("Idempotency-Key")` (required) + binds `CreditRequest`.
2. [IdempotencyGuard.runIdempotent](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/idempotency/IdempotencyGuard.java) — a completed key short-circuits to the cached response, skipping every step below.
3. [WalletService.credit](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/service/WalletService.java) → same `applyMutation` dispatch as debit (optimistic-retry vs pessimistic-lock, by `highContention`), running `creditMutation`:
   - `requireNotClosed` — only blocks `CLOSED` wallets. This is the one behavioral difference from debit's `requireActive`, which blocks both `FROZEN` and `CLOSED`.
   - `balance = balance + amount`.
4. [WalletRepository](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/repository/WalletRepository.java) / [Wallet entity](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/domain/Wallet.java) — same as debit.
5. `WalletResponse.from(wallet)`. On success, `IdempotencyGuard` caches it; on failure, the key is released (see [03-debit-wallet.md](03-debit-wallet.md)'s Flow).

## Features

- **Asymmetric status rule vs debit**: `FROZEN` (e.g. a compliance hold) still allows incoming credit but blocks outgoing debit — see [WalletStatus](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/domain/WalletStatus.java) javadoc. `debit` calls `requireActive` (only `ACTIVE` passes); `credit` calls `requireNotClosed` (anything but `CLOSED` passes).
- Same money-precision, concurrency-control, and `Idempotency-Key` behavior as debit (see [03-debit-wallet.md](03-debit-wallet.md)).
