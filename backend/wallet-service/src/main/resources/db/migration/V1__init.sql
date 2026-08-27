
-- Baseline schema (design doc §6.1.1), Oracle Database Free 23ai dialect. Mirrors the
-- Wallet/WalletReservation entity mappings exactly - ddl-auto=validate checks them against this
-- at startup (see each service's application.properties comment). Oracle-specific type choices:
-- VARCHAR2 (not VARCHAR), NUMBER (not NUMERIC), NUMBER(19) for the @Version long, and Oracle
-- 23ai's native BOOLEAN for the high_contention flag.

CREATE TABLE wallet (
    wallet_id       VARCHAR2(36)                NOT NULL,
    user_id         VARCHAR2(255)               NOT NULL,
    currency        VARCHAR2(3)                 NOT NULL,
    balance         NUMBER(18,4)                NOT NULL,
    status          VARCHAR2(20)                NOT NULL,
    high_contention BOOLEAN                     NOT NULL,
    version         NUMBER(19)                  NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT wallet_pkey PRIMARY KEY (wallet_id),
    CONSTRAINT uk_wallet_user_currency UNIQUE (user_id, currency),
    CONSTRAINT wallet_status_check CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE INDEX idx_wallet_user_id ON wallet (user_id);

CREATE TABLE wallet_reservation (
    reservation_id VARCHAR2(36)                NOT NULL,
    wallet_id      VARCHAR2(36)                NOT NULL,
    transaction_id VARCHAR2(36)                NOT NULL,
    amount         NUMBER(18,4)                NOT NULL,
    status         VARCHAR2(20)                NOT NULL,
    expires_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT wallet_reservation_pkey PRIMARY KEY (reservation_id),
    CONSTRAINT wallet_reservation_status_check CHECK (status IN ('HELD', 'CAPTURED', 'RELEASED'))
);
