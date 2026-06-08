package com.itc;

import com.itc.config.FlywayConfig;
import com.itc.config.RabbitMQConfig;
import com.itc.consumer.FileConsumer;
import com.itc.producer.FileProducer;
import com.itc.service.InMemoryIdRegistry;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    // Number of concurrent consumer threads. Each gets its own RabbitMQ channel
    // and its own batch buffer. Increase this to scale throughput on multi-core machines.
    private static final int NUM_CONSUMERS = 10;

    public static void main(String[] args) throws Exception {
        FlywayConfig.migrate();

        // Safe benchmark — phases 1, 2, 4. No truncation. Safe to run anywhere.
        // Usage: --benchmark
        if (args.length >= 1 && args[0].equals("--benchmark")) {
            new Benchmark().runAutomatic();
            return;
        }

        // Full benchmark — all 4 phases including MySQL truncation test. Dev only.
        // Usage: --benchmark-full
        if (args.length >= 1 && args[0].equals("--benchmark-full")) {
            new Benchmark().run();
            return;
        }

        // Bulk path: skip RabbitMQ entirely and load directly via LOAD DATA LOCAL INFILE.
        // Usage: --bulk /path/to/file.csv
        if (args.length >= 1 && args[0].equals("--bulk")) {
            if (args.length < 2) {
                log.error("--bulk requires a file path: --bulk /path/to/file.csv");
                System.exit(1);
            }
            new Benchmark().runAutomatic();
            new BulkLoader().load(args[1]);
            return;
        }

        // Benchmark always runs first so every log captures baseline performance numbers.
        // Phase 3 (MySQL truncate) is excluded — use --benchmark-full for that.
        new Benchmark().runAutomatic();

        log.info("=".repeat(74));
        log.info("Benchmarks done — starting pipeline...");
        log.info("=".repeat(74));

        // RabbitMQ pipeline path (default)
        try (Channel setupChannel = RabbitMQConfig.createChannel()) {
            RabbitMQConfig.declareQueues(setupChannel);
        }

        // One shared registry for all consumers. H2 enforces uniqueness since MySQL
        // no longer has a unique index on generated_id.
        List<FileConsumer> consumers = new ArrayList<>();
        long pipelineStart = System.currentTimeMillis();

        try (InMemoryIdRegistry registry = new InMemoryIdRegistry()) {
            for (int i = 0; i < NUM_CONSUMERS; i++) {
                final int id = i + 1;
                FileConsumer consumer = new FileConsumer(registry);
                consumers.add(consumer);
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

            if (args.length > 0) {
                String filePath = args[0];
                Thread producerThread = new Thread(() -> new FileProducer().produce(filePath));
                producerThread.setName("producer");
                producerThread.start();
                producerThread.join();

                long producerMs = System.currentTimeMillis() - pipelineStart;
                log.info("Producer finished in {}s — waiting for queue to drain...", producerMs / 1000);
                waitForQueueEmpty();

                // Drain in-flight flushes and collect final stats
                for (FileConsumer c : consumers) c.shutdown();

                long totalMs       = System.currentTimeMillis() - pipelineStart;
                long consumerMs    = totalMs - producerMs;
                long totalInserted = consumers.stream().mapToLong(FileConsumer::getTotalProcessed).sum();
                long totalDupes    = consumers.stream().mapToLong(FileConsumer::getTotalDuplicates).sum();
                long totalErrors   = consumers.stream().mapToLong(FileConsumer::getTotalErrors).sum();
                long totalIdGenNs  = consumers.stream().mapToLong(FileConsumer::getTotalIdGenNs).sum();
                long totalDbMs2    = consumers.stream().mapToLong(FileConsumer::getTotalDbMs).sum();
                long totalBatches  = consumers.stream().mapToLong(FileConsumer::getTotalBatches).sum();
                double rate        = totalMs > 0 ? totalInserted / (totalMs / 1000.0) : 0;
                double avgIdGenUs  = totalInserted > 0 ? (totalIdGenNs / (double) totalInserted) / 1_000.0 : 0;
                double avgBatchMs  = totalBatches > 0 ? totalDbMs2 / (double) totalBatches : 0;
                double mysqlRate   = totalDbMs2 > 0 ? totalInserted / (totalDbMs2 / 1000.0) : 0;

                log.info("========================================");
                log.info("PIPELINE COMPLETE");
                log.info("  Publish to queue:   {}s", producerMs / 1000);
                log.info("  Consumer lag:       {}s  (queue drain time after producer finished)", consumerMs / 1000);
                log.info("  Total (end-to-end): {}s", totalMs / 1000);
                log.info("  Inserted:           {}", totalInserted);
                log.info("  Duplicates:         {}", totalDupes);
                log.info("  Errors:             {}", totalErrors);
                log.info("  Avg rate:           {}/s  (end-to-end)", String.format("%.0f", rate));
                log.info("  Avg ID gen:         {}µs/id", String.format("%.2f", avgIdGenUs));
                log.info("  MySQL avg batch:    {}ms/batch  ({} batches)", String.format("%.0f", avgBatchMs), totalBatches);
                log.info("  MySQL throughput:   {}/s  (pure insert time)", String.format("%.0f", mysqlRate));
                log.info("========================================");
                System.exit(0);
            } else {
                log.info("No file provided — consumers are draining the existing queue");
                Thread.currentThread().join();
            }
        }
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
