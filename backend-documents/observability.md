# Observability (Grafana + Prometheus) — Concept, Rationale, and Implementation

Cross-cutting concept doc, not tied to one service — all five services expose the same
Micrometer/Actuator metrics endpoint the same way, and one shared Prometheus + Grafana pair
(in `backend/docker-compose.yml`) scrapes and visualizes all five (same "cross-cutting, own
doc" approach as `idempotency.md` and `kafka-events.md`).

## What this is

Design doc §5.4 lists Grafana + Prometheus as the platform's observability layer, backed by
Spring Boot Actuator + Micrometer. This pass wires that up end to end:

- Every service exposes `/actuator/prometheus` (Micrometer's Prometheus registry), scraped every
  15s by a Prometheus container.
- A Grafana container, pointed at that Prometheus, auto-provisions one dashboard
  (`platform-overview.json`) on startup — no manual datasource/dashboard setup, just open it.
- Three custom, hand-instrumented metrics fill in the NFR-specific gaps default HTTP/JVM metrics
  don't cover (see below) — the design doc names "saga-state dashboards, lock-wait time,
  optimistic-lock retry rate" specifically, and generic request-count/latency metrics don't
  answer any of those on their own.

## Why this matters here specifically

A distributed SAGA spanning five services is exactly the kind of system where "is it actually
working, and where is it slow/contended right now" can't be answered by looking at any one
service's logs in isolation. The three custom metrics below were chosen because they're each a
direct answer to a concurrency-control question this platform's own design doc treats as a
first-class concern (§3.4) — not generic infrastructure metrics, but the actual hazards this
platform was built to handle correctly:

| Metric | Type | Where | What it answers |
|---|---|---|---|
| `saga_state_transitions_total{state=...}` | Counter | conversion-orchestrator, `ConversionService.transition` | Is the saga funnel healthy - are conversions actually reaching `COMPLETED`, or piling up at a compensation state? |
| `wallet_optimistic_lock_retries_total` | Counter | wallet-service, `WalletService.applyWithOptimisticRetry` | How contended are wallets right now - is the optimistic-locking default path (design doc §6.2.1) actually cheap, or should more wallets be flagged `highContention`? |
| `fxrate_lock_wait_time_seconds{quantile=...}` | Summary (client-side p50/p95/p99) | fx-rate-service, `FxRateService.lockRate` | How long does acquiring the rate-lock-creation mutex actually take under load? |

Plus, free from Micrometer/Actuator with no code changes: HTTP request rate and p50/p95/p99
latency per service (`http_server_requests_seconds_*`, percentile-histogram enabled), and JVM
heap usage per service (`jvm_memory_used_bytes`).

## Where each metric is recorded (file by file)

- [ConversionService.java](../backend/conversion-orchestrator/src/main/java/com/paymentplatform/orchestrator/service/ConversionService.java) — `transition()` is the single choke-point every saga state change in this service goes through (see its own javadoc), so instrumenting it there covers the whole state machine in one place, both happy-path and compensation transitions.
- [WalletService.java](../backend/wallet-service/src/main/java/com/paymentplatform/wallet/service/WalletService.java) — `applyWithOptimisticRetry`'s `catch (ObjectOptimisticLockingFailureException)` block increments once per lost race, whether that attempt goes on to retry or exhausts into `WalletConflictException`.
- [FxRateService.java](../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/service/FxRateService.java) — a `Timer.Sample` wraps the whole `doLockRate` call (mutex-acquisition retry loop + critical section), recorded in a `finally` block so both a successful lock and an exhausted-attempts failure count.

## Why a client-side Summary for lock-wait time, not a histogram

`http.server.requests` uses a full Prometheus histogram (`percentiles-histogram`, i.e. `_bucket`
series + `histogram_quantile()` in queries) - the right choice there because volume is high and
queries need to combine buckets across many, unpredictable label combinations (`uri`, `status`,
`method`...) after the fact. `fxrate.lock.wait.time` is a single, low-cardinality metric with a
tiny expected sample count, so a client-side computed Summary
(`management.metrics.distribution.percentiles.fxrate.lock.wait.time=0.5,0.95,0.99`, in
fx-rate-service's `application.properties`) is simpler to configure and graph directly
(`fxrate_lock_wait_time_seconds{quantile="0.95"}`) without a `histogram_quantile()` wrapper -
correct tradeoff at this metric's actual scale, not the right default for every metric in this
platform.

## Prometheus scrape setup

`backend/observability/prometheus/prometheus.yml` — one `static_configs` job per service,
targeting `host.docker.internal:<port>/actuator/prometheus`. Services run on the **host** via
`./mvnw spring-boot:run` (see each service's "How to run it locally"), not as their own
docker-compose containers, so the Prometheus container reaches them via Docker's host-gateway
DNS name rather than a compose service name. `docker-compose.yml`'s `prometheus` service adds
`extra_hosts: host.docker.internal:host-gateway` so this also resolves on Linux, not just Docker
Desktop (which provides it built in on Windows/Mac).

## Grafana provisioning

Fully auto-provisioned, no manual setup — `backend/observability/grafana/`:

- `provisioning/datasources/datasource.yml` — the Prometheus datasource, fixed `uid: prometheus`
  (so the dashboard JSON can reference it deterministically instead of Grafana's auto-generated
  uid), `isDefault: true`.
- `provisioning/dashboards/dashboards.yml` — tells Grafana to load every dashboard JSON from
  `/var/lib/grafana/dashboards` (mounted from `grafana/dashboards/`) on startup.
- `dashboards/platform-overview.json` — the one dashboard this pass ships: HTTP request rate and
  p99 latency per service, saga state transitions, wallet optimistic-lock retry rate, FX
  lock-wait time (p50/p95/p99), JVM heap per service.
- `docker-compose.yml`'s `grafana` service also sets `GF_AUTH_ANONYMOUS_ENABLED=true` (Viewer
  role) so the dashboard is viewable without logging in - `admin`/`admin` still works for the
  full editor if needed (change the password in a real deployment; this is local dev only, no
  different from every other service's plaintext local credentials in this platform).

## How to run it locally

```bash
cd backend
docker compose up -d prometheus grafana
# ... plus the usual Postgres/Redis/Kafka containers and all 5 services, see the root README
```

- Prometheus UI: http://localhost:9090 (Status → Targets should show all 5 services `UP`)
- Grafana: http://localhost:3000 (anonymous viewer works; `admin`/`admin` for editing) → the
  "Distributed Payment Platform - Overview" dashboard is already there.

## Verification performed

All done manually against the real containers and all 5 real services running locally:

1. **Scrape endpoints**: `curl localhost:808{1..5}/actuator/prometheus` on all 5 services -
   each returned Micrometer's Prometheus text format, `application` label present on every
   series (from `management.metrics.tags.application`).
2. **Prometheus targets**: `GET /api/v1/targets` - all 5 jobs (`wallet-service`,
   `fx-rate-service`, `conversion-orchestrator`, `merchant-payment-service`, `ledger-service`)
   showed `"health":"up"`.
3. **Custom metrics, real traffic**: created two wallets, ran two real conversions through the
   orchestrator, a couple of plain wallet calls. Then queried Prometheus directly:
   - `saga_state_transitions_total` - `RATE_LOCKED`/`SOURCE_DEBITED`/`DEST_CREDITED`/`COMPLETED`
     each at exactly `2` (two conversions run), correctly labeled by `state`.
   - `sum by (application) (http_server_requests_seconds_count)` - non-zero, correctly split per
     service.
   - `fxrate_lock_wait_time_seconds{quantile=...}` - real p50/p95/p99 values present.
4. **Grafana provisioning**: `GET /api/health` - `200`; `GET /api/search?query=platform` found
   the provisioned dashboard by its known `uid`; `GET /api/datasources` (basic auth) confirmed
   the Prometheus datasource registered with `uid: prometheus`, matching the dashboard JSON's
   references.
5. **Dashboard panel query, through Grafana itself** (not just Prometheus directly): queried
   Grafana's datasource proxy with the exact same PromQL the "HTTP Request Rate" panel uses -
   got back correctly-shaped, non-empty, per-`application` results, proving the full path
   (dashboard → Grafana → provisioned datasource → Prometheus → real scraped metrics) works end
   to end, not just that Prometheus itself has data.

## What's next

- More dashboards/panels as new NFR-relevant metrics get added (e.g. once Kafka consumers exist,
  consumer-lag panels).
- Alerting rules (Prometheus Alertmanager or Grafana-native) - not built, this pass is
  dashboards-only.
- Real credentials/auth on Grafana for anything beyond local dev.

## Related docs

- `wallet-service-implementation.md` / `fx-rate-service-implementation.md` /
  `conversion-orchestrator-implementation.md` — each has a short "Observability" pointer back
  here rather than repeating this doc's content.
