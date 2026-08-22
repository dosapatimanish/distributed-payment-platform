# Pay (Charge a Merchant)

`POST /api/v1/merchant-payments`

## Purpose

Attempts to charge a merchant via the mock acquirer (design doc §6.3.4) and records the
outcome. This is the endpoint the design doc's SAGA flow diagram (§5.3) calls "charge merchant"
— once conversion-orchestrator is wired to call it (see its own implementation notes' What's
next), this is the step that comes after the source wallet debit and before the destination
credit.

## Request

Header: `Idempotency-Key` (required) — see Features.

```json
{
  "transactionId": "txn-pay-1",
  "merchantId": "merchant-abc",
  "amount": 75.00,
  "currency": "USD"
}
```

| Field | Type | Rule |
|---|---|---|
| `transactionId` | string | `@NotBlank` — the saga/business transaction this payment belongs to; UNIQUE, one payment per transaction |
| `merchantId` | string | `@NotBlank` — use the configured decline value (`acct-decline` by default) to trigger a deterministic decline, see Features |
| `amount` | decimal | `@NotNull`, `@DecimalMin("0.0001")` |
| `currency` | string | `@NotBlank`, exactly 3 chars |

## Response — `201 Created`

Header `Location: /api/v1/merchant-payments/{paymentId}`

**Approved:**

```json
{
  "paymentId": "7211366b-9c26-46b4-b37a-56bab65bfaf4",
  "transactionId": "txn-pay-1",
  "merchantId": "merchant-abc",
  "amount": 75.0000,
  "currency": "USD",
  "acquirerRef": "acq-c83b9eab-01bd-4681-9771-39cb3a7d1db2",
  "status": "COMPLETED",
  "createdAt": "2026-08-22T16:09:20.991866Z",
  "updatedAt": "2026-08-22T16:09:20.991866Z"
}
```

**Declined** (same status code, different `status` field):

```json
{
  "paymentId": "1f0b18a2-0442-4563-9f3a-42770b99e09f",
  "transactionId": "txn-pay-2",
  "merchantId": "acct-decline",
  "amount": 30.0000,
  "currency": "USD",
  "acquirerRef": null,
  "status": "FAILED",
  "createdAt": "2026-08-22T16:09:21.700418Z",
  "updatedAt": "2026-08-22T16:09:21.700418Z"
}
```

A retried call with the same `Idempotency-Key` returns this exact body again, whichever outcome
it was — the acquirer is never charged twice.

## Error responses

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | missing `Idempotency-Key` header, missing/blank field, currency not 3 chars, or `amount` ≤ 0 |
| 409 | `PAYMENT_CONFLICT` | a payment already exists for this `transactionId` (a duplicate call with a *different* `Idempotency-Key`) |
| 409 | `IDEMPOTENCY_KEY_IN_PROGRESS` | another request with this same key is still processing |

Note there is **no error status for "the acquirer declined the charge"** — that's
`status: FAILED` in a `201` body, not an HTTP error. See Features.

## Flow (file by file)

1. [MerchantPaymentController.pay](../../backend/merchant-payment-service/src/main/java/com/paymentplatform/merchantpayment/web/MerchantPaymentController.java) — `@RequestHeader("Idempotency-Key")` (required) + binds `PaymentRequest`.
2. [IdempotencyGuard.runIdempotent](../../backend/merchant-payment-service/src/main/java/com/paymentplatform/merchantpayment/idempotency/IdempotencyGuard.java) — a completed key short-circuits to the cached `PaymentResponse`, skipping everything below.
3. [MerchantPaymentService.pay](../../backend/merchant-payment-service/src/main/java/com/paymentplatform/merchantpayment/service/MerchantPaymentService.java):
   - [AcquirerGatewayClient.charge](../../backend/merchant-payment-service/src/main/java/com/paymentplatform/merchantpayment/acquirer/AcquirerGatewayClient.java) — the mock acquirer, resolves synchronously to approved or declined.
   - builds a `MerchantPayment` already in its final state (`COMPLETED` or `FAILED`) — no intermediate `PENDING` row.
   - `repository.save(payment)` — the `transaction_id` UNIQUE constraint catches a duplicate/retried request for the same transaction; caught as `DataIntegrityViolationException`, rethrown as `PaymentConflictException`.
   - publishes `payment.completed` or `payment.failed` via [MerchantPaymentEventPublisher](../../backend/merchant-payment-service/src/main/java/com/paymentplatform/merchantpayment/event/MerchantPaymentEventPublisher.java), keyed by `transactionId`.
4. `PaymentResponse.from(payment)`. On success, `IdempotencyGuard` caches it; on failure to even start (e.g. a genuine bug, not a decline), the key is released.

## Features

- **A decline is a business outcome, not a request error**: consistent with
  conversion-orchestrator's `startConversion` — the request to attempt a charge was valid and
  was processed correctly either way, so it's always `201`, with `status` carrying the real
  result. A client should branch on `status`, not HTTP status code.
- **Deterministic mock acquirer**: `merchantId` equal to the configured
  `merchantpayment.acquirer.decline-merchant-id` (default `acct-decline`) always fails; every
  other `merchantId` always succeeds. Not random — lets a declined-payment path be exercised on
  demand, same idea as fx-rate-service's simulated feed.
- **`Idempotency-Key` beyond the design doc's literal spec**: the design doc's REST contract
  table marks this endpoint's concurrency control as "Idempotent on transaction_id UNIQUE" only
  — no header. This service adds one anyway, same value-add reasoning as fx-rate-service's
  `lockRate` (see [idempotency.md](../idempotency.md)): the unique constraint alone turns a
  legitimate retry into a `409 PAYMENT_CONFLICT` error, not a replay. The header fixes that.
