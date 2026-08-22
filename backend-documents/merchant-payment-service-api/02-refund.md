# Refund

`POST /api/v1/merchant-payments/{paymentId}/refund`

## Purpose

Compensating action for a completed payment (design doc §5.3's compensation path, once
conversion-orchestrator is wired to call it — see the implementation notes' What's next). Calls
the mock acquirer's refund and marks the payment `REFUNDED`.

## Request

Path param only: `paymentId` (UUID string). No body, no `Idempotency-Key` header — see Features.

## Response — `200 OK`

```json
{
  "paymentId": "7211366b-9c26-46b4-b37a-56bab65bfaf4",
  "transactionId": "txn-pay-1",
  "merchantId": "merchant-abc",
  "amount": 75.0000,
  "currency": "USD",
  "acquirerRef": "acq-c83b9eab-01bd-4681-9771-39cb3a7d1db2",
  "status": "REFUNDED",
  "createdAt": "2026-08-22T16:09:20.991866Z",
  "updatedAt": "2026-08-22T16:09:21.973130700Z"
}
```

Calling this again on the same `paymentId` returns this exact same body, `200`, no error.

## Error responses

| Status | Code | When |
|---|---|---|
| 404 | `PAYMENT_NOT_FOUND` | no payment with that id |
| 409 | `INVALID_PAYMENT_STATE` | payment was never `COMPLETED` (still `PENDING` or already `FAILED`) — nothing was actually charged, so there's nothing to refund |

## Flow (file by file)

1. [MerchantPaymentController.refund](../../backend/merchant-payment-service/src/main/java/com/paymentplatform/merchantpayment/web/MerchantPaymentController.java) — `@PathVariable paymentId`, no request DTO, no Idempotency-Key.
2. [MerchantPaymentService.refund](../../backend/merchant-payment-service/src/main/java/com/paymentplatform/merchantpayment/service/MerchantPaymentService.java):
   - loads the payment (`PaymentNotFoundException` if missing).
   - **already `REFUNDED`** → returns it as-is, no state change, still `200`.
   - **not `COMPLETED`** → rejected, `InvalidPaymentStateException`.
   - **`COMPLETED`** → calls `AcquirerGatewayClient.refund`, flips status to `REFUNDED`, saves.
3. `PaymentResponse.from(payment)`.

## Features

- **Idempotent by design** (design doc §6.4 calls this out explicitly, same as fx-rate-service's
  `releaseLock`): a saga compensation step or a retried refund call must never fail just because
  the refund already happened — calling this twice returns `200` both times, second call a pure
  no-op. This is why it doesn't need an `Idempotency-Key` — the business layer already gives a
  retry the exact same safety a key would add, so the header would be pure overhead.
- **Mock refund always succeeds** in this build (no configurable failure hook, unlike `pay`'s
  deterministic decline) — a failing refund is deferred until compensation actually calls this
  endpoint and needs its own failure path to test. See implementation notes.
