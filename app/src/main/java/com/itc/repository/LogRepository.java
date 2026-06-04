package com.itc.repository;

import com.itc.config.DatabaseConfig;
import com.itc.model.Log;

import java.sql.*;

public class LogRepository {
    public void save(Log log) throws SQLException {
        String sql = """
                INSERT INTO logs (level, source, message, stack_trace, payload, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, log.getLevel());
            stmt.setString(2, log.getSource());
            stmt.setString(3, log.getMessage());
            stmt.setString(4, log.getStacktrace());
            stmt.setString(5, log.getPayload());
            stmt.setString(6, log.getCorrelationId());
            stmt.executeUpdate();
        }
    }
}
