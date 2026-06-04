package com.itc.producer;

import com.itc.config.RabbitMQConfig;
import com.rabbitmq.client.Channel;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileProducer {
     private static final Logger log = LoggerFactory.getLogger(FileProducer.class);
    public void produce(String filePath) {
         log.info("Producer started for file: {}", filePath);
        // ...
        try (Channel channel = RabbitMQConfig.createChannel();
             BufferedReader reader = new BufferedReader(new FileReader(filePath))) {

            RabbitMQConfig.declareQueues(channel);

            String line;
            boolean isHeader = true;
            long count = 0;
            while ((line = reader.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }
                channel.basicPublish("", RabbitMQConfig.getQueueName(), null, line.getBytes());
                count++;
            }
            log.info("Producer finished. Total records published: {}", count);

        } catch (IOException | java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("Producer failed", e);
        }
    }
}
