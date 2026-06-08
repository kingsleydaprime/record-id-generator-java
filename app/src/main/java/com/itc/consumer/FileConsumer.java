package com.itc.consumer;

import com.itc.config.RabbitMQConfig;
import com.itc.model.Log;
import com.itc.model.Transaction;
import com.itc.repository.LogRepository;
import com.itc.repository.TransactionRepository;
import com.itc.service.FeeCalculator;
import com.itc.service.IdGeneratorService;
import com.itc.service.InMemoryIdRegistry;
import com.rabbitmq.client.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private final FeeCalculator feeCalculator = new FeeCalculator();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int BATCH_SIZE = 50_000;
    private static final Logger log = LoggerFactory.getLogger(FileConsumer.class);
    private static final int FLUSH_INTERVAL_SECONDS = 10;
    private final ExecutorService flushExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object batchLock = new Object();

    // Shared across all consumers — H2 in-memory registry enforces uniqueness
    // since MySQL no longer has a unique index on generated_id.
    private final InMemoryIdRegistry registry;

    // volatile so Main can safely read these after shutdown() drains the executor
    private volatile long totalProcessed = 0;
    private volatile long totalDuplicates = 0;
    private volatile long totalErrors = 0;
    private volatile long totalIdGenNs = 0;
    private volatile long totalDbMs = 0;
    private volatile long totalBatches = 0;
    private final long startTime = System.currentTimeMillis();

    public FileConsumer(InMemoryIdRegistry registry) {
        this.registry = registry;
    }

    public void shutdown() throws InterruptedException {
        scheduler.shutdown();
        scheduler.awaitTermination(30, TimeUnit.SECONDS);
        flushExecutor.shutdown();
        flushExecutor.awaitTermination(60, TimeUnit.SECONDS);
    }

    public long getTotalProcessed()  { return totalProcessed; }
    public long getTotalDuplicates() { return totalDuplicates; }
    public long getTotalErrors()     { return totalErrors; }
    public long getTotalIdGenNs()    { return totalIdGenNs; }
    public long getTotalDbMs()       { return totalDbMs; }
    public long getTotalBatches()    { return totalBatches; }

    private String generateUniqueId() throws SQLException {
        String id = idGenerator.generate();
        while (!registry.register(Long.parseLong(id))) {
            id = idGenerator.generate();
        }
        return id;
    }

    public void consume() throws IOException, java.util.concurrent.TimeoutException {
        Channel channel = RabbitMQConfig.createChannel();
        channel.basicQos(BATCH_SIZE * 2);

        List<Transaction> batch = new ArrayList<>();
        List<String> batchLines = new ArrayList<>();
        List<Long> deliveryTags = new ArrayList<>();

        DeliverCallback callback = (consumerTag, delivery) -> {
            String line = new String(delivery.getBody());
            try {
                Transaction transaction = parseLine(line);
                long idStart = System.nanoTime();
                transaction.setGeneratedId(generateUniqueId());
                totalIdGenNs += System.nanoTime() - idStart;
                feeCalculator.calculate(transaction);

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

        channel.basicConsume(RabbitMQConfig.getQueueName(), false, callback, consumerTag -> {});
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

    private void flushBatch(Channel channel, List<Transaction> batch, List<String> batchLines,
                             List<Long> deliveryTags) throws IOException, SQLException {
        long batchStart = System.currentTimeMillis();
        int skipped = 0;

        long dbStart = System.currentTimeMillis();
        try {
            skipped = transactionRepository.saveBatch(batch);
        } catch (SQLException e) {
            if (isRetriable(e)) {
                log.warn("Retriable DB error ({}), falling back to individual inserts.", e.getErrorCode());
                skipped = fallbackToIndividual(batch, batchLines, deliveryTags, channel);
            } else {
                log.error("Unexpected error during batch insert", e);
                throw e;
            }
        }
        long dbMs = System.currentTimeMillis() - dbStart;

        long batchMs = System.currentTimeMillis() - batchStart;
        totalProcessed += batch.size() - skipped;
        totalDuplicates += skipped;
        totalDbMs += dbMs;
        totalBatches++;

        logStats(batch.size(), skipped, batchMs, dbMs);
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

    private int fallbackToIndividual(List<Transaction> batch, List<String> batchLines,
                                      List<Long> deliveryTags, Channel channel) {
        int skipped = 0;
        for (int i = 0; i < batch.size(); i++) {
            try {
                transactionRepository.save(batch.get(i));
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

    private void logStats(int batchSize, int skipped, long batchMs, long dbMs) {
        long elapsed = System.currentTimeMillis() - startTime;
        long otherMs = batchMs - dbMs; // parse + id gen + h2 registry + ack
        double batchRate = batchMs > 0 ? (batchSize - skipped) / (batchMs / 1000.0) : 0;
        double lifetimeRate = totalProcessed / (elapsed / 1000.0);
        log.info("Batch {}: inserted={} skipped={} in {}ms [db={}ms other={}ms] | batch={}/s lifetime={}/s total={} dupes={} errors={}",
                batchSize, batchSize - skipped, skipped, batchMs, dbMs, otherMs,
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
