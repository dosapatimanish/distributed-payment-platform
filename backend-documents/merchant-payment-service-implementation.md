# merchant-payment-service — Implementation Notes

Fourth microservice in the platform. Built standalone (design doc §6.3.4, §6.1.4, §6.4, §6.5),
same way as wallet-service and fx-rate-service were before conversion-orchestrator existed to
wire them together - not yet called by conversion-orchestrator (see What's next).

## What this step built

The Merchant Payment Service, as a standalone Spring Boot 4.1.1 (Java 25) module on port
`:8084`, backed by its own PostgreSQL database (`payment_db`):

- `POST /api/v1/merchant-payments` — charge a merchant via a mock acquirer.
- `POST /api/v1/merchant-payments/{paymentId}/refund` — compensating refund.
- `GET /api/v1/merchant-payments/{paymentId}` — poll payment status (not in the design doc's
  REST contract table, added for symmetry with the other three services - same "not in the
  table, but needed" reasoning as fx-rate-service's `consumeLock`).
- A deterministic mock acquirer (`AcquirerGatewayClient`) - approves every charge except for one
  configured "always declines" merchant id, so `payment.failed` and a declined-payment path can
  actually be exercised, not just the happy path.
- The same `Idempotency-Key` mechanism as the other three services (design doc §6.2.3) on `pay`.
- Kafka events `payment.completed` / `payment.failed` (design doc §6.5), keyed by
  `transactionId`.

### Deliberately deferred (and why)

| Deferred | Why |
|---|---|
| Wiring into conversion-orchestrator's saga | Explicit scope decision for this pass - build and verify this service standalone first (same order wallet-service/fx-rate-service were built in), extend the saga with `PAYMENT_*` states as a clean follow-up rather than growing both at once. |
| Real external acquirer integration | `AcquirerGatewayClient` is a deterministic mock (config-driven decline-by-merchant-id) - no real HTTP call, no API key/downtime handling to build against yet. |
| Resilience4j circuit breaker | Design doc §6.3.4 calls for one wrapping `AcquirerGatewayClient`. Wrapping a mock in a circuit breaker would be pure decoration - there is no real external dependency yet to actually protect against. Revisit once a real acquirer exists. |
| Configurable refund failure | The mock's `refund()` always succeeds - only the primary charge needed a deterministic failure hook to exercise `payment.failed` and (eventually) the orchestrator's compensation path; a failing refund is a rarer edge case, not built yet. |
| Transactional Outbox | Same category as the other three services' deferred Outbox. |
| ~~Testcontainers integration tests~~ | **Done** (Postgres only) — `MerchantPaymentRepositoryIntegrationTest`, see testing-guide.md's Pattern 6. Real Redis/Kafka Testcontainers still deferred. |
| ~~Flyway/Liquibase~~ | **Done** — `db/migration/V1__init.sql`, `ddl-auto=validate`. Same `spring-boot-starter-flyway` gotcha as wallet-service (see its implementation notes' gotchas section). |

## Package layout

```
com.paymentplatform.merchantpayment
├── ping/          toolchain-check endpoint
├── domain/        MerchantPayment, PaymentStatus
├── repository/    MerchantPaymentRepository
├── acquirer/      AcquirerGatewayClient (mock), AcquirerChargeResult
├── service/       MerchantPaymentService
├── event/         MerchantPaymentEventPublisher + event records
├── web/           MerchantPaymentController + request/response DTO records
├── exception/     custom exceptions + GlobalExceptionHandler
└── idempotency/   IdempotencyGuard, IdempotencyKeyInProgressException (fourth independent copy)
```

## The mock acquirer

```java
public AcquirerChargeResult charge(String merchantId, BigDecimal amount, String currency) {
    if (declineMerchantId.equals(merchantId)) {
        return AcquirerChargeResult.declined("Acquirer declined the charge for merchant " + merchantId);
    }
    return AcquirerChargeResult.approved("acq-" + UUID.randomUUID());
}
```

Deterministic, not random - `merchantpayment.acquirer.decline-merchant-id` (default
`acct-decline`) always fails, everything else always succeeds. Same design principle as
fx-rate-service's simulated rate feed: fake the external dependency in a way that's actually
*controllable* for testing/demoing both outcomes, not just the happy path. `charge()` always
resolves synchronously to `COMPLETED` or `FAILED` - there's no realistic `PENDING`-then-webhook
flow to model since nothing external is actually being called.

## Idempotency-Key — one deliberate deviation from the design doc's literal spec

See [idempotency.md](idempotency.md) for the full concept writeup. Worth calling out
specifically here: the design doc's REST contract table (§6.4) does **not** list an
`Idempotency-Key` header for `POST /merchant-payments` - it lists "Idempotent on
transaction_id UNIQUE" as that endpoint's concurrency control, unlike `POST /wallets/{id}/debit`
and `POST /conversions`, which the table explicitly marks with `(Idempotency-Key header)`.

This service adds the header anyway, for the same reason fx-rate-service's `lockRate` does
(see its own implementation notes): the `transaction_id` unique constraint alone stops a
*duplicate* payment from being created, but turns a legitimate retry into a `409
PAYMENT_CONFLICT` error rather than a clean replay of the original result. The header fixes
that gap on top of the constraint, exactly as it does for fx-rate's rate lock. This is a
deliberate improvement over the design doc's literal minimum, applied consistently with how the
same gap was already closed elsewhere - not a misreading of the spec.

`refund` doesn't get one - already idempotent at the business layer (a no-op on an
already-`REFUNDED` payment), same reasoning as fx-rate-service's `releaseLock`. `getPayment` is
read-only.

## Applying the `Persistable` lesson from the start

`MerchantPayment` has the exact same shape that caused conversion-orchestrator's `createdAt`/
`updatedAt`-comes-back-null bug: an application-assigned `paymentId`, no `@Version` field. It
implements `Persistable<String>` from the very first version of the file, not as a fix applied
after hitting the bug again - see conversion-orchestrator-implementation.md's "A real bug this
caught" section for the full story this was learned from. Manually verified here too (see
below): `createdAt`/`updatedAt` were populated correctly on the very first `curl` response,
first try.

## Automated tests

30 tests total, all passing (`./mvnw test`) — unit tests (Mockito) for business logic, plus one
Testcontainers integration test class against a real Postgres for the persistence layer.

- **`MerchantPaymentServiceTest`** (9 tests) — `MerchantPaymentRepository`,
  `AcquirerGatewayClient`, and `MerchantPaymentEventPublisher` all mocked. Covers both charge
  outcomes (approved → `COMPLETED` + `publishCompleted`; declined → `FAILED` +
  `publishFailed`), the duplicate-`transactionId` conflict path (and that it does *not*
  publish an event for a payment that was never actually persisted), and the full refund state
  machine (completed → refunded, already-refunded → idempotent no-op, never-completed → error,
  not-found → error).
- **`MerchantPaymentControllerTest`** (8 tests) — `@WebMvcTest(MerchantPaymentController.class)`,
  service and `IdempotencyGuard` both mocked with `@MockitoBean`, same passthrough-stub pattern
  as the other three services. Notably asserts a *declined* payment still returns `201` (see
  Features below).
- **`IdempotencyGuardTest`** (7 tests) — identical structure to the other three services' own.
- **`MerchantPaymentEventPublisherTest`** (3 tests) — identical structure to the other services'
  own event-publisher tests (Pattern 5 in testing-guide.md).
- **`MerchantPaymentRepositoryIntegrationTest`** (3 tests) — testing-guide.md's Pattern 6: a real
  `postgres:16-alpine` Testcontainers container, Flyway-migrated. Proves `createdAt`/`updatedAt`
  populated on `save()`'s return, `uk_merchant_payment_transaction_id` really enforced, `status`
  round-trip.

## Schema notes

- `payment_id` is an app-generated `UUID.randomUUID().toString()`, `VARCHAR(36)` - same
  portability reasoning as every other entity in this platform.
- `transaction_id` carries a `UNIQUE` constraint (design doc §6.1.4) - the actual mechanism that
  stops a duplicate charge attempt at the data layer, independent of the `Idempotency-Key` layer
  above it.
- `acquirer_ref` is nullable - a declined charge never gets one.

## How to run it locally

```bash
cd backend
docker compose up -d payment-postgres redis kafka
cd merchant-payment-service && ./mvnw spring-boot:run   # :8084
```

payment-postgres publishes on host port **5437** (5432/5433/5434/5435/5436 already taken
locally) - see `backend/docker-compose.yml`.

## Verification performed

All done manually via `curl` against real Postgres, Redis, and Kafka:

1. **Approved charge**: `POST /merchant-payments` for a normal merchant id → `201`,
   `status: COMPLETED`, `acquirerRef` populated, `createdAt`/`updatedAt` correctly populated on
   the immediate response (the `Persistable` fix, applied from the start - see above).
2. **Declined charge**: same call for `merchantId: "acct-decline"` → still `201` (see Features),
   `status: FAILED`, `acquirerRef: null`.
3. **Idempotency-Key replay**: re-sent the approved-charge request with the same key → identical
   `paymentId` back, no second payment row created.
4. **Duplicate `transactionId`, different key**: same `transactionId` as the first charge, a
   *different* `Idempotency-Key` → `409 PAYMENT_CONFLICT`.
5. **Refund**: refunded the completed payment → `status: REFUNDED`; refunded it again → same
   `REFUNDED` response, idempotent no-op, no error.
6. **Kafka events, against a real broker**: the approved charge produced a `payment.completed`
   message keyed by `txn-pay-1` with `acquirerRef` in the payload; the declined charge produced
   a `payment.failed` message keyed by `txn-pay-2` with the decline `reason`. Read back with
   `kafka-console-consumer.sh --from-beginning`.

## What's next

- ~~Wire this service into conversion-orchestrator's saga~~ — **Done.** `SagaStateMachine` now
  has `PAYMENT_COMPLETED`/`PAYMENT_FAILED` (no separate `PAYMENT_PENDING` - this service's `pay`
  is fully synchronous, so there's no "in flight" state worth persisting, unlike the design
  doc's async-oriented full table); a `MerchantPaymentServiceClient` calls this service
  alongside the existing wallet/fx-rate clients; a declined charge (or a failure debiting the
  destination wallet to actually pay for an *approved* charge) triggers full compensation,
  including calling this service's `refund` when the charge itself already succeeded for real.
  See conversion-orchestrator-implementation.md's "What this step built" (second pass) and its
  "Two real bugs this second pass caught" for the full story, including two genuine bugs the
  wiring surfaced.
- ~~Ledger Service~~ — **Done**, standalone and wired into the saga - see
  ledger-service-implementation.md and conversion-orchestrator-implementation.md's "Third pass".
  This service's own charge/refund amounts aren't separately ledgered yet (only the underlying
  currency conversion is) - see that section's "What's deliberately not captured yet".
- ~~Grafana + Prometheus observability~~ — **Done**, across all five services at once. This
  service gets the default HTTP/JVM metrics (no custom instrumentation of its own, unlike
  wallet-service/fx-rate-service/conversion-orchestrator) - see
  [observability.md](observability.md).
- Configurable refund failure in the mock acquirer — `refund` now has its first real caller
  (conversion-orchestrator's compensation path), so a deterministic refund-failure hook (same
  idea as `charge`'s decline-merchant-id) is a natural next addition to actually exercise that
  path, rather than a hypothetical one.
- ~~Testcontainers integration tests~~ — **Done** (Postgres only, see Deferred table above).
