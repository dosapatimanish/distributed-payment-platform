-- Baseline schema (design doc §6.1.2), exactly matching what Hibernate's ddl-auto=update had
-- already generated from FxRate/FxRateLock - reproduced here, not redesigned. fx_rate is the
-- simulated-feed's persisted rate history (RateRefreshScheduler); the live rate lookup itself
-- goes through the in-memory FxRateCache, not this table.

CREATE TABLE fx_rate (
    rate_id        VARCHAR(36)                 NOT NULL,
    base_currency  VARCHAR(3)                  NOT NULL,
    quote_currency VARCHAR(3)                  NOT NULL,
    rate           NUMERIC(18,8)                NOT NULL,
    source         VARCHAR(50)                 NOT NULL,
    effective_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fx_rate_pkey PRIMARY KEY (rate_id)
);

CREATE INDEX idx_fx_rate_pair_effective_at ON fx_rate (base_currency, quote_currency, effective_at);

CREATE TABLE fx_rate_lock (
    lock_id        VARCHAR(36)                 NOT NULL,
    transaction_id VARCHAR(36)                 NOT NULL,
    base_currency  VARCHAR(3)                  NOT NULL,
    quote_currency VARCHAR(3)                  NOT NULL,
    locked_rate    NUMERIC(18,8)                NOT NULL,
    amount         NUMERIC(18,4)                NOT NULL,
    status         VARCHAR(20)                 NOT NULL,
    expires_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fx_rate_lock_pkey PRIMARY KEY (lock_id),
    CONSTRAINT uk_fx_rate_lock_transaction_id UNIQUE (transaction_id),
    CONSTRAINT fx_rate_lock_status_check CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED', 'EXPIRED'))
);
