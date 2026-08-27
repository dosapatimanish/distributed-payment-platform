-- Baseline schema (design doc §6.1.3), Oracle Database Free 23ai dialect. Mirrors the
-- ConversionTransaction/SagaStepLog entity mappings exactly - ddl-auto=validate checks them
-- against this at startup. saga_step_log.payload is CLOB - Hibernate's mapping for the @Lob
-- String field on Oracle (was Postgres OID before the DB switch; CLOB is the natural fit here
-- and drops the large-object-reference caveat the OID mapping carried).

CREATE TABLE conversion_transaction (
    transaction_id   VARCHAR2(36)                NOT NULL,
    user_id          VARCHAR2(255)               NOT NULL,
    source_wallet_id VARCHAR2(255)               NOT NULL,
    dest_wallet_id   VARCHAR2(255)               NOT NULL,
    source_currency  VARCHAR2(3)                 NOT NULL,
    dest_currency    VARCHAR2(3)                 NOT NULL,
    source_amount    NUMBER(18,4)                NOT NULL,
    dest_amount      NUMBER(18,4),
    locked_rate      NUMBER(18,8),
    fx_lock_id       VARCHAR2(36),
    saga_state       VARCHAR2(30)                NOT NULL,
    idempotency_key  VARCHAR2(80)                NOT NULL,
    created_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT conversion_transaction_pkey PRIMARY KEY (transaction_id),
    CONSTRAINT uk_conversion_transaction_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT conversion_transaction_saga_state_check CHECK (saga_state IN (
        'STARTED', 'RATE_LOCKED', 'SOURCE_DEBITED', 'DEST_CREDITED', 'PAYMENT_COMPLETED',
        'COMPLETED', 'FAILED', 'DEBIT_FAILED', 'CREDIT_FAILED', 'PAYMENT_FAILED', 'COMPENSATING',
        'DEST_DEBITED_BACK', 'SOURCE_CREDITED_BACK', 'LOCK_RELEASED', 'COMPENSATED'
    ))
);

CREATE TABLE saga_step_log (
    step_id        VARCHAR2(36)                NOT NULL,
    transaction_id VARCHAR2(36)                NOT NULL,
    step_name      VARCHAR2(50)                NOT NULL,
    status         VARCHAR2(20)                NOT NULL,
    payload        CLOB,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT saga_step_log_pkey PRIMARY KEY (step_id),
    CONSTRAINT saga_step_log_status_check CHECK (status IN ('SUCCESS', 'FAILED', 'COMPENSATED'))
);
