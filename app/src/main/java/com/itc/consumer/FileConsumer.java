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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileConsumer {
    private final TransactionRepository transactionRepository = new TransactionRepository();
    private final LogRepository logRepository = new LogRepository();
    private final IdGeneratorService idGenerator = new IdGeneratorService();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int BATCH_SIZE = 10_000;
    private static final Logger log = LoggerFactory.getLogger(FileConsumer.class);
    private final ExecutorService flushExecutor = Executors.newSingleThreadExecutor();

    // Observability counters — updated only from the RabbitMQ callback thread
    private long totalProcessed = 0;
    private long totalDuplicates = 0;
    private long totalErrors = 0;
    private final long startTime = System.currentTimeMillis();

    public void consume() throws IOException, java.util.concurrent.TimeoutException {
        Channel channel = RabbitMQConfig.createChannel();
        RabbitMQConfig.declareQueues(channel);
        channel.basicQos(BATCH_SIZE); // Prefetch count for fair dispatch

        List<Transaction> batch = new ArrayList<>();
        List<String> batchLines = new ArrayList<>();
        List<Long> deliveryTags = new ArrayList<>();

        DeliverCallback callback = (consumerTag, delivery) -> {
            String line = new String(delivery.getBody());
            try {
                Transaction transaction = parseLine(line);
                transaction.setSourceHash(computeHash(transaction));
                transaction.setId(idGenerator.generate());
                batch.add(transaction);
                batchLines.add(line);
                deliveryTags.add(delivery.getEnvelope().getDeliveryTag());

                if (batch.size() >= BATCH_SIZE) {
                    List<Transaction> batchSnapshot = new ArrayList<>(batch);
                    List<String> linesSnapshot = new ArrayList<>(batchLines);
                    List<Long> tagsSnapshot = new ArrayList<>(deliveryTags);
                    batch.clear();
                    batchLines.clear();
                    deliveryTags.clear();
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
            } catch (Exception e) {
                totalErrors++;
                logError(e, line);
                // Nack to DLQ — message is preserved for replay, not silently discarded
                channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
            }
        };

        channel.basicConsume(RabbitMQConfig.getQueueName(), false, callback, consumerTag -> {});
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

    try {
        skipped = transactionRepository.saveBatch(batch);
    }
    catch (SQLException e) {
        if (isRetriable(e)) {
            log.warn("Retriable DB error ({}), falling back to individual INSERT IGNORE.", e.getErrorCode());
            skipped = fallbackToIndividualIgnore(batch, batchLines, deliveryTags, channel);
        } else {
            log.error("Unexpected error during batch insert", e);
            throw e;
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

private boolean isRetriable(SQLException e) {
    int code = e.getErrorCode();
    return code == 1213 // deadlock
        || code == 1205 // lock wait timeout
        || (e.getMessage() != null && e.getMessage().toLowerCase().contains("deadlock"));
}

// New fallback using INSERT IGNORE instead of normal INSERT
private int fallbackToIndividualIgnore(List<Transaction> batch, List<String> batchLines,
                                       List<Long> deliveryTags, Channel channel) {
    int skipped = 0;
    for (int i = 0; i < batch.size(); i++) {
        try {
            int result = transactionRepository.saveIgnore(batch.get(i));
            if (result == 0) skipped++;   // duplicate
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

    private Transaction parseLine(String line) {
        String[] fields = line.replace("\"", "").split(",");
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
