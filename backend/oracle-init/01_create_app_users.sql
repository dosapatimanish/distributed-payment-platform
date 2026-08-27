-- Runs once, on first container init. gvenzl/oracle-free executes
-- /container-entrypoint-initdb.d/*.sql against CDB$ROOT (not the app PDB) as SYS, with
-- "_ORACLE_SCRIPT" set - so a bare CREATE USER here would make a *common* user and the GRANTs
-- would land in the root container only (the app then hits ORA-01045 on the paymentdb service).
-- Switch into the PDB first so these are normal PDB-local users with PDB-local privileges.
ALTER SESSION SET CONTAINER = PAYMENTDB;

-- One Oracle user == one schema == one service. Each service connects as its own user
-- (jdbc:oracle:thin:@//host:1521/paymentdb, user=<svc>_app) and its Flyway migration builds
-- its tables in that user's schema, with its own flyway_schema_history. Table names never
-- collide across services anyway, but the schema boundary keeps them fully independent.
--
-- CONNECT grants CREATE SESSION; RESOURCE grants CREATE TABLE / SEQUENCE / INDEX / etc.
-- UNLIMITED TABLESPACE lets the schema actually store rows (RESOURCE alone gives no quota);
-- it's tablespace-name-independent, unlike QUOTA ... ON <name>.

CREATE USER wallet_app IDENTIFIED BY wallet_app_pw;
GRANT CONNECT, RESOURCE, UNLIMITED TABLESPACE TO wallet_app;

CREATE USER fxrate_app IDENTIFIED BY fxrate_app_pw;
GRANT CONNECT, RESOURCE, UNLIMITED TABLESPACE TO fxrate_app;

CREATE USER orchestrator_app IDENTIFIED BY orchestrator_app_pw;
GRANT CONNECT, RESOURCE, UNLIMITED TABLESPACE TO orchestrator_app;

CREATE USER payment_app IDENTIFIED BY payment_app_pw;
GRANT CONNECT, RESOURCE, UNLIMITED TABLESPACE TO payment_app;

CREATE USER ledger_app IDENTIFIED BY ledger_app_pw;
GRANT CONNECT, RESOURCE, UNLIMITED TABLESPACE TO ledger_app;
