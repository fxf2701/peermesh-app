# Relay Reliability Tests

This doc covers reliability-oriented checks for the relay server.

## Prerequisites
- Relay server running and reachable (default: http://localhost:8080).
- `MIGRATIONS_TEST_DATABASE_URL` points to a disposable test database.

## Run the startup schema validation test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-startup-schema-tests.sh
./server/relay/scripts/run-startup-schema-tests.sh
```

Expected output includes:
- `PASS startup_schema_missing`
- `PASS startup_schema_valid`

Optional: override timeout (seconds):

```bash
STARTUP_SCHEMA_TEST_TIMEOUT_SECONDS=30 ./server/relay/scripts/run-startup-schema-tests.sh
```

## Run the cleanup watchdog test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-cleanup-watchdog-tests.sh
./server/relay/scripts/run-cleanup-watchdog-tests.sh
```

Expected output includes:
- `PASS cleanup_watchdog_stuck_detected`

Optional: override timeout (seconds):

```bash
CLEANUP_WATCHDOG_TEST_TIMEOUT_SECONDS=20 ./server/relay/scripts/run-cleanup-watchdog-tests.sh
```

## Run the DB circuit breaker test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-db-circuit-breaker-tests.sh
./server/relay/scripts/run-db-circuit-breaker-tests.sh
```

Expected output includes:
- `PASS db_circuit_open`
- `PASS db_circuit_half_open`

Optional: override timeout (seconds):

```bash
DB_CIRCUIT_TEST_TIMEOUT_SECONDS=12 ./server/relay/scripts/run-db-circuit-breaker-tests.sh
```

## Run the ingest retry queue test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-ingest-retry-queue-tests.sh
./server/relay/scripts/run-ingest-retry-queue-tests.sh
```

Expected output includes:
- `PASS ingest_retry_queue_success`
- `PASS ingest_retry_queue_drop`

Optional: override timeout (seconds):

```bash
INGEST_RETRY_TEST_TIMEOUT_SECONDS=12 ./server/relay/scripts/run-ingest-retry-queue-tests.sh
```

## Run the partition auto-create test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-partition-auto-create-tests.sh
./server/relay/scripts/run-partition-auto-create-tests.sh
```

Expected output includes:
- `PASS partition_auto_create`

Optional: override timeout (seconds):

```bash
PARTITION_AUTO_CREATE_TEST_TIMEOUT_SECONDS=25 ./server/relay/scripts/run-partition-auto-create-tests.sh
```

## Run the DB cooldown test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-db-cooldown-tests.sh
./server/relay/scripts/run-db-cooldown-tests.sh
```

Expected output includes:
- `PASS db_cooldown_activation`
- `PASS db_cooldown_deactivation`
- `PASS db_cooldown_metric`

Optional: override timeout (seconds):

```bash
DB_COOLDOWN_TEST_TIMEOUT_SECONDS=12 ./server/relay/scripts/run-db-cooldown-tests.sh
```
