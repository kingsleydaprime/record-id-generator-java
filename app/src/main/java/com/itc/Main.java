package com.itc;

import com.itc.config.FlywayConfig;
import com.itc.config.RabbitMQConfig;
import com.itc.consumer.FileConsumer;
import com.itc.producer.FileProducer;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    // Number of concurrent consumer threads. Each gets its own RabbitMQ channel
    // and its own batch buffer. Increase this to scale throughput on multi-core machines.
    private static final int NUM_CONSUMERS = 10;

    public static void main(String[] args) throws Exception {
        FlywayConfig.migrate();

        try (Channel setupChannel = RabbitMQConfig.createChannel()) {
            RabbitMQConfig.declareQueues(setupChannel);
        }

        // Start N consumer threads before the producer so they are ready to receive
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            final int id = i + 1;
            FileConsumer consumer = new FileConsumer();
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
        } else {
            log.info("No file provided — consumers are draining the existing queue");
            Thread.currentThread().join(); // keep JVM alive until interrupted
        }
    }
}
