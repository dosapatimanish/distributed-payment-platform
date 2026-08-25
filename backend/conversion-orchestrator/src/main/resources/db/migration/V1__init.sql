-- Baseline schema (design doc §6.1.3), exactly matching what Hibernate's ddl-auto=update had
-- already generated from ConversionTransaction/SagaStepLog - reproduced here, not redesigned.
-- saga_step_log.payload is OID (Postgres large-object reference), not TEXT - Hibernate's default
-- mapping for a @Lob String field, kept as-is rather than "fixed" in this pass; see this
-- service's implementation notes for the known caveat that comes with it.

CREATE TABLE conversion_transaction (
    transaction_id   VARCHAR(36)                 NOT NULL,
    user_id          VARCHAR(255)                NOT NULL,
    source_wallet_id VARCHAR(255)                NOT NULL,
    dest_wallet_id   VARCHAR(255)                NOT NULL,
    source_currency  VARCHAR(3)                  NOT NULL,
    dest_currency    VARCHAR(3)                  NOT NULL,
    source_amount    NUMERIC(18,4)                NOT NULL,
    dest_amount      NUMERIC(18,4),
    locked_rate      NUMERIC(18,8),
    fx_lock_id       VARCHAR(36),
    saga_state       VARCHAR(30)                 NOT NULL,
    idempotency_key  VARCHAR(80)                 NOT NULL,
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
    step_id        VARCHAR(36)                 NOT NULL,
    transaction_id VARCHAR(36)                 NOT NULL,
    step_name      VARCHAR(50)                 NOT NULL,
    status         VARCHAR(20)                 NOT NULL,
    payload        OID,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT saga_step_log_pkey PRIMARY KEY (step_id),
    CONSTRAINT saga_step_log_status_check CHECK (status IN ('SUCCESS', 'FAILED', 'COMPENSATED'))
);
