CREATE TABLE transactions (
    id VARCHAR(12) NOT NULL PRIMARY KEY,
    payment_type_id VARCHAR(50),
    source_id VARCHAR(50),
    thirdparty_id VARCHAR(100),
    source_date_created DATETIME,
    source_account_no VARCHAR(50),
    source_trans_id VARCHAR(100),
    channel_id VARCHAR(50),
    terminal_id VARCHAR(50),
    merchant_id VARCHAR(50),
    product_id VARCHAR(50),
    sub_merchant_id VARCHAR(50),
    accountref VARCHAR(100),
    accountname VARCHAR(255),
    paymentmsisdn VARCHAR(50),
    narration VARCHAR(255),
    currency VARCHAR(10),
    amount DECIMAL(18,2),
    fees DECIMAL(18,2),
    year INT,
    processor VARCHAR(100),
    country VARCHAR(100),
    transtype VARCHAR(50),
    month VARCHAR(10),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    level VARCHAR(10),
    source VARCHAR(100),
    message TEXT,
    stack_trace TEXT,
    payload TEXT,
    correlation_id VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
