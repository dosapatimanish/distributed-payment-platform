# Create Wallet

`POST /api/v1/wallets`

## Purpose

Opens a new single-currency wallet for a user. One user can hold at most one wallet per
currency (`user_id` + `currency` unique constraint on the `wallet` table).

## Request

```json
{
  "userId": "user-123",
  "currency": "USD",
  "highContention": false
}
```

| Field | Type | Rule |
|---|---|---|
| `userId` | string | `@NotBlank` |
| `currency` | string | `@NotBlank`, exactly 3 chars (ISO 4217) |
| `highContention` | boolean | optional, default `false` — see Features |

## Response — `201 Created`

Header `Location: /api/v1/wallets/{walletId}`

```json
{
  "walletId": "b3b3c1b0-1e2a-4b3a-9c1a-0f7e2b6a1234",
  "userId": "user-123",
  "currency": "USD",
  "balance": 0.0000,
  "status": "ACTIVE",
  "highContention": false,
  "createdAt": "2026-08-22T10:00:00Z",
  "updatedAt": "2026-08-22T10:00:00Z"
}
```

## Error responses

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | missing `userId`, or `currency` not exactly 3 chars |
| 409 | `DUPLICATE_WALLET` | wallet already exists for this `(userId, currency)` |

## Flow (file by file)

1. [WalletController.createWallet](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/web/WalletController.java) — `@RequestBody` bound + validated into `CreateWalletRequest`.
2. [WalletService.createWallet](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/service/WalletService.java) —
   - `WalletRepository.findByUserIdAndCurrency` — pre-check for an existing wallet, throws `DuplicateWalletException` if found.
   - builds a new `Wallet` entity: server-generated `walletId` (UUID), balance `0.0000`, status `ACTIVE`.
   - `walletRepository.save(wallet)`. If two requests race past the pre-check at the same instant, the DB unique constraint rejects the second insert — caught as `DataIntegrityViolationException` and re-thrown as `DuplicateWalletException` (belt-and-braces, not just an app-level check).
3. `Wallet` entity ([domain/Wallet.java](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/domain/Wallet.java)) — `@PrePersist` stamps `createdAt`/`updatedAt`.
4. `WalletResponse.from(wallet)` — entity mapped to response DTO for the JSON body.
5. On the duplicate path: `DuplicateWalletException` → `GlobalExceptionHandler.handleConflict` → 409 `ErrorResponse`.

## Features

- **Idempotency by business key**: the `(userId, currency)` uniqueness stops accidental double-wallet creation — not the same as an `Idempotency-Key` header (not implemented yet, see implementation notes).
- **`highContention` flag**: set `true` only for wallets expected to take very frequent concurrent writes (e.g. a platform fee pool). It changes which locking strategy every later mutation on this wallet uses — pessimistic (`SELECT ... FOR UPDATE`) instead of the default optimistic-retry. Ordinary user wallets should leave this `false`.
