# Relay Storage & Performance Tests

This doc describes how to test storage/performance hardening:
batch insert, cleanup coordination, and optional partition detection.

## Prerequisites
- Relay server running and reachable (default: http://localhost:8080).
- Postgres running with the relay schema applied.
- Cleanup interval/grace configured to small values for the lock test.

## Configure relay for tests
Set low values in `server/.env` or `server/.env.dev`:

```
CLEANUP_INTERVAL_SECONDS=2
CLEANUP_GRACE_SECONDS=1
CLEANUP_USE_ADVISORY_LOCK=1
DB_POOL_MAX=2
DB_CONN_TIMEOUT_MS=500
```

Restart relay after changes:

```bash
./server/start.sh --dev --build
```

## Run the storage/performance test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-storage-tests.sh
USE_ENV_DEV=1 ./server/relay/scripts/run-storage-tests.sh
```

Expected output includes:
- `PASS batch_insert_ingest`
- `PASS batch_insert_db_rows`
- `PASS db_pool_timeout`
- `PASS cleanup_lock`
- `PASS partitioned_schema_detected` or `SKIP partitioned_schema_detected`

## Notes
- Partition detection is optional; it is only `PASS` if you applied
  `server/relay/config/schema_partitioned.sql`.
