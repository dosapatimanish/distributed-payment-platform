# Start Conversion

`POST /api/v1/conversions`

## Purpose

Starts and runs a currency conversion saga to completion (design doc §5.3, reduced scope — see
the implementation notes for exactly what changed and why). Always: locks a rate, debits the
source wallet, credits the destination wallet. Optionally, if `merchantId` is given: also
charges that merchant for the converted amount, spending the funds just credited to the
destination wallet to pay for it. On any failure, automatically compensates (reverses whatever
already happened) and marks the saga `COMPENSATED` instead. Requires the source and destination
wallets to already exist (via wallet-service), and (if `merchantId` is given)
merchant-payment-service to be reachable.

## Request

Header: `Idempotency-Key` (required) — see [idempotency.md](../idempotency.md).

**Wallet-to-wallet only** (no merchant):

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

**With a merchant charge** — add `merchantId`:

```json
{
  "userId": "e2e2-user",
  "sourceWalletId": "5cab8ee0-0331-4094-b057-7f74a6dc82dc",
  "destWalletId": "83aeadd4-87c9-46c2-a2a0-0f20d6415670",
  "sourceCurrency": "USD",
  "destCurrency": "INR",
  "sourceAmount": 50.00,
  "merchantId": "merchant-abc"
}
```

| Field | Type | Rule |
|---|---|---|
| `userId` | string | `@NotBlank` |
| `sourceWalletId` / `destWalletId` | string | `@NotBlank` — must be real wallet-service wallet ids |
| `sourceCurrency` / `destCurrency` | string | `@NotBlank`, exactly 3 chars |
| `sourceAmount` | decimal | `@NotNull`, `@DecimalMin("0.0001")` |
| `merchantId` | string | Optional. Absent/blank → plain wallet-to-wallet conversion. Present → also charge this merchant for the converted amount (`destAmount`, in `destCurrency`) via merchant-payment-service. Use the configured decline value (`acct-decline` by default) to trigger a deterministic decline. |

## Response — `201 Created`

Header `Location: /api/v1/conversions/{transactionId}`

**Happy path, no merchant** — destination wallet keeps the converted funds:

```json
{
  "transactionId": "0d387d4a-4083-438b-8c75-b9eb517ebb69",
  "sourceAmount": 100.0000,
  "destAmount": 8254.8571,
  "lockedRate": 82.54857115,
  "sagaState": "COMPLETED"
}
```

(fields trimmed for brevity — the real response includes every field shown in the request examples plus `createdAt`/`updatedAt`)

**Happy path, merchant charge approved** — destination wallet's balance is **unchanged** by this
call (credited, then immediately debited back out to pay the merchant):

```json
{
  "transactionId": "08b54040-12da-461b-8310-c606e59eeea0",
  "sourceAmount": 50.0000,
  "destAmount": 4128.8253,
  "lockedRate": 82.57650517,
  "sagaState": "COMPLETED"
}
```

**A failed-and-compensated attempt still returns `201`** — the transaction record was created
and fully processed either way. Check `sagaState` for the real outcome:

```json
{
  "transactionId": "a5b705eb-5a1c-4ac4-a6d1-4fbe15847a21",
  "sourceAmount": 30.0000,
  "destAmount": 2534.4976,
  "lockedRate": 84.48325467,
  "sagaState": "COMPENSATED"
}
```

A retried call with the same `Idempotency-Key` returns this exact body again unchanged, whatever
`sagaState` it ended at — the saga (merchant charge included, if any) does not run a second time.

## Error responses

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | missing `Idempotency-Key` header, missing/blank required field, currency not 3 chars, or `sourceAmount` ≤ 0 |
| 409 | `IDEMPOTENCY_KEY_IN_PROGRESS` | another request with this same key is still processing |

Note there is **no 4xx/5xx for "the conversion itself failed"** — a rate-lock failure, a
wallet-not-found, an insufficient-funds error, or a declined merchant charge at any point in the
saga all still return `201` with `sagaState` set to `FAILED` or `COMPENSATED`. This is
deliberate: those are saga *outcomes*, not request-level errors — the request to start the saga
was valid and was processed correctly, whether or not the money actually moved.

## Flow (file by file)

1. [ConversionController.startConversion](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/web/ConversionController.java) — `@RequestHeader("Idempotency-Key")` (required) + binds `ConversionRequest`.
2. [IdempotencyGuard.runIdempotent](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/idempotency/IdempotencyGuard.java) — a completed key short-circuits to the cached `ConversionResponse`, skipping everything below.
3. [ConversionService.startConversion](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/service/ConversionService.java) — creates and persists a `ConversionTransaction` (`STARTED`), then runs the saga:
   - [FxRateServiceClient.lockRate](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/client/FxRateServiceClient.java) — `POST` to fx-rate-service. Failure → `FAILED`, saga ends (nothing to compensate).
   - [WalletServiceClient.debit](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/client/WalletServiceClient.java) on the source wallet. Failure → `DEBIT_FAILED` → compensate (release the lock only).
   - `WalletServiceClient.credit` on the destination wallet, for `sourceAmount × lockedRate`. Failure → `CREDIT_FAILED` → compensate (reverse the debit, then release the lock).
   - **If `merchantId` is present**: [MerchantPaymentServiceClient.pay](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/client/MerchantPaymentServiceClient.java), then (on approval) `WalletServiceClient.debit` on the destination wallet again to actually spend the funds. Any failure here → `PAYMENT_FAILED` → compensate (reverse the credit, then the debit, then release the lock). See merchant-payment-service-api/01-pay.md for why a decline is read from the response body, not an exception.
   - `FxRateServiceClient.consumeLock` — best-effort, run only once every step above that could still trigger compensation has already succeeded (see implementation notes' "Bug 2" for why this ordering matters).
   - `SagaState.COMPLETED`.
4. Every transition goes through [SagaStateMachine.transition](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/saga/SagaStateMachine.java), which rejects anything not in its valid-transitions table, and every step's outcome is written to `saga_step_log`.
5. `ConversionResponse.from(txn)`. On success, `IdempotencyGuard` caches it; on failure to even start (e.g. a genuine bug), the key is released.

## Features

- **Real compensation, not just error reporting**: verified manually with real wallet balance
  checks before/after for every failure point — a failed credit really does reverse the debit;
  a declined merchant charge really does reverse both the credit and the debit, in the correct
  order, before releasing the lock (see implementation notes' Verification performed).
- **The merchant charge is additive, not a replacement**: omitting `merchantId` gives you back
  exactly the wallet-to-wallet behavior this endpoint always had — nothing about the base
  conversion changes when a merchant is involved, except that the destination wallet's credit
  gets spent immediately instead of kept.
- **Distinct `Idempotency-Key` per downstream call**: the orchestrator derives
  `{key}-lock`, `{key}-debit`, `{key}-credit`, `{key}-consume`, `{key}-pay`, `{key}-spend`,
  `{key}-compensate-debit`, `{key}-compensate-credit` from its own key — each is a genuinely
  separate HTTP request to a different service, needing its own safe-retry identity, not a share
  of the top-level key.
- **Each step commits immediately, not batched**: matches the SAGA principle that each local
  step commits its own transaction right away — `conversion_transaction`/`saga_step_log` always
  reflect exactly how far the saga actually got, even if you inspected them mid-flight (not
  currently observable externally, since this pass is fully synchronous within one request, but
  the persistence pattern is built for it).
