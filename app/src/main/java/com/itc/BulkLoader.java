package com.itc;

import com.itc.config.DatabaseConfig;
import com.itc.service.IdGeneratorService;
import com.itc.service.InMemoryIdRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.sql.*;

public class BulkLoader {
    private static final Logger log = LoggerFactory.getLogger(BulkLoader.class);
    private final IdGeneratorService idGenerator = new IdGeneratorService();
    private static final int LOG_INTERVAL = 500_000;

    public void load(String csvPath) throws Exception {
        log.info("Bulk load starting: {}", csvPath);
        Path tempFile = Files.createTempFile("bulk_load_", ".csv");
        try (InMemoryIdRegistry registry = new InMemoryIdRegistry()) {
            long rows = preProcess(csvPath, tempFile, registry);
            loadIntoDb(tempFile, rows);
        } finally {
            Files.deleteIfExists(tempFile);
            log.info("Temp file cleaned up.");
        }
    }

    private long preProcess(String csvPath, Path tempFile, InMemoryIdRegistry registry) throws IOException, SQLException {
        log.info("Step 1/2 — pre-processing: assigning IDs, writing temp file...");
        long rows = 0;
        long collisions = 0;
        long start = System.currentTimeMillis();

        try (BufferedReader reader = Files.newBufferedReader(Path.of(csvPath));
             BufferedWriter writer = Files.newBufferedWriter(tempFile)) {

            reader.readLine(); // skip CSV header

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                String id = idGenerator.generate();
                while (!registry.register(Long.parseLong(id))) {
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

            log.info("Step 2/2 — loading {} rows via LOAD DATA LOCAL INFILE...", expectedRows);
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

            if (loaded < expectedRows) {
                log.warn("{} rows were skipped — check for constraint violations.",
                        expectedRows - loaded);
            }
        }
    }
}
