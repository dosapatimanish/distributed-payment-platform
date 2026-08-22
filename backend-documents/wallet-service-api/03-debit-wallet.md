# Debit Wallet

`POST /api/v1/wallets/{walletId}/debit`

## Purpose

Immediately removes `amount` from a wallet's balance — no hold step first. Used for direct
debits; the reserve → capture flow (see docs 05/06) is the two-step alternative when a hold
needs to exist before the money actually moves.

## Request

Header: `Idempotency-Key` (required) — a retry with the same key replays the original response instead of debiting twice. See [01-create-wallet.md](01-create-wallet.md)'s Features for the full mechanics.

```json
{
  "amount": 50.00,
  "transactionId": "txn-001"
}
```

| Field | Type | Rule |
|---|---|---|
| `amount` | decimal | `@NotNull`, `@DecimalMin("0.0001")` — must be positive |
| `transactionId` | string | `@NotBlank` — caller's business transaction id, used only for logging here (not yet persisted against the debit — see Features) |

## Response — `200 OK`

```json
{
  "walletId": "b3b3c1b0-1e2a-4b3a-9c1a-0f7e2b6a1234",
  "userId": "user-123",
  "currency": "USD",
  "balance": 50.0000,
  "status": "ACTIVE",
  "highContention": false,
  "createdAt": "2026-08-22T10:00:00Z",
  "updatedAt": "2026-08-22T10:05:00Z"
}
```

## Error responses

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | missing `Idempotency-Key` header, `amount` missing/≤0, or blank `transactionId` |
| 404 | `WALLET_NOT_FOUND` | no wallet with that id |
| 409 | `WALLET_NOT_ACTIVE` | wallet is `FROZEN` or `CLOSED` |
| 409 | `WALLET_CONFLICT` | optimistic-lock retries exhausted (5 attempts) under heavy concurrent writes |
| 409 | `WALLET_LOCK_TIMEOUT` | pessimistic lock wait exceeded 3000ms (high-contention wallets only) |
| 409 | `IDEMPOTENCY_KEY_IN_PROGRESS` | another request with this same key is still processing |
| 422 | `INSUFFICIENT_FUNDS` | `amount` > current balance |

## Flow (file by file)

1. [WalletController.debit](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/web/WalletController.java) — `@RequestHeader("Idempotency-Key")` (required) + binds `DebitRequest`.
2. [IdempotencyGuard.runIdempotent](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/idempotency/IdempotencyGuard.java) — a completed key short-circuits straight to the cached `WalletResponse`, skipping every step below; a fresh key proceeds.
3. [WalletService.debit](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/service/WalletService.java) → `applyMutation(walletId, this::debitMutation)`:
   - `applyMutation` first does a plain `findById` to read `wallet.highContention`, then dispatches:
     - **normal wallet** → `applyWithOptimisticRetry` — loads wallet, applies `debitMutation`, `save()`; on `ObjectOptimisticLockingFailureException` (another writer won the `@Version` race) retries up to 5x with linear backoff (20/40/60/80ms), then gives up as `WalletConflictException`.
     - **`highContention=true` wallet** → `applyWithPessimisticLock` — `WalletRepository.findByIdForUpdate` takes a DB row lock (`SELECT ... FOR UPDATE`, 3s timeout), applies `debitMutation`, `save()`. A lock-wait timeout surfaces as `PessimisticLockingFailureException`.
   - `debitMutation` — `requireActive` (must be `ACTIVE`, else `WalletNotActiveException`), `requireSufficientFunds` (else `InsufficientFundsException`), then `balance = balance - amount`.
4. [WalletRepository](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/repository/WalletRepository.java) — `findById` (CRUD) or `findByIdForUpdate` (custom locking query), `save`.
5. `WalletResponse.from(wallet)` — updated entity mapped to response. On success, `IdempotencyGuard` caches this response before returning it.
6. Failure paths all land in [GlobalExceptionHandler](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/exception/GlobalExceptionHandler.java), each mapped to a distinct `code` in `ErrorResponse`. `IdempotencyGuard` releases the key on any failure, so a retry with the same key gets a fresh attempt rather than replaying the error.

## Features

- **Two concurrency strategies, chosen per-wallet**: optimistic-with-retry is the default (cheap, no lock held between read and write); pessimistic is opt-in via `highContention` for wallets where retries would themselves become the bottleneck (see design doc §6.2.1, and `WalletService` class javadoc).
- **Money precision**: `amount` and `balance` are `BigDecimal`, scaled to 4 decimal places (`MONEY_SCALE`) with `HALF_UP` rounding on every write — avoids floating-point drift on financial values.
- **`transactionId` is currently log-only**: it's not yet written to a ledger/audit table against this debit. Worth knowing if you're relying on it for traceability today — flagged in the implementation notes as a deferred piece (Kafka events / outbox), not an oversight.
- **`Idempotency-Key` vs `transactionId`**: two different ids for two different jobs. `transactionId` names the business operation (for logs/future audit); `Idempotency-Key` names *this specific HTTP attempt* at it, and is what actually prevents a double debit on retry — see [01-create-wallet.md](01-create-wallet.md)'s Features.
