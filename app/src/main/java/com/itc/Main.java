package com.itc;

import com.itc.config.DatabaseConfig;
import com.itc.config.FlywayConfig;
import com.itc.config.RabbitMQConfig;
import com.itc.consumer.FileConsumer;
import com.itc.producer.FileProducer;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    // Number of concurrent consumer threads. Each gets its own RabbitMQ channel
    // and its own batch buffer. Increase this to scale throughput on multi-core machines.
    private static final int NUM_CONSUMERS = 10;

    public static void main(String[] args) throws Exception {
        FlywayConfig.migrate();

        // Bulk path: skip RabbitMQ entirely and load directly via LOAD DATA LOCAL INFILE.
        // Usage: --bulk /path/to/file.csv
        if (args.length >= 1 && args[0].equals("--bulk")) {
            if (args.length < 2) {
                log.error("--bulk requires a file path: --bulk /path/to/file.csv");
                System.exit(1);
            }
            new BulkLoader().load(args[1]);
            return;
        }

        // RabbitMQ pipeline path (default)
        try (Channel setupChannel = RabbitMQConfig.createChannel()) {
            RabbitMQConfig.declareQueues(setupChannel);
        }

        boolean isFileLoad = args.length > 0;

        // Shared set used only in file-load mode. The unique index is dropped during
        // the load, so this set is the only thing preventing cross-consumer duplicates.
        // In drain mode the index stays active and handles the rare collision via retry.
        Set<Long> usedIds = isFileLoad ? ConcurrentHashMap.newKeySet(8_000_000) : null;

        for (int i = 0; i < NUM_CONSUMERS; i++) {
            final int id = i + 1;
            FileConsumer consumer = isFileLoad ? new FileConsumer(usedIds) : new FileConsumer();
            Thread t = new Thread(() -> {
                try {
                    consumer.consume();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            t.setName("consumer-" + id);
            t.start();
        }

        log.info("Started {} consumer threads", NUM_CONSUMERS);

        if (isFileLoad) {
            String filePath = args[0];
            dropGeneratedIdIndex();

            Thread producerThread = new Thread(() -> new FileProducer().produce(filePath));
            producerThread.setName("producer");
            producerThread.start();
            producerThread.join();

            log.info("Producer finished — waiting for queue to drain...");
            waitForQueueEmpty();
            rebuildGeneratedIdIndex();
        } else {
            log.info("No file provided — consumers are draining the existing queue");
            Thread.currentThread().join();
        }
    }

    private static void dropGeneratedIdIndex() throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("ALTER TABLE transactions DROP INDEX ux_generated_id");
                log.info("Dropped ux_generated_id index for load.");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1091) {
                    log.info("ux_generated_id index already absent, skipping drop.");
                } else {
                    throw e;
                }
            }
        }
    }

    private static void rebuildGeneratedIdIndex() throws SQLException {
        log.info("Rebuilding ux_generated_id index...");
        long start = System.currentTimeMillis();
        try (Connection conn = DatabaseConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE UNIQUE INDEX ux_generated_id ON transactions(generated_id)");
        }
        log.info("Index rebuilt in {}ms", System.currentTimeMillis() - start);
    }

    private static void waitForQueueEmpty() throws Exception {
        try (Channel monitor = RabbitMQConfig.createChannel()) {
            while (true) {
                long remaining = monitor.queueDeclarePassive(RabbitMQConfig.getQueueName()).getMessageCount();
                if (remaining == 0) break;
                log.info("  queue depth: {} messages remaining...", remaining);
                Thread.sleep(5_000);
            }
        }
        // Queue shows zero ready messages, but consumers may still be committing
        // their last in-flight batch. One full flush cycle (10s) + buffer is enough.
        log.info("Queue empty — waiting 30s for in-flight flushes to commit...");
        Thread.sleep(30_000);
    }
}
