package com.itc;

import com.itc.config.DatabaseConfig;
import com.itc.service.IdGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;

public class BulkLoader {
    private static final Logger log = LoggerFactory.getLogger(BulkLoader.class);
    private final IdGeneratorService idGenerator = new IdGeneratorService();
    private static final int LOG_INTERVAL = 500_000;

    public void load(String csvPath) throws Exception {
        log.info("Bulk load starting: {}", csvPath);
        Path tempFile = Files.createTempFile("bulk_load_", ".csv");
        try {
            long rows = preProcess(csvPath, tempFile);
            loadIntoDb(tempFile, rows);
        } finally {
            Files.deleteIfExists(tempFile);
            log.info("Temp file cleaned up.");
        }
    }

    private long preProcess(String csvPath, Path tempFile) throws IOException {
        log.info("Step 1/2 — pre-processing: assigning IDs, writing temp file...");
        long rows = 0;
        long collisions = 0;
        long start = System.currentTimeMillis();

        // Store used IDs as longs to avoid String overhead (~46MB vs ~460MB for String set)
        Set<Long> usedIds = new HashSet<>(8_000_000);

        try (BufferedReader reader = Files.newBufferedReader(Path.of(csvPath));
             BufferedWriter writer = Files.newBufferedWriter(tempFile)) {

            reader.readLine(); // skip CSV header

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                // Generate a unique ID — collisions are ~16 across 5.75M rows so this
                // loop almost never iterates more than once.
                String id = idGenerator.generate();
                while (!usedIds.add(Long.parseLong(id))) {
                    collisions++;
                    id = idGenerator.generate();
                }

                writer.write(id);
                writer.write(',');
                writer.write(line);
                writer.newLine();

                rows++;
                if (rows % LOG_INTERVAL == 0) {
                    log.info("  pre-processed {} rows...", rows);
                }
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Pre-processing done: {} rows, {} id collisions resolved, in {}ms", rows, collisions, elapsed);
        return rows;
    }

    private void loadIntoDb(Path tempFile, long expectedRows) throws SQLException {
        String filePath = tempFile.toAbsolutePath().toString().replace("\\", "/");

        String loadSql = """
                LOAD DATA LOCAL INFILE '%s'
                INTO TABLE transactions
                FIELDS TERMINATED BY ','
                OPTIONALLY ENCLOSED BY '"'
                LINES TERMINATED BY '\\n'
                (generated_id, payment_type_id, source_id, thirdparty_id, source_date_created,
                 source_account_no, source_trans_id, channel_id, terminal_id, merchant_id,
                 product_id, sub_merchant_id, accountref, accountname, paymentmsisdn,
                 narration, currency, amount, fees, year, processor, country, transtype, month)
                """.formatted(filePath);

        try (Connection conn = DatabaseConfig.getConnection();
             Statement stmt = conn.createStatement()) {

            // Drop the unique index so the load is pure sequential writes — no per-row
            // B-tree maintenance means no page splits and no slowdown as the table grows.
            log.info("Step 2/3 — dropping index for load...");
            stmt.execute("ALTER TABLE transactions DROP INDEX ux_generated_id");

            log.info("Step 3/3 — loading {} rows via LOAD DATA LOCAL INFILE...", expectedRows);
            long loadStart = System.currentTimeMillis();
            stmt.execute(loadSql);

            long loaded;
            try (ResultSet rs = stmt.executeQuery("SELECT ROW_COUNT()")) {
                rs.next();
                loaded = rs.getLong(1);
            }
            long loadMs = System.currentTimeMillis() - loadStart;
            log.info("Load done: {}/{} rows in {}ms ({}/s)",
                    loaded, expectedRows, loadMs,
                    String.format("%.0f", loaded / (loadMs / 1000.0)));

            // Rebuild the index in one bulk sort pass — orders of magnitude faster than
            // maintaining it per-row during the load.
            log.info("Rebuilding ux_generated_id index...");
            long indexStart = System.currentTimeMillis();
            stmt.execute("CREATE UNIQUE INDEX ux_generated_id ON transactions(generated_id)");
            log.info("Index built in {}ms", System.currentTimeMillis() - indexStart);

            if (loaded < expectedRows) {
                log.warn("{} rows were skipped — check for constraint violations.",
                        expectedRows - loaded);
            }
        }
    }
}
