-- Baseline schema (design doc §6.1.5), Oracle Database Free 23ai. Bank-style identifier scheme
-- (backend-documents/identifier-scheme.md): wallet_id -> account_no (12 chars); the primary key
-- is composite (transaction_id, entry_no) - entry_no is a 2-digit leg number ('01', '02', ...),
-- so a row's place in the posting is readable from the id. transaction_id stays VARCHAR2(64) - a
-- compensation reversal posts under "{16-digit}-reversal" (25 chars).

CREATE TABLE ledger_entry (
    transaction_id VARCHAR2(64)                NOT NULL,
    entry_no       VARCHAR2(4)                 NOT NULL,
    account_no     VARCHAR2(12)                NOT NULL,
    entry_type     VARCHAR2(10)                NOT NULL,
    amount         NUMBER(18,4)                NOT NULL,
    currency       VARCHAR2(3)                 NOT NULL,
    balance_after  NUMBER(18,4)                NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ledger_entry_pkey PRIMARY KEY (transaction_id, entry_no),
    CONSTRAINT ledger_entry_entry_type_check CHECK (entry_type IN ('DEBIT', 'CREDIT'))
);

CREATE INDEX idx_ledger_entry_account_no ON ledger_entry (account_no);
