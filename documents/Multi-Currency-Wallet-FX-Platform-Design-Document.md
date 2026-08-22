# Multi-Currency Wallet & FX Settlement Platform

**A Microservices Architecture with SAGA-based Distributed Transaction Orchestration and High-Concurrency Financial Consistency Controls**

Fintech Engineering — System Design Document
Version 1.0 | August 2026

| | |
|---|---|
| **Document Type** | Business & Technical Design Document (BRD + HLD + LLD) |
| **Domain** | Fintech — Digital Wallets, FX Conversion, Payments |
| **Architecture Style** | Microservices, Event-Driven, Orchestration-based SAGA |
| **Primary Focus Areas** | Distributed Transaction Consistency, Concurrency Control |
| **Status** | Draft for Review |
| **Owner** | Engineering — Platform Team |

---

## 1. Executive Summary

This document defines the business rationale and complete technical design for a Multi-Currency Wallet & FX Settlement Platform — a fintech system that lets a customer hold balances in multiple currencies, convert between them at a live, locked exchange rate, and pay merchants in the merchant's settlement currency, all within a single, auditable, financially consistent operation.

The platform is built as a set of independently deployable Spring Boot microservices coordinated through an orchestration-based SAGA over Apache Kafka, because a conversion-and-payment operation spans multiple independently-owned databases where a single ACID transaction is not achievable. The document places specific emphasis on two engineering concerns that are the primary technical risk in a system like this:

- **Distributed transaction consistency** — guaranteeing that a multi-step, multi-service financial operation either completes fully or is fully and correctly reversed (compensated), with no state where money is deducted but never delivered, or delivered twice.
- **Concurrency correctness** — guaranteeing correct outcomes when many requests touch the same wallet, the same FX rate, or the same idempotency key at the same instant, which is the normal operating condition for a payments system, not an edge case.

The remainder of this document covers the business problem, the solution approach, a high-level architecture with block diagrams, and a low-level design including database schemas, service classes and methods, REST/Kafka contracts, and the concurrency control mechanisms used throughout.

---

## 2. Business Problem Statement

### 2.1 Industry Context

Digital-first fintech products increasingly serve customers who hold, earn, or spend money in more than one currency — freelancers paid in USD who spend in INR, travellers, cross-border e-commerce shoppers, and gig workers on global platforms. These customers expect to convert currency and pay a merchant in a single, near-instant action, with a rate that does not change between the moment they see it and the moment the payment settles.

### 2.2 Current State Pain Points

- Existing in-house systems typically implement wallet balance, FX conversion, and merchant settlement as tightly coupled modules inside a monolith, or as services that call each other synchronously without any recovery path for partial failure.
- When a merchant payment fails after the customer's source wallet has already been debited and the FX rate consumed, manual reconciliation teams have to identify and reverse the transaction — a slow, error-prone, and costly process, and a source of customer complaints and regulatory risk.
- Under concurrent load — many simultaneous transactions against the same wallet, the same currency pair, or duplicate retried requests from flaky mobile networks — naive implementations produce lost updates, double-spends, or duplicate merchant charges.
- FX rates move every second; a rate quoted to the customer must be honoured for the transaction even while the live rate keeps changing underneath it, which most simple designs do not handle safely.
- Lack of an immutable, queryable audit trail makes it hard to satisfy compliance and dispute-resolution requirements common in fintech regulation.

### 2.3 Stakeholders

| Stakeholder | Interest / Concern |
|---|---|
| Customer | Fast, correct conversion and payment; no lost or duplicated money; transparent rate. |
| Merchant / Acquirer | Guaranteed settlement in agreed currency; no double-charging on retries. |
| Compliance / Risk | Full audit trail, explainability of every balance change, regulatory reporting. |
| Operations / Support | Ability to see the exact state of a stuck transaction and resolve it without manual DB edits. |
| Engineering | A system that fails safely under partial outages and concurrent load, without data corruption. |

### 2.4 Business Objectives

- Enable multi-currency wallet balances with real-time conversion and merchant payment in one customer-facing flow.
- Guarantee that every conversion either completes end-to-end or is automatically and correctly reversed — with zero manual reconciliation for the standard failure cases.
- Guarantee correctness under concurrent access to the same account, the same FX rate, and the same customer request (retries).
- Provide a fully auditable, double-entry ledger suitable for financial and regulatory reporting.
- Scale each functional area (wallets, FX, payments, ledger) independently as transaction volume grows.

### 2.5 Success Metrics (KPIs)

| Metric | Target |
|---|---|
| Saga completion success rate (happy path) | ≥ 99.9% |
| Automatic compensation success rate on failure | 100% (no manual reconciliation for known failure modes) |
| Balance correctness under concurrent load (load test) | Zero lost updates / zero over-drafts across 10,000 concurrent ops |
| Duplicate transaction rate on client retries | 0% (fully idempotent) |
| End-to-end conversion + payment latency (p99) | < 3 seconds excluding external acquirer latency |
| Mean time to detect a stuck/failed saga | < 1 minute (via Grafana alerting) |

---

## 3. Solution Approach

### 3.1 Solution Overview

The platform decomposes the business capability into five independently owned microservices — **Wallet, FX Rate, Conversion Orchestrator, Merchant Payment, and Ledger** — each with its own Oracle schema, communicating synchronously for commands and asynchronously over Apache Kafka for events. A dedicated Conversion Orchestrator service implements an orchestration-based SAGA that drives the multi-step conversion-and-payment process through an explicit state machine, and issues compensating actions automatically whenever any step fails.

### 3.2 Why Microservices (and not a monolith)

- Wallet, FX rate, and merchant payment naturally have different scaling profiles — FX rate reads/refreshes happen far more often than payments — so they need to scale independently.
- Wallet and Ledger data must be strictly isolated and auditable per financial-service boundary, which maps naturally to database-per-service ownership.
- Independent deployability lets the payment-acquirer integration evolve (new acquirers, new APIs) without redeploying the wallet or ledger services, which are the most sensitive to change.
- Failure isolation: an outage or bug in the Merchant Payment service (talking to an external acquirer) must not take down wallet balance reads or FX rate quoting.

### 3.3 Why the SAGA Pattern — and why it is the correct solution, not just a preference

A single currency-conversion-and-payment operation touches four separate databases (wallet, FX rate, payment, ledger), each owned by a different service. This makes a classic local ACID transaction physically impossible. The two real alternatives, and why they were rejected, are shown below.

| Approach | Why it does not fit this problem |
|---|---|
| Two-Phase Commit (2PC) / XA transactions | Requires all participating resources to hold locks and stay available for the full duration of the transaction. A slow or unavailable acquirer call would block wallet rows for other users, destroying throughput and availability — unacceptable for a payments system under load. |
| Single shared database | Removes service autonomy and independent scaling/deployability, recreates a monolith, and does not work once a real external acquirer (a separate system entirely) is in the loop. |
| **Orchestration-based SAGA (chosen)** | Each local step commits its own local transaction immediately (fast, no cross-service locks held). If a later step fails, previously completed steps are undone via explicit compensating transactions (e.g., reverse the debit at the exact locked rate). A central orchestrator makes the multi-step, multi-branch business logic (retry vs. compensate vs. escalate) explicit and testable, rather than scattered as implicit event reactions across services. |

In short: SAGA is not used because it is fashionable — it is used because the transaction genuinely spans independently owned systems, partial failure is a normal (not exceptional) outcome given a real external payment acquirer in the loop, and the business requires a guaranteed, automatic path back to a correct state (a full refund at the original rate) whenever any step fails.

### 3.4 Why Concurrency Control Is a First-Class Design Concern

Because this is a financial system, correctness under concurrent access is not a performance nice-to-have — it is a correctness requirement with a direct monetary consequence. Four concrete concurrency hazards are designed for explicitly throughout this document:

- **Lost updates on wallet balance** — two simultaneous debits against the same wallet must never both succeed if only one can be covered by the balance.
- **FX rate races** — the rate used for a conversion must be the exact rate quoted and locked at request time, immune to concurrent rate refreshes happening in the background.
- **Duplicate execution** — a retried client request (double-tap, network timeout-retry) must never execute the underlying debit/payment twice.
- **Out-of-order event processing** — events for the same wallet/transaction must be processed in the order they occurred, even with multiple concurrent Kafka consumer threads.

Section [6.2](#62-concurrency-control-design) details the exact locking, distributed-locking, and idempotency mechanisms used to close each of these hazards.

### 3.5 Scope

**In Scope:**
- Multi-currency wallet creation, balance management, and statement/ledger access.
- Live FX rate quoting and short-lived rate locking for a specific conversion.
- Orchestrated SAGA for convert-and-pay, including automatic compensation on failure.
- Merchant payment execution against a (simulated/adapter-based) external acquirer.
- Double-entry ledger and audit trail.
- Concurrency-safe implementations of every write path listed above, with a load-test harness proving correctness.

**Out of Scope (for this phase):**
- KYC/onboarding, card issuance, and physical settlement/nostro-vostro account management with real banking rails.
- Multi-region active-active deployment (single-region deployment is assumed for this phase).

---

## 4. System Block Diagram

The system at the highest level: client applications, the API gateway and auth layer, the five core microservices, the Kafka event bus that carries SAGA events, the shared infrastructure (Oracle, Redis, monitoring), and external integrations.

```
                              ┌─────────────────────┐
                              │   Client Apps        │
                              │ (Web / Mobile / Ops)  │
                              └──────────┬───────────┘
                                         │ HTTPS
                              ┌──────────▼───────────┐
                              │     API Gateway        │
                              │  (OAuth2/JWT, routing,  │
                              │   rate limiting)        │
                              └──────────┬───────────┘
                                         │
        ┌───────────┬───────────┬───────┼────────┬──────────────┬─────────────┐
        ▼           ▼           ▼        ▼        ▼              ▼             ▼
   ┌────────┐  ┌─────────┐ ┌──────────────┐ ┌───────────┐ ┌────────┐   ┌──────────────┐
   │  Auth  │  │ Wallet  │ │  FX Rate     │ │Conversion │ │ Ledger │   │  Merchant     │
   │Service │  │ Service │ │  Service     │ │Orchestrator│ │Service │   │  Payment Svc  │
   └────────┘  └────┬────┘ └──────┬───────┘ └─────┬─────┘ └───┬────┘   └──────┬───────┘
                     │             │               │           │               │
                     └─────────────┴───────┬───────┴───────────┴───────────────┘
                                            │
                                  ┌─────────▼──────────┐
                                  │   Apache Kafka        │
                                  │ (SAGA event bus,       │
                                  │  partitioned by         │
                                  │  walletId / txnId)      │
                                  └─────────┬──────────┘
                                            │
                      ┌─────────────────────┼─────────────────────┐
                      ▼                     ▼                     ▼
               ┌─────────────┐      ┌──────────────┐      ┌──────────────┐
               │  Oracle DB    │      │    Redis       │      │  Grafana /    │
               │ (per-service   │      │ (distributed    │      │  Prometheus    │
               │   schemas)      │      │  locks, idem-   │      │ (monitoring)   │
               └─────────────┘      │  potency keys) │      └──────────────┘
                                     └──────────────┘

                                            │
                                  ┌─────────▼──────────┐
                                  │  External Acquirer    │
                                  │  (merchant settlement)  │
                                  └────────────────────┘
```

**Key points illustrated:**
- All client traffic enters through a single API Gateway, which terminates OAuth2/JWT authentication before routing to the appropriate service.
- The **Conversion Orchestrator** is the SAGA control point — it is the only service that knows the full business process; the other services only know their own local step.
- Kafka decouples services in time — a service can be briefly unavailable without blocking the others — and provides the durable, ordered event log the SAGA relies on for recovery.
- Redis provides two very different but equally critical concurrency primitives: distributed locks (for the FX rate lock) and idempotency-key storage (for safe request retries).

---

## 5. High-Level Design (HLD)

### 5.1 Architecture & Deployment Diagram

Each microservice is deployed independently (Docker/Kubernetes), owns its own Oracle schema, and communicates via REST (sync commands) and Kafka (async events). See [Section 5.4](#54-technology-stack-mapping) for the full technology mapping and [Section 6.1](#61-database-schema) for schema ownership detail.

### 5.2 Service Responsibilities

| Service | Responsibility | Owns Data |
|---|---|---|
| API Gateway | Routing, TLS termination, rate limiting, JWT validation, request/response logging. | None (stateless) |
| Auth Service | OAuth2 authorization server; issues and validates JWT access tokens; user/client credentials. | `user`, `client_credentials` |
| Wallet Service | Create wallets, hold balances per currency, execute concurrency-safe debit/credit/reserve operations, publish wallet events. | `wallet`, `wallet_reservation` |
| FX Rate Service | Ingest live FX rates, serve current rates, issue and manage short-lived rate locks via distributed locking. | `fx_rate`, `fx_rate_lock` |
| Conversion Orchestrator | Drive the SAGA state machine for convert-and-pay; issue commands to Wallet/FX/Payment; trigger compensation on failure; own idempotency. | `conversion_transaction`, `saga_step_log`, `outbox_event` |
| Merchant Payment Service | Execute payment against external acquirer; support refund (compensation) calls. | `merchant_payment` |
| Ledger Service | Record immutable double-entry ledger lines for every completed or compensated transaction; serve statements. | `ledger_entry` |
| Notification Service | Notify customer/ops of saga outcome (success, failure, compensated). | `notification_log` |

### 5.3 SAGA Orchestration Flow

The orchestration-based SAGA drives a currency conversion followed by a merchant payment, including both the happy path and the compensation path that runs automatically if the merchant payment is declined after the source wallet has already been debited and the FX rate locked.

```
Customer          Orchestrator        FX Rate Svc      Wallet Svc      Payment Svc      Ledger Svc
   │                    │                  │                │                │               │
   │ 1. POST /conversions                  │                │                │               │
   │  (Idempotency-Key) │                  │                │                │               │
   ├───────────────────►│                  │                │                │               │
   │                    │ 2. check idem key (Redis SETNX)    │                │               │
   │                    │──┐               │                │                │               │
   │                    │◄─┘               │                │                │               │
   │                    │ 3. lock rate     │                │                │               │
   │                    ├─────────────────►│                │                │               │
   │                    │ 4. rate.locked   │                │                │               │
   │                    │◄─────────────────┤                │                │               │
   │                    │ 5. debit source wallet             │                │               │
   │                    ├────────────────────────────────────►│               │               │
   │                    │ 6. wallet.debited                   │               │               │
   │                    │◄────────────────────────────────────┤               │               │
   │                    │ 7. charge merchant                                  │               │
   │                    ├─────────────────────────────────────────────────────►│               │
   │                    │                                                     │               │
   │        ┌───────────┴─────────────────  HAPPY PATH  ─────────────────────┴───────┐        │
   │        │ 8a. payment.completed                                                    │        │
   │        │◄────────────────────────────────────────────────────────────────────────┤        │
   │        │ 9a. credit dest wallet ──► wallet.credited                               │        │
   │        │ 10a. record double-entry ledger ─────────────────────────────────────────┼───────►│
   │        │ 11a. saga_state = COMPLETED                                              │        │
   │        └───────────────────────────────────────────────────────────────────────┘        │
   │        ┌───────────┴────────────  COMPENSATION PATH  ───────────────────────────┐        │
   │        │ 8b. payment.failed                                                       │        │
   │        │◄────────────────────────────────────────────────────────────────────────┤        │
   │        │ 9b. reverse source debit (at locked rate) ──► wallet.credited (reversal) │        │
   │        │ 10b. release FX rate lock                                                │        │
   │        │ 11b. record REVERSED ledger entry ───────────────────────────────────────┼───────►│
   │        │ 12b. saga_state = COMPENSATED                                            │        │
   │        └───────────────────────────────────────────────────────────────────────┘        │
```

**Design notes on the flow:**
- The idempotency check (step 2) happens before any state-changing call, so a retried request with the same `Idempotency-Key` short-circuits to the previously computed result instead of re-running the saga.
- The FX rate lock (steps 3–4) is acquired before the wallet debit so that the exact rate used for compensation math is fixed up-front — this avoids the classic bug of reversing a debit at a different (current) rate than the one originally used.
- Every orchestrator state transition is persisted to `conversion_transaction` and `saga_step_log` (via the Transactional Outbox pattern, [Section 6.6](#66-saga-state-machine)) before the corresponding command/event is sent, so an orchestrator crash mid-saga can resume exactly where it left off on restart.
- Compensating actions (reverse the debit, release the lock, record a `REVERSED` ledger entry) are themselves idempotent and safe to retry — a requirement covered in [Section 6.2](#62-concurrency-control-design).

### 5.4 Technology Stack Mapping

| Layer | Technology | Usage in this Platform |
|---|---|---|
| Languages | Java, TypeScript, SQL | Java 25 + Spring Boot for all backend services; TypeScript/React for customer and ops UI; SQL/PL-SQL for Oracle schema and stored logic. |
| Backend Framework | Spring Boot, Spring Security (OAuth2/JWT), Hibernate/JPA | REST controllers, service layer, JPA entities with `@Version` optimistic locking and `@Lock` pessimistic locking, JWT resource-server validation on every service. |
| Service Communication | REST (sync commands), Apache Kafka (async events) | Orchestrator issues synchronous commands to Wallet/FX/Payment; those services publish completion/failure events asynchronously on Kafka for durability and decoupling. |
| API Gateway | Spring Cloud Gateway | Central entry point, JWT pre-validation, routing, rate limiting. |
| Frontend | React.js, Redux, HTML5, CSS | Customer wallet & conversion UI; internal ops console for SAGA state visualization. |
| Data Store | Oracle DB (database-per-service) | `wallet_db`, `fxrate_db`, `saga_db`, `payment_db`, `ledger_db` — each owned exclusively by its service. |
| Cache / Coordination | Redis (Redisson client) | Distributed locks for FX rate locking, idempotency-key storage, short-TTL FX rate snapshot cache. |
| Messaging | Apache Kafka | SAGA event bus; topics partitioned by `walletId`/`transactionId` for per-aggregate ordering. |
| Containerization | Docker (Docker Compose for local, K8s-ready manifests for production) | Each microservice, Kafka, Redis, and Oracle run as containers; independent scaling per service. |
| Observability | Grafana + Prometheus | Saga state dashboards, lock-wait time, optimistic-lock retry rate, Kafka consumer lag, p99 latency. |

### 5.5 Non-Functional Requirements

| Category | Requirement |
|---|---|
| Consistency | Strong consistency within each service's local transaction (per-wallet row); eventual, SAGA-guaranteed consistency across the end-to-end conversion, with automatic compensation. |
| Availability | Target 99.95% for the API Gateway and core services; no single service outage should block the others (enforced by async Kafka decoupling). |
| Concurrency Correctness | Zero lost updates, zero double-spends, and zero duplicate executions under concurrent load — proven via the load-test harness in [Section 7](#7-concurrency-testing-strategy). |
| Latency | p99 < 3s for the full convert-and-pay control path, excluding external acquirer latency; FX rate reads < 50ms via cache. |
| Scalability | Each service scales horizontally and independently; Kafka partition count is the scaling unit for ordered, per-aggregate throughput. |
| Security | OAuth2/JWT on every inbound request; mTLS between internal services; secrets managed centrally, never in code/images. |
| Auditability | Every balance-changing event produces an immutable ledger entry and an outbox event; full transaction history is reconstructable from the audit log alone. |
| Resilience | Orchestrator recovers in-flight sagas after a crash/restart from persisted state; distributed locks use lease + auto-renewal to avoid silent expiry mid-operation. |

---

## 6. Low-Level Design (LLD)

### 6.1 Database Schema

Each service owns its schema exclusively; no service queries another service's tables directly. Cross-service data needed for a decision (e.g., the locked rate) is passed explicitly in commands/events, never fetched via a shared database join.

#### 6.1.1 `wallet_db`

**Table: `wallet`**

| Column | Type | Constraints |
|---|---|---|
| wallet_id | VARCHAR2(36) | PRIMARY KEY (UUID) |
| user_id | VARCHAR2(36) | NOT NULL, INDEX |
| currency | VARCHAR2(3) | NOT NULL — ISO 4217 code |
| balance | NUMBER(18,4) | NOT NULL, DEFAULT 0, CHECK (balance >= 0) |
| status | VARCHAR2(20) | NOT NULL — ACTIVE \| FROZEN \| CLOSED |
| version | NUMBER(10) | NOT NULL, DEFAULT 0 — JPA `@Version`, optimistic-lock column |
| created_at | TIMESTAMP | NOT NULL |
| updated_at | TIMESTAMP | NOT NULL |

Unique constraint: `(user_id, currency)` — one wallet per user per currency.

**Table: `wallet_reservation`**

| Column | Type | Constraints |
|---|---|---|
| reservation_id | VARCHAR2(36) | PRIMARY KEY (UUID) |
| wallet_id | VARCHAR2(36) | NOT NULL, FK → wallet.wallet_id |
| transaction_id | VARCHAR2(36) | NOT NULL, INDEX — SAGA transaction id |
| amount | NUMBER(18,4) | NOT NULL |
| status | VARCHAR2(20) | NOT NULL — HELD \| CAPTURED \| RELEASED |
| created_at | TIMESTAMP | NOT NULL |
| expires_at | TIMESTAMP | NOT NULL — safety TTL for orphaned holds |

#### 6.1.2 `fxrate_db`

**Table: `fx_rate`**

| Column | Type | Constraints |
|---|---|---|
| rate_id | VARCHAR2(36) | PRIMARY KEY |
| base_currency | VARCHAR2(3) | NOT NULL |
| quote_currency | VARCHAR2(3) | NOT NULL |
| rate | NUMBER(18,8) | NOT NULL |
| source | VARCHAR2(50) | NOT NULL — feed provider identifier |
| effective_at | TIMESTAMP | NOT NULL, INDEX |

**Table: `fx_rate_lock`**

| Column | Type | Constraints |
|---|---|---|
| lock_id | VARCHAR2(36) | PRIMARY KEY (UUID) |
| transaction_id | VARCHAR2(36) | NOT NULL, UNIQUE |
| base_currency | VARCHAR2(3) | NOT NULL |
| quote_currency | VARCHAR2(3) | NOT NULL |
| locked_rate | NUMBER(18,8) | NOT NULL |
| amount | NUMBER(18,4) | NOT NULL |
| status | VARCHAR2(20) | NOT NULL — ACTIVE \| CONSUMED \| RELEASED \| EXPIRED |
| expires_at | TIMESTAMP | NOT NULL — typically now() + 10s |
| created_at | TIMESTAMP | NOT NULL |

#### 6.1.3 `saga_db` (Conversion Orchestrator)

**Table: `conversion_transaction`**

| Column | Type | Constraints |
|---|---|---|
| transaction_id | VARCHAR2(36) | PRIMARY KEY (UUID) |
| user_id | VARCHAR2(36) | NOT NULL |
| source_wallet_id | VARCHAR2(36) | NOT NULL |
| dest_wallet_id | VARCHAR2(36) | NOT NULL |
| source_currency / dest_currency | VARCHAR2(3) | NOT NULL |
| source_amount / dest_amount | NUMBER(18,4) | NOT NULL |
| locked_rate | NUMBER(18,8) | NULLABLE until rate is locked |
| fx_lock_id | VARCHAR2(36) | NULLABLE — FK reference (logical, cross-service) |
| saga_state | VARCHAR2(30) | NOT NULL — see [Section 6.6](#66-saga-state-machine) state list |
| idempotency_key | VARCHAR2(80) | NOT NULL, UNIQUE |
| created_at / updated_at | TIMESTAMP | NOT NULL |

**Table: `saga_step_log`**

| Column | Type | Constraints |
|---|---|---|
| step_id | VARCHAR2(36) | PRIMARY KEY |
| transaction_id | VARCHAR2(36) | NOT NULL, FK → conversion_transaction, INDEX |
| step_name | VARCHAR2(50) | NOT NULL — e.g. RATE_LOCK, DEBIT, PAYMENT, COMPENSATE_DEBIT |
| status | VARCHAR2(20) | NOT NULL — SUCCESS \| FAILED \| COMPENSATED |
| payload | CLOB | Request/response snapshot for audit and replay |
| created_at | TIMESTAMP | NOT NULL |

**Table: `outbox_event`** (Transactional Outbox pattern)

| Column | Type | Constraints |
|---|---|---|
| event_id | VARCHAR2(36) | PRIMARY KEY |
| aggregate_id | VARCHAR2(36) | NOT NULL — transaction_id, INDEX |
| event_type | VARCHAR2(50) | NOT NULL |
| payload | CLOB | NOT NULL — serialized event |
| published | CHAR(1) | NOT NULL, DEFAULT 'N' |
| created_at | TIMESTAMP | NOT NULL |

#### 6.1.4 `payment_db`

**Table: `merchant_payment`**

| Column | Type | Constraints |
|---|---|---|
| payment_id | VARCHAR2(36) | PRIMARY KEY |
| transaction_id | VARCHAR2(36) | NOT NULL, UNIQUE, INDEX |
| merchant_id | VARCHAR2(36) | NOT NULL |
| amount | NUMBER(18,4) | NOT NULL |
| currency | VARCHAR2(3) | NOT NULL |
| acquirer_ref | VARCHAR2(64) | NULLABLE — external reference |
| status | VARCHAR2(20) | NOT NULL — PENDING \| COMPLETED \| FAILED \| REFUNDED |
| created_at / updated_at | TIMESTAMP | NOT NULL |

#### 6.1.5 `ledger_db`

**Table: `ledger_entry`**

| Column | Type | Constraints |
|---|---|---|
| entry_id | VARCHAR2(36) | PRIMARY KEY |
| transaction_id | VARCHAR2(36) | NOT NULL, INDEX |
| wallet_id | VARCHAR2(36) | NOT NULL, INDEX |
| entry_type | VARCHAR2(10) | NOT NULL — DEBIT \| CREDIT |
| amount | NUMBER(18,4) | NOT NULL |
| currency | VARCHAR2(3) | NOT NULL |
| balance_after | NUMBER(18,4) | NOT NULL |
| created_at | TIMESTAMP | NOT NULL |

Rows in `ledger_entry` are append-only / immutable (no `UPDATE` or `DELETE` at the application layer); a correction is always a new, offsetting entry. The service enforces that entries for a given `transaction_id` always net to zero across the involved wallets — the double-entry invariant.

### 6.2 Concurrency Control Design

This section is the technical core of the platform and maps each concurrency hazard identified in [Section 3.4](#34-why-concurrency-control-is-a-first-class-design-concern) to a specific, named mechanism.

#### 6.2.1 Wallet balance — Optimistic vs. Pessimistic Locking

- **Default path** (low-contention personal wallets): JPA optimistic locking via a `@Version` column on `wallet`. A concurrent update that lost the race throws `ObjectOptimisticLockingFailureException`; the service layer catches it and retries the debit up to N times with a small backoff before surfacing a conflict to the caller.
- **Hot-path** (high-contention wallets, e.g., a merchant settlement or platform-fee wallet hit thousands of times/sec): pessimistic locking via `@Lock(LockModeType.PESSIMISTIC_WRITE)` issuing `SELECT … FOR UPDATE`, with an explicit lock timeout (Oracle `SELECT FOR UPDATE WAIT n`) so a stalled request fails fast instead of piling up connections.
- **Decision rule** encoded in `WalletService`: wallets flagged `high_contention=true` (config-driven) use pessimistic locking; all others use optimistic locking with retry — giving the correctness guarantee everywhere while paying the pessimistic-lock cost only where it is actually needed.

#### 6.2.2 FX rate locking — Distributed Locking

- A conversion must use one fixed rate for its entire lifetime, even though the FX Rate Service refreshes rates roughly every second from multiple concurrent refresh threads.
- The rate lock itself is coordinated with a Redisson `RLock` keyed by the currency pair (`rate:lock:{base}:{quote}`) with a lease time and an auto-renewal watchdog, so a lock cannot silently expire mid-operation while the holding thread is still working, but also cannot be held forever if that thread crashes.
- Locally, within a single FX Rate Service instance, the in-memory rate cache is a `ConcurrentHashMap` guarded by a `ReadWriteLock`: many concurrent readers get the current snapshot without blocking each other, while the scheduled refresh thread takes the write lock only for the brief moment it swaps in a new snapshot.

#### 6.2.3 Idempotency — Safe Retries Under Concurrency

- Every state-changing request (initiate conversion, debit, merchant payment) carries a client-supplied `Idempotency-Key`.
- The orchestrator performs an atomic Redis `SETNX idem:{key} → "IN_PROGRESS"` (TTL 24h) before doing any work. If the key already exists, the second concurrent (or retried) request is short-circuited: if the first attempt already finished, the cached result is returned; if it is still in progress, the caller receives a `409`/`425` telling it to poll rather than re-submit.
- This closes the classic double-tap / network-retry race where two nearly-simultaneous identical requests would otherwise both pass validation and both execute.

#### 6.2.4 Ordered Event Processing — Kafka Partitioning

- All SAGA-relevant Kafka topics are partitioned by `walletId` (wallet-scoped events) or `transactionId` (saga-scoped events), guaranteeing that events for the same aggregate are always delivered, in order, to the same consumer thread.
- The orchestrator's `@KafkaListener` is configured with concurrency equal to the partition count, so throughput scales with partitions while per-aggregate ordering is preserved — critical, because processing a `PaymentCompleted` event before the `WalletDebited` event it depends on would corrupt the saga state.

#### 6.2.5 Parallel Independent Work — CompletableFuture

Where sub-steps are genuinely independent (e.g., fetching the current indicative rate and validating the destination wallet exists, before the saga's ordered steps begin), the orchestrator fires them concurrently via `CompletableFuture.supplyAsync()` against a bounded `ExecutorService` and joins with `CompletableFuture.allOf()`, rather than calling them sequentially — reducing control-path latency without weakening any consistency guarantee, since these particular calls are read-only.

#### 6.2.6 Connection Pool & Lock-Hold Discipline

HikariCP pool sizes are tuned per service based on expected concurrent lock-holding transactions; pessimistic-lock transactions are kept as short as possible (lock → mutate → commit, no external calls while the row lock is held) specifically to avoid pool exhaustion under contention.

### 6.3 Service Class Design

Representative classes and key method signatures per service. Getters/setters/DTOs are omitted for brevity.

#### 6.3.1 Wallet Service (port :8081)

| Class | Key Methods | Responsibility |
|---|---|---|
| `WalletController` | `createWallet()`, `getBalance(id)`, `debit(DebitRequest)`, `credit(CreditRequest)`, `reserve(ReserveRequest)`, `releaseReservation(id)` | REST entry points; requires `Idempotency-Key` header on write endpoints. |
| `WalletService` | `debit(walletId, amount, txnId)`, `credit(...)`, `reserveFunds(...)`, `captureReservation(...)`, `releaseReservation(...)` | Core business logic; selects optimistic vs. pessimistic lock strategy per Section 6.2.1. |
| `WalletRepository` | `findByIdForUpdate(id)` `[@Lock(PESSIMISTIC_WRITE)]`, `findById(id)`, `save(wallet)` | Spring Data JPA repository. |
| `WalletEventPublisher` | `publishDebited(event)`, `publishCredited(event)`, `publishDebitFailed(event)` | Writes to outbox / publishes to Kafka on local commit. |
| `WalletCompensationListener` | `onCompensateCredit(command)` | `@KafkaListener` — handles compensation commands from the orchestrator. |
| `IdempotencyGuard` | `checkAndReserve(key)`, `confirm(key, response)` | Redis-backed idempotency check shared library. |

#### 6.3.2 FX Rate Service (port :8082)

| Class | Key Methods | Responsibility |
|---|---|---|
| `FxRateController` | `getCurrentRate(base, quote)`, `lockRate(LockRequest)`, `releaseLock(lockId)` | REST entry points. |
| `FxRateService` | `getCurrentRate(pair)`, `lockRate(txnId, base, quote, amount)`, `consumeLock(lockId)`, `releaseLock(lockId)` | Business logic; delegates locking to `DistributedLockManager`. |
| `FxRateCache` | `get(pair)`, `refresh(pair, snapshot)` | `ConcurrentHashMap` + `ReadWriteLock` in-memory cache. |
| `RateRefreshScheduler` | `refreshRates()` `[@Scheduled(fixedRate=1000)]` | Pulls live rates from the external feed. |
| `DistributedLockManager` | `acquireLock(key, leaseMs)`, `releaseLock(key)` | Redisson `RLock` wrapper with watchdog auto-renewal. |

#### 6.3.3 Conversion Orchestrator (port :8083)

| Class | Key Methods | Responsibility |
|---|---|---|
| `ConversionController` | `initiateConversion(ConversionRequest)`, `getStatus(transactionId)` | Entry point that starts the SAGA. |
| `ConversionSagaOrchestrator` | `startSaga(request)`, `handleRateLocked(evt)`, `handleWalletDebited(evt)`, `handleDebitFailed(evt)`, `handlePaymentCompleted(evt)`, `handlePaymentFailed(evt)` | Drives the state machine; issues next command or triggers `compensate()`. |
| `SagaStateMachine` | `transition(currentState, event): nextState`, `isTerminal(state)` | Pure state-transition logic (Section 6.6), independently unit-testable. |
| `CompensationHandler` | `compensateDebit(txn)`, `releaseRateLock(txn)`, `recordCompensationLedger(txn)` | Executes the compensation sequence; every method is idempotent. |
| `SagaEventListener` | `onWalletEvent(evt)`, `onRateEvent(evt)`, `onPaymentEvent(evt)` | `@KafkaListener(concurrency=partitions)` — one thread per partition, ordered per `transactionId`. |
| `OutboxEventPublisher` | `saveAndPublish(event)` | Persists event + state in the same local transaction, then relays to Kafka (Transactional Outbox). |

#### 6.3.4 Merchant Payment Service (port :8084)

| Class | Key Methods | Responsibility |
|---|---|---|
| `MerchantPaymentController` | `pay(PaymentRequest)`, `refund(RefundRequest)` | REST entry points. |
| `MerchantPaymentService` | `processPayment(txnId, amount, ccy)`, `refundPayment(txnId)` | Orchestrates the acquirer call and local status update. |
| `AcquirerGatewayClient` | `charge(request)`, `refund(request)` | Resilience4j circuit-breaker-wrapped client to the external acquirer. |

#### 6.3.5 Ledger Service (port :8085)

| Class | Key Methods | Responsibility |
|---|---|---|
| `LedgerController` | `getStatement(walletId)`, `postEntries(PostEntriesRequest)` | REST entry points. |
| `LedgerService` | `recordDoubleEntry(debitWalletId, creditWalletId, amount, txnId)` | Writes matched debit/credit rows atomically. |
| `DoubleEntryValidator` | `validate(entries): boolean` | Enforces that entries for a transaction net to zero. |

### 6.4 REST API Contract (Selected Endpoints)

| Method & Path | Description | Concurrency Control Applied |
|---|---|---|
| `POST /api/v1/wallets` | Create a wallet for a user/currency. | Unique constraint `(user_id, currency)` |
| `GET /api/v1/wallets/{id}/balance` | Read current balance. | Read-only, no lock |
| `POST /api/v1/wallets/{id}/debit` (`Idempotency-Key` header) | Debit a wallet for a given transaction. | Optimistic (`@Version`) or pessimistic (`FOR UPDATE`) per wallet profile; idempotency key checked |
| `POST /api/v1/fx/rate-lock` | Lock a rate for a currency pair + amount for ~10s. | Redisson distributed lock per currency pair |
| `DELETE /api/v1/fx/rate-lock/{lockId}` | Release a rate lock (consumed or compensated). | Idempotent release |
| `POST /api/v1/conversions` (`Idempotency-Key` header) | Start the convert-and-pay SAGA. | Redis `SETNX` idempotency; drives SAGA state machine |
| `GET /api/v1/conversions/{transactionId}` | Poll SAGA status. | Read-only |
| `POST /api/v1/merchant-payments` | Execute a merchant charge (called by orchestrator). | Idempotent on `transaction_id` UNIQUE |
| `POST /api/v1/merchant-payments/{id}/refund` | Compensating refund (called by orchestrator). | Idempotent — no-op if already refunded |

### 6.5 Kafka Topic & Event Design

| Topic | Producer → Consumer | Partition Key | Purpose |
|---|---|---|---|
| `wallet.debited` / `wallet.debit.failed` | Wallet Svc → Orchestrator | `walletId` | Signals result of the source-wallet debit step. |
| `wallet.credited` | Wallet Svc → Orchestrator, Ledger Svc | `walletId` | Signals result of destination credit or compensating reversal. |
| `rate.locked` / `rate.lock.failed` | FX Rate Svc → Orchestrator | `transactionId` | Signals result of the FX rate lock step. |
| `payment.completed` / `payment.failed` | Merchant Payment Svc → Orchestrator | `transactionId` | Signals result of the merchant charge step. |
| `saga.completed` / `saga.compensated` | Orchestrator → Notification Svc, Audit | `transactionId` | Terminal saga outcome for downstream notification/audit. |

Consumer group `orchestrator-group` is configured with concurrency equal to each topic's partition count, ensuring strict per-transaction ordering while allowing horizontal scale-out of the orchestrator instances.

### 6.6 SAGA State Machine

| State | Meaning | Valid Next State(s) |
|---|---|---|
| `STARTED` | Saga created, idempotency key reserved. | `RATE_LOCKED`, `FAILED` |
| `RATE_LOCKED` | FX rate locked for this transaction. | `SOURCE_DEBITED`, `RATE_LOCK_FAILED` |
| `SOURCE_DEBITED` | Source wallet debited. | `PAYMENT_PENDING`, `DEBIT_FAILED` |
| `PAYMENT_PENDING` | Merchant payment call in flight. | `PAYMENT_COMPLETED`, `PAYMENT_FAILED` |
| `PAYMENT_COMPLETED` | Acquirer confirmed the charge. | `DEST_CREDITED` |
| `DEST_CREDITED` | Destination wallet credited. | `LEDGER_POSTED` |
| `LEDGER_POSTED` | Double-entry ledger rows written. | `COMPLETED` |
| `COMPLETED` | **Terminal** — saga succeeded. | — |
| `PAYMENT_FAILED` | Acquirer declined / errored. | `COMPENSATING` |
| `COMPENSATING` | Reversal in progress. | `SOURCE_CREDITED_BACK` |
| `SOURCE_CREDITED_BACK` | Debit reversed on source wallet. | `LOCK_RELEASED` |
| `LOCK_RELEASED` | FX rate lock released. | `COMPENSATED` |
| `COMPENSATED` | **Terminal** — saga safely reversed. | — |

`SagaStateMachine.transition()` rejects any transition not in this table, so an out-of-order or duplicate event (e.g., a re-delivered `PaymentCompleted` after the saga is already `COMPLETED`) is safely ignored rather than corrupting state — a second concurrency safeguard on top of Kafka's per-partition ordering.

---

## 7. Concurrency Testing Strategy

Correctness under concurrency is verified with a dedicated load-testing harness, not just asserted in code review.

- **Balance-correctness test** — fire N concurrent debit requests (e.g., 500 threads via a bounded `ExecutorService` + `CountDownLatch`) against a single wallet whose balance can only cover a subset of them; assert that exactly the mathematically correct number succeed and the final balance is exact, with zero lost updates and zero over-drafts.
- **Idempotency test** — fire the same request with the same `Idempotency-Key` from multiple concurrent threads; assert the underlying debit/payment executes exactly once and all callers receive the same response.
- **Rate-lock contention test** — fire concurrent conversion requests for the same currency pair while the background rate-refresh thread is running; assert every transaction's compensation math uses its own originally locked rate, never a rate that changed mid-flight.
- **Chaos / recovery test** — kill the orchestrator process mid-saga (after `SOURCE_DEBITED`, before `PAYMENT_PENDING` completes) and restart it; assert the recovery job resumes the saga from persisted state and reaches a correct terminal state (`COMPLETED` or `COMPENSATED`), never leaving it stuck.
- **Metrics captured** — lock wait time, optimistic-lock retry count, Kafka consumer lag, and end-to-end saga latency, all surfaced on Grafana dashboards so contention issues are visible before they become incidents.

**Tooling:** JMeter or Gatling for load generation; a lightweight Java `ConcurrencyLoadTest` harness for the balance-correctness and idempotency assertions, runnable in CI.

---

## 8. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Distributed (Redis) lock expires while the holder is still working. | Redisson watchdog auto-renews the lease while the operation is active; lease is only released explicitly or on failure. |
| Orchestrator crashes mid-saga. | Every state transition is persisted before its triggering event is sent (Outbox pattern); a recovery job scans non-terminal sagas on startup and resumes/compensates them. |
| Compensating action executed twice (e.g., re-delivered failure event). | All compensation methods are idempotent and check current `saga_state` before acting; `SagaStateMachine` rejects invalid/duplicate transitions. |
| Kafka consumer lag delays compensation, leaving a customer's funds appearing debited longer than expected. | Grafana alerting on consumer lag; partition count sized with headroom; customer-facing status endpoint reflects real-time saga state, not just a spinner. |
| Hot wallet (e.g., platform fee account) becomes a pessimistic-lock bottleneck under peak load. | Lock-hold time minimized (no external calls while locked); connection pool sized for worst-case contention; optional sharding of high-volume aggregate wallets. |
| Clock skew across nodes affects FX rate-lock TTL accuracy. | All nodes NTP-synchronized; TTL checks use a small safety buffer. |

---

## 9. Glossary

| Term | Definition |
|---|---|
| **SAGA** | A pattern for managing a distributed transaction as a sequence of local transactions, each with a defined compensating transaction to undo it if a later step fails. |
| **Orchestration-based SAGA** | A SAGA variant where a central orchestrator service explicitly directs each step and decides when to compensate, as opposed to choreography where services react to each other's events independently. |
| **Compensating Transaction** | An operation that semantically reverses a previously completed local transaction (e.g., a credit that reverses an earlier debit). |
| **Optimistic Locking** | A concurrency-control technique that detects conflicting concurrent updates at commit time via a version number, rather than blocking readers/writers up front. |
| **Pessimistic Locking** | A concurrency-control technique that acquires a row-level lock before reading/modifying data, blocking other transactions until it is released. |
| **Idempotency Key** | A client-supplied unique token that lets a service safely process a retried request exactly once. |
| **Transactional Outbox** | A pattern where a state change and the event announcing it are written in the same local transaction, then relayed to the message broker, guaranteeing no event is lost or published without its state change (and vice versa). |
| **Distributed Lock** | A lock coordinated across multiple service instances/processes (here via Redis/Redisson) rather than within a single process's memory. |

---

*— End of Document —*
