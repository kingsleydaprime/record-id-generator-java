# Record ID Generator

Reads a CSV file of transaction records, publishes each row to RabbitMQ, then consumes them in parallel batches, assigns a unique ID and deduplication hash to each record, and bulk-inserts them into MySQL. Duplicate rows (matched by `source_hash`) are silently skipped.

---

## Architecture

```
CSV file
   |
FileProducer  →  RabbitMQ queue  →  FileConsumer (x6 threads)
                                          |
                                    IdGeneratorService (assigns ID + SHA-256 hash)
                                          |
                                    MySQL (batch INSERT IGNORE)
```

---

## Setup & Running

### Local (infra only via Docker, app on host)

Start MySQL and RabbitMQ:
```bash
docker compose -f docker-compose.dev.yml up -d
```

Run the app (pass the CSV file as an argument):
```bash
./gradlew run --args="path/to/transactions.csv"
```

To drain an existing queue without publishing new records, omit the file argument:
```bash
./gradlew run
```

### Docker (all-in-one)

```bash
cp .env.example .env   # fill in DB_USER, DB_PASSWORD, RABBITMQ_USER, RABBITMQ_PASS, DB_ROOT_PASSWORD
docker compose -f docker-compose.prod.yml up --build
```

---

## Configuration

`app/src/main/resources/application.properties`

| Property | Description |
|---|---|
| `db.url` | JDBC connection string (includes `rewriteBatchedStatements=true`) |
| `db.user` | MySQL username |
| `db.password` | MySQL password |
| `rabbitmq.host` | RabbitMQ host |
| `rabbitmq.port` | RabbitMQ AMQP port (default `5672`) |
| `rabbitmq.user` | RabbitMQ username |
| `rabbitmq.pass` | RabbitMQ password |
| `rabbitmq.queue` | Queue name (default `record.queue`) |

Database migrations run automatically on startup via Flyway.
