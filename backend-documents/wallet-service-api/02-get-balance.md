# Get Balance

`GET /api/v1/wallets/{walletId}/balance`

## Purpose

Read-only lookup of a wallet's current balance and status. Lightest endpoint in the service —
no locking, no mutation, single indexed `findById`.

## Request

Path param only: `walletId` (UUID string).

## Response — `200 OK`

```json
{
  "walletId": "b3b3c1b0-1e2a-4b3a-9c1a-0f7e2b6a1234",
  "currency": "USD",
  "balance": 100.0000,
  "status": "ACTIVE"
}
```

Note this is a slimmer shape than `WalletResponse` — no `userId`, `highContention`, `createdAt`/`updatedAt`. `BalanceResponse` is a separate DTO purpose-built for this endpoint (see Features).

## Error responses

| Status | Code | When |
|---|---|---|
| 404 | `WALLET_NOT_FOUND` | no wallet with that id |

## Flow (file by file)

1. [WalletController.getBalance](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/web/WalletController.java) — `@PathVariable walletId`.
2. [WalletService.getBalance](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/service/WalletService.java) — plain `walletRepository.findById(walletId)`, no lock, no transaction wrapper. Throws `WalletNotFoundException` if absent.
3. [WalletRepository](../../backend/wallet-service/src/main/java/com/paymentplatform/wallet/repository/WalletRepository.java) — inherited `findById` from `JpaRepository`, nothing custom here.
4. `BalanceResponse.from(wallet)` — maps entity to the trimmed DTO.
5. On miss: `WalletNotFoundException` → `GlobalExceptionHandler.handleWalletNotFound` → 404.

## Features

- **Trimmed response shape**: a dedicated `BalanceResponse` DTO instead of reusing `WalletResponse` — a balance check only needs 4 fields, no reason to ship `createdAt`/`updatedAt`/`highContention` on a call that may be polled often.
- **No locking**: this is a plain read, so neither concurrency-control path (optimistic version check, pessimistic `SELECT ... FOR UPDATE`) applies — those only guard mutations (debit/credit/reserve).
