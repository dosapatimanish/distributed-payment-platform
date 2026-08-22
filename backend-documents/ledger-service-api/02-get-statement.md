# Get Statement (Wallet's Ledger History)

`GET /api/v1/ledger/wallets/{walletId}/statement`

## Purpose

Returns every ledger entry recorded against one wallet, oldest first — the append-only audit
trail design doc §6.3.5's `getStatement` describes. Read-only, no lock, no `Idempotency-Key`.

## Response — `200 OK`

```json
{
  "walletId": "wallet-A",
  "entries": [
    {
      "entryId": "0e503591-6118-4b80-a382-1307bb8620af",
      "transactionId": "txn-live-1",
      "walletId": "wallet-A",
      "entryType": "DEBIT",
      "amount": 100.0000,
      "currency": "USD",
      "balanceAfter": 400.0000,
      "createdAt": "2026-08-22T18:14:52.087787Z"
    }
  ]
}
```

A wallet with no entries yet returns `200` with an empty `entries` array — this service doesn't
own wallet identity (wallet-service does), so it has no basis to tell "no entries because
nothing happened yet" apart from "no entries because the walletId doesn't exist"; both look the
same here, and that's fine, this is a ledger read, not a wallet lookup.

## Flow (file by file)

1. [LedgerController.getStatement](../../backend/ledger-service/src/main/java/com/paymentplatform/ledger/web/LedgerController.java) — binds `walletId` path variable.
2. [LedgerService.getStatement](../../backend/ledger-service/src/main/java/com/paymentplatform/ledger/service/LedgerService.java) → `LedgerEntryRepository.findByWalletIdOrderByCreatedAtAsc`.
3. Maps each `LedgerEntry` to a `LedgerEntryResponse`, wrapped in a `StatementResponse`.

## Features

- **No pagination**: every entry for the wallet is returned in one response. Fine for this
  project's scale; a real high-volume wallet would need cursor/offset pagination here — not
  built, tracked as a known gap rather than a silent omission.
