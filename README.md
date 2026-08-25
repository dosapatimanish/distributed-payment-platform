# Distributed Payment Platform

A distributed, multi-currency wallet, FX conversion, and merchant-payment platform built as five
independent Spring Boot microservices, coordinated by an orchestration-based SAGA. Built as a
from-scratch learning/portfolio project: real concurrency control (optimistic + pessimistic
locking), real idempotency (Redis `SETNX`), real compensating transactions, real observability
(Prometheus + Grafana) — verified against real Postgres/Redis/Kafka, not mocked end to end.

Full design rationale lives in
[`documents/Multi-Currency-Wallet-FX-Platform-Design-Document.md`](documents/Multi-Currency-Wallet-FX-Platform-Design-Document.md);
every implementation decision — including every deliberate deviation from that design doc, and
why — is written up in [`backend-documents/`](backend-documents) (see the Documentation Map
below).

## Architecture

```mermaid
flowchart LR
    Client([Customer / Postman / curl])

    Client --> O[Conversion Orchestrator<br/>:8083]
    Client --> W[Wallet Service<br/>:8081]
    Client --> F[FX Rate Service<br/>:8082]
    Client --> M[Merchant Payment Service<br/>:8084]
    Client --> L[Ledger Service<br/>:8085]

    O -->|debit / credit| W
    O -->|lock / consume / release rate| F
    O -->|charge / refund| M
    O -->|post double-entry| L

    W --> WDB[(wallet_db)]
    F --> FDB[(fxrate_db)]
    O --> ODB[(saga_db)]
    M --> MDB[(payment_db)]
    L --> LDB[(ledger_db)]

    W -.->|Idempotency-Key| R[(Redis)]
    F -.->|Idempotency-Key| R
    O -.->|Idempotency-Key| R
    M -.->|Idempotency-Key| R
    L -.->|Idempotency-Key| R

    W -.->|domain events| K[(Kafka)]
    F -.->|domain events| K
    M -.->|domain events| K

    W -.-> P[Prometheus<br/>:9090]
    F -.-> P
    O -.-> P
    M -.-> P
    L -.-> P
    P --> G[Grafana<br/>:3000]
```

Each service owns its own Postgres database (database-per-service) and is independently
deployable and testable; the orchestrator is the only service that calls the other four. Redis
and Kafka are shared infrastructure. Full rationale for this shape (and why a SAGA instead of a
single ACID transaction) is in the design doc's §3.3.

## Services

| Service | Port | Owns | Responsibility |
|---|---|---|---|
| [wallet-service](backend/wallet-service) | `8081` | `wallet_db` | Multi-currency wallet balances, reservations, optimistic/pessimistic concurrency control |
| [fx-rate-service](backend/fx-rate-service) | `8082` | `fxrate_db` | Current FX rates (simulated feed), short-lived rate locks for a conversion |
| [conversion-orchestrator](backend/conversion-orchestrator) | `8083` | `saga_db` | Drives the wallet-to-wallet-conversion-plus-optional-merchant-charge SAGA and its compensation |
| [merchant-payment-service](backend/merchant-payment-service) | `8084` | `payment_db` | Charges/refunds via a mock acquirer |
| [ledger-service](backend/ledger-service) | `8085` | `ledger_db` | Immutable double-entry ledger, posted by the orchestrator on every completed or compensated conversion |

Plus shared infrastructure: Postgres ×5 (one per service), Redis (idempotency keys), Kafka
(domain events), Prometheus + Grafana (metrics).

## The SAGA (conversion, optionally followed by a merchant charge)

```mermaid
sequenceDiagram
    participant Client
    participant O as Orchestrator
    participant F as FX Rate Svc
    participant W as Wallet Svc
    participant M as Merchant Payment Svc
    participant L as Ledger Svc

    Client->>O: POST /conversions (Idempotency-Key)
    O->>F: lock rate
    F-->>O: rate locked
    O->>W: debit source wallet
    W-->>O: debited
    O->>W: credit dest wallet
    W-->>O: credited

    opt merchantId present
        O->>M: charge merchant
        alt approved
            M-->>O: COMPLETED
            O->>W: debit dest wallet (spend the credit)
        else declined / call fails
            M-->>O: FAILED
            O->>W: reverse credit
            O->>W: reverse debit
            O->>F: release lock
            O->>L: record REVERSED entries
            O-->>Client: sagaState: COMPENSATED
        end
    end

    O->>L: record double-entry ledger
    O->>F: consume lock
    O-->>Client: sagaState: COMPLETED
```

Every state transition is persisted before the next step runs, so `conversion_transaction` and
`saga_step_log` always reflect exactly how far a saga got — including a full compensation path
if a debit, credit, or merchant charge fails partway through. See
[`conversion-orchestrator-implementation.md`](backend-documents/conversion-orchestrator-implementation.md)
for every real bug this surfaced and how each was fixed.

## Concurrency, resilience, and correctness patterns actually built

| Pattern | Where | Doc |
|---|---|---|
| Optimistic locking + bounded retry, pessimistic locking for hot wallets | wallet-service | [wallet-service-implementation.md](backend-documents/wallet-service-implementation.md) |
| Redis `SETNX`-backed Idempotency-Key, one independent copy per service | all 5 services | [idempotency.md](backend-documents/idempotency.md) |
| Compensating transactions (full SAGA rollback) | conversion-orchestrator | [conversion-orchestrator-implementation.md](backend-documents/conversion-orchestrator-implementation.md) |
| Kafka domain-event publishing (producer-only, no consumer yet) | wallet/fx-rate/merchant-payment | [kafka-events.md](backend-documents/kafka-events.md) |
| Immutable double-entry ledger, synthetic FX clearing account for cross-currency netting | ledger-service | [ledger-service-implementation.md](backend-documents/ledger-service-implementation.md) |
| Prometheus + Grafana, incl. 3 hand-instrumented metrics answering this platform's own concurrency questions | all 5 services | [observability.md](backend-documents/observability.md) |
| Flyway-versioned schema migrations (replacing `ddl-auto=update`) | all 5 services | each service's implementation notes' "Schema notes" |
| Testcontainers integration tests against a real, Flyway-migrated Postgres | all 5 services | [testing-guide.md](backend-documents/testing-guide.md)'s Pattern 6 |

## Tech stack

Java 25, Spring Boot 4.1.1 (Web MVC, Data JPA, Validation, Actuator), Jackson 3, PostgreSQL 16,
Flyway, Redis 7, Apache Kafka 3.9 (KRaft mode), Prometheus + Grafana, Docker + Docker Compose,
Maven, JUnit 5 + Mockito + AssertJ.

## How to run it locally

Requires Docker (and Java 25 only for the "run on the host" option below — each service's own
bundled `./mvnw` handles Maven itself).

### Option A: one command, everything containerized

```bash
cd backend
docker compose up -d --build
```

Brings up all 5 Postgres instances, Redis, Kafka, Prometheus, Grafana, and all 5 application
services (each with its own `Dockerfile`), fully networked — `docker-compose.yml`'s
`depends_on: condition: service_healthy` means an app container won't even attempt to start
before its own Postgres is actually ready. First run builds all 5 images (a couple of minutes);
after that it's fast. `--build` is only needed after changing a service's source — otherwise
plain `docker compose up -d` reuses the existing images.

### Option B: infrastructure in Docker, services on the host

Useful for iterating on one service without rebuilding its image every time.

```bash
cd backend
docker compose up -d wallet-postgres fxrate-postgres orchestrator-postgres payment-postgres ledger-postgres redis kafka prometheus grafana

cd wallet-service              && ./mvnw spring-boot:run   # :8081
cd fx-rate-service              && ./mvnw spring-boot:run   # :8082
cd merchant-payment-service     && ./mvnw spring-boot:run   # :8084
cd ledger-service               && ./mvnw spring-boot:run   # :8085
cd conversion-orchestrator      && ./mvnw spring-boot:run   # :8083 (start last - calls the other four)
```

Don't mix the two for the same service (both bind the same host port). Everything downstream —
Prometheus, Grafana, and every `curl` example below — works identically either way: all 5
services always end up reachable at `localhost:8081`–`8085` and scraped the same way, since the
containerized services also publish their ports to the host (see `docker-compose.yml`'s comments
on the `kafka` and `prometheus` services for exactly how host-vs-container networking is kept
working both ways at once — including Kafka's two listeners, one per mode).

- Prometheus targets: http://localhost:9090/targets (all 5 should show `UP`)
- Grafana dashboard: http://localhost:3000 (anonymous viewer works; `admin`/`admin` to edit) →
  "Distributed Payment Platform - Overview"

Each service's own implementation notes documents its exact Postgres port and any
service-specific setup.

## Try it

```bash
# Create two wallets
curl -X POST http://localhost:8081/api/v1/wallets -H "Idempotency-Key: k1" \
  -H "Content-Type: application/json" \
  -d '{"userId":"user-1","currency":"USD","highContention":false}'

curl -X POST http://localhost:8081/api/v1/wallets -H "Idempotency-Key: k2" \
  -H "Content-Type: application/json" \
  -d '{"userId":"user-2","currency":"INR","highContention":false}'

# Fund the source wallet (use the walletId from the response above)
curl -X POST http://localhost:8081/api/v1/wallets/{sourceWalletId}/credit \
  -H "Idempotency-Key: k3" -H "Content-Type: application/json" \
  -d '{"amount":100.00,"transactionId":"fund-1"}'

# Run a conversion through the orchestrator
curl -X POST http://localhost:8083/api/v1/conversions -H "Idempotency-Key: k4" \
  -H "Content-Type: application/json" \
  -d '{"userId":"user-1","sourceWalletId":"{sourceWalletId}","destWalletId":"{destWalletId}","sourceCurrency":"USD","destCurrency":"INR","sourceAmount":50.00}'

# See the resulting double-entry ledger
curl http://localhost:8085/api/v1/ledger/wallets/{sourceWalletId}/statement
```

Each service's `backend-documents/<service>-api/` folder has a full OpenAPI spec, a Postman
collection, and one markdown doc per endpoint with sample requests/responses and error tables.

## Testing

236 automated tests across the 5 services — unit tests (Mockito-mocked collaborators) for
business logic, plus one Testcontainers integration test class per service (real Postgres,
Flyway-migrated) for the persistence layer — see
[testing-guide.md](backend-documents/testing-guide.md) for the full scope decision and reusable
test patterns. Plus extensive manual `curl` verification against real Postgres/Redis/Kafka for
every scenario, documented in each service's own implementation notes.

```bash
cd backend/<service> && ./mvnw test   # the Postgres integration tests need Docker running locally
```

## Documentation map

- [`documents/Multi-Currency-Wallet-FX-Platform-Design-Document.md`](documents/Multi-Currency-Wallet-FX-Platform-Design-Document.md) — the original design doc.
- `backend-documents/<service>-implementation.md` — what was built, what's deliberately deferred (and why), real bugs caught and how, schema notes, how to run, manual verification performed.
- `backend-documents/<service>-api/` — OpenAPI spec, Postman collection, per-endpoint docs.
- `backend-documents/idempotency.md`, `kafka-events.md`, `observability.md` — cross-cutting concept docs (what/why/how/use cases) that don't belong to one service.
- `backend-documents/testing-guide.md` — reusable unit-testing patterns and the two mocking traps hit along the way.

## What's deliberately not built yet

Documented as explicit scope decisions, not gaps discovered by accident — full reasoning in each
linked doc:

- Async Kafka-driven orchestration (the orchestrator currently calls the other services
  synchronously over REST; events are published but not yet consumed) and crash-recovery/saga
  resume that would build on it.
- Testcontainers integration tests against real Redis and real Kafka (Postgres is covered — see
  testing-guide.md's Pattern 6).
- Auth (JWT/OAuth2, API Gateway, mTLS) — none of the endpoints are authenticated yet.
- A ledger posting for the merchant-charge "spend" step (only the underlying currency conversion
  is ledgered right now, not the wallet-to-merchant payout) — see
  conversion-orchestrator-implementation.md's "Third pass".
