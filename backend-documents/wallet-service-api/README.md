# Wallet Service — API Docs

7 endpoints, base URL `http://localhost:8081`, all under `/api/v1/wallets`.

| # | Method | Path | Doc |
|---|---|---|---|
| 1 | POST | `/api/v1/wallets` | [01-create-wallet.md](01-create-wallet.md) |
| 2 | GET | `/api/v1/wallets/{walletId}/balance` | [02-get-balance.md](02-get-balance.md) |
| 3 | POST | `/api/v1/wallets/{walletId}/debit` | [03-debit-wallet.md](03-debit-wallet.md) |
| 4 | POST | `/api/v1/wallets/{walletId}/credit` | [04-credit-wallet.md](04-credit-wallet.md) |
| 5 | POST | `/api/v1/wallets/{walletId}/reserve` | [05-reserve-funds.md](05-reserve-funds.md) |
| 6 | POST | `/api/v1/wallets/reservations/{reservationId}/capture` | [06-capture-reservation.md](06-capture-reservation.md) |
| 7 | POST | `/api/v1/wallets/reservations/{reservationId}/release` | [07-release-reservation.md](07-release-reservation.md) |

## Other files here

- [wallet-service.openapi.yaml](wallet-service.openapi.yaml) — OpenAPI 3.0 spec. Import into Swagger UI / Swagger Editor to browse or generate a client.
- [wallet-service.postman_collection.json](wallet-service.postman_collection.json) — Postman collection v2.1. Import directly; `Create Wallet` auto-captures `walletId` into a collection variable, `Reserve Funds` auto-captures `reservationId`, so the rest of the requests chain off them without manual copy-paste.

## Typical flows

- **Direct debit**: 1 (create) → 4 (credit, to fund it) → 3 (debit).
- **Two-step hold**: 1 → 4 → 5 (reserve) → 6 (capture) *or* 7 (release).

See [../wallet-service-implementation.md](../wallet-service-implementation.md) for the concurrency-control design behind debit/credit/reserve (optimistic retry vs. pessimistic lock, `highContention`).
