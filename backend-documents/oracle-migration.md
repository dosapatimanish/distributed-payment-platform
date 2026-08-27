# Postgres → Oracle Migration — What Changed and What It Surfaced

Cross-cutting doc, not tied to one service. All five services (wallet-service, fx-rate-service,
conversion-orchestrator, merchant-payment-service, ledger-service) moved from PostgreSQL 16 to
**Oracle Database Free 23ai** (`gvenzl/oracle-free:23-slim`) in one pass. The design doc always
named Oracle as the target (§6.1); the schema had been kept "Oracle-portable" from the start
(app-generated `VARCHAR` ids, no Postgres-native `uuid`/`jsonb`, no `::text` casts), so this was
a config + dialect + DDL-dialect change, not a rewrite.

**Deployment shape: one shared Oracle instance, schema-per-service** — not a container per
service. Postgres ran one lightweight `postgres:16-alpine` per service (5 containers, ~1 GB RAM
total). Five `gvenzl/oracle-free` instances need ~8–10 GB RAM and OOM'd Docker Desktop on a
normal dev laptop on the first `docker compose up`. So the platform now runs a single
`platform-oracle` container (host port `1521`, one pluggable DB `paymentdb`, ~2 GB), and
`backend/oracle-init/01_create_app_users.sql` creates one Oracle user per service
(`wallet_app`, `fxrate_app`, `orchestrator_app`, `payment_app`, `ledger_app`). Each service
connects as its own user, so it still gets an isolated schema and its own
`flyway_schema_history` — the database-per-service boundary is preserved as a schema boundary,
which is the standard way to run Oracle in dev.

## What changed

| Area | Before | After |
|---|---|---|
| DB image | `postgres:16-alpine`, one per service | `gvenzl/oracle-free:23-slim`, **one shared** |
| Compose service(s) | `wallet-postgres` … `ledger-postgres` (5) | `platform-oracle` (1) |
| Host port(s) | `5432`, `5435`, `5436`, `5437`, `5438` | `1521` (shared) |
| Container port | `5432` | `1521` |
| Volume(s) | `*_postgres_data` → `/var/lib/postgresql/data` (5) | `platform_oracle_data` → `/opt/oracle/oradata` (1) |
| Healthcheck | `pg_isready` | login as `ledger_app` against `paymentdb` (not gvenzl's `healthcheck.sh` — see Operational notes / Issue 5), `start_period: 120s`, `retries: 40` |
| JDBC URL | `jdbc:postgresql://host:5432/<svc>_db` | `jdbc:oracle:thin:@//host:1521/paymentdb` (service-name form; `paymentdb` is the pluggable DB = compose's `ORACLE_DATABASE`) — same for all 5, they differ only by user |
| Per-service isolation | separate database + container | separate Oracle user/schema in the one PDB (created by `backend/oracle-init/01_create_app_users.sql`) |
| Driver | `org.postgresql:postgresql` | `com.oracle.database.jdbc:ojdbc11` (+ `spring.datasource.driver-class-name=oracle.jdbc.OracleDriver`) |
| Flyway per-DB module | `org.flywaydb:flyway-database-postgresql` | `org.flywaydb:flyway-database-oracle` |
| Testcontainers module | `org.testcontainers:testcontainers-postgresql` | `org.testcontainers:testcontainers-oracle-free` |
| Testcontainers class | `org.testcontainers.postgresql.PostgreSQLContainer` | `org.testcontainers.oracle.OracleContainer` |
| Hibernate `ddl-auto` | `validate` (unchanged) | `validate` (unchanged) |

Credentials are unchanged (`<svc>_app` / `<svc>_app_pw`); the users are created by the init
script (`GRANT CONNECT, RESOURCE, UNLIMITED TABLESPACE` each). `ORACLE_PASSWORD` (SYS/SYSTEM) is
`platform_admin_pw`, only used for admin access, never by a service. `APP_USER` is deliberately
**not** set on the container — that makes the init `.sql` run as `SYS` against `CDB$ROOT`, so the
script itself switches into the PDB (`ALTER SESSION SET CONTAINER = PAYMENTDB`) before creating
the users — see Issue 5.

### Connection details

Shared instance facts (local dev — these credentials are throwaway, they live in
`backend/docker-compose.yml` and `backend/oracle-init/01_create_app_users.sql`):

| | Value |
|---|---|
| Image | `gvenzl/oracle-free:23-slim` (Oracle Database Free 23ai) |
| Compose service / container name | `platform-oracle` |
| Host (from the machine) | `localhost:1521` |
| Host (from another container on the compose network) | `platform-oracle:1521` |
| Service name (the pluggable DB) | `paymentdb` (uppercased internally to `PAYMENTDB`) |
| CDB name | `FREE` |
| Data volume | `backend_platform_oracle_data` → `/opt/oracle/oradata` |
| Admin | `SYSTEM` / `platform_admin_pw` (also `SYS` as SYSDBA, same password) — connect to `paymentdb` for PDB-level admin, or `FREE` for the CDB |

Per-service application user (**user name = schema name**, all in the one `PAYMENTDB` PDB):

| Service | User / schema | Password | JDBC URL (host) | JDBC URL (in-container) |
|---|---|---|---|---|
| wallet-service | `wallet_app` | `wallet_app_pw` | `jdbc:oracle:thin:@//localhost:1521/paymentdb` | `jdbc:oracle:thin:@//platform-oracle:1521/paymentdb` |
| fx-rate-service | `fxrate_app` | `fxrate_app_pw` | `jdbc:oracle:thin:@//localhost:1521/paymentdb` | `jdbc:oracle:thin:@//platform-oracle:1521/paymentdb` |
| conversion-orchestrator | `orchestrator_app` | `orchestrator_app_pw` | `jdbc:oracle:thin:@//localhost:1521/paymentdb` | `jdbc:oracle:thin:@//platform-oracle:1521/paymentdb` |
| merchant-payment-service | `payment_app` | `payment_app_pw` | `jdbc:oracle:thin:@//localhost:1521/paymentdb` | `jdbc:oracle:thin:@//platform-oracle:1521/paymentdb` |
| ledger-service | `ledger_app` | `ledger_app_pw` | `jdbc:oracle:thin:@//localhost:1521/paymentdb` | `jdbc:oracle:thin:@//platform-oracle:1521/paymentdb` |

The JDBC URL is identical for all five — they differ only by `spring.datasource.username` /
`spring.datasource.password` (in each service's `application.properties`; the compose file
overrides only the URL via `SPRING_DATASOURCE_URL`). Each user has its own tables **and** its own
`flyway_schema_history` in its own schema.

Tables per schema:

| Schema | Tables |
|---|---|
| `wallet_app` | `wallet`, `wallet_reservation` |
| `fxrate_app` | `fx_rate`, `fx_rate_lock` |
| `orchestrator_app` | `conversion_transaction`, `saga_step_log` |
| `payment_app` | `merchant_payment` |
| `ledger_app` | `ledger_entry` |

### Connecting by hand

**Easy Connect string** (SQL Developer, DBeaver, JDBC): host `localhost`, port `1521`, service
name `paymentdb`; or as one string `localhost:1521/paymentdb`.

```bash
# sqlplus inside the container (no client install needed) - as a service user:
docker exec -it platform-oracle sqlplus wallet_app/wallet_app_pw@//localhost:1521/paymentdb

# ... or as admin:
docker exec -it platform-oracle sqlplus system/platform_admin_pw@//localhost:1521/paymentdb

# from the host, if you have sqlplus / sqlcl locally:
sqlplus ledger_app/ledger_app_pw@//localhost:1521/paymentdb
```

Quick checks:

```sql
-- who am I / which container
SELECT USER, SYS_CONTEXT('USERENV','CON_NAME') FROM dual;

-- this schema's tables and Flyway state
SELECT table_name FROM user_tables ORDER BY table_name;
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;

-- all five app users (run as SYSTEM) - should all be COMMON=NO, CON_ID = the PDB's
SELECT username, common, con_id FROM cdb_users WHERE username LIKE '%\_APP' ESCAPE '\' ORDER BY username;
```

### DDL type mapping (all 5 `db/migration/V1__init.sql`)

| Postgres | Oracle | Note |
|---|---|---|
| `VARCHAR(n)` | `VARCHAR2(n)` | |
| `NUMERIC(p,s)` | `NUMBER(p,s)` | money columns unchanged in precision/scale |
| `BIGINT` (the `@Version` long) | `NUMBER(19)` | |
| `BOOLEAN` (`wallet.high_contention`) | `BOOLEAN` | Oracle 23ai native SQL `BOOLEAN` — see Issue 1 |
| `OID` (`saga_step_log.payload`, a `@Lob String`) | `CLOB` | see Issue 2 |
| `TIMESTAMP(6) WITH TIME ZONE` | `TIMESTAMP(6) WITH TIME ZONE` | unchanged; Hibernate maps the same type code to Oracle's own `TIMESTAMP WITH TIME ZONE` |
| inline `CHECK (... IN (...))`, `UNIQUE`, `PRIMARY KEY`, `CREATE INDEX` | identical | Oracle accepts all of these as written |

## Issues found / addressed during the migration

The migration ran clean at the code level — all five repository integration suites passed on
Oracle on the first run, no runtime regressions. But seven things had to be handled deliberately
to *keep* it clean; each produced a startup failure, an OOM, a wedged Docker engine, or a latent
inconsistency the first time. Issues 0, 5 and 6 were hit bringing the shared stack up for real
(not caught by the Testcontainers suite, which never touches docker-compose or the gvenzl init
mechanism). They are recorded here the same way the Spring Boot 4.x gotchas are in each
service's own notes.

### Issue 0 — five `gvenzl/oracle-free` instances OOM Docker Desktop

The first cut of this migration kept database-per-service literally: one
`gvenzl/oracle-free:23-slim` container per service, host ports `1521`–`1525`. `docker compose
up` then tried to bring all five up at once. Each Oracle Free instance wants ~1.5–2 GB RAM
(SGA + PGA + processes); five in parallel plus the five app containers, Kafka, Redis,
Prometheus and Grafana pushed the Docker Desktop VM past its memory limit. The engine returned
`500 Internal Server Error` on every call and `docker compose` reported
`dependency <svc>-oracle failed to start` for whichever instance lost the race — not a config
error, resource exhaustion.

**The wedged engine does not self-heal.** Once the VM OOMs, every `docker` / `docker compose`
call keeps returning `500 Internal Server Error ... check if the server supports the requested
API version` (misleading — it's not a version problem). Retrying the command does nothing. The
fix is a hard restart of the backend: quit Docker Desktop from the tray, `wsl --shutdown` in
PowerShell, kill any leftover `com.docker.backend.exe` in Task Manager, reopen Docker Desktop
and wait for "Engine running". A full Windows reboot if even that doesn't take. Bump Docker
Desktop's memory (Settings → Resources) to **≥ 8 GB** before retrying — one Oracle + Kafka + 5
Spring services + Prometheus/Grafana still needs headroom.

**Resolution**: collapse to one shared `platform-oracle` container (see the top of this doc).
One instance is ~2 GB and starts once. `backend/oracle-init/01_create_app_users.sql` (mounted
at `/container-entrypoint-initdb.d`, runs once on first init — see Issue 5 for *which container*
it actually runs against) creates the five users. Every service's `SPRING_DATASOURCE_URL` is now
`jdbc:oracle:thin:@//platform-oracle:1521/paymentdb` (host: `//localhost:1521/paymentdb`); they
differ only by `spring.datasource.username`.

### Issue 1 — `wallet.high_contention`: Oracle 23ai native `BOOLEAN`, not `NUMBER(1)`

The reflexive Oracle mapping for a Java `boolean` is `NUMBER(1)` (that was the only option on
Oracle ≤21, and most migration guides still say it). Using it here **fails `ddl-auto=validate`
at startup**: Spring Boot 4.1.1 ships Hibernate 7, which detects an Oracle 23ai connection and
resolves the version-specific dialect — and that dialect expects the *native* SQL `BOOLEAN`
type (new in 23ai) for a `boolean` field, not `NUMBER(1)`. A `NUMBER(1)` column then reports
back as `NUMERIC`, Hibernate expects `BOOLEAN`, and the context refuses to start with a
schema-validation error on `wallet.high_contention`.

**Resolution**: the migration declares `high_contention BOOLEAN`. This works because
`gvenzl/oracle-free:23-slim` *is* 23ai and the Testcontainers image is the same — runtime and
test agree. It would **not** work against Oracle 19c/21c; a downgrade would also need this
column changed to `NUMBER(1)` **and** `@JdbcTypeCode(SqlTypes.BOOLEAN)` or a dialect override on
the entity. Verified by `WalletRepositoryIntegrationTest` (3 tests) loading the context against
a real 23ai container — context load *is* the `validate` check.

### Issue 2 — `saga_step_log.payload`: the Postgres migration said `OID`, the schema notes said `CLOB`

`SagaStepLog.payload` is a `@Lob String`. On Postgres, Hibernate's default `@Lob` + `String`
mapping is `OID` — a **large-object reference**, not inline text: the string lives in
`pg_largeobject`, the column holds only a loid, and reads need the LO API inside the same
transaction. conversion-orchestrator-implementation.md's "Schema notes" had always *described*
this column as a `CLOB`/`@Lob` holding the text directly; the Postgres `V1__init.sql` actually
created it as `OID`. That gap was a latent bug waiting for anyone who queried the column outside
a transaction or dumped/restored the DB and lost the large objects.

**Resolution**: on Oracle, `@Lob String` maps to `CLOB` — inline character data, exactly what
the schema notes always claimed. The Oracle `V1__init.sql` declares `payload CLOB`, and the
prose and the schema now match. No entity change needed. Covered by
`ConversionTransactionRepositoryIntegrationTest` (context load + round-trip).

### Issue 3 — Flyway silently doesn't run without the per-database module

Same trap already documented for Postgres in wallet-service-implementation.md ("Flyway
autoconfiguration… `flyway-database-postgresql` is still needed alongside it"). Flyway 10+ split
per-database support out of `flyway-core`; with only `flyway-core` on the classpath Flyway
**starts, finds no dialect handler, and no-ops** — no log line, no `flyway_schema_history`
table — and then `ddl-auto=validate` fails against an empty schema with a confusing "missing
table" error that says nothing about Flyway. Swapping `flyway-database-postgresql` →
`flyway-database-oracle` (not just removing the Postgres one) is mandatory, not cosmetic.
Confirmed present in all 5 poms; confirmed running by the "Successfully applied 1 migration to
schema … now at version v1" log line on every service's first Oracle startup.

### Issue 4 — `@SpringBootTest` context tests need a live DB; the failure mode changed, not the requirement

`WalletServiceApplicationTests`, `PingControllerTest`, and the equivalent
`*ApplicationTests` in the other services are plain `@SpringBootTest` — **no Testcontainers**,
no `@ServiceConnection`. They have always required a real database listening on the configured
port to pass (they load the full context, which builds the datasource + runs Flyway +
`validate`). Before the migration, running `./mvnw test` with no Postgres up failed them with
`Connection refused … :5432`. After, the same tests fail with `ORA-12541: Cannot connect. No
listener at host localhost port 1521.` if the `platform-oracle` container is not up (and its
init script must have created the service's user). **This is not a regression** — it is the
same pre-existing "these tests need the DB" condition pointing at a new port. Bringing up
`platform-oracle` first makes the full suite green (verified: wallet-service `./mvnw test` =
57/57). The Testcontainers repository integration tests (Pattern 6) are self-contained and need
only Docker.

### Issue 5 — gvenzl runs `container-entrypoint-initdb.d/*.sql` against `CDB$ROOT`, not the app PDB

`backend/oracle-init/01_create_app_users.sql` first ran without `ALTER SESSION SET CONTAINER`.
gvenzl executes init `.sql` files as `SYS` **against the root container** (`FREE`), not against
`ORACLE_DATABASE`'s PDB, and it sets `"_ORACLE_SCRIPT"=true` so a bare `CREATE USER wallet_app`
succeeds there instead of erroring on the missing `C##` prefix. Result:

- the five users were created as **common** users (`cdb_users` showed `COMMON=YES`, `CON_ID=1`),
  visible in every container including `PAYMENTDB` — so a login attempt got far enough to fail
  with `ORA-01045: user WALLET_APP does not have CREATE SESSION privilege` rather than
  `ORA-01017` (which is the tell: the user exists but has no privileges *in this container*);
- `GRANT CONNECT, RESOURCE, UNLIMITED TABLESPACE TO wallet_app` also ran in the root and,
  without `CONTAINER=ALL`, granted **only in the root** — nothing in `PAYMENTDB`. `sqlplus`
  still printed `Grant succeeded`, so the log looked fine.

Every service then died at Hikari datasource init connecting to `//…/paymentdb` with
`ORA-01045`.

**Resolution**: prepend `ALTER SESSION SET CONTAINER = PAYMENTDB;` to the init script. `SYS`
can switch container, and `CREATE USER` / `GRANT` after that are normal PDB-local operations, so
the five become ordinary local users with local privileges in the one PDB the services actually
connect to. Diagnose a recurrence with
`SELECT username, common, con_id FROM cdb_users WHERE username LIKE '%\_APP' ESCAPE '\'` — all
rows should be `COMMON=NO` in the PDB's `con_id`.

### Issue 6 — rebuild the service images after the pom change

`docker compose up -d` (no `--build`) reuses whatever image already exists. The five images had
been built in an earlier session with the **Postgres** poms, so the running containers still had
`org.postgresql:postgresql` on the classpath and no `ojdbc11` — every service crashed at startup
with `java.lang.ClassNotFoundException: oracle.jdbc.OracleDriver` (thrown from
`HikariConfig.setDriverClassName`, wrapped up through Flyway's `dataSource` bean). The pom edit
(`postgresql` → `com.oracle.database.jdbc:ojdbc11`, `flyway-database-postgresql` →
`flyway-database-oracle`) only takes effect once the image is rebuilt.

**Resolution**: `docker compose build` (or `docker compose up -d --build`) after any change to a
service's `pom.xml` or `src/`. Plain `docker compose up -d` is only safe for config-only changes
(the app services read DB URL / Redis host / Kafka bootstrap from environment, so those don't
need a rebuild — the driver on the classpath does).

## Verification performed

All five repository integration suites re-run against `gvenzl/oracle-free:23-slim` (each spins
its own `OracleContainer`, Flyway migrates `V1__init.sql`, Hibernate `validate` runs on context
load):

| Suite | Tests | Result |
|---|---|---|
| `WalletRepositoryIntegrationTest` | 3 | pass |
| `FxRateLockRepositoryIntegrationTest` | 3 | pass |
| `ConversionTransactionRepositoryIntegrationTest` | 3 | pass |
| `MerchantPaymentRepositoryIntegrationTest` | 3 | pass |
| `LedgerEntryRepositoryIntegrationTest` | 3 | pass |

Each suite asserts the real Oracle schema round-trips: `@Version`/timestamp population on
`save()`, a genuine `ORA-00001` on the `UNIQUE` constraints (`uk_wallet_user_currency`,
`uk_fx_rate_lock_transaction_id`, `uk_conversion_transaction_idempotency_key`,
`uk_merchant_payment_transaction_id`), and `NUMBER(18,4)` precision on a `findById` read.

wallet-service's full suite (57 tests, incl. the two `@SpringBootTest` context tests) also ran
green against an earlier per-service `wallet-oracle` — Flyway applied V1 to schema `WALLET_APP`,
context loaded, `validate` passed against Oracle 23.26.

**Full stack, shared instance, `docker compose up -d --build`** (after Issues 0/5/6 were
fixed): `platform-oracle` healthy; all 5 services started (~28–30 s each), each logging
`Database: jdbc:oracle:thin:@//platform-oracle:1521/paymentdb (Oracle 23.26)` then
`Successfully applied 1 migration to schema "<SVC>_APP", now at version v1` — `WALLET_APP`,
`FXRATE_APP`, `ORCHESTRATOR_APP`, `PAYMENT_APP`, `LEDGER_APP` — with `ddl-auto=validate`
passing on every one. `POST /api/v1/wallets` with an `Idempotency-Key` returned `201` with
`createdAt`/`updatedAt` populated; re-sending the same key replayed the identical body
(Redis-backed guard + real Oracle write, end to end).

## Operational notes

- `gvenzl/oracle-free:23-slim` is a **~1 GB image**; the single `platform-oracle` instance
  takes **~2–4 min** to reach `healthy` on first boot (create PDB → run init script → open),
  hence `start_period: 120s` / `retries: 40` on the healthcheck and
  `depends_on: condition: service_healthy` on every app service. Subsequent boots off the
  populated volume are faster. Do **not** re-introduce a container per service — see Issue 0.
- The `platform-oracle` healthcheck is **not** gvenzl's `healthcheck.sh` — that goes green as
  soon as the CDB opens, which on first boot is *before* the init script has created the users,
  so an app container could start and hit `ORA-01045`. It's replaced with a login as
  `ledger_app` (the last user the script creates) against the `paymentdb` service, so
  `service_healthy` genuinely means "users exist and the app can connect".
- The init script `backend/oracle-init/01_create_app_users.sql` runs **only on first init**
  (empty `platform_oracle_data` volume). If you change it, `docker compose down -v` (or
  `docker volume rm backend_platform_oracle_data`) to force a re-init, or apply the change by
  hand (`sqlplus system/…@//localhost:1521/paymentdb`).
- **Rebuild after any `pom.xml` / `src/` change**: `docker compose up -d --build`. Plain
  `up -d` reuses the stale image — see Issue 6.
- The first `./mvnw test` that hits Pattern 6 pulls `gvenzl/oracle-free:23-slim` — one-time,
  slow. CI needs Docker and the disk headroom for it.
- After switching compose files, old `wallet-postgres` … `ledger-postgres` containers and
  `backend_*_postgres_data` volumes (and any `backend_*_oracle_data` from the first
  per-service Oracle cut) are orphaned. Clean with `docker compose down --remove-orphans` and
  `docker volume rm` the stale ones.

## Related docs

- `backend/docker-compose.yml` — the single `platform-oracle` service; `backend/oracle-init/` — the user-creation script.
- Each `backend-documents/<service>-implementation.md` — "Oracle migration" pointer + updated
  schema notes / run instructions / port.
- `backend-documents/testing-guide.md` — Pattern 6, updated for `OracleContainer`.
- `../documents/Multi-Currency-Wallet-FX-Platform-Design-Document.md` §6.1 — the original schema,
  which named Oracle as the target from the start.
