# ledger-service — Implementation Notes

Fifth and last core microservice in the platform (design doc §6.1.5, §6.3.5, §6.4, §6.5). Built
standalone, same way as the previous four were before conversion-orchestrator existed to wire
them together — not yet called by conversion-orchestrator (see What's next).

## What this step built

The Ledger Service, as a standalone Spring Boot 4.1.1 (Java 25) module on port `:8085`, backed
by its own `ledger_app` schema in the one shared Oracle Database Free 23ai instance
(`platform-oracle`, host port `1521`, `paymentdb` PDB; originally PostgreSQL, migrated — see
[oracle-migration.md](oracle-migration.md)):

- `POST /api/v1/ledger/entries` — record one double-entry posting (every line for a
  `transactionId`, written atomically).
- `GET /api/v1/ledger/wallets/{walletId}/statement` — a wallet's full ledger history, oldest
  first.
- `DoubleEntryValidator` — enforces the design doc's "entries for a transaction net to zero"
  invariant, currently scoped to same-currency postings (see below).
- Append-only enforcement: a second posting attempt against a `transactionId` that already has
  entries is rejected (`LedgerConflictException`) — a correction must be a new, offsetting
  posting, never a mutation of the original.
- The same `Idempotency-Key` mechanism as the other four services (design doc §6.2.3) on
  `postEntries`.

### Deliberately deferred (and why)

| Deferred | Why |
|---|---|
| ~~Wiring into conversion-orchestrator's saga~~ | **Done.** `LedgerServiceClient` calls `postEntries` after `COMPLETED` and (with a `-reversal`-suffixed transaction id) during compensation — see conversion-orchestrator-implementation.md's "Third pass: wiring in ledger-service". |
| ~~Cross-currency double-entry netting~~ | **Done.** Resolved with a synthetic FX clearing account, not a validator change — see conversion-orchestrator-implementation.md's "Third pass" section for the full reasoning. `DoubleEntryValidator` itself needed no code changes at all. |
| Kafka events | The design doc's topic table (§6.5) has no ledger-originated topic — this service is the sink other services' events eventually feed (indirectly, via the orchestrator calling it), not a publisher itself. No `spring-kafka` dependency pulled in at all for this service. |
| Transactional Outbox | Same category as the other four services' deferred Outbox — moot here anyway since there's no event to publish (see above). |
| ~~Testcontainers integration tests~~ | **Done** (Oracle, `gvenzl/oracle-free:23-slim`) — `LedgerEntryRepositoryIntegrationTest`, see testing-guide.md's Pattern 6; the direct regression test for Bug 3's `VARCHAR2(64)` column width. Real Redis/Kafka Testcontainers still deferred (this service has no Kafka producer anyway). |
| ~~Flyway/Liquibase~~ | **Done** — `db/migration/V1__init.sql` (Oracle DDL since the migration), `ddl-auto=validate`. Same `spring-boot-starter-flyway` gotcha as wallet-service (see its implementation notes' gotchas section); per-database module is `flyway-database-oracle` now. |
| ~~Oracle~~ | **Done** — Postgres → Oracle Database Free 23ai, platform-wide. See [oracle-migration.md](oracle-migration.md). |
| Pagination on `getStatement` | Every entry for a wallet is returned in one response — fine at this project's scale, would need cursor/offset pagination for a real high-volume wallet. |

## Package layout

```
com.paymentplatform.ledger
├── ping/          toolchain-check endpoint
├── domain/        LedgerEntry, EntryType
├── repository/    LedgerEntryRepository
├── service/       LedgerService, DoubleEntryValidator
├── web/           LedgerController + request/response DTO records
├── exception/     custom exceptions + GlobalExceptionHandler
└── idempotency/   IdempotencyGuard, IdempotencyKeyInProgressException (fifth independent copy)
```

## Double-entry validator — current scope (resolved without changing this class)

Design doc §6.1.5: "The service enforces that entries for a given transaction_id always net to
zero across the involved wallets — the double-entry invariant." Netting only makes sense within
one unit of account, so `DoubleEntryValidator` groups a posting's lines by `currency` and
requires each currency group's DEBIT total to equal its CREDIT total:

```java
for (Map.Entry<String, List<LedgerLineRequest>> group : byCurrency.entrySet()) {
    BigDecimal debitTotal = sum(group.getValue(), EntryType.DEBIT);
    BigDecimal creditTotal = sum(group.getValue(), EntryType.CREDIT);
    if (debitTotal.compareTo(creditTotal) != 0) {
        throw new InvalidLedgerEntriesException(...);
    }
}
```

This is exactly right for a same-currency wallet-to-wallet posting (a debit leg and a credit leg
in the same currency, e.g. a merchant settlement or a plain transfer). It is **not** yet correct
for an FX-conversion posting: the source wallet's debit is in the source currency, the
destination wallet's credit is in the destination currency, and two different currencies can
never net against each other by amount alone. Posting both legs of a real conversion through this validator unchanged would have thrown
`INVALID_LEDGER_ENTRIES` for both currency groups (each has only one leg).

**Resolved** when conversion-orchestrator wired this service in: rather than relaxing the
invariant, the caller now always posts through a synthetic **FX clearing account**
(`SYSTEM-FX-CLEARING` by default) — the source-currency leg pairs with a clearing leg in the
source currency, the destination-currency leg pairs with a clearing leg in the destination
currency, so every currency group nets to zero on its own. Standard double-entry technique for a
cross-currency movement. This class needed **zero code changes** — the fix lives entirely in
`ConversionService.recordLedgerEntries`/`recordLedgerReversal` on the caller's side. Full
reasoning and the exact posting shapes: conversion-orchestrator-implementation.md's "Third pass:
wiring in ledger-service".

## Applying the `Persistable` lesson from the start

`LedgerEntry` has the same shape that originally caused conversion-orchestrator's `createdAt`/
`updatedAt`-comes-back-null bug: an application-assigned `entryId`, no `@Version` field. It
implements `Persistable<String>` from the very first version of the file — see
conversion-orchestrator-implementation.md's "A real bug this caught" section for the story this
was learned from. Manually verified here too (see below): `createdAt` was populated correctly
on the very first `curl` response, first try.

## Automated tests

27 tests total, all passing (`./mvnw test`) — unit tests (Mockito) for business logic, plus one
Testcontainers integration test class against a real Oracle for the persistence layer.

- **`DoubleEntryValidatorTest`** (6 tests) — pure unit tests, no mocks needed (same category as
  `SagaStateMachineTest` in testing-guide.md). Covers a balanced same-currency pair, an empty
  posting, a DEBIT-only posting, a mismatched-amount posting, three legs netting correctly
  within one currency, and the documented cross-currency limitation (two single-leg currency
  groups, each fails its own check).
- **`LedgerServiceTest`** (4 tests) — `LedgerEntryRepository` mocked, real `DoubleEntryValidator`
  wired in (not mocked, since it's pure logic worth exercising for real). Covers a successful
  balanced posting, the `LedgerConflictException` short-circuit (asserts validation and save are
  never reached once a duplicate `transactionId` is detected), an unbalanced posting never
  reaching `save`, and `getStatement` delegating straight to the repository.
- **`LedgerControllerTest`** (7 tests) — `@WebMvcTest(LedgerController.class)`, service and
  `IdempotencyGuard` both mocked with `@MockitoBean`, same passthrough-stub pattern as the other
  four services. Covers the happy path, missing header, empty `entries` (bean validation),
  `INVALID_LEDGER_ENTRIES`, `LEDGER_CONFLICT`, and both a populated and an empty statement.
- **`IdempotencyGuardTest`** (7 tests) — identical structure to the other four services' own.
- **`LedgerEntryRepositoryIntegrationTest`** (3 tests) — testing-guide.md's Pattern 6: a real
  `gvenzl/oracle-free:23-slim` Testcontainers container, Flyway-migrated. The direct regression
  test for Bug 3's `VARCHAR2(64)` column width - saves an entry under a real 45-char
  reversal-style `transactionId` and confirms it round-trips intact; also `createdAt` populated
  on `save()`'s return, `findByWalletIdOrderByCreatedAtAsc`.

## Schema notes

- `entry_id` is an app-generated `UUID.randomUUID().toString()`, `VARCHAR2(36)` — same
  portability reasoning as every other entity in this platform (and what made the Oracle
  migration a dialect change, not a rewrite).
- `transaction_id` is `VARCHAR2(64)`, not `VARCHAR2(36)` — wider than the design doc's literal
  `VARCHAR2(36)` (§6.1.5) and every other UUID-holding column in this platform. Widened after a
  real bug: conversion-orchestrator's compensation path posts a reversal under
  `{originalTransactionId}-reversal` (36 + 9 = 45 chars), which the original 36-char column
  rejected outright with a `value too long` error the first time a live compensation scenario
  actually reached it. See conversion-orchestrator-implementation.md's "Bug 3" for the full
  story — caught by manual cross-service verification, not by either service's unit tests
  (ledger-service's own tests mock the repository, so a real DB column-length constraint
  never enters the picture).
- Indexes on `transaction_id` and `wallet_id` (design doc §6.1.5) — the two columns every query
  in this service filters by (`existsByTransactionId`, `findByWalletIdOrderByCreatedAtAsc`).
- No `UNIQUE` constraint on `transaction_id` itself — a posting legitimately has *multiple* rows
  per `transactionId` (one per leg); the append-only check (`existsByTransactionId`) lives in
  the service layer instead.

## How to run it locally

```bash
cd backend
docker compose up -d platform-oracle redis
cd ledger-service && ./mvnw spring-boot:run   # :8085
```

The shared `platform-oracle` instance is on host port **1521** (`paymentdb` PDB); this service
connects as `ledger_app` — see `backend/docker-compose.yml` and `backend/oracle-init/`.
`gvenzl/oracle-free:23-slim` takes ~2–4 min to become healthy on first boot (creates the per-service users then).

## Verification performed

All done manually via `curl` against real Oracle and Redis:

1. **Balanced posting**: `POST /ledger/entries` with a matched USD DEBIT/CREDIT pair → `201`,
   both lines back with `entryId`s and correctly populated `createdAt` (the `Persistable` fix,
   applied from the start — see above).
2. **Idempotency-Key replay**: re-sent the same request with the same key → identical array
   back, no second posting created.
3. **Duplicate `transactionId`, different key**: same `transactionId`, a *different*
   `Idempotency-Key` → `409 LEDGER_CONFLICT`.
4. **Unbalanced posting**: mismatched DEBIT/CREDIT amounts in the same currency → `400
   INVALID_LEDGER_ENTRIES` with the actual debit/credit totals in the message.
5. **Missing Idempotency-Key**: → `400 VALIDATION_FAILED`.
6. **Statement, populated**: `GET /ledger/wallets/wallet-A/statement` → the one entry posted
   against `wallet-A` in step 1.
7. **Statement, empty**: a wallet id with no postings → `200` with `entries: []`, not a `404`.

## What's next

- ~~Wire this service into conversion-orchestrator's saga~~ — **Done**, without a dedicated
  `LEDGER_POSTED` saga state — see conversion-orchestrator-implementation.md's "Third pass:
  wiring in ledger-service" for why (best-effort, same as `consumeLock`, no new `SagaState`
  needed).
- ~~Resolve the cross-currency double-entry netting gap~~ — **Done**, via the FX clearing
  account (see above), not a validator change.
- A ledger posting for the merchant-charge "spend" step — money that leaves a wallet to actually
  pay a merchant (post-charge, in conversion-orchestrator's `chargeMerchant`) isn't reflected in
  the ledger yet, only the underlying currency conversion is. See conversion-orchestrator-
  implementation.md's "What's deliberately not captured yet".
- ~~Testcontainers integration tests — would have caught Bug 3 above for real~~ — **Done.**
  `LedgerEntryRepositoryIntegrationTest`'s `save_45CharReversalStyleTransactionId_fitsInTheColumn`
  is exactly that regression test now (see testing-guide.md's Pattern 6).
- ~~Grafana + Prometheus observability~~ — **Done**, across all five services at once — see
  [observability.md](observability.md).
