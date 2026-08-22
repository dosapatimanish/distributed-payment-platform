# Conversion Orchestrator — API Docs

2 endpoints, base URL `http://localhost:8083`, all under `/api/v1/conversions`.
`startConversion` requires an `Idempotency-Key` header; `getConversion` (read-only) doesn't —
see [idempotency.md](../idempotency.md).

| # | Method | Path | Doc |
|---|---|---|---|
| 1 | POST | `/api/v1/conversions` | [01-start-conversion.md](01-start-conversion.md) |
| 2 | GET | `/api/v1/conversions/{transactionId}` | [02-get-conversion-status.md](02-get-conversion-status.md) |

## Other files here

- [conversion-orchestrator.openapi.yaml](conversion-orchestrator.openapi.yaml) — OpenAPI 3.0 spec.
- [conversion-orchestrator.postman_collection.json](conversion-orchestrator.postman_collection.json) — Postman collection v2.1. `Start Conversion` auto-captures `transactionId`; set `sourceWalletId`/`destWalletId` collection variables to real wallet-service wallet ids first (create/fund them via wallet-service's own collection).

## Prerequisites to actually run this

Unlike wallet-service and fx-rate-service, this service **calls the other two** - it needs both
running with real data already in place:

1. wallet-service (`:8081`) and fx-rate-service (`:8082`) both up.
2. A source wallet and a destination wallet already created via wallet-service, in the
   currencies you intend to convert between, with the source wallet funded.
3. fx-rate-service tracking that currency pair (`fx.rate.pairs` in its `application.properties`).

## Typical flows

- **Happy path**: create + fund a USD wallet, create an INR wallet (both via wallet-service) →
  `POST /conversions` with those two wallet ids → `sagaState: COMPLETED`.
- **Compensation, debit-side**: same as above but request more than the source wallet's balance
  → debit fails → `sagaState: COMPENSATED`, source wallet balance unchanged.
- **Compensation, credit-side**: request a `destWalletId` that doesn't exist → debit succeeds,
  credit fails → `sagaState: COMPENSATED`, source wallet balance restored to its original value.

See [../conversion-orchestrator-implementation.md](../conversion-orchestrator-implementation.md)
for the SAGA state machine diagram, the reduced-scope decisions (wallet-to-wallet only,
synchronous REST instead of async Kafka), and the manual end-to-end verification performed for
all three flows above.
