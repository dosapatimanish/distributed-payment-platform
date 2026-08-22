# Post Entries (Record a Double-Entry Posting)

`POST /api/v1/ledger/entries`

## Purpose

Writes one double-entry posting — every ledger line for one `transactionId`, in a single
atomic write (design doc §6.3.5 `recordDoubleEntry`). This is the endpoint the design doc's
SAGA flow diagram (§5.3) calls "record double-entry ledger" / "record REVERSED ledger entry" —
once conversion-orchestrator is wired to call it (see this service's implementation notes'
What's next), this is the step that runs after a conversion completes or is compensated.

## Request

Header: `Idempotency-Key` (required) — see Features.

```json
{
  "transactionId": "txn-live-1",
  "entries": [
    { "walletId": "wallet-A", "entryType": "DEBIT", "amount": 100.00, "currency": "USD", "balanceAfter": 400.00 },
    { "walletId": "wallet-B", "entryType": "CREDIT", "amount": 100.00, "currency": "USD", "balanceAfter": 600.00 }
  ]
}
```

| Field | Type | Rule |
|---|---|---|
| `transactionId` | string | `@NotBlank` — the saga/business transaction this posting belongs to; entries are append-only, one posting per `transactionId` (see Features) |
| `entries` | array | `@NotEmpty` — every line of this posting |
| `entries[].walletId` | string | `@NotBlank` |
| `entries[].entryType` | string | `@NotNull` — `DEBIT` or `CREDIT` |
| `entries[].amount` | decimal | `@NotNull`, `@DecimalMin("0.0001")` |
| `entries[].currency` | string | `@NotBlank`, exactly 3 chars |
| `entries[].balanceAfter` | decimal | `@NotNull` — the wallet's balance immediately after this leg, as supplied by the caller (this service doesn't own wallet balances, wallet-service does) |

## Response — `201 Created`

```json
[
  { "entryId": "0e503591-6118-4b80-a382-1307bb8620af", "transactionId": "txn-live-1", "walletId": "wallet-A", "entryType": "DEBIT", "amount": 100.00, "currency": "USD", "balanceAfter": 400.00, "createdAt": "2026-08-22T18:14:52.087787Z" },
  { "entryId": "61907c3c-828d-409a-811f-b4b07d3ac078", "transactionId": "txn-live-1", "walletId": "wallet-B", "entryType": "CREDIT", "amount": 100.00, "currency": "USD", "balanceAfter": 600.00, "createdAt": "2026-08-22T18:14:52.095301800Z" }
]
```

A retried call with the same `Idempotency-Key` returns this exact array again — no second
posting is written.

## Error responses

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | missing `Idempotency-Key` header, missing/blank field, empty `entries`, currency not 3 chars, or an amount ≤ 0 |
| 400 | `INVALID_LEDGER_ENTRIES` | fails the double-entry invariant — see Features |
| 409 | `LEDGER_CONFLICT` | entries already exist for this `transactionId` (a duplicate call with a *different* `Idempotency-Key`) — see Features |
| 409 | `IDEMPOTENCY_KEY_IN_PROGRESS` | another request with this same key is still processing |

## Flow (file by file)

1. [LedgerController.postEntries](../../backend/ledger-service/src/main/java/com/paymentplatform/ledger/web/LedgerController.java) — `@RequestHeader("Idempotency-Key")` (required) + binds `PostEntriesRequest`.
2. [IdempotencyGuard.runIdempotent](../../backend/ledger-service/src/main/java/com/paymentplatform/ledger/idempotency/IdempotencyGuard.java) — a completed key short-circuits to the cached response array, skipping everything below.
3. [LedgerService.postEntries](../../backend/ledger-service/src/main/java/com/paymentplatform/ledger/service/LedgerService.java):
   - `repository.existsByTransactionId(...)` — a posting already recorded for this transaction throws `LedgerConflictException` before validation even runs.
   - [DoubleEntryValidator.validate](../../backend/ledger-service/src/main/java/com/paymentplatform/ledger/service/DoubleEntryValidator.java) — enforces the double-entry invariant (see Features).
   - saves every line as a `LedgerEntry`, in one `@Transactional` method — all lines land, or none do.
4. `LedgerEntryResponse.from(...)` per line. On success, `IdempotencyGuard` caches the whole array; on failure the key is released.

## Features

- **Append-only, one posting per transaction**: ledger rows are never updated or deleted at the
  application layer (design doc §6.1.5). A correction is always a *new*, offsetting posting
  under a different `transactionId` (e.g. a `-reversal` suffix), never a second call against the
  original one — that's what `LEDGER_CONFLICT` guards.
- **Double-entry invariant**: `DoubleEntryValidator` groups lines by currency and requires each
  currency group's `DEBIT` total to equal its `CREDIT` total — exactly right for a same-currency
  wallet-to-wallet posting. A cross-currency FX-conversion posting (source-currency debit vs
  destination-currency credit) can't net that way by amount alone; conversion-orchestrator
  resolves this on the *caller* side with a synthetic FX clearing account (each real leg paired
  with a same-currency clearing leg), not by changing this validator — see
  conversion-orchestrator-implementation.md's "Third pass: wiring in ledger-service".
- **`Idempotency-Key`**: same reasoning as every other write endpoint in this platform (see
  [idempotency.md](../idempotency.md)) — a retried posting (e.g. the orchestrator retrying after
  a timeout) must replay the original result, not attempt a second posting and hit
  `LEDGER_CONFLICT`.
