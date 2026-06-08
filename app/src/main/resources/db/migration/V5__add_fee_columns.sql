ALTER TABLE transactions
    ADD COLUMN network_fee DECIMAL(18,6) NULL,
    ADD COLUMN itc_fee     DECIMAL(18,6) NULL;
