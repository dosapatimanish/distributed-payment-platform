# Start Conversion

`POST /api/v1/conversions`

## Purpose

Starts and runs a wallet-to-wallet currency conversion SAGA to completion (design doc §5.3,
reduced scope - see the implementation notes for exactly what changed and why). Locks a rate,
debits the source wallet, credits the destination wallet, marks the rate lock consumed - or, on
any failure, automatically compensates (reverses whatever already happened) and marks the saga
`COMPENSATED` instead. Requires both wallets to already exist (via wallet-service).

## Request

Header: `Idempotency-Key` (required) — see [idempotency.md](../idempotency.md).

```json
{
  "userId": "e2e-user",
  "sourceWalletId": "d7063dc8-ac68-489e-bc57-9129acbf80e2",
  "destWalletId": "21a05d48-914d-4b1c-af6b-fbc63b74d01f",
  "sourceCurrency": "USD",
  "destCurrency": "INR",
  "sourceAmount": 100.00
}
```

| Field | Type | Rule |
|---|---|---|
| `userId` | string | `@NotBlank` |
| `sourceWalletId` / `destWalletId` | string | `@NotBlank` — must be real wallet-service wallet ids |
| `sourceCurrency` / `destCurrency` | string | `@NotBlank`, exactly 3 chars |
| `sourceAmount` | decimal | `@NotNull`, `@DecimalMin("0.0001")` |

## Response — `201 Created`

Header `Location: /api/v1/conversions/{transactionId}`

**Happy path:**

```json
{
  "transactionId": "0d387d4a-4083-438b-8c75-b9eb517ebb69",
  "userId": "e2e-user",
  "sourceWalletId": "d7063dc8-ac68-489e-bc57-9129acbf80e2",
  "destWalletId": "21a05d48-914d-4b1c-af6b-fbc63b74d01f",
  "sourceCurrency": "USD",
  "destCurrency": "INR",
  "sourceAmount": 100.0000,
  "destAmount": 8254.8571,
  "lockedRate": 82.54857115,
  "sagaState": "COMPLETED",
  "createdAt": "2026-08-22T15:23:46.758683Z",
  "updatedAt": "2026-08-22T15:23:47.756951Z"
}
```

**A failed-and-compensated attempt still returns `201`** — the transaction record was created
and fully processed either way. Check `sagaState` for the real outcome:

```json
{
  "transactionId": "fb67bc6d-100a-4fc9-ba9d-e0fbd696c362",
  "sourceAmount": 999999.0000,
  "destAmount": 81336051.2439,
  "lockedRate": 81.33613258,
  "sagaState": "COMPENSATED"
}
```

(fields trimmed above for brevity — the real response includes every field from the happy-path example)

A retried call with the same `Idempotency-Key` returns this exact body again unchanged, whatever
`sagaState` it ended at — the saga does not run a second time.

## Error responses

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | missing `Idempotency-Key` header, missing/blank required field, currency not 3 chars, or `sourceAmount` ≤ 0 |
| 409 | `IDEMPOTENCY_KEY_IN_PROGRESS` | another request with this same key is still processing |

Note there is **no 4xx/5xx for "the conversion itself failed"** — a rate-lock failure, a
wallet-not-found, an insufficient-funds error partway through the saga all still return `201`
with `sagaState` set to `FAILED` or `COMPENSATED`. This is deliberate: those are saga *outcomes*,
not request-level errors — the request to start the saga was valid and was processed correctly,
whether or not the money actually moved.

## Flow (file by file)

1. [ConversionController.startConversion](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/web/ConversionController.java) — `@RequestHeader("Idempotency-Key")` (required) + binds `ConversionRequest`.
2. [IdempotencyGuard.runIdempotent](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/idempotency/IdempotencyGuard.java) — a completed key short-circuits to the cached `ConversionResponse`, skipping everything below.
3. [ConversionService.startConversion](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/service/ConversionService.java) — creates and persists a `ConversionTransaction` (`STARTED`), then runs the saga:
   - [FxRateServiceClient.lockRate](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/client/FxRateServiceClient.java) — `POST` to fx-rate-service. Failure → `FAILED`, saga ends (nothing to compensate).
   - [WalletServiceClient.debit](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/client/WalletServiceClient.java) on the source wallet. Failure → `DEBIT_FAILED` → compensate (release the lock only).
   - `WalletServiceClient.credit` on the destination wallet, for `sourceAmount × lockedRate`. Failure → `CREDIT_FAILED` → compensate (reverse the debit, then release the lock).
   - `FxRateServiceClient.consumeLock` — best-effort; failure here does not change the saga's outcome (see [02-get-conversion-status.md](02-get-conversion-status.md)'s note on `lockedRate`).
   - `SagaState.COMPLETED`.
4. Every transition goes through [SagaStateMachine.transition](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/saga/SagaStateMachine.java), which rejects anything not in its valid-transitions table, and every step's outcome is written to `saga_step_log`.
5. `ConversionResponse.from(txn)`. On success, `IdempotencyGuard` caches it; on failure to even start (e.g. a genuine bug), the key is released.

## Features

- **Real compensation, not just error reporting**: a failure after the debit already succeeded
  actually reverses it (a compensating credit back to the source wallet) before releasing the
  rate lock — verified manually with a real wallet balance check before/after (see
  implementation notes).
- **Distinct `Idempotency-Key` per downstream call**: the orchestrator derives
  `{key}-lock`, `{key}-debit`, `{key}-credit`, `{key}-consume`, `{key}-compensate-debit` from its
  own key — each is a genuinely separate HTTP request to a different service, needing its own
  safe-retry identity, not a share of the top-level key.
- **Each step commits immediately, not batched**: matches the SAGA principle that each local
  step commits its own transaction right away — `conversion_transaction`/`saga_step_log` always
  reflect exactly how far the saga actually got, even if you inspected them mid-flight (not
  currently observable externally, since this pass is fully synchronous within one request, but
  the persistence pattern is built for it).
