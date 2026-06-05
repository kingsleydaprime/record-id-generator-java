package com.itc.consumer;

import com.itc.config.RabbitMQConfig;
import com.itc.model.Log;
import com.itc.model.Transaction;
import com.itc.repository.LogRepository;
import com.itc.repository.TransactionRepository;
import com.itc.service.IdGeneratorService;
import com.rabbitmq.client.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileConsumer {
    private final TransactionRepository transactionRepository = new TransactionRepository();
    private final LogRepository logRepository = new LogRepository();
    private final IdGeneratorService idGenerator = new IdGeneratorService();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int BATCH_SIZE = 50_000;
    private static final Logger log = LoggerFactory.getLogger(FileConsumer.class);
    private static final int FLUSH_INTERVAL_SECONDS = 10;
    private final ExecutorService flushExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object batchLock = new Object();

    // Shared across all consumers in file-load mode so cross-consumer collisions are
    // caught in Java before reaching the DB (which has no unique index during the load).
    // null in drain mode — the index is present and handles the rare collision via retry.
    private final Set<Long> usedIds;

    // Observability counters — updated only from the RabbitMQ callback thread
    private long totalProcessed = 0;
    private long totalDuplicates = 0;
    private long totalErrors = 0;
    private final long startTime = System.currentTimeMillis();

    // Drain mode: unique index is active, DB catches the rare collision via retry logic.
    public FileConsumer() {
        this.usedIds = null;
    }

    // File-load mode: unique index is dropped for speed; shared set prevents cross-consumer duplicates.
    public FileConsumer(Set<Long> usedIds) {
        this.usedIds = usedIds;
    }

    private String generateUniqueId() {
        if (usedIds == null) return idGenerator.generate();
        String id = idGenerator.generate();
        while (!usedIds.add(Long.parseLong(id))) {
            id = idGenerator.generate();
        }
        return id;
    }

    public void consume() throws IOException, java.util.concurrent.TimeoutException {
        // log.debug("consume() start on {}", Thread.currentThread().getName());
        Channel channel = RabbitMQConfig.createChannel();
        // log.debug("channel created on {}", Thread.currentThread().getName());
        channel.basicQos(BATCH_SIZE * 2);
        // log.debug("QoS set on {}", Thread.currentThread().getName());

        List<Transaction> batch = new ArrayList<>();
        List<String> batchLines = new ArrayList<>();
        List<Long> deliveryTags = new ArrayList<>();

        DeliverCallback callback = (consumerTag, delivery) -> {
            // log.debug("message received on {}", Thread.currentThread().getName());
            String line = new String(delivery.getBody());
            try {
                Transaction transaction = parseLine(line);
                transaction.setGeneratedId(generateUniqueId());

                List<Transaction> batchSnapshot = null;
                List<String> linesSnapshot = null;
                List<Long> tagsSnapshot = null;

                synchronized (batchLock) {
                    batch.add(transaction);
                    batchLines.add(line);
                    deliveryTags.add(delivery.getEnvelope().getDeliveryTag());

                    if (batch.size() >= BATCH_SIZE) {
                        batchSnapshot = new ArrayList<>(batch);
                        linesSnapshot = new ArrayList<>(batchLines);
                        tagsSnapshot = new ArrayList<>(deliveryTags);
                        batch.clear();
                        batchLines.clear();
                        deliveryTags.clear();
                    }
                }

                if (batchSnapshot != null) {
                    submitFlush(channel, batchSnapshot, linesSnapshot, tagsSnapshot);
                }
            } catch (Exception e) {
                totalErrors++;
                logError(e, line);
                channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
            }
        };

        // Flush whatever is buffered every 10 seconds — handles the tail end of the queue
        // where remaining messages never accumulate to a full batch.
        scheduler.scheduleAtFixedRate(() -> {
            List<Transaction> batchSnapshot;
            List<String> linesSnapshot;
            List<Long> tagsSnapshot;

            synchronized (batchLock) {
                if (batch.isEmpty()) return;
                batchSnapshot = new ArrayList<>(batch);
                linesSnapshot = new ArrayList<>(batchLines);
                tagsSnapshot = new ArrayList<>(deliveryTags);
                batch.clear();
                batchLines.clear();
                deliveryTags.clear();
            }

            log.info("Timed flush: flushing {} buffered messages", batchSnapshot.size());
            submitFlush(channel, batchSnapshot, linesSnapshot, tagsSnapshot);
        }, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // log.debug("calling basicConsume on {}", Thread.currentThread().getName());
        channel.basicConsume(RabbitMQConfig.getQueueName(), false, callback, consumerTag -> {});
        // log.debug("basicConsume returned on {}", Thread.currentThread().getName());
    }

    private void submitFlush(Channel channel, List<Transaction> batchSnapshot,
                              List<String> linesSnapshot, List<Long> tagsSnapshot) {
        flushExecutor.submit(() -> {
            try {
                flushBatch(channel, batchSnapshot, linesSnapshot, tagsSnapshot);
            } catch (Exception e) {
                log.error("Flush failed, nacking {} messages to DLQ: {}", tagsSnapshot.size(), e.getMessage(), e);
                for (Long tag : tagsSnapshot) {
                    try { channel.basicNack(tag, false, false); } catch (IOException ignored) {}
                }
            }
        });
    }

    private void flushBatch(Channel channel, List<Transaction> batch, List<String> batchLines, List<Long> deliveryTags) throws IOException, SQLException {
    long batchStart = System.currentTimeMillis();
    int skipped = 0;
    int maxPkRetries = 3;

    for (int attempt = 0; attempt <= maxPkRetries; attempt++) {
        try {
            skipped = transactionRepository.saveBatch(batch);
            break;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062 && attempt < maxPkRetries) {
                String dupId = parseDuplicateId(e.getMessage());
                if (dupId == null) throw e;
                final int currentAttempt = attempt;
                batch.stream()
                     .filter(t -> dupId.equals(t.getGeneratedId()))
                     .findFirst()
                     .ifPresent(t -> {
                         log.warn("generated_id collision on id={}, retry {}/{}", dupId, currentAttempt + 1, maxPkRetries);
                         t.setGeneratedId(generateUniqueId());
                     });
            } else if (isRetriable(e)) {
                log.warn("Retriable DB error ({}), falling back to individual inserts.", e.getErrorCode());
                skipped = fallbackToIndividual(batch, batchLines, deliveryTags, channel);
                break;
            } else {
                log.error("Unexpected error during batch insert", e);
                throw e;
            }
        }
    }

    long batchMs = System.currentTimeMillis() - batchStart;
    totalProcessed += batch.size() - skipped;
    totalDuplicates += skipped;

    logStats(batch.size(), skipped, batchMs);
    channel.basicAck(deliveryTags.get(deliveryTags.size() - 1), true);

    batch.clear();
    batchLines.clear();
    deliveryTags.clear();
}

private String parseDuplicateId(String message) {
    if (message == null) return null;
    java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("Duplicate entry '(.+?)' for key 'transactions\\.ux_generated_id'")
            .matcher(message);
    return m.find() ? m.group(1) : null;
}

private boolean isRetriable(SQLException e) {
    int code = e.getErrorCode();
    return code == 1213 // deadlock
        || code == 1205 // lock wait timeout
        || (e.getMessage() != null && e.getMessage().toLowerCase().contains("deadlock"));
}

// New fallback using INSERT IGNORE instead of normal INSERT
private int fallbackToIndividual(List<Transaction> batch, List<String> batchLines,
                                  List<Long> deliveryTags, Channel channel) {
    int skipped = 0;
    for (int i = 0; i < batch.size(); i++) {
        try {
            saveWithRetry(batch.get(i));
            channel.basicAck(deliveryTags.get(i), false);
        } catch (Exception ex) {
            totalErrors++;
            logError(ex, batchLines.get(i));
            try {
                channel.basicNack(deliveryTags.get(i), false, false);
            } catch (IOException ignored) {}
        }
    }
    return skipped;
}

private void saveWithRetry(Transaction t) throws SQLException {
    int maxRetries = 3;
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
        try {
            transactionRepository.save(t);
            return;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062 && attempt < maxRetries) {
                log.warn("generated_id collision on id={}, retry {}/{}", t.getGeneratedId(), attempt + 1, maxRetries);
                t.setGeneratedId(generateUniqueId());
            } else {
                throw e;
            }
        }
    }
}
    private void logStats(int batchSize, int skipped, long batchMs) {
        long elapsed = System.currentTimeMillis() - startTime;
        double batchRate = batchMs > 0 ? (batchSize - skipped) / (batchMs / 1000.0) : 0;
        double lifetimeRate = totalProcessed / (elapsed / 1000.0);
        log.info("Batch {}: inserted={} skipped={} in {}ms | batch={}/s lifetime={}/s total={} dupes={} errors={}",
                batchSize, batchSize - skipped, skipped, batchMs,
                String.format("%.0f", batchRate), String.format("%.0f", lifetimeRate),
                totalProcessed, totalDuplicates, totalErrors);
    }

    private String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }

    private Transaction parseLine(String line) {
        String[] fields = splitCsv(line);
        Transaction t = new Transaction();
        t.setPaymentTypeId(fields[0]);
        t.setSourceId(fields[1]);
        t.setThirdpartyId(fields[2]);
        t.setSourceDateCreated(LocalDateTime.parse(fields[3], FORMATTER));
        t.setSourceAccountNo(fields[4]);
        t.setSourceTransId(fields[5]);
        t.setChannelId(fields[6]);
        t.setTerminalId(fields[7]);
        t.setMerchantId(fields[8]);
        t.setProductId(fields[9]);
        t.setSubMerchantId(fields[10]);
        t.setAccountref(fields[11]);
        t.setAccountname(fields[12]);
        t.setPaymentmsisdn(fields[13]);
        t.setNarration(fields[14]);
        t.setCurrency(fields[15]);
        t.setAmount(new BigDecimal(fields[16]));
        t.setFees(new BigDecimal(fields[17]));
        t.setYear(Integer.parseInt(fields[18]));
        t.setProcessor(fields[19]);
        t.setCountry(fields[20]);
        t.setTranstype(fields[21]);
        t.setMonth(fields[22]);
        return t;
    }

    private void logError(Exception e, String payload) {
        Log errorLog = new Log();
        errorLog.setLevel("ERROR");
        errorLog.setSource("FileConsumer");
        errorLog.setMessage(e.getMessage());
        errorLog.setStacktrace(java.util.Arrays.toString(e.getStackTrace()));
        errorLog.setPayload(payload);
        try {
            logRepository.save(errorLog);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
