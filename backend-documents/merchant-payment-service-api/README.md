# Merchant Payment Service — API Docs

3 endpoints, base URL `http://localhost:8084`, all under `/api/v1/merchant-payments`. `pay`
requires an `Idempotency-Key` header; `refund` (already idempotent by design) and
`getPayment` (read-only) don't — see [idempotency.md](../idempotency.md).

| # | Method | Path | Doc |
|---|---|---|---|
| 1 | POST | `/api/v1/merchant-payments` | [01-pay.md](01-pay.md) |
| 2 | POST | `/api/v1/merchant-payments/{paymentId}/refund` | [02-refund.md](02-refund.md) |
| 3 | GET | `/api/v1/merchant-payments/{paymentId}` | [03-get-payment-status.md](03-get-payment-status.md) |

## Other files here

- [merchant-payment-service.openapi.yaml](merchant-payment-service.openapi.yaml) — OpenAPI 3.0 spec.
- [merchant-payment-service.postman_collection.json](merchant-payment-service.postman_collection.json) — Postman collection v2.1. `Pay (Approved)` auto-captures `paymentId`; `Pay (Declined)` uses the magic `acct-decline` merchant id to trigger a deterministic decline.

## Typical flows

- **Approved charge**: `POST /merchant-payments` with a normal `merchantId` → `status: COMPLETED`.
- **Declined charge**: same, but `merchantId: "acct-decline"` (configurable via
  `merchantpayment.acquirer.decline-merchant-id`) → `status: FAILED`, `acquirerRef: null`.
- **Compensation**: refund a completed payment → `status: REFUNDED`; refund it again → same
  response, idempotent no-op.

Standalone service for this pass — **not yet called by conversion-orchestrator**. See
[../merchant-payment-service-implementation.md](../merchant-payment-service-implementation.md)
for the mock acquirer design, the deliberate Idempotency-Key deviation from the design doc's
literal contract table, and what wiring this into the saga will need.
