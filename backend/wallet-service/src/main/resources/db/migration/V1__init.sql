-- Baseline schema (design doc §6.1.1), Oracle Database Free 23ai. Bank-style identifier scheme
-- (backend-documents/identifier-scheme.md): wallet_id -> account_no (12 chars), user_id -> cif
-- (10 digits). Plus the currency reference table this service owns and the account_sequence
-- counter that feeds the 5-digit account-number sequence.

CREATE TABLE currency (
    code         VARCHAR2(3)  NOT NULL,
    numeric_code VARCHAR2(3)  NOT NULL,
    short_code   VARCHAR2(2)  NOT NULL,
    name         VARCHAR2(64) NOT NULL,
    minor_units  NUMBER(10)   NOT NULL,
    active       VARCHAR2(1)  DEFAULT 'Y' NOT NULL,
    CONSTRAINT currency_pkey PRIMARY KEY (code),
    CONSTRAINT uk_currency_short_code   UNIQUE (short_code),
    CONSTRAINT uk_currency_numeric_code UNIQUE (numeric_code),
    CONSTRAINT currency_active_check CHECK (active IN ('Y', 'N'))
);

INSERT INTO currency (code, numeric_code, short_code, name, minor_units, active) VALUES ('USD', '840', '01', 'US Dollar',      2, 'Y');
INSERT INTO currency (code, numeric_code, short_code, name, minor_units, active) VALUES ('EUR', '978', '02', 'Euro',           2, 'Y');
INSERT INTO currency (code, numeric_code, short_code, name, minor_units, active) VALUES ('INR', '356', '03', 'Indian Rupee',   2, 'Y');
INSERT INTO currency (code, numeric_code, short_code, name, minor_units, active) VALUES ('GBP', '826', '04', 'Pound Sterling', 2, 'Y');

-- Per (currency short_code, CIF prefix) running counter for the trailing 5 digits of an account
-- number. No JPA entity - AccountNumberGenerator drives it with a native MERGE + SELECT.
CREATE TABLE account_sequence (
    currency_code VARCHAR2(2) NOT NULL,
    cif_prefix    VARCHAR2(5) NOT NULL,
    next_val      NUMBER(9)   NOT NULL,
    CONSTRAINT account_sequence_pkey PRIMARY KEY (currency_code, cif_prefix)
);

CREATE TABLE wallet (
    account_no      VARCHAR2(12)                NOT NULL,
    cif             VARCHAR2(10)                NOT NULL,
    currency        VARCHAR2(3)                 NOT NULL,
    balance         NUMBER(18,4)                NOT NULL,
    status          VARCHAR2(20)                NOT NULL,
    high_contention BOOLEAN                     NOT NULL,
    version         NUMBER(19)                  NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT wallet_pkey PRIMARY KEY (account_no),
    CONSTRAINT uk_wallet_cif_currency UNIQUE (cif, currency),
    CONSTRAINT wallet_status_check CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE INDEX idx_wallet_cif ON wallet (cif);

CREATE SEQUENCE wallet_reservation_seq START WITH 1 INCREMENT BY 1 CACHE 20 NOCYCLE;

CREATE TABLE wallet_reservation (
    reservation_id VARCHAR2(20)                NOT NULL,
    account_no     VARCHAR2(12)                NOT NULL,
    transaction_id VARCHAR2(16)                NOT NULL,
    amount         NUMBER(18,4)                NOT NULL,
    status         VARCHAR2(20)                NOT NULL,
    expires_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT wallet_reservation_pkey PRIMARY KEY (reservation_id),
    CONSTRAINT wallet_reservation_status_check CHECK (status IN ('HELD', 'CAPTURED', 'RELEASED'))
);
