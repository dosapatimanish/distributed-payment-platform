-- Baseline schema (design doc §6.1.4), Oracle Database Free 23ai dialect. Mirrors the
-- MerchantPayment entity mapping exactly - ddl-auto=validate checks it against this at startup.

CREATE SEQUENCE merchant_payment_seq START WITH 1 INCREMENT BY 1 CACHE 20 NOCYCLE;

CREATE TABLE merchant_payment (
    payment_id     VARCHAR2(20)                NOT NULL,
    transaction_id VARCHAR2(16)                NOT NULL,
    merchant_id    VARCHAR2(255)               NOT NULL,
    amount         NUMBER(18,4)                NOT NULL,
    currency       VARCHAR2(3)                 NOT NULL,
    acquirer_ref   VARCHAR2(64),
    status         VARCHAR2(20)                NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT merchant_payment_pkey PRIMARY KEY (payment_id),
    CONSTRAINT uk_merchant_payment_transaction_id UNIQUE (transaction_id),
    CONSTRAINT merchant_payment_status_check CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REFUNDED'))
);
