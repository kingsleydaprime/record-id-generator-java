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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
    private static final int BATCH_SIZE = 10_000;
    private static final Logger log = LoggerFactory.getLogger(FileConsumer.class);
    private static final int FLUSH_INTERVAL_SECONDS = 10;
    private final ExecutorService flushExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object batchLock = new Object();

    // Observability counters — updated only from the RabbitMQ callback thread
    private long totalProcessed = 0;
    private long totalDuplicates = 0;
    private long totalErrors = 0;
    private final long startTime = System.currentTimeMillis();

    public void consume() throws IOException, java.util.concurrent.TimeoutException {
        log.debug("consume() start on {}", Thread.currentThread().getName());
        Channel channel = RabbitMQConfig.createChannel();
        log.debug("channel created on {}", Thread.currentThread().getName());
        channel.basicQos(BATCH_SIZE * 2);
        log.debug("QoS set on {}", Thread.currentThread().getName());

        List<Transaction> batch = new ArrayList<>();
        List<String> batchLines = new ArrayList<>();
        List<Long> deliveryTags = new ArrayList<>();

        DeliverCallback callback = (consumerTag, delivery) -> {
            log.debug("message received on {}", Thread.currentThread().getName());
            String line = new String(delivery.getBody());
            try {
                Transaction transaction = parseLine(line);
                // transaction.setSourceHash(computeHash(transaction));
                transaction.setId(idGenerator.generate());

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

        log.debug("calling basicConsume on {}", Thread.currentThread().getName());
        channel.basicConsume(RabbitMQConfig.getQueueName(), false, callback, consumerTag -> {});
        log.debug("basicConsume returned on {}", Thread.currentThread().getName());
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

    // private void flushBatch(Channel channel, List<Transaction> batch, List<String> batchLines, List<Long> deliveryTags) throws IOException {
    //     long batchStart = System.currentTimeMillis();
    //     try {
    //         int skipped = transactionRepository.saveBatch(batch);
    //         long batchMs = System.currentTimeMillis() - batchStart;
    //         totalProcessed += batch.size() - skipped;
    //         totalDuplicates += skipped;
    //         logStats(batch.size(), skipped, batchMs);
    //         channel.basicAck(deliveryTags.get(deliveryTags.size() - 1), true);
    //     } catch (Exception e) {
    //         log.warn("Batch insert failed, falling back to individual saves. Cause: {}", e.getMessage());
    //         for (int i = 0; i < batch.size(); i++) {
    //             try {
    //                 saveWithRetry(batch.get(i));
    //                 totalProcessed++;
    //                 channel.basicAck(deliveryTags.get(i), false);
    //             } catch (Exception ex) {
    //                 totalErrors++;
    //                 logError(ex, batchLines.get(i));
    //                 channel.basicNack(deliveryTags.get(i), false, false);
    //             }
    //         }
    //     } finally {
    //         batch.clear();
    //         batchLines.clear();
    //         deliveryTags.clear();
    //     }
    // }

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
                     .filter(t -> dupId.equals(t.getId()))
                     .findFirst()
                     .ifPresent(t -> {
                         log.warn("PK collision on id={}, retry {}/{}", dupId, currentAttempt + 1, maxPkRetries);
                         t.setId(idGenerator.generate());
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
            .compile("Duplicate entry '(.+?)' for key 'PRIMARY'")
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
                log.warn("PK collision on id={}, retry {}/{}", t.getId(), attempt + 1, maxRetries);
                t.setId(idGenerator.generate());
            } else {
                throw e;
            }
        }
    }
}
    // private void saveWithRetry(Transaction transaction) throws Exception {
    //     int attempts = 0;
    //     while (attempts < 3) {
    //         try {
    //             transaction.setId(idGenerator.generate());
    //             transactionRepository.save(transaction);
    //             return;
    //         } catch (SQLIntegrityConstraintViolationException e) {
    //             if (isDuplicateHash(e)) {
    //                 totalDuplicates++;
    //                 log.debug("Skipping duplicate: sourceTransId={}", transaction.getSourceTransId());
    //                 return;
    //             }
    //             attempts++;
    //             if (attempts == 3) throw e;
    //         }
    //     }
    // }

    // private boolean isDuplicateHash(SQLIntegrityConstraintViolationException e) {
    //     return e.getMessage() != null && e.getMessage().contains("ux_source_hash");
    // }

    private String computeHash(Transaction t) {
        try {
            String input = t.getSourceTransId() + "|" + t.getSourceId() + "|" + t.getSourceDateCreated();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private void logStats(int batchSize, int skipped, long batchMs) {
        long elapsed = System.currentTimeMillis() - startTime;
        double rate = totalProcessed / (elapsed / 1000.0);
        log.info("Batch {}: inserted={} skipped={} in {}ms | total={} rate={}/sec dupes={} errors={}",
                batchSize, batchSize - skipped, skipped, batchMs,
                totalProcessed, String.format("%.0f", rate),
                totalDuplicates, totalErrors);
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
