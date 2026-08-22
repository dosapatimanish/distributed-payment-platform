# Get Conversion Status

`GET /api/v1/conversions/{transactionId}`

## Purpose

Read-only poll of a conversion saga's current state — matches design doc §6.4's
"Poll SAGA status" endpoint. Since this pass runs the saga synchronously to completion within
the `POST /conversions` call itself (see [01-start-conversion.md](01-start-conversion.md)), this
endpoint mostly re-reads a terminal state today rather than catching a saga mid-flight — it
becomes more useful once the async-Kafka-driven architecture (see implementation notes'
What's next) makes sagas run over multiple separate requests/events.

## Request

Path param only: `transactionId` (UUID string). No body, no header.

## Response — `200 OK`

Same shape as [Start Conversion](01-start-conversion.md)'s response:

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

`destAmount`/`lockedRate` are `null` if the saga never got past the rate-lock step
(`sagaState: FAILED` from a rate-lock failure).

## Error responses

| Status | Code | When |
|---|---|---|
| 404 | `CONVERSION_NOT_FOUND` | no conversion with that `transactionId` |

## Flow (file by file)

1. [ConversionController.getConversion](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/web/ConversionController.java) — `@PathVariable transactionId`, no request DTO, no Idempotency-Key.
2. [ConversionService.getConversion](../../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/service/ConversionService.java) — plain `ConversionTransactionRepository.findById`, throws `ConversionNotFoundException` if absent.
3. `ConversionResponse.from(txn)`.

## Features

- **No locking, no mutation**: a plain read, same as wallet-service's `getBalance` and
  fx-rate-service's `getCurrentRate` — none of the three services' read-only endpoints need
  `Idempotency-Key` or any concurrency control.
- **`sagaState` is the authoritative outcome field**, not the HTTP status — `POST /conversions`
  always returns `201` regardless of whether the saga actually succeeded (see
  [01-start-conversion.md](01-start-conversion.md)'s Response section for why). A client
  polling this endpoint (or reading the `POST` response directly) should branch on `sagaState`,
  not on HTTP status, to know what actually happened.
