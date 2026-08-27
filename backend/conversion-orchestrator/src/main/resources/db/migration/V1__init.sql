-- Baseline schema (design doc §6.1.3), Oracle Database Free 23ai. Bank-style identifier scheme
-- (backend-documents/identifier-scheme.md): transaction_id is a 16-char id, user_id -> cif,
-- source/dest_wallet_id -> source/dest_account_no (12 chars). Plus unit_master (the bank
-- business date, rolled by a scheduler) and txn_sequence (the daily transaction counter).

CREATE TABLE unit_master (
    unit_id       VARCHAR2(8)  NOT NULL,
    unit_name     VARCHAR2(64) NOT NULL,
    business_date DATE         NOT NULL,
    CONSTRAINT unit_master_pkey PRIMARY KEY (unit_id)
);

INSERT INTO unit_master (unit_id, unit_name, business_date) VALUES ('MAIN', 'Main Unit', TRUNC(SYSDATE));

-- One row per business date; next_val is the running daily transaction sequence. No JPA entity
-- for the increment - TransactionSequenceService drives it with a native MERGE + SELECT.
CREATE TABLE txn_sequence (
    business_date DATE      NOT NULL,
    next_val      NUMBER(9) DEFAULT 1 NOT NULL,
    CONSTRAINT txn_sequence_pkey PRIMARY KEY (business_date)
);

CREATE TABLE conversion_transaction (
    transaction_id    VARCHAR2(16)                NOT NULL,
    cif               VARCHAR2(10)                NOT NULL,
    source_account_no VARCHAR2(12)                NOT NULL,
    dest_account_no   VARCHAR2(12)                NOT NULL,
    source_currency   VARCHAR2(3)                 NOT NULL,
    dest_currency     VARCHAR2(3)                 NOT NULL,
    source_amount     NUMBER(18,4)                NOT NULL,
    dest_amount       NUMBER(18,4),
    locked_rate       NUMBER(18,8),
    fx_lock_id        VARCHAR2(36),
    saga_state        VARCHAR2(30)                NOT NULL,
    idempotency_key   VARCHAR2(80)                NOT NULL,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT conversion_transaction_pkey PRIMARY KEY (transaction_id),
    CONSTRAINT uk_conversion_transaction_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT conversion_transaction_saga_state_check CHECK (saga_state IN (
        'STARTED', 'RATE_LOCKED', 'SOURCE_DEBITED', 'DEST_CREDITED', 'PAYMENT_COMPLETED',
        'COMPLETED', 'FAILED', 'DEBIT_FAILED', 'CREDIT_FAILED', 'PAYMENT_FAILED', 'COMPENSATING',
        'DEST_DEBITED_BACK', 'SOURCE_CREDITED_BACK', 'LOCK_RELEASED', 'COMPENSATED'
    ))
);

-- Composite PK (transaction_id, step_no) - step_no is a 2-digit running number per saga
-- ('01', '02', ...) so a row's position in the sequence is readable from the id itself.
CREATE TABLE saga_step_log (
    transaction_id VARCHAR2(16)                NOT NULL,
    step_no        VARCHAR2(4)                 NOT NULL,
    step_name      VARCHAR2(50)                NOT NULL,
    status         VARCHAR2(20)                NOT NULL,
    account_no     VARCHAR2(12),
    payload        CLOB,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT saga_step_log_pkey PRIMARY KEY (transaction_id, step_no),
    CONSTRAINT saga_step_log_status_check CHECK (status IN ('SUCCESS', 'FAILED', 'COMPENSATED'))
);
