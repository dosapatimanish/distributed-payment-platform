-- Baseline schema (design doc §6.1.5), exactly matching what Hibernate's ddl-auto=update had
-- already generated from LedgerEntry - reproduced here, not redesigned. transaction_id is
-- VARCHAR(64), wider than the design doc's literal VARCHAR2(36) - see LedgerEntry's own javadoc
-- for why (a compensation reversal posts under "{originalTransactionId}-reversal", 45 chars).

CREATE TABLE ledger_entry (
    entry_id       VARCHAR(36)                 NOT NULL,
    transaction_id VARCHAR(64)                 NOT NULL,
    wallet_id      VARCHAR(36)                 NOT NULL,
    entry_type     VARCHAR(10)                 NOT NULL,
    amount         NUMERIC(18,4)                NOT NULL,
    currency       VARCHAR(3)                  NOT NULL,
    balance_after  NUMERIC(18,4)                NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ledger_entry_pkey PRIMARY KEY (entry_id),
    CONSTRAINT ledger_entry_entry_type_check CHECK (entry_type IN ('DEBIT', 'CREDIT'))
);

CREATE INDEX idx_ledger_entry_transaction_id ON ledger_entry (transaction_id);
CREATE INDEX idx_ledger_entry_wallet_id ON ledger_entry (wallet_id);
