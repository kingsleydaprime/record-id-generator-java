package com.itc;

import com.itc.config.DatabaseConfig;
import com.itc.config.RabbitMQConfig;
import com.itc.model.Transaction;
import com.itc.repository.TransactionRepository;
import com.itc.service.IdGeneratorService;
import com.itc.service.InMemoryIdRegistry;
import com.rabbitmq.client.Channel;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures the cost of every core operation individually so you can pinpoint bottlenecks.
 *
 * Usage:  ./gradlew run --args="--benchmark"
 *
 * Phase 1 — Micro-benchmarks  (no services required)
 *   ID generation, Long.parseLong, HashSet.add, H2 registry
 *
 * Phase 2 — RabbitMQ throughput  (requires RabbitMQ running)
 *   Publish rate, consume rate
 *
 * Phase 3 — MySQL insert throughput  (requires MySQL running)
 *   Batch sizes: 1, 1 000, 10 000, 50 000
 *   WARNING: TRUNCATE TABLE transactions before and after each test
 *
 * Phase 4 — Projections + observed pipeline timings
 */
public class Benchmark {

    private static final int    WARMUP      =    10_000;
    private static final int    MICRO_N     = 1_000_000;
    private static final int    MQ_N        =   100_000;
    private static final int    DB_N        =   100_000;
    private static final long   TOTAL_ROWS  = 5_752_677;
    private static final String BENCH_QUEUE = "benchmark.queue";

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Full benchmark — all 4 phases.
     * Phase 3 truncates the transactions table, so only run against a dev database.
     * Triggered by: --benchmark
     */
    public void run() throws Exception {
        header("PHASE 1 — MICRO-BENCHMARKS  (no services required)");
        long genNs      = benchmarkIdGeneration();
        long parseNs    = benchmarkParseLong();
        long hashSetNs  = benchmarkHashSet();
        long h2Ns       = benchmarkH2Registry();

        separator();
        header("PHASE 2 — RABBITMQ THROUGHPUT  (requires RabbitMQ on localhost:5672)");
        benchmarkRabbitMQ();

        separator();
        header("PHASE 3 — MYSQL INSERT THROUGHPUT  (requires MySQL on localhost:3306)");
        System.out.println("  WARNING: TRUNCATE TABLE transactions is run before and after each test.");
        System.out.println("  Only run this against a dev/throwaway database.\n");
        benchmarkMySQL();

        separator();
        header("PHASE 4 — PROJECTIONS + OBSERVED PIPELINE TIMINGS");
        projections(genNs, parseNs, hashSetNs, h2Ns);
        separator();
        pipelineSummary();
    }

    /**
     * Automatic benchmark — runs at the start of every pipeline run.
     * Skips Phase 3 (MySQL truncate) so it is always safe to call.
     * Triggered by: any normal pipeline invocation (with or without a file path).
     */
    public void runAutomatic() throws Exception {
        header("AUTO-BENCHMARK — baseline measurements before pipeline starts");
        System.out.println("  Tip: run with --benchmark to also include MySQL insert throughput.");
        System.out.println();

        header("PHASE 1 — MICRO-BENCHMARKS  (no services required)");
        long genNs     = benchmarkIdGeneration();
        long parseNs   = benchmarkParseLong();
        long hashSetNs = benchmarkHashSet();
        long h2Ns      = benchmarkH2Registry();

        separator();
        header("PHASE 2 — RABBITMQ THROUGHPUT  (requires RabbitMQ on localhost:5672)");
        benchmarkRabbitMQ();

        separator();
        header("PHASE 3 — PROJECTIONS + OBSERVED PIPELINE TIMINGS");
        projections(genNs, parseNs, hashSetNs, h2Ns);
        separator();
        pipelineSummary();

        System.out.println();
        System.out.println("=".repeat(74));
        System.out.println("  Benchmarks done — starting pipeline...");
        System.out.println("=".repeat(74));
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Phase 1 — Micro-benchmarks
    // -------------------------------------------------------------------------

    private long benchmarkIdGeneration() {
        IdGeneratorService gen = new IdGeneratorService();
        for (int i = 0; i < WARMUP; i++) gen.generate();

        long start = System.nanoTime();
        for (int i = 0; i < MICRO_N; i++) gen.generate();
        long ns = System.nanoTime() - start;

        printMicro("IdGeneratorService.generate()",
                ns, "SecureRandom over digit alphabet — generates one 12-char ID string");
        return ns;
    }

    private long benchmarkParseLong() {
        IdGeneratorService gen = new IdGeneratorService();
        String[] ids = new String[MICRO_N];
        for (int i = 0; i < MICRO_N; i++) ids[i] = gen.generate();

        long start = System.nanoTime();
        long sink = 0;
        for (int i = 0; i < MICRO_N; i++) sink += Long.parseLong(ids[i]);
        long ns = System.nanoTime() - start;
        if (sink == 0) System.out.print(""); // prevent dead-code elimination

        printMicro("Long.parseLong(id)",
                ns, "converts the String ID to a primitive long before storing in registry");
        return ns;
    }

    private long benchmarkHashSet() {
        IdGeneratorService gen = new IdGeneratorService();

        // warmup with a throwaway set
        Set<Long> warmupSet = new HashSet<>(WARMUP);
        for (int i = 0; i < WARMUP; i++) warmupSet.add(Long.parseLong(gen.generate()));

        // pre-generate IDs so timing loop only measures the add()
        String[] ids = new String[MICRO_N];
        for (int i = 0; i < MICRO_N; i++) ids[i] = gen.generate();

        Set<Long> set = new HashSet<>(MICRO_N + 16);
        long start = System.nanoTime();
        int added = 0;
        for (int i = 0; i < MICRO_N; i++) {
            if (set.add(Long.parseLong(ids[i]))) added++;
        }
        long ns = System.nanoTime() - start;

        int collisions = MICRO_N - added;
        long memMb = (long) MICRO_N * 48 / (1024 * 1024);
        printMicro("HashSet<Long>.add()  (parseLong included)",
                ns, collisions + " collisions in " + fmt(MICRO_N) + " calls | ~" + memMb + " MB at " + fmt(MICRO_N) + " entries (boxed Long ≈ 48 bytes)");
        return ns;
    }

    private long benchmarkH2Registry() throws SQLException {
        IdGeneratorService gen = new IdGeneratorService();

        // pre-generate IDs
        String[] ids = new String[MICRO_N + WARMUP];
        for (int i = 0; i < ids.length; i++) ids[i] = gen.generate();

        // full call: parseLong + register
        long h2Ns;
        int collisions;
        try (InMemoryIdRegistry registry = new InMemoryIdRegistry()) {
            for (int i = 0; i < WARMUP; i++) registry.register(Long.parseLong(ids[i]));

            long start = System.nanoTime();
            int registered = 0;
            for (int i = WARMUP; i < WARMUP + MICRO_N; i++) {
                if (registry.register(Long.parseLong(ids[i]))) registered++;
            }
            h2Ns = System.nanoTime() - start;
            collisions = MICRO_N - registered;
        }

        long memMb = (long) MICRO_N * 8 / (1024 * 1024);
        printMicro("InMemoryIdRegistry.register()  (parseLong included)",
                h2Ns, collisions + " collisions in " + fmt(MICRO_N) + " calls | ~" + memMb + " MB at " + fmt(MICRO_N) + " entries (raw BIGINT, no boxing)");

        // register() alone — isolates just the H2 INSERT cost
        long[] longs = new long[MICRO_N + WARMUP];
        for (int i = 0; i < longs.length; i++) longs[i] = Long.parseLong(ids[i]);

        try (InMemoryIdRegistry registry = new InMemoryIdRegistry()) {
            for (int i = 0; i < WARMUP; i++) registry.register(longs[i]);

            long start = System.nanoTime();
            for (int i = WARMUP; i < WARMUP + MICRO_N; i++) registry.register(longs[i]);
            long aloneNs = System.nanoTime() - start;

            System.out.printf("  └─ H2 INSERT alone (no parseLong): %,d ns/call | %,.0f calls/sec%n%n",
                    aloneNs / MICRO_N, rate(aloneNs));
        }

        return h2Ns;
    }

    // -------------------------------------------------------------------------
    // Phase 2 — RabbitMQ throughput
    // -------------------------------------------------------------------------

    private void benchmarkRabbitMQ() {
        // A realistic CSV line — same size as actual messages
        String payload = "MOMO,MTN,TXN001,2024-01-01 00:00:00,ACC001,SRC001,MOB,TERM001," +
                "MERCH001,PROD001,SUB001,REF001,John Doe,0244000000,Payment for goods," +
                "GHS,50.00,2.50,2024,MTN,Ghana,TRANSFER,JAN";
        byte[] body = payload.getBytes();

        System.out.printf("  Message size: %d bytes  |  sample: %,d messages%n%n", body.length, MQ_N);

        // --- Publish ---
        try (Channel ch = RabbitMQConfig.createChannel()) {
            ch.queueDeclare(BENCH_QUEUE, false, false, true, null);

            // fire-and-forget (no confirms) — raw throughput ceiling
            long start = System.nanoTime();
            for (int i = 0; i < MQ_N; i++) {
                ch.basicPublish("", BENCH_QUEUE, null, body);
            }
            long ns = System.nanoTime() - start;
            System.out.printf("  Publish (fire-and-forget):       %,.0f msg/sec  (%,dms for %,d msgs)%n",
                    rate(ns), ns / 1_000_000, MQ_N);

            // confirmed publish — what the actual producer does
            ch.queuePurge(BENCH_QUEUE);
            ch.confirmSelect();
            start = System.nanoTime();
            for (int i = 0; i < MQ_N; i++) {
                ch.basicPublish("", BENCH_QUEUE, null, body);
            }
            ch.waitForConfirmsOrDie(30_000);
            ns = System.nanoTime() - start;
            System.out.printf("  Publish (with broker confirms):  %,.0f msg/sec  (%,dms for %,d msgs)%n%n",
                    rate(ns), ns / 1_000_000, MQ_N);

        } catch (Exception e) {
            System.out.printf("  Publish: SKIPPED — RabbitMQ not reachable (%s)%n%n",
                    rootCause(e));
        }

        // --- Consume ---
        try {
            // pre-fill the queue with fresh messages
            try (Channel pub = RabbitMQConfig.createChannel()) {
                pub.queueDeclare(BENCH_QUEUE, false, false, true, null);
                pub.queuePurge(BENCH_QUEUE);
                for (int i = 0; i < MQ_N; i++) pub.basicPublish("", BENCH_QUEUE, null, body);
            }

            CountDownLatch latch = new CountDownLatch(MQ_N);
            AtomicLong firstDelivery = new AtomicLong(0);

            try (Channel ch = RabbitMQConfig.createChannel()) {
                ch.basicQos(MQ_N);
                ch.basicConsume(BENCH_QUEUE, true, (tag, delivery) -> {
                    firstDelivery.compareAndSet(0, System.nanoTime());
                    latch.countDown();
                }, tag -> {});

                long waitStart = System.nanoTime();
                boolean done = latch.await(60, TimeUnit.SECONDS);
                long totalNs = System.nanoTime() - firstDelivery.get();

                if (done) {
                    System.out.printf("  Consume (no-op handler, autoAck): %,.0f msg/sec  (%,dms for %,d msgs)%n%n",
                            rate(totalNs), totalNs / 1_000_000, MQ_N);
                } else {
                    long received = MQ_N - latch.getCount();
                    System.out.printf("  Consume: TIMED OUT — received %,d of %,d in 60s%n%n", received, MQ_N);
                }
            }

            // cleanup
            try (Channel ch = RabbitMQConfig.createChannel()) {
                ch.queueDelete(BENCH_QUEUE);
            }
        } catch (Exception e) {
            System.out.printf("  Consume: SKIPPED — RabbitMQ not reachable (%s)%n%n", rootCause(e));
        }
    }

    // -------------------------------------------------------------------------
    // Phase 3 — MySQL insert throughput
    // -------------------------------------------------------------------------

    private void benchmarkMySQL() {
        // verify MySQL is reachable before we do anything destructive
        try (Connection conn = DatabaseConfig.getConnection();
             Statement s = conn.createStatement()) {
            s.execute("SELECT 1 FROM transactions LIMIT 1");
        } catch (Exception e) {
            System.out.printf("  SKIPPED — MySQL not reachable or transactions table missing (%s)%n%n",
                    rootCause(e));
            return;
        }

        int[] batchSizes = {1, 1_000, 10_000, 50_000};
        TransactionRepository repo = new TransactionRepository();
        IdGeneratorService gen = new IdGeneratorService();

        System.out.printf("  %-16s  %-18s  %-18s  %s%n",
                "Batch size", "rows/sec", "time for " + fmt(DB_N), "time projected 5.75M rows");
        System.out.printf("  %-16s  %-18s  %-18s  %s%n",
                "-".repeat(16), "-".repeat(18), "-".repeat(18), "-".repeat(24));

        for (int batchSize : batchSizes) {
            try {
                truncate();

                // generate fresh unique IDs for this run
                List<Transaction> rows = new ArrayList<>(DB_N);
                try (InMemoryIdRegistry registry = new InMemoryIdRegistry()) {
                    for (int i = 0; i < DB_N; i++) {
                        String id = gen.generate();
                        while (!registry.register(Long.parseLong(id))) id = gen.generate();
                        rows.add(mockTransaction(id, i));
                    }
                }

                long start = System.nanoTime();
                for (int i = 0; i < DB_N; i += batchSize) {
                    repo.saveBatch(rows.subList(i, Math.min(i + batchSize, DB_N)));
                }
                long ns = System.nanoTime() - start;

                double rowsPerSec   = rate(ns);
                double projectedSec = TOTAL_ROWS / rowsPerSec;

                System.out.printf("  %-16s  %-18s  %-18s  %s%n",
                        fmt(batchSize),
                        String.format("%,.0f", rowsPerSec),
                        ns / 1_000_000 + " ms",
                        projectedSec < 60
                                ? String.format("%.1f sec", projectedSec)
                                : String.format("%.1f min", projectedSec / 60));

                truncate();
            } catch (Exception e) {
                System.out.printf("  Batch size %s: FAILED — %s%n", fmt(batchSize), rootCause(e));
            }
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Phase 4 — Projections + observed timings
    // -------------------------------------------------------------------------

    private void projections(long genNs, long parseNs, long hashSetNs, long h2Ns) {
        System.out.println("  PROJECTED TIME FOR PURE ID PROCESSING  (" + fmt(TOTAL_ROWS) + " rows)\n");
        System.out.printf("  %-52s  %s%n", "Operation", "Projected time");
        System.out.printf("  %-52s  %s%n", "-".repeat(52), "-".repeat(20));

        printProjection("ID generation only                (generate)", genNs);
        printProjection("ID generation + parseLong         (generate + parse)", genNs + parseNs);
        printProjection("HashSet dedup (generate + parse + add)",        hashSetNs);
        printProjection("H2 registry   (generate + parse + register)",   h2Ns);
        System.out.println();
        System.out.println("  Note: these are the ID processing costs in isolation.");
        System.out.println("  In the real pipeline, MySQL write time is the dominant cost (see below).");
    }

    private void pipelineSummary() {
        System.out.println("  OBSERVED PIPELINE TIMINGS — MEASURED ON THIS MACHINE\n");
        System.out.printf("  %-62s  %s%n", "Operation", "Elapsed");
        System.out.printf("  %-62s  %s%n", "-".repeat(62), "-".repeat(25));

        prow("RabbitMQ pipeline  (V1: ConcurrentHashMap, 10 consumers, 50k batch)",  "~13 min");
        prow("RabbitMQ pipeline  (V2: H2 registry,       10 consumers, 50k batch)",  "12 min 50 sec");
        prow("  └─ lifetime throughput per consumer  (V2)",                           "~741 rows/sec");
        prow("  └─ combined across 10 consumers      (V2)",                           "~7,400 rows/sec");
        prow("Bulk load  (--bulk: pre-process + LOAD DATA LOCAL INFILE)",             "7 min 10 sec");
        prow("Total rows",                                                             "5,752,677");
        System.out.println();
        System.out.println("  To see where time goes INSIDE each batch, look for the [db=Xms other=Xms]");
        System.out.println("  breakdown in the consumer log. 'db' is the MySQL INSERT. 'other' is");
        System.out.println("  everything else: CSV parse, ID generation, H2 registry, and RabbitMQ ack.");
        System.out.println("  If db >> other, MySQL is the bottleneck. If other >> db, look at the queue.");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Transaction mockTransaction(String generatedId, int seq) {
        Transaction t = new Transaction();
        t.setGeneratedId(generatedId);
        t.setPaymentTypeId("MOMO");
        t.setSourceId("SRC" + seq);
        t.setThirdpartyId("3P" + seq);
        t.setSourceDateCreated(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
        t.setSourceAccountNo("ACC" + seq);
        t.setSourceTransId("TXN" + seq);
        t.setChannelId("MOB");
        t.setTerminalId("TERM001");
        t.setMerchantId("MERCH001");
        t.setProductId("PROD001");
        t.setSubMerchantId("SUB001");
        t.setAccountref("REF" + seq);
        t.setAccountname("Test User");
        t.setPaymentmsisdn("0244000000");
        t.setNarration("Benchmark row");
        t.setCurrency("GHS");
        t.setAmount(new BigDecimal("50.00"));
        t.setFees(new BigDecimal("2.50"));
        t.setYear(2024);
        t.setProcessor("MTN");
        t.setCountry("Ghana");
        t.setTranstype("TRANSFER");
        t.setMonth("JAN");
        return t;
    }

    private void truncate() throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             Statement s = conn.createStatement()) {
            s.execute("TRUNCATE TABLE transactions");
        }
    }

    private void printMicro(String name, long totalNs, String note) {
        System.out.printf("%n  %s%n", name);
        System.out.printf("    per call:   %,8d ns%n", totalNs / MICRO_N);
        System.out.printf("    throughput: %,12.0f calls/sec%n", rate(totalNs));
        System.out.printf("    note:       %s%n", note);
    }

    private void printProjection(String label, long totalNs) {
        double secs = (double) totalNs / MICRO_N * TOTAL_ROWS / 1_000_000_000.0;
        String time = secs < 60
                ? String.format("%.1f sec", secs)
                : String.format("%.1f min", secs / 60);
        System.out.printf("  %-52s  %s%n", label, time);
    }

    private void prow(String label, String value) {
        System.out.printf("  %-62s  %s%n", label, value);
    }

    private double rate(long totalNs) {
        return MICRO_N / (totalNs / 1_000_000_000.0);
    }

    private void header(String title) {
        System.out.println();
        System.out.println("=".repeat(74));
        System.out.println("  " + title);
        System.out.println("=".repeat(74));
        System.out.println();
    }

    private void separator() {
        System.out.println();
        System.out.println("-".repeat(74));
    }

    private String fmt(long n) {
        return String.format("%,d", n);
    }

    private String rootCause(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
