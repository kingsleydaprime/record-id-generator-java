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

        // Benchmark path: measure individual operation costs without needing MySQL or RabbitMQ.
        // Usage: --benchmark
        if (args.length >= 1 && args[0].equals("--benchmark")) {
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
        // Phase 3 (MySQL truncate) is excluded — use --benchmark alone for that.
        new Benchmark().runAutomatic();

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

                log.info("Producer finished — waiting for queue to drain...");
                waitForQueueEmpty();

                // Drain in-flight flushes and collect final stats
                for (FileConsumer c : consumers) c.shutdown();

                long totalMs       = System.currentTimeMillis() - pipelineStart;
                long totalInserted = consumers.stream().mapToLong(FileConsumer::getTotalProcessed).sum();
                long totalDupes    = consumers.stream().mapToLong(FileConsumer::getTotalDuplicates).sum();
                long totalErrors   = consumers.stream().mapToLong(FileConsumer::getTotalErrors).sum();
                double rate        = totalMs > 0 ? totalInserted / (totalMs / 1000.0) : 0;

                log.info("========================================");
                log.info("PIPELINE COMPLETE");
                log.info("  Duration:   {}s", totalMs / 1000);
                log.info("  Inserted:   {}", totalInserted);
                log.info("  Duplicates: {}", totalDupes);
                log.info("  Errors:     {}", totalErrors);
                log.info("  Avg rate:   {}/s", String.format("%.0f", rate));
                log.info("========================================");
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
