# Unit Testing Guide

How wallet-service and fx-rate-service are unit-tested, written as a reusable reference for the
next service (Conversion Orchestrator, Merchant Payment, Ledger) rather than a report on what
already exists. Every pattern below is copy-pasteable — it's what's actually in
`WalletServiceTest`, `WalletControllerTest`, `FxRateServiceTest`, `FxRateControllerTest`, etc.

## Scope decision: unit tests only, for now

Both services currently have **unit tests only** — no Testcontainers/real-Postgres integration
tests yet. Deliberate, not an oversight:

| | Unit tests (done) | Testcontainers integration tests (deferred) |
|---|---|---|
| Speed | ~2-6s per module | Much slower — spins up real Postgres per run |
| Needs Docker at test time | No | Yes |
| Proves | Business logic, validation, error mapping | The DB actually enforces `SELECT ... FOR UPDATE`, unique constraints, `@Version` conflicts under real concurrent load |

The unit tests below **simulate** the concurrency behavior (e.g. feeding a mocked repository a
scripted sequence of `ObjectOptimisticLockingFailureException`s) rather than proving Postgres
itself does what the code assumes. That gap is real and worth closing with an integration-test
pass later — see each service's implementation-notes doc for the "what's next" entry.

## What's already on the classpath — no pom.xml changes needed

Neither service depends on the classic `spring-boot-starter-test`. Spring Boot 4's modular test
starters split it up, but the granular test starter already in both `pom.xml`s
(`spring-boot-starter-actuator-test`) transitively pulls in `spring-boot-starter-test`, which
brings everything used below: JUnit Jupiter 6, Mockito 5 (+ `mockito-junit-jupiter`), AssertJ,
`spring-test` (MockMvc). Confirmed by running `./mvnw dependency:tree` before writing any test —
worth doing that first in a new service rather than guessing at dependencies to add.

`spring-boot-starter-webmvc-test` (also already present) is what actually supplies `@WebMvcTest`
itself.

## Pattern 1 — service-layer unit test (Mockito, no Spring context)

For a `@Service` class whose collaborators are repositories/other beans: mock every
collaborator, no `ApplicationContext` involved, fast (`WalletServiceTest` runs 22 tests in well
under a second).

```java
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletRepository walletRepository;
    // ... one @Mock per constructor-injected collaborator

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(walletRepository, /* ... */);
    }

    @Test
    void debit_sufficientFunds_reducesBalance() {
        Wallet wallet = activeWallet("w-1", new BigDecimal("100.0000"), false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        Wallet result = walletService.debit("w-1", new BigDecimal("40.00"), "txn-1");

        assertThat(result.getBalance()).isEqualByComparingTo("60.0000");
    }
}
```

Naming convention used throughout: `methodUnderTest_condition_expectedOutcome`. Read the test
name and you know what it proves without opening the body.

`when(repo.save(any(X.class))).thenAnswer(inv -> inv.getArgument(0))` is the standard
"pretend-persist" stub — returns whatever was passed in, so the mutation the service made is
still visible on the returned object, without a real DB round-trip.

## Pattern 2 — controller-layer slice test (`@WebMvcTest`)

Loads only the one controller plus any `@RestControllerAdvice` in the same package scan (so
`GlobalExceptionHandler` comes along automatically) — not the full application context. The
service layer is a Mockito double.

```java
@WebMvcTest(WalletController.class)
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WalletService walletService;

    @Test
    void getBalance_notFound_returns404WithErrorCode() throws Exception {
        when(walletService.getBalance("missing")).thenThrow(new WalletNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/wallets/missing/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
    }
}
```

Use this layer to test **request binding, `@Valid` validation, and the HTTP status/error-code
mapping** for every endpoint — not business logic (that's Pattern 1's job). A controller test
suite that mirrors every row in the service's own error table (see each service's
`backend-documents/*-api/` docs) is the right level of coverage.

### Two Spring Boot 4 import gotchas that cost real time here

- **`@MockitoBean`, not `@MockBean`.** `@MockBean` doesn't exist any more in this Spring
  Framework 7 / Boot 4.1 combo — the class simply isn't on the classpath. Use
  `org.springframework.test.context.bean.override.mockito.MockitoBean`.
- **`@WebMvcTest` moved packages.** It now lives in
  `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, not the old
  `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest`. When an import doesn't
  resolve and you're not sure where a Boot 4 class moved to, don't guess — unzip the actual jar
  and grep it: `unzip -l spring-boot-webmvc-test-4.1.1.jar | grep WebMvcTest`. That's how this
  was actually found (see wallet-service-implementation.md's "Spring Boot 4.1.1 gotchas"
  section).
- **`status().isUnprocessableEntity()` is deprecated** (Spring Framework 7). Use
  `status().is(422)` instead — same assertion, no deprecation warning.

## Pattern 3 — testing a class with no Spring/mocking need at all

If a class's dependencies are simple, in-memory, and cheap to construct for real (no I/O, no
Spring wiring), just `new` it — no `@Mock`, no Spring context. `FxRateCacheTest` and
`DistributedLockManagerTest` both do this: they're plain `ConcurrentHashMap`-backed classes, so
the "real" object under test in a service-layer test (Pattern 1) can also just be a real
instance instead of a mock, e.g.:

```java
@ExtendWith(MockitoExtension.class)
class FxRateServiceTest {

    @Mock
    private FxRateLockRepository lockRepository; // has a real DB behind it - mock it

    private FxRateCache cache; // simple in-memory class - use the real thing
    private FxRateService fxRateService;

    @BeforeEach
    void setUp() {
        cache = new FxRateCache();
        fxRateService = new FxRateService(cache, new DistributedLockManager(), lockRepository, 10L);
    }
}
```

Mocking something that has no meaningful behavior to fake just adds noise — prefer a real
instance whenever the "real" cost is a few lines of pure Java.

## Two mocking traps worth knowing before you hit them

### Trap 1 — `UnnecessaryStubbingException` from a shared `@BeforeEach` stub

Mockito's strict-stubs mode (the JUnit 5 default via `MockitoExtension`) fails a test if a stub
set up in `@BeforeEach` is never actually used by that particular test method. This bites any
time some tests in a class need a shared stub and others don't — e.g. `WalletServiceTest`'s
`transactionManager.getTransaction(...)` stub is only exercised by tests that reach
`applyMutation` (debit/credit/pessimistic-path reserves); `createWallet`, `getBalance`, and most
error-path tests never touch it.

Fix: mark that one stub `lenient()`, not the whole test class:

```java
@BeforeEach
void setUp() {
    lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(mock(TransactionStatus.class));
    walletService = new WalletService(walletRepository, reservationRepository, transactionManager, 15L);
}
```

Don't reach for `@MockitoSettings(strictness = Strictness.LENIENT)` on the whole class just to
silence this — it also hides genuinely unused/wrong stubs elsewhere in the same class.
`lenient()` on the one stub that's legitimately conditional is more precise.

### Trap 2 — a mocked repository returning the *same* mutable object across calls

If service code mutates an entity in place (`wallet.setBalance(...)`) and a test stubs
`repository.findById(...)` to return one shared object every time it's called, repeated
mutation-then-"failed"-save cycles will keep compounding on that same instance — even though a
real database would have rolled back and left the row untouched.

This surfaced writing the optimistic-retry test for `WalletService.debit`: the first version
returned one `Wallet` from every `findById` stub call. The retry loop calls `debitMutation`
before `save()` can throw, so balance went 100 → 90 (attempt 1, save throws) → 80 (attempt 2,
save throws) → 70 (attempt 3, save succeeds) — the test then wrongly asserted 90, when the code
was actually correct and the mock was lying about what a rolled-back transaction leaves behind.

Fix: `thenAnswer(...)` returning a **fresh** entity on every call, so each retry attempt starts
from the real, unmutated, "committed" state:

```java
when(walletRepository.findById("w-1"))
        .thenAnswer(inv -> Optional.of(activeWallet("w-1", new BigDecimal("100.0000"), false)));
```

General rule: if the code under test mutates an entity in place and your test has more than one
logical "transaction attempt" touching it, prefer `thenAnswer` returning a new instance over
`thenReturn` handing back one shared instance — unless you specifically want to prove mutation
*is* visible across calls (e.g. `releaseReservation`'s "no wallet mutation happens" test
legitimately reuses one wallet instance to prove nothing changed on it).

## Mocking a `TransactionTemplate`-based method (no `@Transactional`, no proxy to intercept)

Both `WalletService` and (less directly) `FxRateService` avoid `@Transactional` on
self-invoked methods, for the reason recorded in wallet-service-implementation.md (a
self-invocation call never goes through the Spring proxy, so `@Transactional` on it silently
does nothing). They build a `TransactionTemplate` from an injected `PlatformTransactionManager`
instead and call `transactionTemplate.execute(status -> { ... })` directly.

That's easy to make testable without a real transaction manager or database: `execute()` only
needs `transactionManager.getTransaction(...)` to return something before it will run the real
callback synchronously.

```java
@Mock
private PlatformTransactionManager transactionManager;

@BeforeEach
void setUp() {
    lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(mock(TransactionStatus.class));
    walletService = new WalletService(walletRepository, reservationRepository, transactionManager, 15L);
}
```

Everything inside the callback — the actual repository calls — is still exercised for real by
the test; only the transaction machinery around it is faked. If you write a new service that
uses this same `TransactionTemplate`-instead-of-`@Transactional` pattern (worth doing any time a
method calls another `@Transactional`-flavored method on `this`), this is the stub to reach for.

## Current test inventory

| Service | Test class | Count | What it covers |
|---|---|---|---|
| wallet-service | `WalletServiceTest` | 22 | Business rules, both concurrency-control paths, reservation state machine |
| wallet-service | `WalletControllerTest` | 11 | Request validation + HTTP/error-code mapping, all 7 endpoints |
| fx-rate-service | `FxRateCacheTest` | 3 | Snapshot get/refresh/replace |
| fx-rate-service | `DistributedLockManagerTest` | 5 | Acquire/release/lease-expiry/wrong-id-can't-steal-lock |
| fx-rate-service | `RateRefreshSchedulerTest` | 3 | Cache seeding, persisted-row count, bounded random walk, malformed config fails fast |
| fx-rate-service | `FxRateServiceTest` | 15 | Full rate-lock state machine, including the consume-vs-release expiry asymmetry |
| fx-rate-service | `FxRateControllerTest` | 9 | Request validation + HTTP/error-code mapping, all 4 endpoints |

**68 tests total, all passing.** Run per service: `cd backend/<service> && ./mvnw test`.

## Checklist for testing the next service

1. `./mvnw dependency:tree` first — confirm what's already on the test classpath before adding
   any dependency.
2. One `@ExtendWith(MockitoExtension.class)` test class per `@Service`, mocking only the
   collaborators that have real (DB/I/O) behavior behind them — use real instances for anything
   simple enough to construct directly (Pattern 3).
3. One `@WebMvcTest` test class per `@RestController`, `@MockitoBean` for the service — walk the
   endpoint's own API doc's error table and write one test per row.
4. Watch for the two mocking traps above — shared `@BeforeEach` stubs need `lenient()` unless
   every test uses them; mutated entities need a fresh object per mock call, not a shared one.
5. If the service uses `TransactionTemplate` instead of `@Transactional` (check for
   self-invocation first), mock `PlatformTransactionManager.getTransaction(...)` to return a
   bare `TransactionStatus` mock.
6. Testcontainers/real-Postgres integration tests are still a deliberate gap across both
   existing services — worth deciding once, for whichever service takes it on first, rather than
   re-deciding per service.
