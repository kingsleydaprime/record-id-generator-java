# Java Zero to Hero — Record ID Generator

A complete guide built from real experience: processing a 1.46GB CSV, generating unique 12-digit IDs, pushing records through RabbitMQ, and persisting to MySQL — all in Java.

---

## 1. Why Java?

Java is the dominant language in enterprise and fintech. It is statically typed, compiled to bytecode, and runs on the JVM (Java Virtual Machine). Every major bank, payment processor, and fintech company (like ITC) has Java at its core.

Key traits:
- **Strongly typed** — every variable has a declared type
- **Compiled** — caught at compile time, not runtime
- **Object-oriented** — everything lives in classes
- **Platform-independent** — write once, run anywhere (JVM handles it)

---

## 2. Setting Up Java

### Check your Java version
```bash
java -version
```

Multiple versions can coexist on the same machine. Enterprise Java uses **Java 21 (LTS)**. LTS = Long Term Support — stable, supported for years.

### Set JAVA_HOME
When multiple versions exist, tell your system which one to use:

```bash
# Find available versions
update-alternatives --list java

# Add to ~/.zshrc
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

# Apply
source ~/.zshrc
```

`JAVA_HOME` is an environment variable that tells tools (Maven, Gradle, IDEs) where Java lives.

---

## 3. Build Tools — Maven vs Gradle

Java projects need a **build tool** to manage dependencies (external libraries), compile code, and run tests.

| | Maven | Gradle |
|---|---|---|
| Config file | `pom.xml` | `build.gradle` or `build.gradle.kts` |
| Language | XML | Groovy or Kotlin DSL |
| Default in enterprise | Yes | Growing fast |
| Android/Kotlin | No | Yes |

### Gradle with Kotlin DSL (what we used)

```bash
mkdir my-project && cd my-project
gradle init --type java-application --dsl kotlin
```

This generates:
- `app/src/main/java/` — your source code
- `app/src/test/java/` — your tests
- `build.gradle.kts` — dependency config
- `gradlew` — wrapper script (use this, not `gradle` directly)

### Adding dependencies

In `build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.rabbitmq:amqp-client:5.21.0")
    implementation("com.mysql:mysql-connector-j:8.3.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.15.0")
    implementation("org.flywaydb:flyway-mysql:10.15.0")
    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")
}
```

### Running your app
```bash
./gradlew run
./gradlew build
./gradlew test
```

---

## 4. Java Project Structure

```
record-id-generator/
├── app/src/main/java/com/itc/
│   ├── Main.java               # Entry point
│   ├── config/                 # DB, RabbitMQ, Flyway setup
│   ├── model/                  # Data classes (Transaction, Log)
│   ├── repository/             # Database operations
│   ├── service/                # Business logic (ID generation)
│   ├── producer/               # Reads file → pushes to queue
│   └── consumer/               # Reads queue → writes to DB
├── app/src/main/resources/
│   ├── application.properties  # Config
│   └── db/migration/           # SQL migration files
└── app/src/test/java/com/itc/ # Tests
```

**Package naming convention:** `com.companyname.projectname` — reverse domain notation, globally unique.

This structure works fine for a small project. It groups files by **technical role** (config, model, repository). Every real file in this project has a clear place.

### For Scale — Layered / Hexagonal Architecture

As a codebase grows, grouping by technical role breaks down. You end up with 30 files in `model/`, 30 in `repository/`, and no way to tell which ones belong to the same feature. The scalable approach is to group by **business domain** first, then by technical role inside that.

```
com.itc/
├── domain/                     # Pure business logic — zero framework imports
│   ├── model/                  #   Transaction, Log, and other core types
│   ├── port/                   #   Interfaces your domain depends on
│   │   ├── TransactionStore.java   #     "I need something that can save transactions"
│   │   └── IdGenerator.java        #     "I need something that generates IDs"
│   └── service/                #   Business rules (ID generation, validation)
│       └── IdGeneratorService.java
│
├── application/                # Orchestrates domain — knows about use cases
│   ├── producer/               #   File reading, publishing to queue
│   │   └── FileProducer.java
│   └── consumer/               #   Message processing, batch coordination
│       └── FileConsumer.java
│
├── infrastructure/             # Implements the domain's ports — talks to the outside world
│   ├── db/                     #   MySQL implementation of TransactionStore
│   │   ├── DatabaseConfig.java
│   │   ├── TransactionRepository.java   # implements TransactionStore
│   │   └── LogRepository.java
│   ├── messaging/              #   RabbitMQ wiring
│   │   └── RabbitMQConfig.java
│   └── migration/              #   Flyway
│       └── FlywayConfig.java
│
└── Main.java                   # Wires everything together and starts the app
```

The key rule: **domain never imports infrastructure**. `IdGeneratorService` has no idea MySQL exists. `FileConsumer` depends on the `TransactionStore` interface, not `TransactionRepository` directly. This means you can swap MySQL for PostgreSQL by writing a new `TransactionRepository` — nothing in `domain/` or `application/` changes.

This pattern is called **Hexagonal Architecture** (also known as Ports and Adapters). The "ports" are the interfaces in `domain/port/`. The "adapters" are the implementations in `infrastructure/`.

| | Small project (this one) | At scale |
|---|---|---|
| Grouped by | Technical role | Business domain + role |
| Fine up to | ~5 domain concepts | Any size |
| Changes to MySQL affect | Only `repository/` | Only `infrastructure/db/` |
| Can test domain without DB | No — hard to mock | Yes — domain has no DB dependency |

---

## 5. Java Basics You Need to Know

### Classes and Objects
```java
// A class is a blueprint
public class Transaction {
    private String id;       // field
    private BigDecimal amount;

    // Constructor
    public Transaction(String id, BigDecimal amount) {
        this.id = id;
        this.amount = amount;
    }

    // Getter
    public String getId() { return id; }
}

// Create an object (instance of the class)
Transaction t = new Transaction("123456789012", new BigDecimal("34.02"));
```

### Access Modifiers
- `public` — accessible everywhere
- `private` — only within the same class
- `protected` — within package and subclasses

### Static vs Instance
```java
// Static — belongs to the class, not an object
public static void main(String[] args) { }

// Instance — belongs to an object
public String getId() { return this.id; }
```

### Common Types
```java
String name = "Kingsley";
int year = 2026;
long bigNumber = 100000000L;
double price = 34.02;
BigDecimal precise = new BigDecimal("34.02"); // Use for money, never double
boolean active = true;
LocalDateTime now = LocalDateTime.now();
```

### Exception Handling
```java
try {
    // risky operation
    transactionRepository.save(transaction);
} catch (SQLIntegrityConstraintViolationException e) {
    // handle duplicate ID — regenerate
} catch (SQLException e) {
    // handle other DB errors
} finally {
    // always runs
}
```

---

## 6. Lombok — Less Boilerplate

Java requires you to write getters and setters manually for every field. For a class like `Transaction` with 25 fields, this is brutal.

Here is what even a **trimmed-down** version of `Transaction` looks like without Lombok — just 5 of the 25 fields:

```java
public class Transaction {
    private String id;
    private String sourceHash;
    private String paymentTypeId;
    private BigDecimal amount;
    private LocalDateTime sourceDateCreated;

    // Constructor
    public Transaction() {}

    // Getters
    public String getId() { return id; }
    public String getSourceHash() { return sourceHash; }
    public String getPaymentTypeId() { return paymentTypeId; }
    public BigDecimal getAmount() { return amount; }
    public LocalDateTime getSourceDateCreated() { return sourceDateCreated; }

    // Setters
    public void setId(String id) { this.id = id; }
    public void setSourceHash(String sourceHash) { this.sourceHash = sourceHash; }
    public void setPaymentTypeId(String paymentTypeId) { this.paymentTypeId = paymentTypeId; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setSourceDateCreated(LocalDateTime sourceDateCreated) {
        this.sourceDateCreated = sourceDateCreated;
    }

    // toString — so you can print the object
    @Override
    public String toString() {
        return "Transaction{id='" + id + "', sourceHash='" + sourceHash +
               "', paymentTypeId='" + paymentTypeId + "', amount=" + amount +
               ", sourceDateCreated=" + sourceDateCreated + "}";
    }

    // equals — so two Transaction objects with the same data are considered equal
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction)) return false;
        Transaction t = (Transaction) o;
        return Objects.equals(id, t.id) && Objects.equals(amount, t.amount);
        // ... and every other field
    }

    // hashCode — needed whenever you override equals
    @Override
    public int hashCode() {
        return Objects.hash(id, sourceHash, paymentTypeId, amount, sourceDateCreated);
    }
}
```

That is ~50 lines for **5 fields**. The actual `Transaction` has 25 fields. Without Lombok, the full class would be **over 200 lines** of code that carries zero business logic — it just moves data around.

Now the same class with Lombok:

```java
import lombok.Data;

@Data
public class Transaction {
    private String id;
    private String sourceHash;
    private String paymentTypeId;
    private String sourceId;
    private String thirdpartyId;
    private LocalDateTime sourceDateCreated;
    private String sourceAccountNo;
    private String sourceTransId;
    private String channelId;
    private String terminalId;
    private String merchantId;
    private String productId;
    private String subMerchantId;
    private String accountref;
    private String accountname;
    private String paymentmsisdn;
    private String narration;
    private String currency;
    private BigDecimal amount;
    private BigDecimal fees;
    private int year;
    private String processor;
    private String country;
    private String transtype;
    private String month;
}
```

`@Data` generates at compile time:
- A getter for every field (`getId()`, `getAmount()`, etc.)
- A setter for every non-final field (`setId()`, `setAmount()`, etc.)
- `toString()` — prints all field values
- `equals()` and `hashCode()` — based on all fields
- A required-args constructor

The 200+ line manual version becomes 28 lines. No getters to forget, no `toString()` to keep in sync when you add a field — Lombok regenerates everything automatically every time you build.

**How it works**: Lombok hooks into the Java compiler as an annotation processor. It reads `@Data` and injects the bytecode before compilation finishes. There is no runtime dependency — Lombok is only needed at compile time (`compileOnly` in Gradle).

---

## 7. Configuration — Properties Files

Java convention for config:

```properties
# application.properties — safe to commit (no secrets)
db.url=jdbc:mysql://localhost:3306/itc_db
rabbitmq.host=localhost
rabbitmq.port=5672
file.path=/data/transactions.csv
```

```properties
# application-local.properties — GITIGNORED (secrets)
db.user=itc
db.password=itc
rabbitmq.user=guest
rabbitmq.pass=guest
```

Reading in Java:
```java
Properties props = new Properties();
try (InputStream in = MyClass.class.getClassLoader()
        .getResourceAsStream("application.properties")) {
    props.load(in);
}
String url = props.getProperty("db.url");
```

---

## 8. Database — MySQL + HikariCP + Flyway

### HikariCP — Connection Pooling

Opening a new DB connection every time is expensive. HikariCP maintains a **pool** of reusable connections.

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:mysql://localhost:3306/itc_db");
config.setUsername("itc");
config.setPassword("itc");
config.setMaximumPoolSize(10); // max 10 concurrent connections

HikariDataSource dataSource = new HikariDataSource(config);

// Get a connection from the pool
Connection conn = dataSource.getConnection();
```

### Flyway — Database Migrations

Flyway runs SQL scripts automatically in order, tracking which ones have run. Never worry about manually creating tables again.

File: `resources/db/migration/V1__create_tables.sql`

```sql
CREATE TABLE transactions (
    id VARCHAR(12) NOT NULL PRIMARY KEY,
    payment_type_id VARCHAR(50),
    amount DECIMAL(18,2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

Naming convention: `V{version}__{description}.sql`

```java
Flyway flyway = Flyway.configure()
    .dataSource(url, user, password)
    .locations("classpath:db/migration")
    .load();
flyway.migrate(); // runs pending migrations on startup
```

### Prepared Statements

Never concatenate SQL strings — SQL injection risk. Always use `PreparedStatement`:

```java
String sql = "INSERT INTO transactions (id, amount) VALUES (?, ?)";
PreparedStatement stmt = conn.prepareStatement(sql);
stmt.setString(1, transaction.getId());
stmt.setBigDecimal(2, transaction.getAmount());
stmt.executeUpdate();
```

---

## 9. RabbitMQ — Message Queue

RabbitMQ is a **message broker**. It decouples producers (who create messages) from consumers (who process them).

```
[File] → [Producer] → [RabbitMQ Queue] → [Consumer] → [MySQL]
```

Why? The producer can push millions of records without waiting for the DB. The consumer processes at its own pace. If the consumer crashes, messages stay in the queue — nothing is lost.

### Key concepts:
- **Queue** — holds messages until consumed
- **Channel** — a virtual connection inside a connection
- `basicPublish` — send a message
- `basicConsume` — listen and receive messages
- `basicAck` — acknowledge: "I processed this, remove it from queue"
- `basicQos(1)` — process one message at a time (fair dispatch)

```java
// Producer
channel.queueDeclare("record.queue", true, false, false, null);
//                   name           durable
channel.basicPublish("", "record.queue", null, message.getBytes());

// Consumer
channel.basicConsume("record.queue", false, (tag, delivery) -> {
    String message = new String(delivery.getBody());
    // process...
    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
}, tag -> {});
```

`durable: true` means the queue survives a RabbitMQ restart.

---

## 10. Threads — Running Code Concurrently

A **thread** is an independent execution path. The JVM runs multiple threads simultaneously.

```java
// Create a thread
Thread producerThread = new Thread(() -> {
    producer.produce(filePath); // runs in parallel
});

Thread consumerThread = new Thread(() -> {
    consumer.consume(); // also runs in parallel
});

// Start both
consumerThread.start(); // start consumer first — it needs to be ready
producerThread.start();

// Wait for producer to finish before exiting main
producerThread.join();
```

### Why threads here?

- **Producer** reads 1.46GB CSV and pushes to queue — slow I/O
- **Consumer** listens on queue and writes to DB — separate pace

Without threads, they'd run sequentially: produce everything, then consume. With threads, they run at the same time — producer fills the queue as consumer drains it.

### Thread safety

When multiple threads share data, conflicts can happen. In this project:
- Producer and consumer don't share state — RabbitMQ is the middleman
- HikariCP handles concurrent DB connections safely
- `SecureRandom` in `IdGeneratorService` is thread-safe

---

## 11. Generating Unique 12-Digit IDs

Requirements: numeric only (0–9), 12 digits, no hyphens, unique.

```java
import java.security.SecureRandom;

public class IdGeneratorService {
    private static final String DIGITS = "0123456789";
    private static final int LENGTH = 12;
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
        }
        return sb.toString();
    }
}
```

`SecureRandom` vs `Random`: `SecureRandom` is cryptographically strong — better for IDs that need to be unpredictable.

### Collision handling

10^12 = 1 trillion possible IDs. Collisions are rare but possible (birthday problem). Handle at DB level:

```sql
id VARCHAR(12) NOT NULL PRIMARY KEY  -- unique constraint
```

```java
// On SQLIntegrityConstraintViolationException, retry up to 3 times
int attempts = 0;
while (attempts < 3) {
    try {
        transaction.setId(idGenerator.generate());
        transactionRepository.save(transaction);
        return;
    } catch (SQLIntegrityConstraintViolationException e) {
        attempts++;
    }
}
```

### Exact Collision Probability — The Birthday Paradox Math

With 12 random decimal digits (k = 10^12 possible values) and n records, the expected number of collisions across the whole dataset is:

```
Expected collisions ≈ n² / (2k)
```

For 5.75M rows:
```
(5,750,000)² / (2 × 1,000,000,000,000) ≈ 16 collisions
```

So across a 5.75M row load you expect roughly **16 rows** to hit a collision — the retry loop fires 16 times and regenerates 16 IDs. Essentially zero impact on performance or correctness.

The 1-in-2-million threshold (where each new insert has a ~50% chance of colliding) is reached at roughly √(10^12) = **1 million rows**. Before that, collisions are extremely rare. After it, they become more frequent but still manageable with a retry limit of 3.

### In-Memory Dedup for Bulk Loads (No DB Round-Trip)

When the unique index is dropped during a bulk load (for speed — see Section 35), the DB can no longer catch collisions. Instead, track used IDs in a Java set before they ever reach the DB:

**HashSet\<Long\> (single-threaded, e.g. BulkLoader)**
```java
Set<Long> usedIds = new HashSet<>(8_000_000); // pre-sized
String id = idGenerator.generate();
while (!usedIds.add(Long.parseLong(id))) {    // add() returns false if already present
    id = idGenerator.generate();
}
// id is now guaranteed unique within this load
```

**ConcurrentHashMap.newKeySet() (shared across threads)**

When multiple consumer threads each generate IDs independently, they can collide with each other — not just with the DB. A thread-safe shared set prevents cross-thread duplicates:

```java
// In Main.java — created once, passed to all consumers
Set<Long> usedIds = ConcurrentHashMap.newKeySet(8_000_000);

// In FileConsumer — shared across all 10 consumers
private String generateUniqueId() {
    String id = idGenerator.generate();
    while (!usedIds.add(Long.parseLong(id))) {
        id = idGenerator.generate();
    }
    return id;
}
```

### Memory Cost and Alternatives

Storing 5.75M longs in a `ConcurrentHashMap` costs roughly **250–300MB** (boxed `Long` ≈ 48 bytes per entry including node overhead). For larger files this grows linearly:

| File size | ConcurrentHashMap | Bloom filter |
|---|---|---|
| 5.75M rows | ~300MB | ~6MB |
| 50M rows | ~2.4GB | ~60MB |
| 500M rows | OutOfMemoryError | ~600MB |

**Bloom filter** (Guava): probabilistic, uses 50× less memory. Can have false positives (says "seen" when it hasn't — causes an unnecessary regeneration) but never false negatives (never misses an actual collision). For ID dedup, false positives are harmless — you just generate another ID.

```java
// build.gradle.kts: implementation("com.google.guava:guava:33.0.0-jre")
BloomFilter<Long> seen = BloomFilter.create(
    Funnels.longFunnel(), 50_000_000, 0.001); // 50M entries, 0.1% false positive rate
```

**Post-load dedup check**: no in-memory tracking at all. Load everything (index dropped), then find collisions with SQL after:
```sql
SELECT generated_id, COUNT(*) FROM transactions
GROUP BY generated_id HAVING COUNT(*) > 1;
```
Update those ~16 rows with fresh IDs, then create the unique index. Memory cost: zero during load.

### V2: H2 In-Memory Database as the Uniqueness Registry

The supervisor asked for a second version that uses an actual **in-memory database** instead of raw Java collections. This also removes the unique index on `generated_id` from MySQL entirely (V4 migration) — the in-memory DB becomes the sole uniqueness enforcer.

#### What H2 is

**H2** is a relational database written entirely in Java. It runs inside the same JVM as your application — no separate server process, no Docker container, no network call. You get a full SQL engine (tables, indexes, constraints, transactions) with zero infrastructure overhead.

Three modes:

| Mode | URL | What it means |
|---|---|---|
| In-memory | `jdbc:h2:mem:mydb` | Database lives in RAM; gone when the JVM exits |
| Embedded file | `jdbc:h2:./data/mydb` | Persists to a local file, no server needed |
| Server | `jdbc:h2:tcp://localhost/mydb` | Network server mode, like MySQL |

For ID tracking we use **in-memory mode**. `DB_CLOSE_DELAY=-1` keeps the database alive for the lifetime of the JVM (without it, H2 closes the database when the last connection closes):

```java
DriverManager.getConnection("jdbc:h2:mem:id_registry;DB_CLOSE_DELAY=-1");
```

#### How It Works

A single table with a `BIGINT PRIMARY KEY` is created at startup:

```sql
CREATE TABLE used_ids (id BIGINT PRIMARY KEY)
```

When a consumer wants to register an ID, it attempts an INSERT:
- **INSERT succeeds** → ID is new, approved — `register()` returns `true`
- **INSERT fails with duplicate key** → ID already taken — `register()` returns `false`, generate another

H2 signals a duplicate key with SQLState class `"23"` (the ANSI SQL standard for integrity constraint violations):

```java
public synchronized boolean register(long id) throws SQLException {
    insertStmt.setLong(1, id);
    try {
        insertStmt.executeUpdate();
        return true;
    } catch (SQLException e) {
        if (e.getSQLState() != null && e.getSQLState().startsWith("23")) return false;
        throw e;
    }
}
```

This is functionally identical to `ConcurrentHashMap.newKeySet().add()` — same boolean return semantics, just with SQL doing the uniqueness check. The `synchronized` prevents two threads calling `executeUpdate()` on the same `PreparedStatement` simultaneously.

#### What Changed (V4 Migration + Code)

**V4 migration** — drops the unique index from MySQL permanently. The DB no longer enforces uniqueness on `generated_id`. H2 is the only guard:

```sql
ALTER TABLE transactions DROP INDEX ux_generated_id;
```

**`InMemoryIdRegistry`** (new, `com.itc.service`): opens one H2 connection, creates the table, exposes `register(long id)`, implements `AutoCloseable`.

**`FileConsumer`** — simplified to one constructor:

```java
// V1 — nullable field, null check, two constructors
private final Set<Long> usedIds;       // null in drain mode
public FileConsumer() { this.usedIds = null; }
public FileConsumer(Set<Long> usedIds) { this.usedIds = usedIds; }

// V2 — always required, one constructor, no null check
private final InMemoryIdRegistry registry;
public FileConsumer(InMemoryIdRegistry registry) { this.registry = registry; }
```

**`Main.java`** — cleaner:

```java
try (InMemoryIdRegistry registry = new InMemoryIdRegistry()) {
    for (int i = 0; i < NUM_CONSUMERS; i++) {
        new Thread(() -> new FileConsumer(registry).consume()).start();
    }
    // ... run producer or drain
}
```

`dropGeneratedIdIndex()` and `rebuildGeneratedIdIndex()` are removed entirely — there is no MySQL index to manage.

**`BulkLoader.java`** — `HashSet<Long>` → `InMemoryIdRegistry`, and `loadIntoDb()` becomes a single-step LOAD DATA with no drop/rebuild.

#### Why B-Tree Splits Are Not a Problem in H2

H2's PRIMARY KEY is also a B-tree index — so technically the same random-insert B-tree split problem that hurt MySQL exists here too. But it doesn't matter:

- **RAM vs disk**: a B-tree split in H2 is pointer rearrangement in heap memory (~100ns). In MySQL InnoDB it triggers a disk write, redo log append, and possible buffer pool eviction (~1–10ms). The difference is 10,000×.
- **Table size**: `used_ids` only stores `BIGINT` keys — no row data. The entire 5.75M-entry table fits in ~100MB of RAM. The tree is shallow, fully cache-resident, and never evicted.
- **No durability overhead**: H2 in-memory mode has no write-ahead log, no fsync, no durable commit — a split is just a few pointer updates.

H2 also supports hash indexes (`CREATE HASH INDEX`) for O(1) lookup with no B-tree at all. For this use case the standard B-tree is already fast enough.

#### Alternatives Considered

| Alternative | What it is | Why we didn't use it |
|---|---|---|
| `ConcurrentHashMap.newKeySet()` | Java hash set, thread-safe | Previous version — works, but not an actual database |
| `HashSet<Long>` | Java hash set, single-threaded | Only works for `BulkLoader`; not thread-safe for 10 consumers |
| **Redis** | In-memory server, `SADD` returns 0/1 | Requires a separate process; network overhead (~100μs) per check |
| **SQLite in-memory** (`:memory:`) | C library, runs in RAM | Needs a JNI bridge (`sqlite-jdbc`); H2 is pure Java and simpler to add |
| **Chronicle Map** | Off-heap, low GC pressure | Overkill for this scale; adds complexity |
| **RoaringBitmap** | Compressed bitset | Only works for integer IDs within `int` range (2^32); our IDs go up to 10^12 |
| **Bloom filter** | Probabilistic, 50× less RAM | False positives force unnecessary regeneration — harmless but messy; H2 is exact |
| **Post-load SQL check** | No in-memory tracking at all | Zero overhead during load, but requires a post-load query + manual update pass |

#### Scale Trade-Offs

| | `ConcurrentHashMap` | H2 in-memory |
|---|---|---|
| Lookup speed | ~50–100ns (hash table) | ~5–50μs (JDBC round-trip + B-tree) |
| Thread safety | Built-in (segment locking) | `synchronized` on the `PreparedStatement` |
| Memory at 5.75M rows | ~275MB (boxed `Long` with node overhead) | ~100MB (raw `BIGINT`, compact B-tree pages) |
| Memory at 50M rows | ~2.4GB | ~800MB |
| Memory at 500M rows | `OutOfMemoryError` likely | ~8GB (may also OOM) |
| Expresses the rule | Implicit (collection uniqueness) | Explicit (`PRIMARY KEY` constraint in SQL) |

**For this project's scale**: H2 is slightly slower per check but uses less memory due to no `Long` boxing overhead. The speed difference is invisible in practice — it's swamped by MySQL write time and network latency.

**At very large scale (100M+ rows)**: both approaches strain JVM heap. At that point, use off-heap options (Chronicle Map, Redis) or the post-load SQL check approach (zero memory cost during load).

**Architectural benefit of H2**: uniqueness is expressed as a SQL constraint — explicit, self-documenting, and enforced by a database engine rather than implicit collection behaviour.

#### What If H2 Stored the Full Rows? The Windowed Staging Buffer

A natural extension of the V2 idea: instead of writing each batch directly to MySQL, what if H2 also held the full transaction rows — then you push them to MySQL in bulk windows?

```
RabbitMQ → parse + generate ID → INSERT full row into H2
                                        ↓  (every 500k rows)
                                  bulk export H2 → MySQL
                                  clear H2 staging rows
                                        ↑  (continue)
```

**Memory stays bounded**: 500k rows × ~700 bytes ≈ 350MB per window, regardless of total file size. The window size is tunable.

**The durability problem — when do you ack?**

This is the fundamental tension the pattern creates:

| Ack timing | What happens on JVM crash | Problem |
|---|---|---|
| Ack as messages arrive | Messages gone from RabbitMQ; not yet in MySQL | Silent data loss |
| Ack only after MySQL flush | 500k messages stay unacked the whole window | `basicQos` must be 500k; crash redelivers the whole window |

Neither is clean. The current approach (ack after each MySQL batch commit) gives you at-most-one-batch-lost on crash. Windowed staging either loses the whole window or holds 500k unacked messages in RabbitMQ.

**The cross-batch uniqueness problem**

When you clear H2's row data after each flush, you also lose the ID history. Batch 2 could generate an ID that batch 1 already inserted into MySQL. Fix: split H2 into two tables with different lifecycles:

```sql
-- Never cleared — tracks IDs for the entire run (~100MB total)
CREATE TABLE used_ids (id BIGINT PRIMARY KEY)

-- Cleared after every flush — holds full rows for the current window (~350MB)
CREATE TABLE staging (generated_id VARCHAR(12), payment_type_id VARCHAR(50), ...)
```

The `used_ids` table grows for the whole run. The `staging` table is `TRUNCATE`d after each window push.

**When windowed staging wins**

The extra complexity pays off when rows need in-memory processing that is easier in SQL than in Java:

- **Deduplication across rows in the same window** — `SELECT source_trans_id, COUNT(*) GROUP BY ... HAVING COUNT(*) > 1` in H2 before pushing
- **Joins or lookups against reference data** — load a lookup table into H2 at startup, join against it during processing
- **Aggregations before write** — sum by merchant, validate totals, then write the enriched rows

For straight insert-and-move-on (what this project does), the windowed staging approach adds complexity without clear performance gain over the existing bulk path.

**Comparison**

| Approach | Memory ceiling | Durability on crash | MySQL write pattern | Complexity |
|---|---|---|---|---|
| Current RabbitMQ (50k batches) | Low (one batch in RAM) | Lose one in-flight batch | Many small committed batches | Low |
| `--bulk` (LOAD DATA) | Medium (temp CSV on disk) | Lose the whole run | One giant LOAD DATA | Medium |
| Windowed H2 staging (500k window) | Medium (one window in RAM) | Lose one window | Several medium bulk loads | High |
| Full H2 staging (all rows) | ~4–5GB for 5.75M rows | Lose everything | One flush at end | Extreme |

---

## 12. CSV Parsing at Scale

For a 1.46GB file, never load it all into memory. Read line by line:

```java
BufferedReader reader = new BufferedReader(new FileReader(filePath))
```

`BufferedReader` reads chunks into a buffer — much faster than reading byte by byte.

```java
String line;
boolean isHeader = true;
while ((line = reader.readLine()) != null) {
    if (isHeader) { isHeader = false; continue; } // skip header row
    channel.basicPublish("", queueName, null, line.getBytes());
}
```

### Parsing a CSV line

```java
String[] fields = line.replace("\"", "").split(",");
// "MOMO","MTN","123" → after replace → MOMO,MTN,123 → split → [MOMO, MTN, 123]
```

Map to object by index (order matches CSV column order):
```java
t.setPaymentTypeId(fields[0]);
t.setSourceId(fields[1]);
t.setAmount(new BigDecimal(fields[16]));
```

### The Quoted Comma Bug — Why split(",") Is Not Enough

The approach above has a silent failure mode. Consider a CSV row where one field contains a comma inside quotes:

```
"MOMO","MTN","TXN001","2024-01-01 00:00:00","ACC","SRC","MOB","TERM","MERCH","PROD","SUB","REF","Smith, John","0244000000","Payment","GHS",50.00,2.50,2024,"MTN","Ghana","TRANSFER","JAN"
```

After `line.replace("\"", "")`, the quotes are gone:
```
MOMO,MTN,TXN001,2024-01-01 00:00:00,ACC,SRC,MOB,TERM,MERCH,PROD,SUB,REF,Smith, John,0244000000,Payment,GHS,50.00,...
```

Now `split(",")` sees the comma inside `Smith, John` as a field separator. Every field from index 13 onwards shifts by one. What was `fields[16]` (amount = `50.00`) is now `fields[17]` (currency = `GHS`). When the code calls `new BigDecimal("GHS")`, Java throws:

```
Character G is neither a decimal digit number, decimal point, nor "e" notation exponential mark.
```

The message gets nacked to the DLQ. The record never makes it to the database.

The fix is a proper CSV parser that tracks whether you're inside quotes:

```java
private String[] splitCsv(String line) {
    List<String> fields = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (char c : line.toCharArray()) {
        if (c == '"') {
            inQuotes = !inQuotes;        // toggle quote state
        } else if (c == ',' && !inQuotes) {
            fields.add(current.toString().trim());
            current.setLength(0);        // reset for next field
        } else {
            current.append(c);
        }
    }
    fields.add(current.toString().trim()); // last field (no trailing comma)
    return fields.toArray(new String[0]);
}
```

Then `parseLine` becomes:
```java
String[] fields = splitCsv(line);  // was: line.replace("\"", "").split(",")
```

Commas inside quotes are now preserved as part of the field value. The field count is always correct regardless of what the data contains.

This is a very common bug in data pipelines. Any time you see `split(",")` on a CSV line, ask: can any field value contain a comma? If yes — and in real-world data it almost always can (names, addresses, narrations) — the simple split will break silently on those rows.

---

## 13. Logging — SLF4J + Logback

Never use `System.out.println` in production. Use a proper logging framework.

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileProducer {
    private static final Logger log = LoggerFactory.getLogger(FileProducer.class);

    public void produce(String filePath) {
        log.info("Producer started: {}", filePath);
        log.debug("Debug detail here");
        log.warn("Something suspicious");
        log.error("Something failed", exception);
    }
}
```

Log levels (low → high): `TRACE → DEBUG → INFO → WARN → ERROR`

SLF4J is the interface. Logback is the implementation. They're configured via `logback.xml` in resources.

---

## 14. Log Tables — A Standard You Should Always Apply

A **log table** in your database captures processing errors alongside the data they belong to. This is separate from application logs (which go to files/stdout).

```sql
CREATE TABLE logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    level VARCHAR(10),          -- ERROR, WARN, INFO
    source VARCHAR(100),        -- which class/service
    message TEXT,               -- what happened
    stack_trace TEXT,           -- full Java stack trace
    payload TEXT,               -- the raw data that failed
    correlation_id VARCHAR(100),-- trace across services
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

Why a log table?
- **Reprocessing** — you have the exact payload that failed; replay it later
- **Auditing** — prove what happened and when
- **Debugging** — stack traces stored permanently, not lost when the app restarts
- **Reporting** — query error rates, patterns, affected records

Apply this to every project that processes data: ETL pipelines, API servers, background jobs, file imports.

---

## 15. Docker — Reproducible Environments

Docker packages your app and its dependencies (MySQL, RabbitMQ) into isolated containers. Same behavior on every machine.

### Key concepts
- **Image** — a blueprint (mysql:8, rabbitmq:management)
- **Container** — a running instance of an image
- **Volume** — persists data beyond container lifecycle
- **docker-compose** — orchestrates multiple containers

### Dev compose
```yaml
services:
  mysql:
    image: mysql:8
    ports:
      - "3306:3306"     # host:container
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: itc_db
      MYSQL_USER: itc
      MYSQL_PASSWORD: itc
    volumes:
      - mysql_data:/var/lib/mysql   # data survives container restarts

  rabbitmq:
    image: rabbitmq:management
    ports:
      - "5672:5672"     # AMQP protocol
      - "15672:15672"   # Management UI → http://localhost:15672

volumes:
  mysql_data:
```

```bash
docker compose -f docker-compose.dev.yml up -d    # start in background
docker compose -f docker-compose.dev.yml ps       # check status
docker compose -f docker-compose.dev.yml down     # stop
```

### Dockerfile (multi-stage build)
```dockerfile
# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN ./gradlew build -x test  # -x test skips tests

# Stage 2: Run (smaller image, no JDK)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Multi-stage keeps your production image small — JRE only, no build tools.

---

## 16. The Full Architecture

```
┌─────────────────────────────────────────────────────┐
│                   Java Application                   │
│                                                      │
│  Thread 1 (Producer)      Thread 2 (Consumer)        │
│  ┌─────────────────┐      ┌──────────────────────┐   │
│  │  FileProducer   │      │   FileConsumer        │   │
│  │                 │      │                       │   │
│  │ Read CSV line   │      │ Receive message       │   │
│  │ Publish to      │      │ Parse CSV line        │   │
│  │ RabbitMQ        │      │ Generate 12-digit ID  │   │
│  └────────┬────────┘      │ Save to MySQL         │   │
│           │               │ Log errors to logs    │   │
└───────────┼───────────────┴──────────┬────────────┘
            │                          │
            ▼                          ▼
      ┌──────────┐              ┌──────────┐
      │ RabbitMQ │─────────────▶│  MySQL   │
      │  Queue   │              │ itc_db   │
      └──────────┘              └──────────┘
```

---

## 17. Key Takeaways

| Concept | What You Learned |
|---|---|
| Java setup | JAVA_HOME, multiple JDK versions, LTS versions |
| Gradle | Kotlin DSL, dependencies, `./gradlew run` |
| Project structure | Layered architecture: model → repository → service → producer/consumer |
| Lombok | `@Data` eliminates boilerplate |
| Properties | `application.properties` for config, `application-local.properties` for secrets |
| HikariCP | Connection pooling — never open a new DB connection per operation |
| Flyway | Schema migrations — versioned SQL, auto-applied on startup |
| Prepared statements | Always parameterize SQL — never concatenate |
| RabbitMQ | Decouples producers and consumers; durable queues survive restarts |
| Threads | `new Thread(() -> {}).start()` — run tasks concurrently |
| ID generation | `SecureRandom` + digit alphabet + DB unique constraint + retry |
| CSV at scale | `BufferedReader` line by line — never load the whole file into memory |
| Logging | SLF4J + Logback — levels: DEBUG, INFO, WARN, ERROR |
| Log table | Always have one for data pipelines — payload, stack trace, timestamp |
| Docker | Dev vs prod compose; multi-stage Dockerfile |
| Static initializer | `static { }` runs once at class load — used for shared connections |
| Try-with-resources | `try (Resource r = ...)` — auto-closes anything that implements `AutoCloseable` |
| Text blocks | `"""..."""` — multi-line strings without concatenation or escape clutter |
| Functional interfaces | Any interface with one method can be replaced with a lambda |
| LocalDateTime ↔ JDBC | `Timestamp.valueOf(localDateTime)` bridges Java 8 date types to JDBC |
| BigDecimal precision | IEEE 754 floating point cannot represent 0.1 exactly — never use double for money |
| Graceful shutdown | `thread.join()` waits for a thread to finish — missing it causes silent data loss |
| Streaming vs chunking | `BufferedReader` already streams — the real bottleneck is one INSERT per message |
| Batch inserts | `addBatch()` + `executeBatch()` — 50–100x faster than one row at a time |
| ID strategies | Timestamp+random hybrid, UUID v4/v7, Snowflake, ULID, hash-based — each fits different needs |
| Idempotent pipeline | SHA-256 `source_hash` as a secondary UNIQUE constraint — re-runs skip duplicates safely |
| Dead Letter Queue | `basicNack` failed messages instead of acking — lets you replay them after fixing the bug |
| Observability | Track messages/sec, batch latency, duplicate rate, queue depth — fly with instruments, not blind |
| PK design | Generated ID = surrogate key (row identity); `source_hash` = business key (deduplication) |

---

## 18. Static Initializer Blocks

A `static { }` block runs **once, when the class is first loaded by the JVM** — before any object is created or any static method is called. It's used to set up shared state that needs to happen exactly once.

In this project, both `DatabaseConfig` and `RabbitMQConfig` use it to establish a single shared connection:

```java
public class DatabaseConfig {
    private static HikariDataSource dataSource;  // shared across all threads

    static {
        // runs once at class load time
        Properties props = new Properties();
        // ... load config ...
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.getProperty("db.url"));
        // ...
        dataSource = new HikariDataSource(config);  // one pool for the whole app
    }

    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();  // borrows from the pool
    }
}
```

Why not do this in a constructor? Because you'd create a new connection pool every time you write `new DatabaseConfig()`. The static block ensures exactly one pool exists, no matter how many times the class is referenced.

If the static block throws an unchecked exception, the class fails to load and the JVM throws `ExceptionInInitializerError` — your app crashes immediately on startup rather than failing silently later.

---

## 19. Try-With-Resources

Java requires that external resources (file handles, DB connections, network channels) be **explicitly closed** after use. If you forget, you leak memory, file descriptors, or DB connections until the process dies.

Try-with-resources automates this. Any object that implements `AutoCloseable` (which has a single `close()` method) can be declared in the `try (...)` header:

```java
// Without try-with-resources — easy to forget the close, or miss it on exception
Connection conn = DatabaseConfig.getConnection();
PreparedStatement stmt = conn.prepareStatement(sql);
try {
    stmt.executeUpdate();
} finally {
    stmt.close();  // must manually close both
    conn.close();
}

// With try-with-resources — close() is called automatically, even if an exception is thrown
try (Connection conn = DatabaseConfig.getConnection();
     PreparedStatement stmt = conn.prepareStatement(sql)) {
    stmt.executeUpdate();
}
// conn and stmt are closed here, no matter what
```

Multiple resources can be declared in one `try (...)`, separated by semicolons. They are closed in **reverse order** of declaration (stmt first, then conn — important because closing a connection before its statement would cause issues).

In this project it's used everywhere: `FileProducer` (Channel + BufferedReader), `TransactionRepository` (Connection + PreparedStatement), `LogRepository`, and both config classes (InputStream).

---

## 20. Text Blocks (Multi-line Strings)

Introduced in Java 15, text blocks let you write multi-line strings with triple quotes (`"""`). Before this, SQL had to be concatenated across many lines with `+` and explicit newlines — error-prone and hard to read.

```java
// Old way — fragile, easy to forget a space or newline
String sql = "INSERT INTO transactions (" +
             "    id, payment_type_id, amount" +
             ") VALUES (?, ?, ?)";

// Text block — the SQL looks exactly like SQL
String sql = """
        INSERT INTO transactions (
            id, payment_type_id, amount
        ) VALUES (?, ?, ?)
        """;
```

The leading whitespace common to all lines is automatically stripped (based on the closing `"""`'s indentation), so your SQL isn't padded with spaces. You can still use `?` placeholders normally — text blocks are just a string literal, nothing special at runtime.

Used in `TransactionRepository` and `LogRepository` for all SQL statements.

---

## 21. Functional Interfaces and Callbacks

A **functional interface** is any interface that has exactly one abstract method. Java allows you to replace it with a lambda anywhere that interface is expected.

`DeliverCallback` from the RabbitMQ library is one such interface:

```java
// What DeliverCallback looks like under the hood (simplified):
@FunctionalInterface
public interface DeliverCallback {
    void handle(String consumerTag, Delivery message) throws IOException;
}
```

Because it has one method, you can write a lambda instead of a full class:

```java
// Full anonymous class (old style):
DeliverCallback callback = new DeliverCallback() {
    @Override
    public void handle(String consumerTag, Delivery delivery) throws IOException {
        String line = new String(delivery.getBody());
        // process...
    }
};

// Lambda (modern style — same thing, less noise):
DeliverCallback callback = (consumerTag, delivery) -> {
    String line = new String(delivery.getBody());
    // process...
};
```

The lambda is **asynchronous** here — `channel.basicConsume(...)` returns immediately. The callback is stored and invoked later by RabbitMQ's internal thread whenever a message arrives. This is why the consumer thread never appears to "do" anything in a loop — it just registers the callback and stays alive.

Other functional interfaces you'll see constantly in Java:
- `Runnable` — `() -> { ... }` (used for threads)
- `Comparator<T>` — `(a, b) -> a.value - b.value`
- `Predicate<T>` — `x -> x > 0`

---

## 22. LocalDateTime ↔ JDBC Timestamp

Java's date/time API (`LocalDateTime`, `LocalDate`, `ZonedDateTime`) was introduced in Java 8. JDBC (the database connection API) predates this — it uses `java.sql.Timestamp`, `java.sql.Date`, etc. from the 1990s.

When you try to store a `LocalDateTime` in a database via a `PreparedStatement`, you can't call `stmt.setLocalDateTime(...)` — that method doesn't exist. You must convert:

```java
// LocalDateTime → java.sql.Timestamp (for PreparedStatement.setTimestamp)
stmt.setTimestamp(5, Timestamp.valueOf(t.getSourceDateCreated()));

// java.sql.Timestamp → LocalDateTime (when reading back from ResultSet)
LocalDateTime dt = resultSet.getTimestamp("source_date_created").toLocalDateTime();
```

`Timestamp.valueOf(localDateTime)` treats the `LocalDateTime` as a local time (no timezone). This is fine when your app and database are in the same timezone. If they're not, use `ZonedDateTime` and `Timestamp.from(zonedDateTime.toInstant())` instead.

---

## 23. Why BigDecimal for Money (Not double)

`double` and `float` use [IEEE 754](https://en.wikipedia.org/wiki/IEEE_754) binary floating-point, which **cannot exactly represent most decimal fractions**. This is a fundamental property of how binary works:

```java
double a = 0.1;
double b = 0.2;
System.out.println(a + b);  // prints 0.30000000000000004 — not 0.3
```

This is not a Java bug. It's the same in every language using IEEE 754 (Python, JavaScript, C, etc.).

For financial calculations, even a sub-cent rounding error compounds across millions of transactions:

```java
// Scenario: charge 1000 customers GHS 34.10
double total = 0;
for (int i = 0; i < 1000; i++) total += 34.10;
System.out.println(total);  // 34100.000000000985 — 0.001 off

BigDecimal exactTotal = BigDecimal.ZERO;
for (int i = 0; i < 1000; i++) exactTotal = exactTotal.add(new BigDecimal("34.10"));
System.out.println(exactTotal);  // 34100.00 — exact
```

`BigDecimal` stores numbers in decimal (base 10) internally, so `0.1` is represented exactly. Always construct it from a **String**, not a double:

```java
new BigDecimal("34.10")  // correct — exact decimal
new BigDecimal(34.10)    // wrong — inherits the double's imprecision
```

In the database, use `DECIMAL(18, 2)` for money columns — MySQL's equivalent of BigDecimal.

---

## 24. Graceful Shutdown — The join() Gap

In `Main.java`, the producer thread is joined but the consumer thread is not:

```java
consumerThread.start();
producerThread.start();

producerThread.join();  // waits for producer to finish
// main() returns here — JVM exits
```

When `main()` returns, the JVM calls `System.exit()` internally, which **kills all threads** — including the consumer, which may still be processing messages it pulled from the queue but hasn't saved to the database yet.

In practice this often works because the consumer processes faster than the producer publishes, so by the time the producer finishes, the queue is already drained. But it's a race condition: under load, the last batch of messages could be lost.

The correct fix is to signal the consumer to stop and then wait for it:

```java
// Option 1: join the consumer too (if consume() returns at some point)
consumerThread.join();

// Option 2: use a CountDownLatch or poison pill message
// Producer sends a special "DONE" message as the last item
// Consumer exits its loop when it sees it, then the thread ends naturally
```

This is a general lesson: **always think about what happens when your app shuts down**. Data pipelines can silently lose the last few records if shutdown isn't handled explicitly.

---

## 25. SQL — The Language of Databases

SQL (Structured Query Language) is how you communicate with a relational database. Every query falls into one of four categories:

| Category | Purpose | Commands |
|---|---|---|
| **DML** — Data Manipulation | Read and change rows | `SELECT`, `INSERT`, `UPDATE`, `DELETE` |
| **DDL** — Data Definition | Create and change structure | `CREATE`, `ALTER`, `DROP`, `TRUNCATE` |
| **TCL** — Transaction Control | Group operations | `COMMIT`, `ROLLBACK`, `BEGIN` |
| **DCL** — Data Control | Permissions | `GRANT`, `REVOKE` |

---

### SELECT — Reading Data

```sql
-- Every column
SELECT * FROM transactions;

-- Specific columns
SELECT id, amount, currency, source_date_created
FROM transactions;

-- With a condition
SELECT * FROM transactions
WHERE currency = 'GHS';

-- Multiple conditions
SELECT * FROM transactions
WHERE currency = 'GHS'
  AND amount > 100.00;

-- Pattern match (% = any characters, _ = one character)
SELECT * FROM transactions
WHERE accountname LIKE 'JOHN%';

-- Sort results
SELECT * FROM transactions
ORDER BY amount DESC;           -- highest first
ORDER BY source_date_created ASC;  -- oldest first

-- Limit rows returned
SELECT * FROM transactions
LIMIT 100;

-- Skip the first N rows (useful for pagination)
SELECT * FROM transactions
ORDER BY created_at DESC
LIMIT 50 OFFSET 100;  -- rows 101–150
```

---

### Aggregate Functions — Summary Queries

```sql
-- Count all rows
SELECT COUNT(*) FROM transactions;

-- Count non-null values in a column
SELECT COUNT(merchant_id) FROM transactions;

-- Sum, average, min, max
SELECT
    SUM(amount)   AS total_amount,
    AVG(amount)   AS avg_amount,
    MIN(amount)   AS min_amount,
    MAX(amount)   AS max_amount
FROM transactions;

-- Group results by a column
SELECT currency, COUNT(*), SUM(amount)
FROM transactions
GROUP BY currency;

-- Filter groups (HAVING is WHERE for aggregated results)
SELECT processor, COUNT(*) AS total
FROM transactions
GROUP BY processor
HAVING COUNT(*) > 1000;
```

---

### INSERT — Adding Rows

```sql
-- Insert one row
INSERT INTO transactions (id, payment_type_id, amount, currency)
VALUES ('123456789012', 'MOMO', 34.10, 'GHS');

-- Insert multiple rows at once
INSERT INTO transactions (id, payment_type_id, amount, currency)
VALUES
    ('000000000001', 'CARD', 50.00, 'GHS'),
    ('000000000002', 'MOMO', 20.00, 'USD');
```

---

### UPDATE — Changing Existing Rows

```sql
-- Update specific rows
UPDATE transactions
SET processor = 'MTN_NEW'
WHERE processor = 'MTN';

-- Update multiple columns
UPDATE transactions
SET currency = 'GHS',
    country  = 'Ghana'
WHERE country = 'GH';
```

**Always include a WHERE clause.** Without it, every row in the table is updated.

---

### DELETE vs TRUNCATE — Removing Rows

This is the one you just used:

```sql
-- DELETE removes specific rows (or all rows with no WHERE)
DELETE FROM transactions WHERE year = 2023;

-- DELETE with no condition — removes all rows, slowly (row by row, logs each one)
DELETE FROM transactions;

-- TRUNCATE — removes ALL rows instantly
TRUNCATE TABLE transactions;
```

| | `DELETE` (no WHERE) | `TRUNCATE` |
|---|---|---|
| Speed | Slow — deletes row by row | Fast — deallocates all pages at once |
| Can be rolled back | Yes (inside a transaction) | No (in MySQL, it's auto-committed) |
| Resets AUTO_INCREMENT | No | Yes |
| Triggers | Fires row-level triggers | Does not fire triggers |
| WHERE clause | Yes | No |

Use `TRUNCATE` when you want to wipe a table clean and start fresh (e.g., re-running a data load during development). Use `DELETE` when you want to remove specific rows, or when you need rollback safety.

---

### DROP — Removing Structure

```sql
-- Remove the table entirely (structure + all data)
DROP TABLE transactions;

-- Remove only if it exists (safe to run even if table doesn't exist)
DROP TABLE IF EXISTS transactions;
```

`TRUNCATE` keeps the table, `DROP` destroys it. After a `DROP`, the table is gone and must be recreated (via Flyway migration in this project).

---

### Useful Queries for This Project's Tables

```sql
-- How many transactions were loaded?
SELECT COUNT(*) FROM transactions;

-- Total amount processed, by currency
SELECT currency, COUNT(*) AS count, SUM(amount) AS total
FROM transactions
GROUP BY currency
ORDER BY total DESC;

-- Transactions by processor
SELECT processor, COUNT(*) AS count
FROM transactions
GROUP BY processor
ORDER BY count DESC;

-- Transactions by year and month
SELECT year, month, COUNT(*) AS count, SUM(amount) AS total
FROM transactions
GROUP BY year, month
ORDER BY year, month;

-- Find any duplicate source_trans_id (data quality check)
SELECT source_trans_id, COUNT(*) AS occurrences
FROM transactions
GROUP BY source_trans_id
HAVING COUNT(*) > 1;

-- Check recent errors in the log table
SELECT level, source, message, payload, created_at
FROM logs
ORDER BY created_at DESC
LIMIT 20;

-- Count errors by source
SELECT source, COUNT(*) AS error_count
FROM logs
WHERE level = 'ERROR'
GROUP BY source;

-- Wipe transactions to re-run a load (development only)
TRUNCATE TABLE transactions;
```

---

### SQL Data Types Used in This Project

| SQL Type | Java Type | Use case |
|---|---|---|
| `VARCHAR(n)` | `String` | Text with a max length |
| `TEXT` | `String` | Long text (no length limit) |
| `DECIMAL(18,2)` | `BigDecimal` | Exact decimal — always for money |
| `INT` | `int` | Whole numbers |
| `BIGINT` | `long` | Large whole numbers (auto-increment IDs) |
| `DATETIME` | `LocalDateTime` | Date and time, no timezone |
| `TIMESTAMP` | `Timestamp` / `LocalDateTime` | Date + time, auto-set by DB |

`DECIMAL(18,2)` means: up to 18 total digits, 2 after the decimal point. Same precision guarantee as `BigDecimal` — this is why both are used together for money.

---

## 26. ID Design — Is Random Enough, and Better Strategies

### Is the current 12-digit random ID sufficient?

Yes — but understanding *why* matters.

`SecureRandom` produces 10¹² (1 trillion) possible values. The danger is the **birthday problem**: collisions become likely when you've inserted roughly √(10¹²) = **1 million rows**. At that point, each new insert has about a 1-in-2-million chance of colliding. The retry mechanism handles this. For a 1.5GB file with a few million rows, you'll see at most a handful of retries across the whole run.

The current implementation is *safe*. The question is whether you can do *better*.

---

### The Timestamp + Random Hybrid

Your instinct is right — mixing time with randomness is a well-established pattern. The key is which part of the timestamp you use.

**Bad**: last 5 digits of Unix milliseconds
```
Unix ms ≈ 1748000000000
Last 5 digits: 00000–99999
Cycles every: 100,000 ms = 100 seconds
```
Two IDs generated 100 seconds apart could have the same prefix. Useless.

**Good**: date prefix — `YYYYMMDD` (8 digits) + 4 random

```java
// 8-digit date prefix + 4-digit random suffix = 12 digits total
// Example: 202406020387

String datePrefix = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")); // "20240602"
String randomSuffix = String.format("%04d", random.nextInt(10000));                  // "0387"
String id = datePrefix + randomSuffix;                                                // "202406020387"
```

Benefits:
- IDs sort chronologically — queries like `WHERE id BETWEEN '20240601...' AND '20240630...'` work naturally
- The random part only needs uniqueness within a single day (10,000 values per date prefix)
- You can tell at a glance when a record was created from its ID alone

**Warning**: with only 4 random digits (10,000 values per day), collision risk is high if you're inserting more than a few thousand records per day. Adjust the split:

| Split | Date precision | Random space |
|---|---|---|
| `YYYYMMDD` (8) + 4 random | Day | 10,000 per day |
| `YYYYMMDD` (8) + 4 random | Day | 10,000 per day |
| `YYYYMM` (6) + 6 random | Month | 1,000,000 per month |
| `YYYYMMDDHHMI` (12) + 0 | Minute | Sequential, no randomness |

For a pipeline processing millions of records, `YYYYMMDD` (8) + 4 random is too tight. Stay with 12 fully random, or use one of the modern approaches below.

---

### Other ID Strategies

#### 1. UUID v4 — The Industry Default

```java
import java.util.UUID;
String id = UUID.randomUUID().toString();
// "550e8400-e29b-41d4-a716-446655440000"
```

- 128 bits of randomness — effectively zero collision probability
- Universally recognised across every language and system
- Downside: 36 characters with hyphens (or 32 without), not numeric, and random insertion order fragments B-tree indexes (bad for write-heavy tables)

#### 2. UUID v7 — Time-Ordered UUID (Modern Standard)

UUID v7 encodes a millisecond timestamp in the first 48 bits, then random bits. Looks like a standard UUID but sorts by creation time. Better for database indexes than v4 because new rows insert at the "right end" of the index rather than at random positions.

No built-in Java support yet — use a library like `uuid-creator`.

**UUID v4 and the random insert problem**: UUID v4 is fully random (128 bits). When used as a primary key or in a unique index, every new row lands at a random position in the B-tree — causing constant page splits, exactly like the random VARCHAR(12) PK problem described in Section 30. At scale (millions of rows), throughput degrades badly. UUID v7 solves this because the timestamp prefix keeps new rows near the right end of the index tree.

#### 3. Snowflake ID (Twitter's Approach)

A 64-bit integer composed of:
```
[ 41 bits: ms timestamp ] [ 10 bits: machine/worker ID ] [ 12 bits: sequence ]
```

- Fits in a `LONG` — fast, compact, numeric
- The sequence counter (0–4095) allows 4096 IDs per millisecond per machine
- Machine ID allows multiple servers to generate IDs without coordination
- Naturally time-sortable

Used by Twitter, Discord, Instagram (their variant). The right choice for distributed systems generating millions of IDs/second.

#### 4. ULID — Universally Unique Lexicographically Sortable Identifier

```
01ARZ3NDEKTSV4RRFFQ69G5FAV
├── 48-bit timestamp ──┤├── 80-bit random ──┤
```

26 characters, Crockford Base32 encoded. Sortable, URL-safe, and compatible with UUID storage (same 128 bits). Good for APIs and document stores.

#### 5. Hash-Based ID (Deterministic)

Instead of generating a random ID, derive it from the source data:

```java
import java.security.MessageDigest;

String input = sourceTransId + "|" + sourceId + "|" + sourceDateCreated;
MessageDigest digest = MessageDigest.getInstance("SHA-256");
byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
// Encode as hex string, take first 12 chars
String id = HexFormat.of().formatHex(hash).substring(0, 12);
```

Key property: **the same source transaction always produces the same ID**. This means:
- Re-running the pipeline on the same file produces the same IDs
- If a duplicate source transaction appears, it generates the same ID and the DB unique constraint rejects it — automatic deduplication
- No retry needed — if it exists, it exists

Downside: two completely different transactions could theoretically produce the same 12-char prefix (truncation collision). The full 64-char SHA-256 has no practical collision risk; truncating to 12 chars reintroduces it.

---

### Why Pure Random 12 Digits Wins for This Project's Scale

This is counterintuitive — adding a timestamp *sounds* better, but for a file with millions of records, the math shows the opposite.

The birthday problem tells you at what volume collisions become likely: roughly √(total possible values).

| Strategy | Collision space | Collisions likely after |
|---|---|---|
| `YYYYMMDD` (8) + 4 random | 10,000 per day | **~100 records** |
| `YYYYMM` (6) + 6 random | 1,000,000 per month | ~1,000 records |
| 12 pure random digits | 1,000,000,000,000 | ~1,000,000 records |

A 1.5GB CSV with ~5 million records: the `YYYYMMDD+4` approach would have constant retries from the first few hundred records onward. Pure random handles 5 million records with only a handful of retries across the whole run.

**The supervisor's requirement ("come up with your own way") is already satisfied by the current approach.** It is genuinely custom — `SecureRandom` over a numeric alphabet, 12 digits, with DB-level collision handling and retry logic. That is a design decision, not a library call.

The timestamp+random hybrid is the right choice when you need IDs that are **time-sortable** (useful for pagination, range queries, or debugging). If that matters, use `YYYYMM` (6) + 6 random = 12 total, which gives 1M slots per month. For this pipeline, `created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP` already gives you the time dimension — the ID doesn't need to encode it.

### Recommendation for This Project

Keep the generated ID as the primary key and keep the pure random approach. Add a **`source_hash`** column (full SHA-256, not truncated) as a secondary `UNIQUE` constraint. See Section 28 for the full explanation of why this is the right split.

---

## 27. Idempotent Pipelines

**Idempotent** means: running the operation once or a hundred times produces the same result.

A pipeline is idempotent if you can re-run it on the same data safely — no duplicates, no errors, no data loss. This is critical because pipelines fail and need to be restarted.

Currently this pipeline is **not idempotent**. Re-running it on the same CSV inserts every row again, creating duplicates (the primary key changes because the ID is random).

---

### Making It Idempotent with SHA-256

Add a `source_hash` column — a SHA-256 fingerprint of the fields that uniquely identify a source transaction:

```sql
-- Add to the transactions table (new Flyway migration)
ALTER TABLE transactions
ADD COLUMN source_hash VARCHAR(64) NOT NULL,
ADD UNIQUE INDEX ux_source_hash (source_hash);
```

Compute it in Java before saving:

```java
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

private String computeHash(Transaction t) throws Exception {
    String input = t.getSourceTransId() + "|" + t.getSourceId() + "|" + t.getSourceDateCreated();
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash);  // 64-char hex string
}
```

Now in `saveWithRetry`:
```java
transaction.setSourceHash(computeHash(transaction));
transaction.setId(idGenerator.generate());
transactionRepository.save(transaction);
```

When you re-run the pipeline, the same source transaction produces the same hash. MySQL rejects the insert with `SQLIntegrityConstraintViolationException` on the `source_hash` unique index. You catch it and skip — not an error, just a duplicate.

```java
} catch (SQLIntegrityConstraintViolationException e) {
    if (e.getMessage().contains("ux_source_hash")) {
        log.info("Skipping duplicate: {}", transaction.getSourceTransId());
        // not an error — already processed
    } else {
        attempts++;  // ID collision — regenerate and retry
        if (attempts == 3) throw e;
    }
}
```

### INSERT IGNORE / ON DUPLICATE KEY

MySQL also supports handling duplicates at the SQL level:

```sql
-- Silently skip duplicate rows (no error thrown)
INSERT IGNORE INTO transactions (...) VALUES (...);

-- Insert or update if duplicate
INSERT INTO transactions (id, source_hash, amount, ...)
VALUES (?, ?, ?, ...)
ON DUPLICATE KEY UPDATE amount = VALUES(amount);
```

`INSERT IGNORE` is useful when you want to re-run safely without any application-level handling. The downside is that it also silently ignores *other* errors (like type mismatches), which can hide bugs.

---

## 28. Dead Letter Queues (DLQ)

### The Problem with Acking on Failure

The current consumer acks every message — even failures:

```java
} catch (Exception e) {
    logError(e, line);
    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);  // message is gone
}
```

This means a bad message (malformed CSV, a field that doesn't parse, a DB constraint violation you didn't expect) is **permanently lost** from the queue. It's in the `logs` table, but you can't replay it from RabbitMQ.

### What a DLQ Does

A Dead Letter Queue is a second queue where messages are sent automatically when they fail — either because:
- They were rejected (`basicNack` or `basicReject` with `requeue=false`)
- They expired (message TTL reached)
- The queue reached max length

```
Main Queue ──→ Consumer (fails) ──nack──→ Dead Letter Exchange ──→ DLQ
```

You configure it at queue declaration time:

```java
Map<String, Object> args = new HashMap<>();
args.put("x-dead-letter-exchange", "");           // use default exchange
args.put("x-dead-letter-routing-key", "record.queue.dlq");  // route to this queue

channel.queueDeclare("record.queue", true, false, false, args);
channel.queueDeclare("record.queue.dlq", true, false, false, null);
```

Then in the consumer, **nack instead of ack on failure**:

```java
} catch (Exception e) {
    logError(e, line);
    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
    // false, false = don't requeue → message goes to DLQ instead
}
```

Now failed messages sit in `record.queue.dlq`. You can:
- Inspect them in the RabbitMQ management UI
- Write a separate consumer to replay them after fixing the bug
- Set up alerts when DLQ depth grows

### Nack vs Ack

| | `basicAck` | `basicNack(... false)` | `basicNack(... true)` |
|---|---|---|---|
| Message fate | Deleted from queue | Sent to DLQ (if configured) | Put back at front of queue |
| Use when | Processed successfully | Failed, don't retry | Failed, retry immediately |
| Risk | — | — | Infinite loop if always failing |

`basicNack` with `requeue=true` is dangerous — if the message always fails, it gets requeued forever (poison pill loop). Only use it for transient failures (e.g., DB temporarily unreachable).

### Investigating the DLQ — Finding Out What Failed

When the pipeline finishes and the row count in the database is lower than the CSV row count, the first place to check is the DLQ. The gap is almost always sitting there. In the RabbitMQ management UI (`http://localhost:15672`), go to **Queues and Streams** and look for `record.queue.dlq`. The message count is the number of rows that failed.

To see what the actual error was, query the `logs` table:

```sql
SELECT level, source, message, payload, created_at
FROM logs
ORDER BY created_at DESC
LIMIT 50;
```

`payload` contains the raw CSV line that failed. `message` contains the Java exception message. This is why the log table stores the payload — you can see exactly what data caused the failure and fix the parsing or processing logic before replaying.

### Replaying from the DLQ — The Shovel Plugin

Once you've fixed the bug, you need to move the failed messages back to the main queue so they get processed again. RabbitMQ's **Shovel plugin** does this — it reads messages from one queue and publishes them to another.

Enable it inside the Docker container:

```bash
# Find the container name
docker ps

# Enable the plugin
docker exec record-id-generator-rabbitmq-1 rabbitmq-plugins enable rabbitmq_shovel rabbitmq_shovel_management
```

After enabling, refresh the management UI and go to **Queues → record.queue.dlq**. Scroll down to **Move messages**, set the destination to `record.queue`, and click Move. The failed messages are now back in the main queue. Run the consumer in drain mode to process them:

```bash
./gradlew run
```

The fixed code will parse them correctly this time. Any that still fail go back to the DLQ, keeping the failure set isolated and inspectable.

### The Full Failure Cycle

```
CSV row with comma in field
    ↓
parseLine() → NumberFormatException → caught by callback catch block
    ↓
logError() → writes to logs table (payload + stack trace)
    ↓
basicNack(tag, false, false) → message goes to record.queue.dlq
    ↓
Fix the bug (splitCsv instead of split(","))
    ↓
Move DLQ → record.queue via Shovel
    ↓
Consumer processes successfully → INSERT → basicAck
```

The DLQ is not just a safety net — it's a processing inbox for your failure cases. Every failure is preserved, inspectable, and replayable. Nothing is permanently lost unless you explicitly delete the DLQ.

---

## 29. Observability — Measuring What's Happening

Running a pipeline and waiting until it finishes is flying blind. Observability means instrumenting your code to answer: *is it working, how fast, and where is it breaking?*

### Four Key Metrics for This Pipeline

#### 1. Messages per second (throughput)

How fast is the producer publishing, and how fast is the consumer processing?

```java
// Simple counter — increment on every message processed
private long messagesProcessed = 0;
private long startTime = System.currentTimeMillis();

// In the callback, after successful save:
messagesProcessed++;
if (messagesProcessed % 10_000 == 0) {
    long elapsed = System.currentTimeMillis() - startTime;
    double rate = messagesProcessed / (elapsed / 1000.0);
    log.info("Processed {} records at {}/sec", messagesProcessed, String.format("%.0f", rate));
}
```

**The lifetime rate is a lie (measurement artifact).** `totalProcessed / elapsed` is a cumulative average from the moment the app started. Early batches look fast because `elapsed` is small. As time grows, the number drags toward the true long-term average even if every batch runs at identical speed — creating a perception of slowing that is partly just math.

Always log **both** per-batch rate and lifetime rate:

```java
private void logStats(int batchSize, int skipped, long batchMs) {
    long elapsed = System.currentTimeMillis() - startTime;
    double batchRate    = batchMs > 0 ? (batchSize - skipped) / (batchMs / 1000.0) : 0;
    double lifetimeRate = totalProcessed / (elapsed / 1000.0);
    log.info("Batch {}: inserted={} skipped={} in {}ms | batch={}/s lifetime={}/s total={} dupes={} errors={}",
            batchSize, batchSize - skipped, skipped, batchMs,
            String.format("%.0f", batchRate), String.format("%.0f", lifetimeRate),
            totalProcessed, totalDuplicates, totalErrors);
}
```

`batchRate` tells you actual instantaneous throughput. `lifetimeRate` tells you overall progress. If `batchRate` is stable but `lifetimeRate` looks like it's declining, that's the math artifact — not a real slowdown.

#### 2. Batch insert latency

How long does each `executeBatch()` take? Spikes indicate DB pressure.

```java
long start = System.currentTimeMillis();
stmt.executeBatch();
conn.commit();
long latencyMs = System.currentTimeMillis() - start;
log.info("Batch of {} inserted in {}ms", transactions.size(), latencyMs);
```

#### 3. Duplicate rate

If you've added `source_hash` (Section 27), count how many inserts were skipped as duplicates:

```java
private long duplicatesSkipped = 0;

// In the catch block for hash duplicates:
duplicatesSkipped++;
if (duplicatesSkipped % 1000 == 0) {
    log.warn("Duplicate rate: {} skipped so far", duplicatesSkipped);
}
```

High duplicate rate = the source file has duplicate records, or you're reprocessing already-loaded data.

#### 4. Queue depth

How many messages are waiting in RabbitMQ? If depth grows continuously, the consumer is slower than the producer.

```java
// Check queue depth via the AMQP channel
long depth = channel.messageCount(RabbitMQConfig.getQueueName());
log.info("Queue depth: {}", depth);
```

Or via the RabbitMQ Management API at `http://localhost:15672` (guest/guest by default) — the management UI shows queue depth, publish rate, and consumer rate in real time.

### Reading the RabbitMQ Management UI

The management UI is the fastest way to diagnose whether the pipeline is running, stalled, or broken. Here is what each number means and what to look for.

**Connections tab** — shows every TCP connection from your app to RabbitMQ:

| Column | What to check |
|---|---|
| State | Should be `running`. `blocked` means RabbitMQ's memory alarm is active. |
| Channels | How many AMQP channels are open on this connection. 0 means the channel was closed after use (e.g. the setup channel). |
| To client (B/s) | Data flowing FROM RabbitMQ TO your app. `2 B/s` = heartbeat only, no message delivery. If consumers are registered but this is low, RabbitMQ is not dispatching messages. |
| From client (B/s) | Data flowing FROM your app TO RabbitMQ. Heartbeats + acks. |

During a healthy load: **To client** should be KB/s or MB/s per consumer connection as messages stream in.

**Queues and Streams → record.queue** — the single most useful page:

| Field | Meaning |
|---|---|
| Ready | Messages waiting to be delivered to a consumer |
| Unacked | Messages delivered but not yet acked (currently being processed) |
| Consumers | Number of registered consumers. Should match `NUM_CONSUMERS`. |
| Consumer capacity | 100% = consumers are fully utilising their prefetch quota. Low % = consumers are idle. |
| State | `idle` = queue has consumers but is currently empty. `running` = messages flowing. |
| Deliver (manual ack) rate | Messages/sec being sent to consumers. 0.00/s with non-zero Ready = consumers are not receiving. |

**What specific states mean:**

```
Ready=98337, Unacked=0, Consumers=0
→ No consumers connected. App is not running or failed before basicConsume.

Ready=98337, Unacked=0, Consumers=10
→ Consumers ARE registered but not receiving. RabbitMQ is connected but not dispatching.
  Check the "To client" B/s on the Connections tab — if it's 2 B/s (heartbeat only),
  the issue is server-side (queue state, flow control, or a silent channel error).

Ready=0, Unacked=2000, Consumers=10
→ Healthy: all Ready messages delivered, consumers are processing them.

Ready=0, Unacked=0, Consumers=10, State=idle
→ Queue is empty. Processing is complete (or producer hasn't published yet).
```

### For Production — Micrometer + Prometheus

The manual counter approach above works for development. In production, use **Micrometer** — a metrics facade (like SLF4J, but for metrics) that sends data to Prometheus, Datadog, CloudWatch, or any other backend.

```java
// Add to build.gradle.kts
implementation("io.micrometer:micrometer-core:1.13.0")
implementation("io.micrometer:micrometer-registry-prometheus:1.13.0")

// Instrument your code
MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
Counter processed = registry.counter("pipeline.messages.processed");
Timer batchLatency = registry.timer("pipeline.batch.latency");

// In the consumer:
processed.increment(batch.size());
batchLatency.record(() -> transactionRepository.saveBatch(batch));
```

Prometheus scrapes the metrics endpoint every 15s. Grafana visualises them. You get dashboards showing throughput, latency percentiles (p50, p95, p99), and error rates over time — without any manual logging.

---

## 30. Primary Key Design — Generated ID vs Separate Concerns

This is a fundamental database design question: should your primary key carry business meaning, or should it be a pure internal identifier?

### Surrogate Key (what we have)

A **surrogate key** has no business meaning — it exists only as a database row identifier.

```sql
id VARCHAR(12) NOT NULL PRIMARY KEY  -- "483920174651" — meaningless outside this DB
```

Pros:
- Stable — never changes even if the business data changes
- Compact — 12 chars
- Fast — string comparisons on a short fixed-width column

Cons:
- Nothing stops you from inserting the same source transaction twice with different IDs
- Losing idempotency — re-running the pipeline creates duplicates

### Natural Key

A **natural key** uses a field from the business data as the PK.

```sql
source_trans_id VARCHAR(100) NOT NULL PRIMARY KEY
```

Pros:
- Automatic deduplication — re-running is safe
- Meaningful — you can join across systems using a shared identifier

Cons:
- Business data changes — what if `source_trans_id` gets corrected in the source system?
- May be null or non-unique at the source (data quality issues)
- Long strings are slower in B-tree indexes

### The Right Split for This Project

Keep the generated ID as PK. Add `source_hash` as a secondary `UNIQUE` constraint. Two separate concerns:

| Column | Type | Purpose |
|---|---|---|
| `id` VARCHAR(12) | Surrogate key | Internal DB row identifier |
| `source_hash` VARCHAR(64) | Business key | Deduplication, idempotency |

```sql
-- Updated schema
CREATE TABLE transactions (
    id          VARCHAR(12)  NOT NULL PRIMARY KEY,
    source_hash VARCHAR(64)  NOT NULL,
    ...
    UNIQUE INDEX ux_source_hash (source_hash)
);
```

This way:
- The PK stays stable and compact
- Re-running the pipeline skips already-processed rows (hash collision)
- You can query by either — `WHERE id = ?` for internal lookups, `WHERE source_hash = ?` for deduplication checks

The generated ID is fine as a PK. The problem it *doesn't* solve (idempotency) is what `source_hash` solves.

### The Clustered Index Problem — Why a Random VARCHAR PK Gets Slower Over Time

In InnoDB (MySQL's storage engine), the **primary key IS the table**. Rows are physically stored inside the B-tree, ordered by the PK value. This is called a **clustered index**.

When the PK is random (e.g. `VARCHAR(12)` filled with random digits), every new row has to be inserted at a random position in the tree. When the target leaf page is full, InnoDB splits it into two half-full pages and updates the parent — this is a **page split**. As the table grows:

- Page splits happen constantly (random inserts always find full pages)
- The tree gets taller → more levels to traverse per insert
- The working set grows beyond the buffer pool → disk reads

Observed impact: batch throughput starts fast and degrades with every batch until the pipeline takes hours instead of minutes.

### The Fix — V3 Migration: BIGINT AUTO_INCREMENT Clustered Key

Swap the PK to a sequential `BIGINT AUTO_INCREMENT`. Every new row appends to the right end of the tree — no splits, no random positioning. The random business ID moves to a separate `generated_id` column with a secondary unique index.

```sql
-- V3__optimize_pk.sql
ALTER TABLE transactions
    DROP INDEX ux_source_hash,
    DROP COLUMN source_hash,
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,   -- sequential, no B-tree splits
    ADD COLUMN generated_id VARCHAR(12) NOT NULL DEFAULT '' AFTER id,
    ADD UNIQUE INDEX ux_generated_id (generated_id);   -- secondary index on random values
```

Updated schema:

| Column | Type | Role |
|---|---|---|
| `id` | `BIGINT AUTO_INCREMENT` | Clustered key — sequential, no page splits |
| `generated_id` | `VARCHAR(12) UNIQUE` | Business ID — random, in a secondary index |

**Why secondary index splits are less severe**: a secondary index only stores the key + a pointer to the row. The pages are smaller. Splits still happen (random values), but they don't involve moving actual row data. The main improvement is eliminating clustered index splits, which are far more expensive.

In Java, `id` is never set — MySQL handles it. Only `generated_id` is set from `IdGeneratorService`:

```java
// Before (random varchar was the PK)
transaction.setId(idGenerator.generate());

// After (random string is a business key, db handles the real PK)
transaction.setGeneratedId(idGenerator.generate());
```

**Important:** V3 requires an empty `transactions` table. Drop the Docker volume and restart before loading so all three migrations run on clean tables:

```bash
docker compose -f docker-compose.dev.yml down -v
docker compose -f docker-compose.dev.yml up -d
```

---

## 26. Performance at Scale — Streaming vs Chunking vs Batch Inserts

When you're dealing with a 1.5GB file, three concepts come up. They solve different problems and are often confused.

---

### Streaming — Reading Without Loading

**Streaming** means processing data one piece at a time as it arrives, never holding the whole thing in memory.

`BufferedReader` in `FileProducer` is already doing this:

```java
BufferedReader reader = new BufferedReader(new FileReader(filePath));
String line;
while ((line = reader.readLine()) != null) {
    channel.basicPublish("", queueName, null, line.getBytes());
}
```

Each `readLine()` call fetches the next line from disk. Only one line is in memory at a time. The JVM's memory usage stays flat whether the file is 1MB or 10GB. **This is already solved in this project.**

`BufferedReader` internally reads a chunk (typically 8KB) from disk into a buffer, then hands you lines from that buffer one at a time — so you get streaming semantics with good disk I/O efficiency. You don't need to implement this yourself.

---

### Chunking — Processing in Groups

**Chunking** means deliberately collecting N items together before doing something with them. It's a throughput strategy, not a memory strategy.

Example: instead of publishing messages one at a time, you could collect 1000 lines and publish them together. This is useful when the per-item overhead (network round-trip, transaction cost) is expensive.

```java
// Streaming: publish every line immediately — N round-trips for N lines
channel.basicPublish("", queueName, null, line.getBytes());

// Chunked: accumulate, then publish as a batch — 1 round-trip per 1000 lines
List<String> chunk = new ArrayList<>();
chunk.add(line);
if (chunk.size() == 1000) {
    publishBatch(channel, chunk);  // one network operation
    chunk.clear();
}
```

---

### The Real Bottleneck — One INSERT Per Message

The file reading is fine. The problem is on the **consumer side**.

Currently:
- `basicQos(1)` — RabbitMQ sends only 1 message at a time to the consumer
- One `INSERT` per message — every single record is its own database round-trip

For a file with 500,000 rows, that's **500,000 individual SQL inserts**. Each one:
1. Borrows a connection from HikariCP
2. Sends the INSERT to MySQL over the network
3. MySQL writes it, flushes to disk, responds
4. Returns the connection to the pool

A single INSERT takes ~1–5ms. 500,000 × 2ms = **~17 minutes**. This is the actual bottleneck.

```
Current flow (slow):
Message 1 → INSERT → ack
Message 2 → INSERT → ack
Message 3 → INSERT → ack
... (500,000 times)

Batched flow (fast):
Message 1  ┐
Message 2  │
...        ├─→ single INSERT with 500 rows → ack all 500
Message 500┘
```

---

### The Fix — Batch Inserts

Batch inserts use a single `PreparedStatement` with multiple value rows, all in one transaction:

```sql
-- Instead of 500 separate INSERTs:
INSERT INTO transactions (id, amount, currency) VALUES (?, ?, ?);
INSERT INTO transactions (id, amount, currency) VALUES (?, ?, ?);
-- ... 498 more

-- One batch INSERT:
INSERT INTO transactions (id, amount, currency) VALUES
    (?, ?, ?),
    (?, ?, ?),
    (?, ?, ?);
-- ... all 500 in one statement
```

In Java, you use `addBatch()` and `executeBatch()`:

```java
try (Connection conn = DatabaseConfig.getConnection();
     PreparedStatement stmt = conn.prepareStatement(sql)) {

    conn.setAutoCommit(false);  // wrap everything in one transaction

    for (Transaction t : batch) {
        stmt.setString(1, t.getId());
        stmt.setBigDecimal(2, t.getAmount());
        stmt.setString(3, t.getCurrency());
        stmt.addBatch();        // stage the row, don't execute yet
    }

    stmt.executeBatch();        // send all rows to MySQL in one shot
    conn.commit();              // commit the whole batch
}
```

Two changes are required:
1. **`basicQos(500)`** — tell RabbitMQ to prefetch 500 messages so the consumer has a full batch to work with
2. **Accumulate in a `List<Transaction>`**, then flush when it reaches the batch size

Performance improvement is typically **50–100x** because:
- Network round-trips: 500 → 1
- Transaction overhead: 500 → 1
- MySQL buffer flushing: 500 → 1

---

### Tradeoff — Batch Failure Handling

With individual inserts, each record either succeeds or fails independently. With batch inserts, a single bad row can fail the whole batch.

Three strategies:

| Strategy | How | Tradeoff |
|---|---|---|
| **Fail fast** | Let `executeBatch()` throw, log the whole batch as failed | Simple, but re-running requires reprocessing the whole batch |
| **Retry individually** | On batch failure, retry each row one-by-one to isolate the bad one | Correct, but adds complexity |
| **Skip and log** | On batch failure, catch the error, log the raw payloads, continue | No data loss in the log, but bad rows are discarded |

This project already uses the "skip and log" pattern for individual rows — the same approach applies to batches. The `logs` table with `payload` column is exactly what you need: save the raw CSV line that failed so you can investigate and replay later.

---

### When to Use Each

| Technique | Solves | Use when |
|---|---|---|
| Streaming (`BufferedReader`) | Memory — file never fully loaded | Always, for large files |
| Chunking (collect N, process together) | Throughput — fewer round-trips | Network or transaction overhead is the bottleneck |
| Batch insert (`addBatch` / `executeBatch`) | DB throughput — one transaction for N rows | Writing many rows to a database |
| Increasing `basicQos` | Consumer throughput — prefetch more messages | Consumer is waiting idle between messages |

---

## 31. Consumer Flow Control — Backpressure, Prefetch, and Flushing

These concepts come up when you're tuning a message consumer for reliability and throughput. They are related but solve different problems.

---

### Backpressure

**Backpressure** is the mechanism that stops a fast producer from overwhelming a slow consumer. Without it, the producer can flood the queue faster than the consumer can process, causing the RabbitMQ broker to run out of memory.

`basicQos` is the primary backpressure tool on the consumer side:

```java
channel.basicQos(500);
```

This tells RabbitMQ: "deliver at most 500 unacked messages to me at once." Once our batch fills and we ack all 500, RabbitMQ delivers the next 500. The consumer controls its own intake. If the consumer is busy, RabbitMQ simply stops delivering — the producer can keep publishing into the queue, but the consumer won't be overwhelmed.

**Producer-side backpressure** (optional — if the queue grows too large):

```java
// Before publishing each line, check queue depth
long depth = channel.messageCount(RabbitMQConfig.getQueueName());
if (depth > 50_000) {
    Thread.sleep(500);  // pause — consumer is falling behind
}
```

RabbitMQ also has its own built-in flow control: when its memory or disk threshold is exceeded, it automatically blocks all publisher connections until things clear.

---

### prefetchCount vs BATCH_SIZE

These are the same number in our code — both set to 500. They serve two different purposes:

```java
private static final int BATCH_SIZE = 500;      // how many rows to accumulate before saving
channel.basicQos(BATCH_SIZE);                   // how many messages RabbitMQ delivers at once
```

**`prefetchCount` (the `basicQos` argument)** controls how many messages RabbitMQ delivers to this consumer without waiting for an ack. Setting it equal to `BATCH_SIZE` means: by the time we have a full batch in memory, we've received exactly as many messages as we can hold. They're kept in sync deliberately.

You could separate them:
```java
channel.basicQos(1000);    // prefetch 1000 — keep the consumer busy
// BATCH_SIZE = 500        — but only flush to DB every 500
```

This can improve throughput: the consumer receives messages 501–1000 while it's still writing messages 1–500 to the DB.

---

### channel.sendToQueue returning false and drain (Node.js)

If you encounter this in Node.js code or documentation, here is what it means — and what the Java equivalent is.

In the Node.js `amqplib` library:

```javascript
const ok = channel.sendToQueue(queue, Buffer.from(message));
if (!ok) {
    // The TCP write buffer is full — stop publishing
    // Resume when the buffer drains
    channel.once('drain', () => {
        resumePublishing();
    });
}
```

`sendToQueue` returns `false` when the underlying TCP socket's write buffer is backed up. The `drain` event fires when the buffer empties. This is **TCP-level backpressure** bubbling up through the AMQP client.

In Java, the AMQP client handles this transparently — `basicPublish()` **blocks internally** until there is space in the write buffer. You never see a `false` return value. The blocking behaviour is the Java equivalent of pausing on `false` and waiting for `drain`.

---

### The Partial Batch Problem and flushIntervalMs

The current code only flushes when the batch hits exactly `BATCH_SIZE`:

```java
if (batch.size() >= BATCH_SIZE) {
    flushBatch(channel, batch, batchLines, deliveryTags);
}
```

If the file has 5,487 rows: the first 5,000 are processed in 10 clean batches of 500. The last **487 sit in the batch buffer unacked forever** — never flushed because they never hit 500.

When the process exits, those 487 messages are lost from memory. RabbitMQ still holds them as unacked and **redelivers them on the next run**. With `source_hash` they'll be processed as normal inserts (or duplicates if somehow the process saved some before crashing). This works but it's wasteful — every run reprocesses the tail.

**`flushIntervalMs`** is the configurable timeout that solves this: flush the partial batch every N milliseconds even if it hasn't hit `BATCH_SIZE`.

```java
private static final long FLUSH_INTERVAL_MS = 5_000;  // flush at least every 5 seconds
```

Implementation using a `ScheduledExecutorService`:

```java
ScheduledExecutorService flushTimer = Executors.newSingleThreadScheduledExecutor();
flushTimer.scheduleAtFixedRate(() -> {
    synchronized (batch) {
        if (!batch.isEmpty()) {
            try {
                flushBatch(channel, batch, batchLines, deliveryTags);
            } catch (Exception e) {
                log.error("Timer flush failed", e);
            }
        }
    }
}, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
```

The `synchronized (batch)` is necessary because the timer runs on a **different thread** than the RabbitMQ callback. Both threads touch the `batch` list — without synchronisation, you get a `ConcurrentModificationException` or silent data corruption. The same `synchronized` block must also wrap every `batch.add(...)` call inside the callback.

---

### Flushing on Process Exit (Shutdown Hook)

A JVM **shutdown hook** is a thread that runs when the process is about to exit — triggered by CTRL+C, `System.exit()`, or `main()` returning normally:

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    log.info("Shutdown — flushing {} remaining messages", batch.size());
    synchronized (batch) {
        if (!batch.isEmpty()) {
            try {
                flushBatch(channel, batch, batchLines, deliveryTags);
                log.info("Final flush complete");
            } catch (Exception e) {
                log.error("Final flush failed — {} messages returned to queue", batch.size());
            }
        }
    }
    flushTimer.shutdown();
}));
```

This covers the partial batch gap: when the producer finishes and the process exits, the shutdown hook fires, flushes whatever remains, and the consumer exits cleanly.

The shutdown hook is also on a different thread. It works correctly here because by the time the hook runs, the RabbitMQ callback thread has stopped delivering messages (the connection is closing), so no concurrent modifications can happen.

---

### Keeping Messages Tied to Their Batch

This is what the three parallel lists in `FileConsumer` do:

```java
List<Transaction> batch       = new ArrayList<>();  // parsed objects to insert
List<String>      batchLines  = new ArrayList<>();  // raw CSV lines for error logging
List<Long>        deliveryTags = new ArrayList<>();  // RabbitMQ tags for ack/nack
```

They are always kept in sync — index `i` in all three refers to the same original message. This is important for two reasons:

**1. Multi-ack the whole batch:**
```java
// Ack everything up to and including the last tag
channel.basicAck(deliveryTags.get(deliveryTags.size() - 1), true);
//                                                           ^^^^
//                                                      multiple=true
```

**2. Per-message ack/nack on fallback:**
```java
for (int i = 0; i < batch.size(); i++) {
    try {
        saveWithRetry(batch.get(i));
        channel.basicAck(deliveryTags.get(i), false);         // saved — ack this one
    } catch (Exception e) {
        logError(e, batchLines.get(i));                        // log the raw CSV line
        channel.basicNack(deliveryTags.get(i), false, false);  // failed — DLQ
    }
}
```

Messages stay **unacked in RabbitMQ** for the entire time they sit in the batch buffer. If the app crashes before flushing, RabbitMQ automatically redelivers them to the next consumer that connects. This is **at-least-once delivery** — the message is guaranteed to be processed at least once. The `source_hash` constraint makes redelivery safe by turning duplicate saves into no-ops.

---

### requeue=true vs requeue=false

```java
channel.basicNack(tag, false, true);   // → back to front of queue (retry)
channel.basicNack(tag, false, false);  // → DLQ (park it)
```

Use **requeue=true** only for transient failures where retrying might work:
- DB connection momentarily dropped
- Timeout on a slow query
- Lock contention

Use **requeue=false** (DLQ) for permanent failures where retrying will never work:
- CSV field that won't parse (bad data)
- Constraint violation that won't go away
- Missing required field

The distinction matters because `requeue=true` on a permanently broken message creates a **poison pill loop**: the message goes back to the queue, the consumer picks it up again, fails again, requeues again — forever, at full speed, blocking other messages.

---

### Summary — What Each Knob Controls

| Concept | What it controls | In our code |
|---|---|---|
| `basicQos(n)` / `prefetchCount` | Max unacked messages RabbitMQ delivers | `BATCH_SIZE = 500` |
| `BATCH_SIZE` | How many rows to accumulate before DB insert | 500 |
| `FLUSH_INTERVAL_MS` | Max time a partial batch waits before flush | Not yet implemented |
| Shutdown hook | Flushes remaining batch on process exit | Not yet implemented |
| `basicAck(..., true)` | Acks all messages up to this tag at once | Used after every `flushBatch` |
| `basicNack(..., false)` | Parks message in DLQ | Used on unrecoverable errors |
| `basicNack(..., true)` | Requeues message for retry | Not used — reserved for transient DB failures |
| Backpressure | Slows producer when consumer is behind | Implicit via `basicQos` |

---

## 32. High-Speed Bulk Ingestion — Making It Run in Minutes

This section explains why a seemingly correct batch pipeline can still take hours, and exactly what to change to bring it down to minutes.

---

### The Driver Deception — Why Your Batch Was a Lie

This is the single most important thing in this entire document for performance.

When you call `stmt.executeBatch()` in Java, you expect the driver to send one large SQL statement like:

```sql
INSERT INTO transactions (id, amount) VALUES (?, ?), (?, ?), (?, ?), ...
```

Without a specific URL parameter, the MySQL JDBC driver **does not do this**. It silently breaks your batch back into individual statements:

```sql
INSERT INTO transactions (id, amount) VALUES (?, ?);
INSERT INTO transactions (id, amount) VALUES (?, ?);
INSERT INTO transactions (id, amount) VALUES (?, ?);
-- ... 999 more
```

Your `addBatch()` / `executeBatch()` code compiles and runs without errors. The logs show batches completing. But it is doing 1,000 individual inserts, not one batch. The fix is one parameter appended to the JDBC URL:

```properties
db.url=jdbc:mysql://localhost:3306/records_db?rewriteBatchedStatements=true
```

With this set, the driver rewrites your 1,000 staged rows into a single `INSERT ... VALUES (...), (...), (...)` payload. The difference is typically **10–50x faster** for the DB write phase alone. This is now set in `application.properties`.

---

### The Three Bottlenecks and Their Fixes

Every slow bulk pipeline has the same three problems:

| Bottleneck | Root cause | Fix |
|---|---|---|
| Network round-trips | One INSERT per row or small batches that aren't really batched | `rewriteBatchedStatements=true` + large batch size |
| Transaction overhead | MySQL commits and flushes to disk on every transaction | `innodb_flush_log_at_trx_commit=2` |
| Consumer backpressure | `basicQos` too low — consumer waits idle between deliveries | Raise prefetch count to match batch size |

---

### MySQL Server Tuning for Bulk Loads

These settings make MySQL stop being conservative about every write. They are safe for a bulk load scenario where you can re-run from the CSV if anything goes wrong.

#### Via Docker (configured in `docker-compose.dev.yml`)

The `command:` block passes arguments to the MySQL daemon at startup:

```yaml
mysql:
  image: mysql:8
  command: >
    --innodb-flush-log-at-trx-commit=2
    --innodb-buffer-pool-size=512M
    --max-allowed-packet=256M
    --innodb-log-buffer-size=64M
```

What each one does:

**`--innodb-flush-log-at-trx-commit=2`**
Controls when MySQL flushes the write-ahead log to disk.

| Value | Behaviour | Speed |
|---|---|---|
| `1` | Flush on every commit (default) | Slowest — safest |
| `2` | Write on every commit, flush once/second | Fast — safe for bulk load |
| `0` | Write and flush once/second | Fastest — can lose 1s of data on hard crash |

For a bulk load where you can replay the CSV, `2` gives you most of the speed benefit without the risk of `0`.

**`--innodb-buffer-pool-size=512M`**
The memory buffer InnoDB uses to cache data and indexes before writing to disk. Default is 128MB. Increasing it means MySQL can hold more of the table in memory during the load, reducing disk I/O. A good rule: set to 50–70% of available RAM if this machine is dedicated to MySQL.

**`--max-allowed-packet=256M`**
Maximum size of a single SQL statement. With `rewriteBatchedStatements=true` and a batch size of 5,000 rows × ~500 bytes/row, your INSERT statement can be ~2.5MB. The default 64MB limit is fine for batch sizes up to ~100,000 rows, but increasing it gives headroom.

**`--innodb-log-buffer-size=64M`**
The buffer MySQL uses to accumulate log writes before flushing. Larger = fewer disk flushes during the load.

#### Via SQL (if you need to tune a running instance)

If you can't restart MySQL (e.g., a shared server), run these before starting the load:

```sql
-- Flush logs once per second instead of per commit — biggest single speedup
SET GLOBAL innodb_flush_log_at_trx_commit = 2;

-- Disable constraint checking during the load
SET UNIQUE_CHECKS = 0;
SET FOREIGN_KEY_CHECKS = 0;

-- Increase query size limit
SET GLOBAL max_allowed_packet = 268435456;  -- 256MB
```

**After the load completes, restore safe defaults:**

```sql
SET GLOBAL innodb_flush_log_at_trx_commit = 1;
SET UNIQUE_CHECKS = 1;
SET FOREIGN_KEY_CHECKS = 1;
```

> `UNIQUE_CHECKS=0` tells MySQL to skip unique index validation during inserts. Only safe if your data is clean. We rely on `source_hash` to prevent duplicates at the application level — if you disable `UNIQUE_CHECKS`, MySQL won't catch duplicates. Re-enable before normal use.

---

### Optimal Batch Size

With `rewriteBatchedStatements=true`, larger batches are more efficient up to a point. The sweet spot for a single-machine bulk load:

| Batch size | Batches for 5M rows | Notes |
|---|---|---|
| 500 | 10,000 | Original — fine without rewrite, slow with it |
| 1,000 | 5,000 | Current setting — good |
| 5,000 | 1,000 | Recommended — fewer round-trips, manageable memory |
| 50,000 | 100 | Aggressive — ~50MB per batch in memory, fastest |

The current `BATCH_SIZE = 1000` in `FileConsumer` is already a good setting. To push further, increase it and also increase `basicQos` to match.

---

### Putting It All Together — The Complete Flow

With all optimisations in place:

```
1. FileProducer
   └─ BufferedReader streams 1 line at a time (flat memory usage)
   └─ basicPublish → record.queue

2. RabbitMQ delivers 1,000 messages at once (basicQos = BATCH_SIZE)

3. FileConsumer callback accumulates 1,000 Transaction objects

4. flushBatch() called:
   └─ setAutoCommit(false)
   └─ addBatch() × 1,000
   └─ executeBatch()
        └─ driver rewrites → single INSERT with 1,000 rows (rewriteBatchedStatements=true)
        └─ MySQL writes 1,000 rows in one transaction
        └─ innodb_flush_log_at_trx_commit=2 → no per-commit disk flush
   └─ commit()
   └─ basicAck(lastTag, multiple=true) → clears all 1,000 from queue in one call

5. logStats() → "Batch 1000 saved in 120ms | rate=8333/sec"
```

At 8,000 records/sec, 5 million rows = **~10 minutes**. With batch size 5,000 and MySQL tuning, you can reach 30,000–50,000 records/sec — bringing 5 million rows down to **under 3 minutes**.

---

### Quick Start — Running Everything from Scratch

```bash
# 1. Start MySQL and RabbitMQ
docker compose -f docker-compose.dev.yml up -d

# 2. Wait for MySQL to be ready (first startup takes ~20s)
docker compose -f docker-compose.dev.yml logs -f mysql
# Wait until you see: ready for connections

# 3. (Optional) Delete the old queue if it existed without DLQ args
curl -u guest:guest -X DELETE http://localhost:15672/api/queues/%2F/record.queue

# 4. Run the app
./gradlew run --args="/path/to/transactions.csv"
```

If MySQL was already running from a previous session with the old `itc_db` name:

```bash
# Recreate the containers so the new database name takes effect
docker compose -f docker-compose.dev.yml down -v   # -v removes the data volume
docker compose -f docker-compose.dev.yml up -d
```

> `-v` deletes the MySQL data volume. Only do this if you're OK losing existing data — which is fine during development since you can reload from the CSV.

---

### Direct Install (Ubuntu, no Docker)

If MySQL and RabbitMQ are installed directly on Ubuntu rather than via Docker:

**MySQL**

```bash
sudo apt install mysql-server
sudo systemctl start mysql
sudo mysql -u root -p

# In MySQL:
CREATE DATABASE records_db;
CREATE USER 'itc'@'localhost' IDENTIFIED BY 'itc';
GRANT ALL PRIVILEGES ON records_db.* TO 'itc'@'localhost';
FLUSH PRIVILEGES;

# Apply bulk load tuning (session-level, no restart needed)
SET GLOBAL innodb_flush_log_at_trx_commit = 2;
SET GLOBAL max_allowed_packet = 268435456;
```

To make the tuning permanent without Docker, add to `/etc/mysql/mysql.conf.d/mysqld.cnf`:
```ini
[mysqld]
innodb_flush_log_at_trx_commit = 2
innodb_buffer_pool_size = 512M
max_allowed_packet = 256M
innodb_log_buffer_size = 64M
```

Then `sudo systemctl restart mysql`.

**RabbitMQ**

```bash
sudo apt install rabbitmq-server
sudo systemctl start rabbitmq-server
sudo rabbitmq-plugins enable rabbitmq_management  # enable the web UI
```

The web UI will be at `http://localhost:15672` (guest / guest). No further config needed for this project.

---

## 33. INSERT IGNORE — Handling Duplicates Without Crashing the Batch

### The Problem With Regular INSERT

When a batch of 50,000 rows hits a single duplicate `source_hash`, the whole `executeBatch()` throws `SQLIntegrityConstraintViolationException`. The transaction rolls back. All 50,000 rows are discarded. The code falls back to 50,000 individual saves — one DB round-trip per row. That single duplicate just turned a fast batch into a slow loop.

This is what happened in the logs:
```
WARN  FileConsumer -- Batch insert failed, falling back to individual saves.
      Cause: Duplicate entry '0a77cf83...' for key 'transactions.ux_source_hash'
```

### INSERT IGNORE

`INSERT IGNORE` tells MySQL: if this row would violate a unique constraint, skip it silently and continue. No exception. No rollback. The rest of the batch succeeds.

```sql
-- Regular INSERT — one duplicate kills the whole batch
INSERT INTO transactions (id, source_hash, ...) VALUES (?, ?, ...);

-- INSERT IGNORE — duplicates are silently skipped, batch always succeeds
INSERT IGNORE INTO transactions (id, source_hash, ...) VALUES (?, ?, ...);
```

What MySQL does when it sees a duplicate with `INSERT IGNORE`:
1. Checks the unique indexes before inserting
2. If a conflict exists → skips the row, returns 0 rows affected for that row
3. Moves to the next row
4. No error, no rollback, no exception in Java

This is now the behaviour of `saveBatch()` in `TransactionRepository`. The `save()` method (used only in the individual fallback path) keeps regular `INSERT` so genuine errors still surface as exceptions.

### INSERT IGNORE vs ON DUPLICATE KEY UPDATE vs REPLACE

All three handle duplicates, but differently:

| Statement | On duplicate | Use when |
|---|---|---|
| `INSERT IGNORE` | Skip the row entirely | You want to keep existing data, duplicates are discarded |
| `ON DUPLICATE KEY UPDATE col = VALUES(col)` | Update the existing row | You want to overwrite existing data with new values |
| `REPLACE INTO` | Delete the old row, insert the new one | You want full replacement (dangerous — changes the PK) |

For this pipeline, `INSERT IGNORE` is correct: if the source transaction was already processed, we want to keep what's there and silently discard the new arrival.

### Counting What Was Skipped

`executeBatch()` returns an `int[]` — one entry per row in the batch. With `INSERT IGNORE`:
- `1` — row was inserted
- `0` — row was skipped (duplicate)
- `-2` (`Statement.SUCCESS_NO_INFO`) — driver can't tell (some JDBC implementations)

```java
int[] results = stmt.executeBatch();
conn.commit();

int inserted = 0;
for (int r : results) {
    if (r > 0) inserted += r;
}
int skipped = transactions.size() - inserted;
```

With MySQL Connector/J 8.x and `rewriteBatchedStatements=true`, MySQL returns 0 for ignored rows and 1 for inserted rows — so the count is accurate. If you ever see `SUCCESS_NO_INFO` (-2) values, it means the driver couldn't get per-row counts; the `skipped` count would then be overstated (showing the whole batch as skipped). This is a conservative failure mode — you'll see an inflated duplicate count in logs but nothing will be lost.

The stats log now shows both:
```
INFO  FileConsumer -- Batch 50000: inserted=49997 skipped=3 in 340ms | total=149991 rate=8333/sec dupes=3 errors=0
```

---

### Statement.SUCCESS_NO_INFO — When executeBatch() Lies

When you call `stmt.executeBatch()` you get back an `int[]` — one number per row, representing how many rows that statement affected. For a regular INSERT batch, you'd expect `1` per inserted row and `0` per ignored row.

With `rewriteBatchedStatements=true`, the MySQL driver rewrites your 50,000 individual staged rows into a single SQL statement: `INSERT IGNORE INTO ... VALUES (...), (...), ...`. That one statement produces one result from the database. The driver then has to fill in 50,000 positions in the `int[]` array without knowing which individual rows were affected. It fills them all with `Statement.SUCCESS_NO_INFO` (-2) — the JDBC standard value meaning "I don't know."

Our original counting code discarded -2 values:

```java
for (int r : results) {
    if (r > 0) inserted += r;  // -2 is not > 0, so it's ignored
}
// inserted stays 0, skipped = batch.size() = 50000
```

This is why the logs showed `inserted=0 skipped=50000` even when data WAS being inserted. The fix is `SELECT ROW_COUNT()` — a MySQL function that returns the number of rows actually affected by the last DML statement. It must be called on the same connection, before `commit()` and before any other statement:

```java
stmt.executeBatch();

// ROW_COUNT() returns actual rows inserted by INSERT IGNORE
// (ignored rows are not counted)
int actualInserted;
try (Statement s = conn.createStatement();
     ResultSet rs = s.executeQuery("SELECT ROW_COUNT()")) {
    rs.next();
    actualInserted = rs.getInt(1);
}

conn.commit();
return transactions.size() - actualInserted;  // accurate skipped count
```

`ROW_COUNT()` works because MySQL tracks the affected row count at the engine level, independent of what the JDBC driver reports. It is always accurate for INSERT IGNORE — returning the count of rows that passed the unique constraint check and were actually written.

---

### INSERT IGNORE Performance — Why Skipping Is Not Free

You might expect that skipping a row is instant — after all, nothing is being written. It is not. When MySQL encounters a row in an `INSERT IGNORE` statement, it must:

1. Hash the `source_hash` value
2. Walk the `ux_source_hash` B-tree index to check if that hash exists
3. Find a match → skip the row

Step 2 requires reading index pages from disk or the buffer pool. For a table with millions of rows, the index can be hundreds of megabytes. When the buffer pool is cold (process just started, Docker container restarted), those pages are not in memory — each lookup may require a disk read.

This is what the logs showed:
```
Batch 50000: inserted=0 skipped=50000 in 29417ms | total=0 rate=0/sec dupes=50000
Batch 50000: inserted=0 skipped=50000 in 42155ms | total=0 rate=0/sec dupes=100000
```

`inserted=0 skipped=50000` — every row was a duplicate. The database already had this data from a previous run. MySQL performed 50,000 unique index lookups and skipped every row. 29 seconds for 50,000 lookups = ~0.6ms per lookup — slow because the index pages were cold, getting even slower in the second batch as the buffer pool pressure increased.

**`INSERT IGNORE` is a safety net, not a shortcut.** It is essentially free on a first load (no conflicts → no extra work beyond normal inserts). On a re-run against existing data, it pays the full cost of checking every row against the unique index.

### The Development Workflow

During development you often want to reload the same CSV multiple times. The correct workflow is:

```bash
# Wipe the table — instant, no row-by-row deletion
docker exec -it $(docker compose -f docker-compose.dev.yml ps -q mysql) \
    mysql -u itc -pitc records_db -e "TRUNCATE TABLE transactions;"

# Then run the app again
./gradlew run --args="/path/to/transactions.csv"
```

`TRUNCATE` resets the table to empty in milliseconds regardless of how many rows it had. The next run inserts everything fresh — no duplicates, no index conflict checks, maximum speed.

`TRUNCATE` vs `DELETE FROM transactions`:
- `TRUNCATE` — deallocates the whole table's data pages at once, resets auto-increment. Instant.
- `DELETE FROM transactions` (no WHERE) — deletes row by row, logs each deletion, takes minutes on a large table.

Always use `TRUNCATE` for a full table wipe.

### When to TRUNCATE vs When to Let INSERT IGNORE Handle It

| Scenario | Action |
|---|---|
| Development: re-running the same CSV to test changes | `TRUNCATE TABLE transactions` then re-run |
| Production: recovering from a crash mid-file | Re-run as-is — `INSERT IGNORE` skips already-saved rows |
| Production: same file delivered again by the source | Re-run as-is — duplicates are skipped correctly |
| Production: intentional full reload of new data | `TRUNCATE TABLE transactions` then re-run |

---

## 34. Reading Pipeline Logs — What Everything Means

Understanding what the logs are telling you is as important as writing the code. Here is a full walkthrough of a real run, line by line.

### Startup Phase

```
09:58:20.557 [main] DEBUG FlywayExecutor -- Memory usage: 19 of 252M
```
Flyway is scanning for migration files. `main` thread = the app hasn't started its producer/consumer threads yet.

```
09:58:20.914 [main] DEBUG DbMigrate -- Successfully completed migration of schema `records_db` to version "1 - create tables"
09:58:21.627 [main] DEBUG DbMigrate -- Successfully completed migration of schema `records_db` to version "2 - add source hash"
09:58:21.761 [main] INFO  DbMigrate -- Successfully applied 2 migrations to schema `records_db` (execution time 00:01.516s)
```
Both SQL migration files ran. Tables exist. App is ready to start.

### Producer and Consumer Threads Starting

```
09:58:21.782 [Thread-1] INFO  FileProducer -- Producer started for file: .../transactions.csv
```
Producer thread (`Thread-1`) started. It is now reading the CSV line by line and publishing to RabbitMQ.

```
09:58:21.826 [Thread-0] DEBUG ConsumerWorkService -- Creating executor service with 4 thread(s) for consumer work service
```
Consumer thread (`Thread-0`) registered its callback with RabbitMQ. RabbitMQ's internal thread pool (4 threads) will call the `DeliverCallback` as messages arrive. The consumer itself is now idle — waiting for messages.

### HikariCP Pool Initializing

```
09:58:27.628 [pool-1-thread-3] DEBUG HikariConfig -- jdbcUrl: jdbc:mysql://localhost:3306/records_db?rewriteBatchedStatements=true
09:58:27.719 [pool-1-thread-3] INFO  HikariDataSource -- HikariPool-1 - Starting...
09:58:27.847 [pool-1-thread-3] INFO  HikariPool -- HikariPool-1 - Added connection ...
```
HikariCP doesn't connect to MySQL at startup — it waits until the first query is needed (lazy initialization). This triggered 6 seconds after start, which means the first batch of 50,000 messages had accumulated in memory and was ready to flush. Watch the pool log sequence:

```
total=1, active=1   → first connection added and immediately used (batch insert starting)
total=2, active=1   → pool growing to minimum size (10)
total=3, active=1
...
total=10, active=1  → pool full, one connection still active (insert in progress)
```

### The Failure

```
09:58:48.168 [pool-1-thread-3] WARN  FileConsumer -- Batch insert failed, falling back to individual saves.
             Cause: Duplicate entry '0a77cf837768060d...' for key 'transactions.ux_source_hash'
```
Timestamp: 26 seconds after start. That means it took 26 seconds to accumulate 50,000 messages from RabbitMQ and attempt the batch insert.

**What went wrong:** the batch of 50,000 rows contained at least one row whose `source_hash` already existed in the database (or appeared twice in the CSV). `executeBatch()` threw `SQLIntegrityConstraintViolationException`. The `catch` block caught it, logged this WARN, and started the fallback loop — 50,000 individual `saveWithRetry` calls.

**How to spot this pattern:** any time you see `Batch insert failed, falling back to individual saves`, you are about to do N individual inserts instead of 1 batch. This will always be slow.

**The fix (now applied):** `INSERT IGNORE INTO` in `saveBatch()`. The duplicate is silently skipped, the batch succeeds, this WARN never appears again.

### The Fallback Running

```
09:58:57.986 [HikariPool-1 housekeeper] DEBUG -- Pool stats (total=10, active=0, idle=10, waiting=0)
```
`active=0` — all connections are idle. The individual fallback for all 50,000 rows **completed** in about 9 seconds (09:58:48 to 09:58:57). That's ~5,500 rows/sec on individual inserts — not terrible, but far slower than a proper batch.

```
09:59:57.987 [HikariPool-1 housekeeper] -- Pool stats (total=10, active=1, idle=9, waiting=0)
```
One minute later, `active=1` again — the second batch of 50,000 is being inserted. If this stays `active=1` for many minutes without a `logStats` line appearing, the second batch is also failing and doing the individual fallback.

### Reading HikariPool Stats

The housekeeper logs pool stats every 30 seconds:

```
Pool stats (total=10, active=1, idle=9, waiting=0)
```

| Field | Meaning | What to watch for |
|---|---|---|
| `total` | Connections in the pool | Should reach `maximumPoolSize` (10) during load |
| `active` | Connections currently executing a query | `active=0` means nothing is running |
| `idle` | Connections open but not in use | `idle=10` means the pipeline has stalled |
| `waiting` | Threads waiting for a connection | `waiting > 0` means pool is too small |

**Healthy bulk load**: `active=1` sustained for the duration of each batch insert, alternating with brief `active=0` between batches.

**Stalled pipeline**: `active=0` for many minutes with no `logStats` output — the consumer has stopped processing. Check for an exception above in the logs.

**Pool exhausted**: `waiting > 0` — increase `maximumPoolSize` in `DatabaseConfig` or reduce concurrency.

### The Producer Count Bug

```
INFO  FileProducer -- Producer finished. Total records published: 0
```

This is not an error — the producer DID publish records. The count variable was declared but never incremented:

```java
long count = 0;
while ((line = reader.readLine()) != null) {
    channel.basicPublish(...);
    // count++ was missing here
}
log.info("Total records published: {}", count);  // always 0
```

The consumer receiving messages proves the producer worked. The log was just wrong. The fix (`count++` inside the loop) is now in `FileProducer.java`. This is a common silent bug — the code compiles and runs correctly, only the metric is wrong.

---

### The "All Skipped" Pattern

```
Batch 50000: inserted=0 skipped=50000 in 29417ms | total=0 rate=0/sec dupes=50000 errors=0
Batch 50000: inserted=0 skipped=50000 in 42155ms | total=0 rate=0/sec dupes=100000 errors=0
```

**What it means:** every single row in every batch is a duplicate. The database already contains all of this data from a previous run. `INSERT IGNORE` is doing its job — no wrong data is entering the DB — but it is expensive because MySQL checks the unique index for each of the 50,000 rows before deciding to skip.

**Why it's getting slower:** the second batch (42,155ms) took longer than the first (29,417ms). As more index pages are loaded into the buffer pool by the first batch's lookups, memory pressure increases and older pages are evicted. The buffer pool (512MB) is not large enough to hold the entire index warm, causing increasing cache misses.

**How to fix:** `TRUNCATE TABLE transactions` and run again. The table is empty, there are no duplicates to find, every row inserts cleanly and fast.

**How to distinguish from a stuck pipeline:** a stuck pipeline shows `active=1` in HikariPool stats but never produces a log line. The "all skipped" pattern produces log lines regularly — it IS making progress, just through an expensive path.

### Re-run vs Fresh Load — Why Performance Is So Different

```
Batch 50000: inserted=0 skipped=50000 in 104839ms   ← re-run: 100+ seconds
Batch 50000: inserted=50000 skipped=0 in 1200ms     ← fresh load: 1-2 seconds
```

On a **fresh load** (table was TRUNCATE'd before running), performance is fast because:
- The unique index (`ux_source_hash`) is empty or nearly empty
- Each INSERT adds a new B-tree leaf — leaf pages are mostly in the buffer pool and mostly sequential
- No conflict checks needed — MySQL confirms the key doesn't exist with a single B-tree traversal that always ends at an empty slot

On a **re-run** against an existing table, every row in every batch requires:
1. A full B-tree traversal of `ux_source_hash` to find the existing key
2. The index for 5M rows of VARCHAR(64) is ~320MB — larger than what fits in the buffer pool hot path
3. Cold index pages = disk reads = 5–50ms per cache miss
4. With 50,000 rows per batch, even 2ms average per lookup = 100 seconds

This is not a code bug — it is the fundamental cost of checking uniqueness against an existing dataset. The only way to avoid it is to not check (`TRUNCATE` first) or to not have the index during the load (drop it, load, recreate).

**Rule of thumb**: if you are re-running purely to test performance, always `TRUNCATE TABLE transactions` first. A fresh-load benchmark is the only meaningful one.

---

### What Good Logs Look Like

After the `INSERT IGNORE` fix, every batch should produce one stats line and no WARN:

```
INFO  FileProducer -- Producer started for file: .../transactions.csv
INFO  HikariDataSource -- HikariPool-1 - Start completed.
INFO  FileConsumer -- Batch 50000: inserted=50000 skipped=0 in 340ms | total=50000 rate=8333/sec dupes=0 errors=0
INFO  FileConsumer -- Batch 50000: inserted=49997 skipped=3 in 312ms | total=99997 rate=8547/sec dupes=3 errors=0
INFO  FileConsumer -- Batch 50000: inserted=50000 skipped=0 in 298ms | total=149997 rate=8721/sec dupes=3 errors=0
...
INFO  FileProducer -- Producer finished. Total records published: 5000000
```

If you see `WARN Batch insert failed` — `INSERT IGNORE` is not in the SQL. If `logStats` stops appearing and `active=1` is sustained indefinitely — the batch is hanging (check for a lock or an extremely slow query). If `rate` is under 1,000/sec — `rewriteBatchedStatements=true` is not in the JDBC URL.

---

## 35. The Load-Then-Index Pattern — Getting From Hours to Minutes

### Why the Rate Drops With Each Batch

```
Batch 1: 50,000 rows in 16s  → 2,221/sec
Batch 2: 50,000 rows in 21s  → 2,062/sec
Batch 3: 50,000 rows in 31s  → 1,772/sec
Batch 4: 50,000 rows in 64s  → 1,306/sec
Batch 5: 50,000 rows in 73s  → 1,080/sec
Batch 6: 50,000 rows in 58s  →   990/sec  ← still decelerating
```

The deceleration is caused by the `ux_source_hash` unique index. SHA-256 hashes are effectively random strings. Inserting random keys into a B-tree causes **page splits** — when a leaf page is full, MySQL has to split it into two pages, update the parent node, and write everything back. This happens constantly during random inserts.

Worse: as the index grows beyond the buffer pool size, MySQL starts reading index pages from disk. The `source_hash` index for 5.75M rows of `VARCHAR(64)` is roughly:
- 64 bytes per key × 5.75M rows = ~368MB raw data
- Plus B-tree node overhead (~50-100%): ~600-750MB total index size

With a 512MB buffer pool shared between data pages, index pages, and internal structures, the working set quickly overflows. Every overflow = a disk read for that page. At Docker I/O speeds, each page fault adds 5-50ms.

At 990 rows/sec with 5.45M rows remaining: **~1.5 hours**. Not minutes.

---

### The Solution — Load-Then-Index

Instead of maintaining the unique index row by row during inserts, drop it before loading and rebuild it after. MySQL rebuilds an index from scratch by **sorting all the keys and building the B-tree bottom-up** — a completely different algorithm that is 10-50x faster than inserting one key at a time into a live tree.

```
Per-row index maintenance (current):   each insert = B-tree traversal + possible page split
Bulk index build (rebuild after load):  sort all keys → build tree in one pass, no splits
```

**The workflow:**

```bash
# Step 1: Drop the unique index (keeps the column, removes the B-tree)
mysql -u itc -pitc records_db -e "ALTER TABLE transactions DROP INDEX ux_source_hash;"

# Step 2: Wipe existing data for a clean load
mysql -u itc -pitc records_db -e "TRUNCATE TABLE transactions;"

# Step 3: Run the load — INSERT IGNORE with no unique index is just INSERT
./gradlew run

# Step 4: After the app finishes, rebuild the index
# MySQL sorts all 5.75M source_hash values and builds the B-tree in one pass
mysql -u itc -pitc records_db -e "ALTER TABLE transactions ADD UNIQUE INDEX ux_source_hash (source_hash);"
```

Steps 1-3 run the same Java code unchanged — `INSERT IGNORE` with no unique index simply inserts every row (IGNORE has nothing to conflict against). The index rebuild in step 4 typically takes 1-3 minutes for 5-10M rows.

**Expected performance without the index:** 10,000-50,000 rows/sec. For 5.75M rows: **2-10 minutes**.

---

### Why Not Just Disable Unique Checks?

`SET UNIQUE_CHECKS=0` tells MySQL to skip uniqueness verification during inserts. It's faster than per-row checking, but it has a dangerous side effect: MySQL still adds keys to the unique index, just without checking for duplicates first. If two rows have the same `source_hash`, both keys end up in the index. You then have a unique index with duplicate values — a corrupt state that can cause subtle query bugs later.

`DROP INDEX` before loading and `ADD UNIQUE INDEX` after is safer: the index doesn't exist during the load, so there's nothing to corrupt. The rebuild at the end either succeeds (no duplicates in the data) or fails with a clear error (duplicate found).

---

### innodb_buffer_pool_size — Keep the Index in RAM

Even without the load-then-index pattern, increasing the buffer pool helps by keeping more index pages in memory. The `source_hash` index alone is ~600-750MB for 5.75M rows:

```yaml
# docker-compose.dev.yml
--innodb-buffer-pool-size=2G
```

With 2GB, the entire `source_hash` index fits in memory. Cache miss rate drops dramatically. The per-row B-tree traversal still happens, but it hits RAM instead of disk. Expected improvement: 3-5x (from ~1,000/sec to 3,000-5,000/sec). Still not minutes for 5.75M rows, but significantly better.

Apply with a container restart (no data loss):
```bash
docker compose -f docker-compose.dev.yml restart mysql
```

---

### Choosing Your Strategy

| Goal | Approach |
|---|---|
| Fastest possible first load | Drop index → load → rebuild index |
| Idempotent re-runs (recover from crash) | Keep index, use `INSERT IGNORE` |
| Development iteration (re-run same file often) | TRUNCATE → drop index → load → rebuild |
| Production nightly load (new data only) | Keep index, `INSERT IGNORE` skips processed rows |

For a one-time bulk load of a CSV file, the drop-then-rebuild pattern is always faster. The unique index exists to protect against re-run duplicates — during a controlled first load where you TRUNCATE'd first, that protection adds cost with no benefit.

---

### LOAD DATA LOCAL INFILE — The Fastest Bulk Path

For one-time file loads where RabbitMQ isn't required, MySQL's native bulk loader is 5–20× faster than JDBC batch inserts. It bypasses the normal insert engine entirely.

**Setup (both client and server must opt in):**

```properties
# application.properties — client side
db.url=jdbc:mysql://localhost:3306/new_db?rewriteBatchedStatements=true&allowMultiQueries=true&allowLoadLocalInfile=true
```

```yaml
# docker-compose.dev.yml — server side
mysql:
  command: >
    --innodb-flush-log-at-trx-commit=2
    --innodb-buffer-pool-size=2G
    --innodb-redo-log-capacity=2G
    --local-infile=1          # ← required — disabled by default in MySQL 8
```

If either side is missing, MySQL throws: `Loading local data is disabled; this must be enabled on both the client and server sides`.

**The three-step flow (implemented in `BulkLoader.java`):**

```
Step 1 — Pre-process (~44s for 5.75M rows):
  Stream CSV line by line
  For each row: generate ID, check HashSet<Long>, regenerate if collision
  Write "generated_id,original_csv_line" to a temp file

Step 2 — Load (~60-90s):
  DROP INDEX ux_generated_id         ← no per-row B-tree maintenance
  LOAD DATA LOCAL INFILE temp_file   ← MySQL reads directly from client disk
  SELECT ROW_COUNT()                 ← actual rows loaded
  CREATE UNIQUE INDEX ux_generated_id ← bulk sort + build in one pass

Step 3 — Cleanup:
  Files.deleteIfExists(tempFile)
```

```java
// The LOAD DATA statement via JDBC
String sql = """
        LOAD DATA LOCAL INFILE '%s'
        INTO TABLE transactions
        FIELDS TERMINATED BY ','
        OPTIONALLY ENCLOSED BY '"'
        LINES TERMINATED BY '\\n'
        (generated_id, payment_type_id, source_id, ...)
        """.formatted(tempFile.toAbsolutePath().toString().replace("\\", "/"));

try (Connection conn = DatabaseConfig.getConnection();
     Statement stmt = conn.createStatement()) {
    stmt.execute("ALTER TABLE transactions DROP INDEX ux_generated_id");
    stmt.execute(sql);
    // ROW_COUNT() gives actual rows loaded
    stmt.execute("CREATE UNIQUE INDEX ux_generated_id ON transactions(generated_id)");
}
```

**Run via the `--bulk` flag (separate from the RabbitMQ pipeline):**

```bash
./gradlew run --args="--bulk /path/to/transactions.csv"
```

**Measured performance on 5.75M rows / 1.5GB:**

| Phase | Time |
|---|---|
| Pre-processing (ID gen + temp file write) | ~44s |
| LOAD DATA LOCAL INFILE | ~60-90s |
| Index rebuild | ~60-90s |
| **Total** | **~7 minutes** |

Compared to ~13 minutes for the RabbitMQ pipeline. The 6-minute gap is RabbitMQ overhead — publishing 5.75M messages, receiving and parsing them in consumers, and the extra network round trips. The MySQL write phase itself is equally fast in both paths (index dropped, sequential inserts).

**`LOCAL` means the file comes from the client** (wherever Java is running), not the MySQL server. This works correctly even when MySQL is inside Docker — the file transfers from the host over the client connection.

---

## 36. File Descriptors — Why RabbitMQ Warns About File Handles

### What the Warning Means

```
[warning] Available file handles: 1024. Please consider increasing system limits
```

Every process on Linux is given a quota of **file descriptors** (FDs) — numbered slots it can use to hold open files, sockets, pipes, and other I/O resources. The default limit per process is 1024.

RabbitMQ uses file descriptors for:
- Every network connection (one FD per TCP socket — each producer and consumer uses at least one)
- Queue storage files (persistent queues are backed by files on disk)
- Internal Erlang runtime processes
- Log files

At 1024 FDs, a busy RabbitMQ node can run out. When it does, it cannot accept new connections and starts rejecting clients. For a development setup with one producer and one consumer the limit is unlikely to be hit, but the warning is still worth fixing — and it matters more as you scale.

### How to Fix It in Docker

Set `ulimits` on the RabbitMQ container in `docker-compose.dev.yml`:

```yaml
rabbitmq:
  image: rabbitmq:management
  ulimits:
    nofile:
      soft: 65536
      hard: 65536
```

`nofile` = "number of open files". `soft` is the default limit the process starts with. `hard` is the ceiling it can raise itself to. Setting both to 65536 (64K) matches RabbitMQ's recommendation for development and light production use.

Restart RabbitMQ to apply (no data is lost — queues are in-memory for dev):
```bash
docker compose -f docker-compose.dev.yml restart rabbitmq
```

After restart the warning disappears. You can verify:
```bash
docker exec $(docker compose -f docker-compose.dev.yml ps -q rabbitmq) \
    rabbitmqctl status | grep "File Descriptors"
# Should show: Total: 65536, Available: 65xxx
```

### For Direct Ubuntu Install (No Docker)

If RabbitMQ is installed directly on Ubuntu, set the limit in `/etc/security/limits.conf`:

```
rabbitmq soft nofile 65536
rabbitmq hard nofile 65536
```

Or in the systemd service override (`/etc/systemd/system/rabbitmq-server.service.d/limits.conf`):

```ini
[Service]
LimitNOFILE=65536
```

Then `sudo systemctl daemon-reload && sudo systemctl restart rabbitmq-server`.

### File Descriptor Sizing

| Setup | Recommended `nofile` |
|---|---|
| Development (1-2 connections) | 65536 |
| Small production (< 100 connections) | 65536 |
| Large production (100+ connections, many queues) | 500000+ |

RabbitMQ's own documentation recommends at least 65536 for any deployment. The Linux kernel default of 1024 is a historical artefact from an era when processes rarely needed more than a few hundred open files simultaneously.

---

## 36. MySQL Redo Log — The Hidden Write Bottleneck

### What the Redo Log Is

Every write in MySQL goes through the **redo log** (also called the write-ahead log or WAL) before it touches actual data files. This is how MySQL guarantees crash safety: if the process dies mid-write, MySQL replays the redo log on the next startup to restore consistency.

The redo log is a **circular buffer** on disk. Its size is fixed at startup. As writes come in, the log fills up. A background process called the **checkpoint thread** continuously flushes dirty pages from the buffer pool to the actual data files and recycles the used log space.

```
Your INSERT → redo log (fast sequential write) → checkpoint → data files (random writes)
             ↑                                                          ↓
             └─────────────── log space recycled ──────────────────────┘
```

### What Happens When It Fills Up

```
[Warning] [MY-014089] [InnoDB] Redo log writer is waiting for a new redo log file.
Consider increasing innodb_redo_log_capacity.
The current log capacity is 104857600 bytes.
The log capacity used is 104857600 bytes.
```

`104857600 bytes = 100MB` — the default. `capacity used = 100%` — completely full.

When the redo log is full, MySQL's write path **blocks entirely**. No new data can be written until the checkpoint thread flushes enough dirty pages to free log space. This is a hard pause — your `executeBatch()` call sits waiting inside MySQL with no error, no timeout, just silence.

This is exactly what caused the 60-100 second batch times. Not the unique index. Not Docker overhead. The redo log was full, MySQL was blocked, and the batch waited until the checkpoint thread freed space. The one 8-second batch happened to run right after a checkpoint completed, when the log was mostly empty.

### How to Fix It

Increase `innodb_redo_log_capacity`. For bulk loading millions of rows, 100MB is far too small:

In `docker-compose.dev.yml`:
```yaml
command: >
  --innodb-flush-log-at-trx-commit=2
  --innodb-buffer-pool-size=512M
  --innodb-redo-log-capacity=2G
  --innodb-log-buffer-size=256M
```

This change requires a MySQL restart (not a volume wipe — data is preserved):
```bash
docker compose -f docker-compose.dev.yml restart mysql
```

Also increased `innodb-log-buffer-size` from 64M to 256M. The log buffer is the in-memory staging area before redo log entries are written to disk. A larger buffer means fewer writes to disk during heavy batch activity.

### Sizing the Redo Log

The redo log must be large enough to hold all the writes that can accumulate between checkpoints. For a bulk load:

| Scenario | Recommended capacity |
|---|---|
| Small datasets (< 1M rows, dev) | 512M |
| Medium datasets (1M–10M rows) | 2G |
| Large datasets (10M+ rows) | 4G–8G |
| Production (sustained write load) | 4G–16G |

A general rule: the redo log should be large enough that checkpoints happen every few minutes, not every few seconds. If you see the `waiting for a new redo log file` warning, double the size and try again.

### innodb_log_buffer_size

The log buffer is MySQL's in-memory write buffer before entries hit the redo log on disk. Default is 16MB–64MB. For a bulk load producing large transactions (50,000 rows per commit), a large log buffer means the entire transaction can stage in memory before a single disk write — then flush once at commit time.

Increasing from 64M to 256M means each batch of 50,000 rows accumulates in memory and hits the redo log in one sequential write, instead of multiple smaller writes that cause more seeking.

### The Complete Picture of a Write

When you call `executeBatch()` and then `commit()`, here is what MySQL actually does:

```
1. executeBatch()
   ├─ Parse the 20MB INSERT IGNORE statement
   ├─ For each row: check unique index in buffer pool (or disk if cold)
   └─ Stage row changes in log buffer (memory)

2. commit()
   ├─ Flush log buffer → redo log file on disk (sequential write)
   │    (blocked here if redo log is full — the 100-second pause)
   ├─ Mark transaction committed in redo log
   └─ Return to Java

3. Background checkpoint thread (async)
   ├─ Reads dirty pages from buffer pool
   ├─ Writes them to .ibd data files (random writes — slow)
   └─ Frees redo log space
```

`innodb_flush_log_at_trx_commit=2` means step 2 writes to the OS page cache (not physical disk) and the OS flushes it once per second. This is why it's faster than the default (`=1` which syncs to physical disk on every commit) — but it means up to 1 second of data could be lost if the machine loses power.

---

## 37. Competing Consumers — Scaling Horizontally

### The Idea

The queue is a waiting room. Right now one consumer empties it. Put four consumers in the room and they empty it four times faster. RabbitMQ handles the distribution automatically — no coordination required between consumers.

```
                    ┌─ consumer-1 → batch 50k → MySQL
Producer → Queue ───┼─ consumer-2 → batch 50k → MySQL
                    ├─ consumer-3 → batch 50k → MySQL
                    └─ consumer-4 → batch 50k → MySQL
```

This is called the **Competing Consumers pattern**. RabbitMQ round-robins messages across all active consumers on the queue. Each consumer sees a different subset of messages — no message is delivered to two consumers simultaneously. Each consumer maintains its own batch buffer, its own delivery tags, its own DB connections. They never talk to each other.

The protocol guarantees: a message is delivered to exactly one consumer, and stays unacked in the queue until that consumer acks it. If a consumer crashes mid-batch, RabbitMQ redelivers its unacked messages to another consumer. `source_hash` + `INSERT IGNORE` makes this safe — the redelivered rows are just skipped.

---

### On the Same Machine — Multiple Threads

The simplest scaling: run multiple `FileConsumer` instances in the same JVM process. Each gets its own channel.

```java
private static final int NUM_CONSUMERS = 4;  // tune to your CPU core count

for (int i = 0; i < NUM_CONSUMERS; i++) {
    FileConsumer consumer = new FileConsumer();
    Thread t = new Thread(() -> consumer.consume());
    t.setName("consumer-" + (i + 1));
    t.start();
}
```

This is already in `Main.java`. A quad-core machine can run 4 consumers in parallel. Each uses one CPU core for CSV parsing and SHA-256 hashing, and one DB connection during a batch flush.

**Rule of thumb**: set `NUM_CONSUMERS` to the number of CPU cores available, minus 1 for the producer thread. On a 4-core machine: 3 consumers. On an 8-core machine: 7 consumers. In practice, the bottleneck is more often MySQL write throughput than CPU — so it is worth testing higher counts. **10 consumers ran successfully on this project's machine** without needing to be reduced, processing 5.75M rows across all 10 threads simultaneously. The initial concern that 10 connections might cause stability problems turned out to be a different bug (see the `declareQueues` race condition below) — once that was fixed, 10 consumers was completely stable.

### The declareQueues Race Condition

When each consumer thread calls `consume()`, the original code called `RabbitMQConfig.declareQueues(channel)` at the start — declaring the DLQ and main queue from within each consumer's own connection. With 10 consumers starting simultaneously on 10 different connections, all 10 sent `Queue.Declare` frames to RabbitMQ for the same queues at the same instant.

RabbitMQ serialises concurrent declarations of the same queue internally. With 10 simultaneous requests, the server backed up and the consumer threads blocked indefinitely waiting for `Queue.DeclareOk` — no exception, no error, no log output. The connections showed as `running` in the management UI, channels were open, but `basicConsume` was never reached. Unacked stayed at 0.

The diagnostic clue was the **"To client: 2 B/s"** column in the Connections tab — 2 bytes per second is just heartbeat traffic. If messages were being delivered, it would show KB/s or MB/s. Heartbeat-only traffic with consumers registered means `basicConsume` was never called.

The fix: declare queues exactly **once** from the main thread before any consumer starts, then remove the call from `consume()`.

```java
// Main.java — before starting consumer threads
try (Channel setupChannel = RabbitMQConfig.createChannel()) {
    RabbitMQConfig.declareQueues(setupChannel);  // done once, not 10 times racing
}

// FileConsumer.consume() — declaration removed
Channel channel = RabbitMQConfig.createChannel();
channel.basicQos(BATCH_SIZE * 2);
// ... no declareQueues call here anymore
channel.basicConsume(...);
```

The rule: queue declaration is the producer's job (or the app's one-time setup job). Consumers should assume the queue exists and just consume from it.

---

### On Multiple Machines — True Horizontal Scale

If one machine isn't enough, run consumer instances on N machines all pointing at the same RabbitMQ and MySQL:

```
Machine 1: producer + 4 consumers ──┐
Machine 2: 4 consumers ─────────────┼──→ RabbitMQ ──→ MySQL
Machine 3: 4 consumers ─────────────┘
```

The only requirement: each machine can reach RabbitMQ and MySQL over the network. The `application.properties` hosts would change from `localhost` to the actual server IPs.

To run only the consumer (no producer) on a machine, you'd split `Main.java` into two modes — or just let the producer finish quickly while the consumers on all machines drain the queue together.

---

### What Limits the Scaling

Adding more consumers helps until something else becomes the bottleneck:

**MySQL concurrent writes**: multiple consumers flushing 50,000 rows simultaneously compete for InnoDB write resources — the redo log, the buffer pool, and index page locks. Typically useful up to 4-8 concurrent batch writers on a single MySQL instance. Beyond that, lock contention grows faster than throughput.

**MySQL connection pool**: each consumer needs one connection during a batch flush. With 4 consumers × pool size of 20 = up to 20 simultaneous connections. MySQL's default `max_connections = 151` handles this easily. Increase if you scale to many machines.

**RabbitMQ throughput**: a single RabbitMQ node can handle millions of messages/sec. For 15M rows it is never the bottleneck.

**Network bandwidth**: if consumers are on different machines, the data travels: RabbitMQ → consumer network → MySQL. A 1Gbps network handles ~100MB/sec, sufficient for this workload.

---

### Performance Estimate — 15M Rows With 4 Consumers

Without the unique index during load (drop-then-rebuild pattern):

| Consumers | Rows/sec | 15M rows |
|---|---|---|
| 1 | ~25,000 | ~10 min |
| 4 | ~80,000 | ~3 min |
| 8 | ~120,000 | ~2 min |

Gains are not perfectly linear because MySQL write throughput is shared. 4 consumers is typically the sweet spot for a single MySQL instance on a dev machine. Beyond 4, you get diminishing returns unless MySQL is on dedicated hardware.

---

### Connection Pool Sizing

With multiple consumers, the HikariCP pool needs to be large enough that no consumer waits for a connection during a flush:

```java
// Rule: pool size ≥ NUM_CONSUMERS + a small buffer for Flyway and overhead
config.setMaximumPoolSize(20);  // covers 4 consumers with room to spare
```

If `waiting > 0` appears in HikariPool stats, the pool is too small. Increase `maximumPoolSize` (and check MySQL's `max_connections` allows it).

---

### Cross-Consumer ID Dedup — The Shared Set Problem

When the unique index is dropped for speed (drop-then-rebuild pattern), each consumer independently generates IDs with no DB-level safety net. Two consumers can generate the same `generated_id` simultaneously and both insert it — silent duplicates in the database.

A per-consumer `HashSet` only prevents collisions within one consumer's own generated IDs. It can't see what the other 9 consumers have generated.

**The fix: a shared `ConcurrentHashMap.newKeySet()` passed to all consumers at startup:**

```java
// Main.java — file-load mode only
Set<Long> usedIds = ConcurrentHashMap.newKeySet(8_000_000);

// Each consumer gets the same set reference
FileConsumer consumer = new FileConsumer(usedIds);
```

```java
// FileConsumer.java — the set is checked atomically
private String generateUniqueId() {
    if (usedIds == null) return idGenerator.generate();  // drain mode: index handles it
    String id = idGenerator.generate();
    while (!usedIds.add(Long.parseLong(id))) {           // add() is atomic in ConcurrentHashMap
        id = idGenerator.generate();
    }
    return id;
}
```

`ConcurrentHashMap.newKeySet()` is thread-safe — multiple threads calling `add()` simultaneously is safe with no external synchronisation. `add()` returns `false` if the value was already present, `true` if newly added. The `while` loop retries until the current thread successfully "claims" the ID.

**Two modes, two strategies:**

| Mode | Index during load | ID dedup mechanism |
|---|---|---|
| File load (`args.length > 0`) | Dropped for speed | Shared `ConcurrentHashMap` across all consumers |
| Drain (no file) | Present | DB unique index catches rare collisions via retry |

The `usedIds` field is `null` in drain mode — `generateUniqueId()` falls back to plain `idGenerator.generate()`, and the existing retry-on-1062 logic handles the ~16 collisions the DB will catch.

---

### V2: H2 Registry Replaces the Shared ConcurrentHashMap

In V2, the `ConcurrentHashMap.newKeySet()` and the two-constructor pattern were replaced with a single `InMemoryIdRegistry` backed by H2. The contract is the same — one shared registry, all consumers see the same state — but the backing store is a real SQL database instead of a Java collection.

The two-mode split (drain mode / file-load mode) disappears entirely because the V4 migration removed the MySQL unique index permanently. There is now only one mode: the H2 registry is always the uniqueness guard, regardless of whether a file was passed or not.

```java
// V1 — two constructors, null check in generateUniqueId()
private final Set<Long> usedIds;
public FileConsumer() { this.usedIds = null; }
public FileConsumer(Set<Long> usedIds) { this.usedIds = usedIds; }

private String generateUniqueId() {
    if (usedIds == null) return idGenerator.generate();   // drain mode: rely on DB index
    String id = idGenerator.generate();
    while (!usedIds.add(Long.parseLong(id))) { id = idGenerator.generate(); }
    return id;
}

// V2 — one constructor, registry always required, no null path
private final InMemoryIdRegistry registry;
public FileConsumer(InMemoryIdRegistry registry) { this.registry = registry; }

private String generateUniqueId() throws SQLException {
    String id = idGenerator.generate();
    while (!registry.register(Long.parseLong(id))) { id = idGenerator.generate(); }
    return id;
}
```

`Main.java` also simplifies: there is no `isFileLoad` branch for the registry, no `dropGeneratedIdIndex()`, and no `rebuildGeneratedIdIndex()` — those three methods are gone because the MySQL index no longer exists.

See Section 11 "V2: H2 In-Memory Database as the Uniqueness Registry" for a full explanation of what H2 is, why B-tree splits are not a problem in-memory, alternative options considered, and scale trade-offs.

---

### This Is Why the Architecture Uses RabbitMQ

Direct CSV-to-MySQL (without a queue) can only use one writer at a time per file — you'd need to split the file manually to parallelise. With RabbitMQ, the queue automatically distributes work to however many consumers you add, on however many machines you have. Adding a new consumer is just starting a new process — no file splitting, no coordination, no code change.

This is the core value proposition of a message queue in a data pipeline.

---

## 38. Removing source_hash for Clean Data

### What Changed and Why

Section 27 explains the `source_hash` system: a SHA-256 fingerprint stored as a `UNIQUE` column so that re-running the same CSV file doesn't insert duplicates. That system was built assuming the source data might be dirty or that the pipeline might need to be re-run safely.

When the data is **known to be clean** — one controlled load with no risk of re-running — the hash becomes pure overhead:

- SHA-256 is computed on every row before insert (CPU cost)
- The value is written to `source_hash VARCHAR(64)` (storage cost)
- MySQL checks the `ux_source_hash` unique index on every insert (the biggest cost — see Section 35 on why index maintenance slows down with scale)

We suspended the hash to measure raw insert throughput on clean data.

### What Changed in FileConsumer.java

```java
// Before — hash computed and stored on every row
transaction.setSourceHash(computeHash(transaction));
transaction.setId(idGenerator.generate());

// After — hash computation commented out
// transaction.setSourceHash(computeHash(transaction));
transaction.setId(idGenerator.generate());
```

**Import that was previously used by `computeHash`:**
```java
import java.security.MessageDigest;      // SHA-256 engine
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets; // for .getBytes(StandardCharsets.UTF_8)
import java.util.HexFormat;              // converts byte[] to hex string
```
These stay in the file because `computeHash` is still there (just not called), but if the method were removed they could be cleaned up.

### What Changed in TransactionRepository.java

`source_hash` was removed from all three INSERT statements and `setParams` was re-numbered:

```java
// Before — source_hash was column 2, everything else shifted by one
INSERT INTO transactions (
    id, source_hash, payment_type_id, ...
) VALUES (?, ?, ?, ...)
stmt.setString(2, t.getSourceHash());
stmt.setString(3, t.getPaymentTypeId());

// After — source_hash removed, 24 columns instead of 25
INSERT INTO transactions (
    id, payment_type_id, ...
) VALUES (?, ?, ...)
stmt.setString(2, t.getPaymentTypeId());
```

The `source_hash` column still exists in the database schema — it just receives `NULL` values. No migration needed. Flyway's V2 migration that created the column and index is unchanged; the index is still there, it just has no data in it.

### Performance Impact

Removing one `UNIQUE VARCHAR(64)` index from a table means:
- Every insert no longer triggers a B-tree lookup + possible page split on that index
- The `ux_source_hash` index stays empty, so those B-tree pages stay small and cold (irrelevant)
- MySQL only needs to maintain the primary key index (`id VARCHAR(12)`)

This is a meaningful speedup when inserting millions of rows. The tradeoff: if you re-run the same file, every row inserts again — duplicates in the database.

### When to Bring It Back

Re-enable both the hash computation and the `source_hash` column insert when:
- The pipeline needs to be idempotent (re-runs are safe)
- Source data might contain duplicates that should be silently skipped
- You are in production and cannot afford to re-examine every row manually

For development load-testing with known-clean CSVs, suspending it is the right call.

---

## 39. PK Collision — Find the Specific Row, Not the Whole Batch

### The Original Problem

When a bulk INSERT fails with a primary key collision (MySQL error 1062), the previous fallback strategy was:

```
Batch of 10,000 fails → retry every row one by one (10,000 individual inserts)
```

One collision penalises 9,999 innocent rows with individual round-trips. That batch becomes 50–100x slower than a normal batch flush.

### The New Strategy — Parse the MySQL Error Message

MySQL's 1062 error message always includes the duplicate key value:

```
Duplicate entry '047382910564' for key 'PRIMARY'
```

We extract that value with a regex, find the one transaction in the batch whose ID matches, regenerate just that ID, and retry the whole batch:

```java
// In flushBatch() — the retry loop
for (int attempt = 0; attempt <= maxPkRetries; attempt++) {
    try {
        skipped = transactionRepository.saveBatch(batch);
        break;  // success
    } catch (SQLException e) {
        if (e.getErrorCode() == 1062 && attempt < maxPkRetries) {
            String dupId = parseDuplicateId(e.getMessage());
            if (dupId == null) throw e;
            batch.stream()
                 .filter(t -> dupId.equals(t.getId()))
                 .findFirst()
                 .ifPresent(t -> {
                     log.warn("PK collision on id={}, retry {}/{}", dupId, currentAttempt + 1, maxPkRetries);
                     t.setId(idGenerator.generate());
                 });
        } else if (isRetriable(e)) {
            // deadlock/lock timeout → individual fallback (different problem)
            skipped = fallbackToIndividual(batch, batchLines, deliveryTags, channel);
            break;
        } else {
            throw e;
        }
    }
}

// Helper — extracts the colliding value from MySQL's error message
private String parseDuplicateId(String message) {
    if (message == null) return null;
    java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("Duplicate entry '(.+?)' for key 'PRIMARY'")
            .matcher(message);
    return m.find() ? m.group(1) : null;
}
```

**Imports used:**
```java
import java.util.regex.Matcher;   // not needed — we used the fully-qualified name inline
import java.util.regex.Pattern;   // same — Pattern.compile() called inline
```

We used fully-qualified names (`java.util.regex.Pattern`) inside the method to avoid adding imports for a one-off helper. Either way is fine.

### The Lambda Effectively-Final Problem

Java requires variables used inside lambdas to be **effectively final** — their value cannot change after the lambda is written. `attempt` is a loop variable that changes every iteration, so it can't be used directly inside `ifPresent`:

```java
// Compile error — attempt is not effectively final
.ifPresent(t -> log.warn("retry {}", attempt + 1, maxPkRetries));

// Fix — capture the current value in a new final variable
final int currentAttempt = attempt;
.ifPresent(t -> log.warn("retry {}", currentAttempt + 1, maxPkRetries));
```

`currentAttempt` is assigned once and never changes, so Java accepts it inside the lambda. This is a very common pattern whenever you need a loop variable inside a lambda.

### Why Three Lists Instead of a Map

The batch is stored as three parallel lists (`batch`, `batchLines`, `deliveryTags`), not as a list of objects that bundle all three together. Index `i` in all three always refers to the same original message.

The stream search (`batch.stream().filter(t -> dupId.equals(t.getId()))`) only searches the `batch` list. Once found, the transaction object's ID is mutated in place — the corresponding entry in `batchLines` and `deliveryTags` is unaffected, because those don't store the ID. The lists stay in sync with no extra bookkeeping.

---

## 40. Per-Consumer Connections — Removing the Shared Reader Thread

### Original Design — One Shared Connection

`RabbitMQConfig` originally had a **static** `Connection`:

```java
public class RabbitMQConfig {
    private static Connection connection;  // ONE connection, shared by everything

    static {
        // runs once at class load
        connection = factory.newConnection();
    }

    public static Channel createChannel() throws IOException {
        return connection.createChannel();  // all channels share this one connection
    }
}
```

All 6 (later 10) consumers called `createChannel()` and got channels on the same underlying TCP connection.

### The Problem — One Reader Thread for All Consumers

A RabbitMQ `Connection` has one **reader thread** — a single thread that reads all AMQP frames off the TCP socket and routes them to the correct channel. With 6 consumers sharing one connection:

```
RabbitMQ server
     │
     TCP socket (one reader thread)
     │
     ├─ channel-1 → consumer-1 callback
     ├─ channel-2 → consumer-2 callback
     ├─ channel-3 → consumer-3 callback
     ├─ channel-4 → consumer-4 callback
     ├─ channel-5 → consumer-5 callback
     └─ channel-6 → consumer-6 callback
```

All 6 consumers' messages had to pass through that single reader thread. With 140,000+ messages in the queue, the reader thread became the bottleneck — it was routing frames faster than it could keep up under load.

### The Fix — One Connection Per Consumer

```java
public class RabbitMQConfig {
    private static final ConnectionFactory factory;  // factory is shared, connections are not

    static {
        factory = new ConnectionFactory();
        factory.setHost(props.getProperty("rabbitmq.host"));
        // ... other config ...
    }

    // Each call creates a fresh Connection → its own reader thread
    public static Channel createChannel() throws IOException, TimeoutException {
        return factory.newConnection().createChannel();
    }
}
```

Now each consumer gets its own TCP connection when it calls `createChannel()`. Each connection has its own reader thread:

```
RabbitMQ server
     │
     ├─ TCP connection-1 (reader thread 1) → consumer-1
     ├─ TCP connection-2 (reader thread 2) → consumer-2
     ├─ TCP connection-3 (reader thread 3) → consumer-3
     ├─ TCP connection-4 (reader thread 4) → consumer-4
     ├─ TCP connection-5 (reader thread 5) → consumer-5
     └─ TCP connection-6 (reader thread 6) → consumer-6
```

Message delivery to all 6 consumers now happens in parallel. No single reader thread is a bottleneck.

**Import change:** `createChannel()` now throws `TimeoutException` (because `factory.newConnection()` can time out), so the method signature changed from `throws IOException` to `throws IOException, TimeoutException`. This required updating `consume()` in `FileConsumer` — but `consume()` already declared `throws java.util.concurrent.TimeoutException`, so no change was needed there.

**Why the channel stays alive after `consume()` returns:** the `channel` local variable is captured by the callback lambda and the scheduler lambda. Java keeps it alive as long as those lambdas are referenced. The connection behind the channel stays alive because the channel references it internally, and the connection's reader/writer threads are GC roots.

---

## 41. Timed Flush — Draining the Tail Without a Full Batch

### The Problem — Partial Batches That Never Flush

Each consumer only flushes when its batch reaches `BATCH_SIZE` (10,000 rows). With 140,000 messages remaining in the queue across 10 consumers:

```
140,000 / 10 = 14,000 messages per consumer
14,000 / 10,000 = 1 full flush + 4,000 leftover
```

Those 4,000 leftover messages per consumer sit in the in-memory batch list. They are **unacked in RabbitMQ** (held but not yet acknowledged). Since `BATCH_SIZE` is never reached again (the queue is empty), they are never flushed. The database never receives them. The consumers just sit there waiting for a batch that will never fill up.

### The Fix — ScheduledExecutorService

A `ScheduledExecutorService` fires a "timed flush" every `FLUSH_INTERVAL_SECONDS` seconds. If the batch has anything in it, it's flushed immediately regardless of size:

```java
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// In FileConsumer fields:
private static final int FLUSH_INTERVAL_SECONDS = 10;
private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

// In consume():
scheduler.scheduleAtFixedRate(() -> {
    List<Transaction> batchSnapshot;
    List<String>      linesSnapshot;
    List<Long>        tagsSnapshot;

    synchronized (batchLock) {
        if (batch.isEmpty()) return;          // nothing to flush
        batchSnapshot = new ArrayList<>(batch);
        linesSnapshot = new ArrayList<>(batchLines);
        tagsSnapshot  = new ArrayList<>(deliveryTags);
        batch.clear();
        batchLines.clear();
        deliveryTags.clear();
    }

    log.info("Timed flush: flushing {} buffered messages", batchSnapshot.size());
    submitFlush(channel, batchSnapshot, linesSnapshot, tagsSnapshot);

}, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
```

**`scheduleAtFixedRate(task, initialDelay, period, unit)`** — runs `task` after `initialDelay`, then again every `period`. If the task throws an unchecked exception, future executions are silently cancelled (this is a known gotcha — wrap the task body in try-catch if exceptions are possible).

### Thread Safety — Why synchronized Is Needed

The RabbitMQ callback runs on RabbitMQ's internal `ConsumerWorkService` thread. The scheduler runs on its own thread. Both threads touch the same `batch`, `batchLines`, and `deliveryTags` lists.

Without synchronisation, two threads modifying a non-thread-safe `ArrayList` simultaneously causes `ConcurrentModificationException` or silent data corruption (lists appearing to have inconsistent lengths, entries pointing to the wrong index).

The fix is a **lock object** shared by both threads:

```java
private final Object batchLock = new Object();
```

Every access to the batch is wrapped:

```java
// In the RabbitMQ callback:
synchronized (batchLock) {
    batch.add(transaction);
    batchLines.add(line);
    deliveryTags.add(delivery.getEnvelope().getDeliveryTag());

    if (batch.size() >= BATCH_SIZE) {
        // snapshot and clear inside the lock
    }
}

// In the scheduler:
synchronized (batchLock) {
    if (batch.isEmpty()) return;
    // snapshot and clear inside the lock
}
```

`synchronized (batchLock)` means: only one thread can execute this block at a time. If the callback is in the middle of `batch.add()`, the scheduler waits until the lock is released before snapshotting. This prevents partial reads and concurrent modification.

The actual flush (`submitFlush`) happens **outside** the lock — there's no reason to hold the lock while talking to MySQL. Only the list operations need protection.

### submitFlush — Extracting the Flush Submission

To avoid duplicating the `flushExecutor.submit(...)` lambda in both the batch-full path and the timed flush path, a private helper method was extracted:

```java
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
```

Both the batch-full path and the timed flush path call `submitFlush`. The `flushExecutor` is single-threaded, so flushes are serialised — they never run concurrently, even if both the batch-full condition and the timer fire at the same moment.

---

## 42. Double-Buffering — Eliminating the Idle Gap With 2x Prefetch

### The Idle Gap Problem

With `basicQos(BATCH_SIZE)` (prefetch = 10,000) and a MySQL insert that takes 25 seconds, here is what a consumer's timeline looks like:

```
t=0s:   RabbitMQ delivers 10,000 messages (prefetch quota filled)
t=0s:   Batch fills up → flush submitted to flushExecutor
t=0s:   Consumer is now idle — all 10,000 messages are "unacked", prefetch quota full
        RabbitMQ won't send more until some are acked
t=25s:  MySQL insert completes
t=25s:  basicAck sent — all 10,000 messages acked
t=25s:  RabbitMQ delivers next 10,000 messages
t=25s:  Consumer idle again while next batch accumulates
...
```

The consumer is **idle for 25 seconds** waiting for the MySQL insert. That idle time is wasted throughput.

### The Fix — basicQos(BATCH_SIZE * 2)

With prefetch set to twice the batch size:

```java
channel.basicQos(BATCH_SIZE * 2);  // was: BATCH_SIZE
```

The timeline becomes:

```
t=0s:   RabbitMQ delivers 20,000 messages (new prefetch quota)
t=0s:   First 10,000 fill the batch → flush submitted → MySQL insert begins
t=0s:   Consumer continues receiving messages 10,001–20,000 into the NEXT batch
t=10s:  Second batch fills (10,000 more messages) → timed flush fires or batch full
t=25s:  First MySQL insert completes → ack sent for first 10,000
t=25s:  RabbitMQ can now deliver another 10,000 (quota is back at 10,000 available)
t=25s:  Second MySQL insert completes → ack sent → third batch begins
```

The consumer now has the **next batch ready** as soon as the current insert finishes. MySQL inserts continuously with no gap between them.

### Why This Works — The flushExecutor Queue

`flushExecutor` is a single-threaded executor. When the second batch fills while the first flush is still running, the second flush task is **queued** in the executor. The moment the first flush completes, the executor immediately picks up the second task. No waiting.

```
flushExecutor queue:
  [flush batch 1] → running (MySQL insert, 25 seconds)
  [flush batch 2] → waiting  ← filled during batch 1's insert
  
After batch 1 completes:
  [flush batch 2] → now running immediately (no idle gap)
  [flush batch 3] → waiting (being filled while batch 2 inserts)
```

The practical effect: instead of MySQL being busy 50% of the time (25s insert, 25s idle), it is busy nearly 100% of the time.

### The Trade-Off

Doubling the prefetch means up to `2 × BATCH_SIZE` messages are "in-flight" (unacked) in the consumer's memory at any time. For `BATCH_SIZE = 10,000`, that's 20,000 messages held in memory. Each message is a CSV line (~200–500 bytes), so ~4–10MB per consumer — negligible.

The risk: if the consumer crashes with 20,000 unacked messages instead of 10,000, RabbitMQ redelivers 20,000 rows on the next run. With the clean-data assumption (no `source_hash`), those rows would be inserted again. This is only a problem if: (a) the table was NOT truncated before re-running AND (b) you care about duplicate rows.

---

## 43. JVM Keepalive — Why Drain Mode Was Silently Exiting

### The Problem

`basicConsume` is **non-blocking**. It registers a callback with RabbitMQ's internal thread pool and returns immediately. The consumer thread that called `consume()` then exits:

```java
// In Main.java — what happens when no file is provided:
for (int i = 0; i < NUM_CONSUMERS; i++) {
    Thread t = new Thread(() -> consumer.consume());
    t.start();  // consumer.consume() returns in milliseconds
}

log.info("No file provided — consumers are draining the existing queue");
// main() returns here → JVM checks for remaining non-daemon threads → finds none → exits
```

After all 10 consumer threads call `consume()` and return, the JVM has no more user threads keeping it alive. It exits — closing all RabbitMQ connections and abandoning any messages that were in transit.

RabbitMQ logs this as `client unexpectedly closed TCP connection`, which is exactly what it was.

### Why the Producer Run Didn't Have This Problem

When the producer is running (`args.length > 0`), `main()` calls `producerThread.join()` — which blocks the main thread until the producer finishes. The main thread staying alive kept the JVM alive throughout the entire producer + consumer run. Drain mode (no producer) had no such anchor.

### The Fix

```java
} else {
    log.info("No file provided — consumers are draining the existing queue");
    Thread.currentThread().join();  // block main thread forever (until Ctrl+C)
}
```

`Thread.currentThread().join()` tells the current thread to wait for itself to finish — which never happens. The main thread blocks indefinitely, keeping the JVM alive while the consumers and their scheduler/executor threads do their work.

When you press Ctrl+C, the JVM receives `SIGINT`, the shutdown sequence begins, and all threads are cleanly terminated.

**Why not `Thread.sleep(Long.MAX_VALUE)`?** Both work. `join()` is semantically cleaner — "wait for this thread to die" rather than "sleep for 292 years". In practice there is no observable difference.

### The Non-Daemon Thread Subtlety

Java has two kinds of threads:
- **User threads** (non-daemon) — the JVM stays alive as long as any of these exist
- **Daemon threads** — the JVM can exit even if these are still running (e.g., GC thread)

`Executors.newSingleThreadScheduledExecutor()` and `Executors.newSingleThreadExecutor()` create **non-daemon** threads by default. So the scheduler and flushExecutor threads should technically keep the JVM alive even without the `join()`. In practice, the thread may not be created until the first task is submitted — if `consume()` returns before the first task fires (10 seconds for the scheduler), there is a window where no non-daemon threads exist. The `join()` on the main thread closes that window entirely, no matter what the executor threads do.

---

## 44. Summary of Changes — This Session

The table below is a condensed record of what changed, why, and what you would look for in the code.

| Change | File | Why | What to look for |
|---|---|---|---|
| `source_hash` computation commented out | `FileConsumer.java` | Clean data — hash overhead not needed | `// transaction.setSourceHash(...)` |
| `source_hash` removed from all INSERTs | `TransactionRepository.java` | Matches above — no hash, no column | 24 params instead of 25 in `setParams` |
| INSERT IGNORE → plain INSERT | `TransactionRepository.java` | No dedup needed; surfacing real errors | `INSERT INTO` not `INSERT IGNORE INTO` |
| PK collision: parse error, fix one row, retry batch | `FileConsumer.java` | 1 collision shouldn't cost 9,999 rows | `parseDuplicateId()`, loop in `flushBatch()` |
| Shared `Connection` → per-consumer `Connection` | `RabbitMQConfig.java` | One reader thread was a bottleneck | `factory.newConnection()` inside `createChannel()` |
| `NUM_CONSUMERS` 6 → 10 | `Main.java` | More parallel inserts — 10 ran stably | `private static final int NUM_CONSUMERS = 10` |
| HikariCP pool 20 → 30 | `DatabaseConfig.java` | Cover 10 consumers with headroom | `config.setMaximumPoolSize(30)` |
| `batchLock` + `synchronized` | `FileConsumer.java` | Scheduler + callback on different threads | `private final Object batchLock` |
| `ScheduledExecutorService scheduler` | `FileConsumer.java` | Tail-end partial batches never flushed | `Executors.newSingleThreadScheduledExecutor()` |
| `basicQos(BATCH_SIZE)` → `basicQos(BATCH_SIZE * 2)` | `FileConsumer.java` | Eliminate idle gap between inserts | `channel.basicQos(BATCH_SIZE * 2)` |
| `Thread.currentThread().join()` in drain mode | `Main.java` | JVM was exiting before consumers finished | In the `else` branch of `if (args.length > 0)` |
| `declareQueues` moved to `Main.java` | `Main.java` / `FileConsumer.java` | 10 threads simultaneously declaring the same queue caused a silent deadlock | `try (Channel setup = ...) { declareQueues(setup); }` before consumer loop |
| `splitCsv()` replaces `split(",")` | `FileConsumer.java` | Quoted fields containing commas shifted all column indices | `splitCsv(line)` in `parseLine()` |
| Shovel plugin for DLQ replay | RabbitMQ container | Move failed messages back to main queue after fixing the bug | `docker exec <container> rabbitmq-plugins enable rabbitmq_shovel rabbitmq_shovel_management` |
| **V2 — H2 in-memory registry** | | | |
| V4 migration — drop `ux_generated_id` from MySQL | `V4__drop_generated_id_index.sql` | MySQL no longer enforces uniqueness; H2 is the sole guard | `ALTER TABLE transactions DROP INDEX ux_generated_id` |
| H2 dependency | `build.gradle.kts` | New dep for in-memory DB | `implementation("com.h2database:h2:2.2.224")` |
| `InMemoryIdRegistry` service | `InMemoryIdRegistry.java` (new) | Replaces `ConcurrentHashMap` / `HashSet` — SQL uniqueness via H2 | `CREATE TABLE used_ids (id BIGINT PRIMARY KEY)` + `register(long id)` |
| `FileConsumer` — single constructor | `FileConsumer.java` | Drain mode / file-load split removed; registry always required | `FileConsumer(InMemoryIdRegistry registry)` only |
| `generateUniqueId()` — calls `registry.register()` | `FileConsumer.java` | Replaces `usedIds.add()`; no null check, no two-path logic | `while (!registry.register(Long.parseLong(id)))` |
| `BulkLoader` — `HashSet<Long>` → `InMemoryIdRegistry` | `BulkLoader.java` | Consistent approach across all paths | `registry.register(...)` in pre-process loop |
| `BulkLoader.loadIntoDb()` — drop/rebuild steps removed | `BulkLoader.java` | MySQL index gone; LOAD DATA is now one step | Step 2/2 instead of steps 2/3 + 3/3 |
| `Main.java` — `dropGeneratedIdIndex` / `rebuildGeneratedIdIndex` deleted | `Main.java` | No MySQL index to manage | `try (InMemoryIdRegistry registry = new InMemoryIdRegistry())` wraps the run |

---

*Built during SIWES @ ITC, Accra — June 2026*
