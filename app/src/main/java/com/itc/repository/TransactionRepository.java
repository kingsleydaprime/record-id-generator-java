package com.itc.repository;

import com.itc.config.DatabaseConfig;
import com.itc.model.Transaction;

import java.sql.*;
import java.util.List;

public class TransactionRepository {

    // Individual save — regular INSERT so constraint violations surface as exceptions.
    // Only used in the batch fallback path where we need explicit error visibility.
    public void save(Transaction t) throws SQLException {
        String sql = """
                INSERT INTO transactions (
                    generated_id, payment_type_id, source_id, thirdparty_id, source_date_created,
                    source_account_no, source_trans_id, channel_id, terminal_id, merchant_id,
                    product_id, sub_merchant_id, accountref, accountname, paymentmsisdn,
                    narration, currency, amount, fees, network_fee, itc_fee,
                    year, processor, country, transtype, month
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setParams(stmt, t);
            stmt.executeUpdate();
        }
    }

    // Bulk save — returns the number of rows skipped.
    public int saveBatch(List<Transaction> transactions) throws SQLException {
        String sql = """
                INSERT INTO transactions (
                    generated_id, payment_type_id, source_id, thirdparty_id, source_date_created,
                    source_account_no, source_trans_id, channel_id, terminal_id, merchant_id,
                    product_id, sub_merchant_id, accountref, accountname, paymentmsisdn,
                    narration, currency, amount, fees, network_fee, itc_fee,
                    year, processor, country, transtype, month
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                for (Transaction t : transactions) {
                    setParams(stmt, t);
                    stmt.addBatch();
                }
                stmt.executeBatch();

                // ROW_COUNT() must be called before commit and before any other statement.
                // With rewriteBatchedStatements=true, executeBatch() returns SUCCESS_NO_INFO (-2)
                // for every row — useless for counting. MySQL's ROW_COUNT() returns the actual
                // number of rows inserted.
                int actualInserted;
                try (Statement rowCount = conn.createStatement();
                     ResultSet rs = rowCount.executeQuery("SELECT ROW_COUNT()")) {
                    rs.next();
                    actualInserted = rs.getInt(1);
                }

                conn.commit();
                return transactions.size() - actualInserted;

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private void setParams(PreparedStatement stmt, Transaction t) throws SQLException {
        stmt.setString(1, t.getGeneratedId());
        stmt.setString(2, t.getPaymentTypeId());
        stmt.setString(3, t.getSourceId());
        stmt.setString(4, t.getThirdpartyId());
        stmt.setTimestamp(5, Timestamp.valueOf(t.getSourceDateCreated()));
        stmt.setString(6, t.getSourceAccountNo());
        stmt.setString(7, t.getSourceTransId());
        stmt.setString(8, t.getChannelId());
        stmt.setString(9, t.getTerminalId());
        stmt.setString(10, t.getMerchantId());
        stmt.setString(11, t.getProductId());
        stmt.setString(12, t.getSubMerchantId());
        stmt.setString(13, t.getAccountref());
        stmt.setString(14, t.getAccountname());
        stmt.setString(15, t.getPaymentmsisdn());
        stmt.setString(16, t.getNarration());
        stmt.setString(17, t.getCurrency());
        stmt.setBigDecimal(18, t.getAmount());
        stmt.setBigDecimal(19, t.getFees());
        stmt.setBigDecimal(20, t.getNetworkFee());
        stmt.setBigDecimal(21, t.getItcFee());
        stmt.setInt(22, t.getYear());
        stmt.setString(23, t.getProcessor());
        stmt.setString(24, t.getCountry());
        stmt.setString(25, t.getTranstype());
        stmt.setString(26, t.getMonth());
    }

}
