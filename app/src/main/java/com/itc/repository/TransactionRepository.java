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
                    id, source_hash, payment_type_id, source_id, thirdparty_id, source_date_created,
                    source_account_no, source_trans_id, channel_id, terminal_id, merchant_id,
                    product_id, sub_merchant_id, accountref, accountname, paymentmsisdn,
                    narration, currency, amount, fees, year, processor, country, transtype, month
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setParams(stmt, t);
            stmt.executeUpdate();
        }
    }

    // Bulk save — INSERT IGNORE means duplicate source_hash rows are silently skipped
    // instead of throwing an exception and failing the whole batch.
    // Returns the number of rows that were skipped (duplicates).
    public int saveBatch(List<Transaction> transactions) throws SQLException {
        String sql = """
                INSERT IGNORE INTO transactions (
                    id, source_hash, payment_type_id, source_id, thirdparty_id, source_date_created,
                    source_account_no, source_trans_id, channel_id, terminal_id, merchant_id,
                    product_id, sub_merchant_id, accountref, accountname, paymentmsisdn,
                    narration, currency, amount, fees, year, processor, country, transtype, month
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                // number of rows inserted by INSERT IGNORE (ignored rows are not counted).
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
        stmt.setString(1, t.getId());
        stmt.setString(2, t.getSourceHash());
        stmt.setString(3, t.getPaymentTypeId());
        stmt.setString(4, t.getSourceId());
        stmt.setString(5, t.getThirdpartyId());
        stmt.setTimestamp(6, Timestamp.valueOf(t.getSourceDateCreated()));
        stmt.setString(7, t.getSourceAccountNo());
        stmt.setString(8, t.getSourceTransId());
        stmt.setString(9, t.getChannelId());
        stmt.setString(10, t.getTerminalId());
        stmt.setString(11, t.getMerchantId());
        stmt.setString(12, t.getProductId());
        stmt.setString(13, t.getSubMerchantId());
        stmt.setString(14, t.getAccountref());
        stmt.setString(15, t.getAccountname());
        stmt.setString(16, t.getPaymentmsisdn());
        stmt.setString(17, t.getNarration());
        stmt.setString(18, t.getCurrency());
        stmt.setBigDecimal(19, t.getAmount());
        stmt.setBigDecimal(20, t.getFees());
        stmt.setInt(21, t.getYear());
        stmt.setString(22, t.getProcessor());
        stmt.setString(23, t.getCountry());
        stmt.setString(24, t.getTranstype());
        stmt.setString(25, t.getMonth());
    }

    // Single row INSERT IGNORE - used only in fallback
    public int saveIgnore(Transaction t) throws SQLException {
        String sql = """
                INSERT IGNORE INTO transactions (
                    id, source_hash, payment_type_id, source_id, thirdparty_id, source_date_created,
                    source_account_no, source_trans_id, channel_id, terminal_id, merchant_id,
                    product_id, sub_merchant_id, accountref, accountname, paymentmsisdn,
                    narration, currency, amount, fees, year, processor, country, transtype, month
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = DatabaseConfig.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {

            setParams(stmt, t);
            int rows = stmt.executeUpdate();
            return rows;                    // 1 = inserted, 0 = duplicate ignored
        }
    }
}
