# Ledger Service — API Docs

2 endpoints, base URL `http://localhost:8085`, all under `/api/v1/ledger`. `postEntries`
requires an `Idempotency-Key` header; `getStatement` (read-only) doesn't — see
[idempotency.md](../idempotency.md).

| # | Method | Path | Doc |
|---|---|---|---|
| 1 | POST | `/api/v1/ledger/entries` | [01-post-entries.md](01-post-entries.md) |
| 2 | GET | `/api/v1/ledger/wallets/{walletId}/statement` | [02-get-statement.md](02-get-statement.md) |

## Other files here

- [ledger-service.openapi.yaml](ledger-service.openapi.yaml) — OpenAPI 3.0 spec.
- [ledger-service.postman_collection.json](ledger-service.postman_collection.json) — Postman collection v2.1. Includes both a balanced posting and one that deliberately fails the double-entry invariant.

## Typical flows

- **Record a transfer**: `POST /ledger/entries` with a matched DEBIT/CREDIT pair in the same
  currency → `201` with both lines.
- **Retry**: same call, same `Idempotency-Key` → identical array back, no second posting.
- **Correction**: never re-post the same `transactionId` (→ `409 LEDGER_CONFLICT`) — post a new,
  offsetting `transactionId` instead.
- **Audit a wallet**: `GET /ledger/wallets/{walletId}/statement` → every entry ever posted
  against that wallet, oldest first.

**Called by conversion-orchestrator** as the last real step of a conversion saga (design doc
§5.3 steps 10a/11b) — see
[../conversion-orchestrator-implementation.md](../conversion-orchestrator-implementation.md)'s
"Third pass: wiring in ledger-service" for the FX clearing-account pattern that lets a
cross-currency conversion post through this service's same-currency-only `DoubleEntryValidator`
unchanged.
