-- Baseline schema (design doc §6.1.1), exactly matching what Hibernate's ddl-auto=update had
-- already generated from Wallet/WalletReservation - reproduced here, not redesigned, so
-- switching to Flyway didn't also silently change the live schema. See each service's
-- application.properties comment on why ddl-auto is now `validate` instead.

CREATE TABLE wallet (
    wallet_id       VARCHAR(36)                 NOT NULL,
    user_id         VARCHAR(255)                NOT NULL,
    currency        VARCHAR(3)                  NOT NULL,
    balance         NUMERIC(18,4)                NOT NULL,
    status          VARCHAR(20)                 NOT NULL,
    high_contention BOOLEAN                     NOT NULL,
    version         BIGINT                      NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT wallet_pkey PRIMARY KEY (wallet_id),
    CONSTRAINT uk_wallet_user_currency UNIQUE (user_id, currency),
    CONSTRAINT wallet_status_check CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE INDEX idx_wallet_user_id ON wallet (user_id);

CREATE TABLE wallet_reservation (
    reservation_id VARCHAR(36)                 NOT NULL,
    wallet_id      VARCHAR(36)                 NOT NULL,
    transaction_id VARCHAR(36)                 NOT NULL,
    amount         NUMERIC(18,4)                NOT NULL,
    status         VARCHAR(20)                 NOT NULL,
    expires_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT wallet_reservation_pkey PRIMARY KEY (reservation_id),
    CONSTRAINT wallet_reservation_status_check CHECK (status IN ('HELD', 'CAPTURED', 'RELEASED'))
);
