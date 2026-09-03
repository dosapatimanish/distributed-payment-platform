-- Customer master: name, email and a hashed password, one row per CIF. Wallet rows
-- stay in the wallet table, linked by CIF as before. Sign-in checks the supplied
-- email + password against this table (see CustomerService.authenticate).

CREATE TABLE customer_master (
    cif           VARCHAR2(10)                NOT NULL,
    first_name    VARCHAR2(64)                NOT NULL,
    last_name     VARCHAR2(64)                NOT NULL,
    email         VARCHAR2(256)               NOT NULL,
    password_hash VARCHAR2(512)               NOT NULL,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT customer_master_pkey PRIMARY KEY (cif),
    CONSTRAINT uk_customer_master_email UNIQUE (email)
);
