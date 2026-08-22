# Get Payment Status

`GET /api/v1/merchant-payments/{paymentId}`

## Purpose

Read-only poll of a payment's current status. Not in the design doc's REST contract table —
added for symmetry with the other three services, same "not in the table but needed" reasoning
as fx-rate-service's `consumeLock`.

## Request

Path param only: `paymentId` (UUID string). No body, no header.

## Response — `200 OK`

Same shape as [Pay](01-pay.md)'s response.

## Error responses

| Status | Code | When |
|---|---|---|
| 404 | `PAYMENT_NOT_FOUND` | no payment with that id |

## Flow (file by file)

1. [MerchantPaymentController.getPayment](../../backend/merchant-payment-service/src/main/java/com/paymentplatform/merchantpayment/web/MerchantPaymentController.java) — `@PathVariable paymentId`, no request DTO.
2. [MerchantPaymentService.getPayment](../../backend/merchant-payment-service/src/main/java/com/paymentplatform/merchantpayment/service/MerchantPaymentService.java) — plain `MerchantPaymentRepository.findById`, throws `PaymentNotFoundException` if absent.
3. `PaymentResponse.from(payment)`.

## Features

- **No locking, no mutation**: a plain read, same as the other three services' read-only
  endpoints — none of them need `Idempotency-Key` or any concurrency control.
- **`status` is the authoritative outcome field**, not the HTTP status from `pay` — see
  [01-pay.md](01-pay.md)'s Features for why a declined charge still returns `201`.
