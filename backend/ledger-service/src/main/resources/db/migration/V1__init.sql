-- Baseline schema (design doc §6.1.5), Oracle Database Free 23ai dialect. Mirrors the
-- LedgerEntry entity mapping exactly - ddl-auto=validate checks it against this at startup.
-- transaction_id is VARCHAR2(64), wider than the design doc's literal VARCHAR2(36) - see
-- LedgerEntry's own javadoc for why (a compensation reversal posts under
-- "{originalTransactionId}-reversal", 45 chars).

CREATE TABLE ledger_entry (
    entry_id       VARCHAR2(36)                NOT NULL,
    transaction_id VARCHAR2(64)                NOT NULL,
    wallet_id      VARCHAR2(36)                NOT NULL,
    entry_type     VARCHAR2(10)                NOT NULL,
    amount         NUMBER(18,4)                NOT NULL,
    currency       VARCHAR2(3)                 NOT NULL,
    balance_after  NUMBER(18,4)                NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ledger_entry_pkey PRIMARY KEY (entry_id),
    CONSTRAINT ledger_entry_entry_type_check CHECK (entry_type IN ('DEBIT', 'CREDIT'))
);

CREATE INDEX idx_ledger_entry_transaction_id ON ledger_entry (transaction_id);
CREATE INDEX idx_ledger_entry_wallet_id ON ledger_entry (wallet_id);
