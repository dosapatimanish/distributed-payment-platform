# Create Wallet

`POST /api/v1/wallets`

## Purpose

Opens a new single-currency wallet for a user. One user can hold at most one wallet per
currency (`user_id` + `currency` unique constraint on the `wallet` table).

## Request

Header: `Idempotency-Key` (required) — see Features.

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

A retried call with the same `Idempotency-Key` gets this exact same body back (same `walletId`
and timestamps) — no second wallet is created.

## Error responses

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | missing `Idempotency-Key` header, missing `userId`, or `currency` not exactly 3 chars |
| 409 | `DUPLICATE_WALLET` | wallet already exists for this `(userId, currency)` |
| 409 | `IDEMPOTENCY_KEY_IN_PROGRESS` | another request with this same `Idempotency-Key` is still being processed — poll, don't resubmit |

## Flow (file by file)

1. [WalletController.createWallet](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/web/WalletController.java) — `@RequestHeader("Idempotency-Key")` (required) + `@RequestBody` bound + validated into `CreateWalletRequest`.
2. [IdempotencyGuard.runIdempotent](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/idempotency/IdempotencyGuard.java) — wraps everything below: a fresh key proceeds to step 3; a key that already completed returns its cached `WalletResponse` directly, skipping steps 3-5 entirely; a key still in flight throws `IdempotencyKeyInProgressException`.
3. [WalletService.createWallet](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/service/WalletService.java) —
   - `WalletRepository.findByUserIdAndCurrency` — pre-check for an existing wallet, throws `DuplicateWalletException` if found.
   - builds a new `Wallet` entity: server-generated `walletId` (UUID), balance `0.0000`, status `ACTIVE`.
   - `walletRepository.save(wallet)`. If two requests race past the pre-check at the same instant, the DB unique constraint rejects the second insert — caught as `DataIntegrityViolationException` and re-thrown as `DuplicateWalletException` (belt-and-braces, not just an app-level check).
4. `Wallet` entity ([domain/Wallet.java](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/domain/Wallet.java)) — `@PrePersist` stamps `createdAt`/`updatedAt`.
5. `WalletResponse.from(wallet)` — entity mapped to response DTO. On success, `IdempotencyGuard` caches this exact response before it's returned.
6. On the duplicate path: `DuplicateWalletException` → `GlobalExceptionHandler.handleConflict` → 409 `ErrorResponse`. `IdempotencyGuard` releases the key on any failure (see Features), so a retry after a genuine duplicate still hits the same duplicate check fresh, rather than replaying a cached error forever.

## Features

- **Idempotency by business key**: the `(userId, currency)` uniqueness stops accidental double-wallet creation at the data-model level, independent of retries.
- **Idempotency by `Idempotency-Key`**: a *different*, complementary guarantee — a network-retried or double-tapped call with the same key gets the exact original response replayed, rather than either creating a second wallet (impossible here, caught by the constraint above) or hitting a spurious `DUPLICATE_WALLET` error on its own retry. Only a successful creation is cached; a failed attempt (e.g. genuine duplicate) releases its key so a corrected retry isn't stuck replaying the old error. See [idempotency.md](../idempotency.md) for the full what/why/how (same mechanism on every write endpoint in both services).
- **`highContention` flag**: set `true` only for wallets expected to take very frequent concurrent writes (e.g. a platform fee pool). It changes which locking strategy every later mutation on this wallet uses — pessimistic (`SELECT ... FOR UPDATE`) instead of the default optimistic-retry. Ordinary user wallets should leave this `false`.
