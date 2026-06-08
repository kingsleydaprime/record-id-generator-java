package com.itc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

public class InMemoryIdRegistry implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(InMemoryIdRegistry.class);

    private final Connection conn;
    private final PreparedStatement insertStmt;

    public InMemoryIdRegistry() throws SQLException {
        // Unique name per instance so multiple InMemoryIdRegistry objects in the same JVM
        // (e.g. benchmark runs two instances back-to-back) never share state.
        // No DB_CLOSE_DELAY needed — the database lives as long as this connection is open,
        // and is garbage-collected when close() is called.
        String dbName = "id_registry_" + System.nanoTime();
        conn = DriverManager.getConnection("jdbc:h2:mem:" + dbName);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE used_ids (id BIGINT PRIMARY KEY)");
        }
        insertStmt = conn.prepareStatement("INSERT INTO used_ids VALUES (?)");
        log.info("H2 in-memory ID registry initialized.");
    }

    // Returns true if the ID was new and successfully registered.
    // Returns false if the ID already exists (collision — caller should generate a new one).
    public synchronized boolean register(long id) throws SQLException {
        insertStmt.setLong(1, id);
        try {
            insertStmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            // SQLState class "23" = integrity constraint violation (duplicate key)
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void close() throws SQLException {
        insertStmt.close();
        conn.close();
    }
}
