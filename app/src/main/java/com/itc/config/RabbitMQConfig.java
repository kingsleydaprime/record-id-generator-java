package com.itc.config;

import com.rabbitmq.client.Channel;
// import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeoutException;

public class RabbitMQConfig {
    private static final ConnectionFactory factory;
    private static final String QUEUE_NAME;
    private static final String DLQ_NAME;

    static {
        Properties props = new Properties();
        try (InputStream in = RabbitMQConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load application.properties", e);
        }

        factory = new ConnectionFactory();
        factory.setHost(props.getProperty("rabbitmq.host"));
        factory.setPort(Integer.parseInt(props.getProperty("rabbitmq.port")));
        factory.setUsername(props.getProperty("rabbitmq.user"));
        factory.setPassword(props.getProperty("rabbitmq.pass"));
        factory.setRequestedHeartbeat(30);

        QUEUE_NAME = props.getProperty("rabbitmq.queue");
        DLQ_NAME = QUEUE_NAME + ".dlq";
    }

    // Each caller gets its own connection — avoids the single shared reader thread bottleneck.
    public static Channel createChannel() throws IOException, TimeoutException {
        return factory.newConnection().createChannel();
    }

    public static String getQueueName() {
        return QUEUE_NAME;
    }

    public static String getDlqName() {
        return DLQ_NAME;
    }

    /**
     * Declares both the main queue and its dead letter queue.
     * The DLQ must be declared first — the main queue references it.
     * NOTE: if the main queue already exists without DLQ args, delete it in
     * the RabbitMQ management UI (localhost:15672) before running.
     */
    public static void declareQueues(Channel channel) throws IOException {
        channel.queueDeclare(DLQ_NAME, true, false, false, null);

        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", "");
        args.put("x-dead-letter-routing-key", DLQ_NAME);
        channel.queueDeclare(QUEUE_NAME, true, false, false, args);
    }
}
