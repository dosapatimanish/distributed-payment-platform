-- Baseline schema (design doc §6.1.4), exactly matching what Hibernate's ddl-auto=update had
-- already generated from MerchantPayment - reproduced here, not redesigned.

CREATE TABLE merchant_payment (
    payment_id     VARCHAR(36)                 NOT NULL,
    transaction_id VARCHAR(36)                 NOT NULL,
    merchant_id    VARCHAR(255)                NOT NULL,
    amount         NUMERIC(18,4)                NOT NULL,
    currency       VARCHAR(3)                  NOT NULL,
    acquirer_ref   VARCHAR(64),
    status         VARCHAR(20)                 NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT merchant_payment_pkey PRIMARY KEY (payment_id),
    CONSTRAINT uk_merchant_payment_transaction_id UNIQUE (transaction_id),
    CONSTRAINT merchant_payment_status_check CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REFUNDED'))
);
