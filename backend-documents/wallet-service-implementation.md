# wallet-service — Implementation Notes

Written after the code was built and manually verified, as a reference for future revision
(and for the next service, which will hit some of the same Spring Boot 4.x surprises).

## What this step built

The Wallet Service, as a standalone Spring Boot 4.1.1 (Java 25) module, backed by real
PostgreSQL persistence:

- Wallet CRUD: create, get balance, debit, credit.
- Reservations (holds): reserve → capture (performs the real debit) or release (frees the hold,
  no balance change).
- Both concurrency-control strategies from the design doc: **optimistic locking with retry**
  (default) and **pessimistic locking** (`SELECT ... FOR UPDATE`) for wallets flagged
  `highContention=true`.
- A consistent JSON error shape for every failure path (`GlobalExceptionHandler`).

### Deliberately deferred (and why)

| Deferred | Why |
|---|---|
| Kafka event publishing (`wallet.debited`, etc.) | Nothing consumes these yet — the Conversion Orchestrator that will react to them doesn't exist yet. Building it now would be untestable plumbing. |
| Redis-backed `Idempotency-Key` handling | Same reason — the SAGA orchestrator is the thing that actually needs safe-retry semantics across services. Wallet-service alone doesn't need it yet. |
| Transactional Outbox pattern | Only meaningful once there's a Kafka publish step to make reliable. |
| Oracle | Postgres is free, Docker-friendly, and close enough in SQL/locking semantics (including `SELECT ... FOR UPDATE`) to develop against. The schema was kept Oracle-portable on purpose (see below) so the eventual migration is a config/dialect change, not a rewrite. |
| Automated tests | Explicit user request for this step — verified manually with `curl` instead (see below). Will be added back in a follow-up step. |
| Flyway/Liquibase migrations | `spring.jpa.hibernate.ddl-auto=update` is fine while the schema is still moving during learning; must be replaced with real migrations once it stabilizes. |

## Package layout

```
com.paymentplatform.wallet
├── ping/          existing toolchain-check endpoint, untouched
├── domain/        Wallet, WalletReservation, WalletStatus, ReservationStatus
├── repository/    WalletRepository, WalletReservationRepository
├── service/       WalletService  (all business logic + concurrency control)
├── web/           WalletController + request/response DTO records
└── exception/     custom exceptions + GlobalExceptionHandler
```

One package per layer, matching the design doc's class list. No mapper/facade layer — DTOs
convert themselves via static `from(...)` methods.

## Concurrency control — what was actually built

Both strategies live in `WalletService`, dispatched by a wallet's `highContention` flag:

```java
private Wallet applyMutation(String walletId, UnaryOperator<Wallet> mutation) {
    Wallet probe = walletRepository.findById(walletId).orElseThrow(...);
    return probe.isHighContention()
        ? applyWithPessimisticLock(walletId, mutation)
        : applyWithOptimisticRetry(walletId, mutation);
}
```

**Optimistic retry** (`applyWithOptimisticRetry`): up to 5 attempts, linear backoff
(20ms, 40ms, 60ms, 80ms between attempts). On the 5th failure, throws `WalletConflictException`
→ HTTP 409.

**Pessimistic locking** (`applyWithPessimisticLock`): single attempt, blocks on
`WalletRepository#findByIdForUpdate` (`SELECT ... FOR UPDATE`, 3s lock-wait timeout). A timeout
surfaces as `PessimisticLockingFailureException` → HTTP 409 (fail fast, not queue forever).

### The bug we hit, and the rule it taught

**Both** paths need each attempt to run inside its own real transaction. The natural way to
write that in Spring is `@Transactional` on the method. That does **not** work here, because
`applyMutation` calls `applyWithPessimisticLock`/`applyWithOptimisticRetry` as a plain `this.`
call from inside the same class — Spring's `@Transactional` is implemented as a proxy wrapping
the bean, and a call that never goes back out through the proxy (a "self-invocation") skips the
proxy entirely, so the annotation is silently ignored.

We hit this for real: the pessimistic path was first written as
```java
@Transactional
Wallet applyWithPessimisticLock(...) { ... walletRepository.findByIdForUpdate(...) ... }
```
and every single call to it failed with:
```
jakarta.persistence.TransactionRequiredException: No active transaction
```
because `findByIdForUpdate`'s `SELECT ... FOR UPDATE` ran with no transaction open at all.

**Fix**: build a `TransactionTemplate` from the auto-configured `PlatformTransactionManager` in
the constructor (with `PROPAGATION_REQUIRES_NEW` set explicitly), and call
`transactionTemplate.execute(status -> { ... })` around the actual DB work in both the
optimistic-retry loop and the pessimistic path. `TransactionTemplate.execute()` opens/commits a
transaction programmatically — it doesn't rely on being called through a Spring proxy, so
self-invocation is a non-issue. This also happens to be exactly what the retry loop needs
anyway: each retry attempt gets a genuinely fresh transaction and persistence context, so a
retry always re-reads the row's current `version` instead of reusing stale state from a failed
attempt.

**Takeaway for future services**: any time a `@Transactional` method is invoked directly from
another method in the *same* class (not via an injected reference to the bean), assume the
annotation does nothing, and use `TransactionTemplate` (or restructure into a separate bean)
instead. This is a general Spring pitfall, not specific to this project.

## Schema notes

- `wallet_id` / `reservation_id` are app-generated `UUID.randomUUID().toString()` values stored
  as `VARCHAR(36)`, not a Postgres-native `uuid` column — kept portable for the later Oracle
  migration.
- `balance` / `amount` are `BigDecimal` at precision 18, scale 4, matching the design doc's
  `NUMBER(18,4)`. `balance >= 0` is enforced in `WalletService`, not a DB `CHECK` constraint
  (Hibernate's `ddl-auto=update` doesn't manage those reliably across dialects).
- `WalletReservation.walletId` is a plain string FK column, not a JPA `@ManyToOne` — the target
  `Wallet` is always loaded explicitly by `WalletService`, through whichever locking strategy it
  chose, never implicitly through a lazy association.

## Spring Boot 4.1.1 gotchas hit along the way

These cost real debugging time and will very likely resurface in the next service, so they're
recorded here rather than left to be rediscovered:

- **`spring-boot-starter-parent` artifact version has no `.RELEASE` suffix.** Spring
  Initializr's own metadata *labels* the current version `4.1.1.RELEASE`, but the real artifact
  on Maven Central is `4.1.1`. Using the label as the POM version fails dependency resolution.
- **`spring-boot-starter-web` is renamed to `spring-boot-starter-webmvc`** (disambiguating from
  WebFlux).
- **No single `spring-boot-starter-test` any more.** Spring Initializr now generates one test
  starter per feature starter instead (`spring-boot-starter-webmvc-test`,
  `spring-boot-starter-validation-test`, `spring-boot-starter-actuator-test`, ...).
- **Test-slice annotations moved packages.** `@AutoConfigureMockMvc` (and `@WebMvcTest`) now
  live in `org.springframework.boot.webmvc.test.autoconfigure`, not the old
  `org.springframework.boot.test.autoconfigure.web.servlet`. Confirmed by unzipping the actual
  jar (`spring-boot-webmvc-test-4.1.1.jar`) and looking at what classes it contains — the
  reliable way to answer "where did this class go" instead of guessing.
- **`HttpStatus.UNPROCESSABLE_ENTITY` is deprecated** in Spring Framework 7 (which Boot 4.1.1
  pulls in). Used `HttpStatus.valueOf(422)` instead.
- Pattern matching in `switch` over exception types is available on the Java 25 runtime, but
  `GlobalExceptionHandler` retains a plain `if (ex instanceof ...)` chain for clear, stable
  exception mapping behavior.

## How to run it locally

```bash
cd backend
docker compose up -d          # starts wallet-postgres (postgres:16-alpine), waits for healthy

cd wallet-service
./mvnw spring-boot:run        # starts the service on :8081
```

Sanity checks:
```bash
curl http://localhost:8081/api/v1/ping
curl http://localhost:8081/actuator/health
```

Teardown:
```bash
cd backend
docker compose down           # stop, keep the data volume
docker compose down -v        # stop and wipe the schema/data
```

## Verification performed

All done manually via `curl` (automated tests deferred, see above):

1. Full lifecycle on a normal wallet: create → duplicate-create rejected (409) → credit → get
   balance → debit → insufficient-funds rejected (422) → reserve → release (balance unchanged)
   → reserve → capture (balance decreases by the reserved amount).
2. **Concurrency demo, optimistic wallet**: seeded a normal wallet with exactly $10.00, fired 20
   concurrent $1 debit requests. Result: exactly 10 succeeded (200), 10 correctly rejected as
   insufficient funds (422), final balance exactly `0.0000` — zero lost updates, zero
   over-drafts.
3. **Concurrency demo, high-contention wallet**: same test against a `highContention=true`
   wallet, going through the pessimistic-lock path instead. Same result: exactly 10 succeeded,
   10 rejected, final balance exactly `0.0000`.
4. This is also where the self-invocation `@Transactional` bug above was actually caught — the
   first attempt at step 3 returned HTTP 500 on every request to the high-contention wallet.

## What's next

- Bring automated tests back (MockMvc controller tests, a `WalletService` unit test with a real
  concurrent-load harness per design doc section 7).
- Kafka event publishing (`wallet.debited`, `wallet.credited`, `wallet.debit.failed`) once the
  Conversion Orchestrator exists to consume them.
- Redis-backed `Idempotency-Key` handling on the write endpoints.
- Transactional Outbox pattern, once there's a Kafka publish step to make reliable.
- Flyway migrations, replacing `ddl-auto=update`, once the schema stabilizes.
- Eventual Oracle migration (schema was kept portable for exactly this).
