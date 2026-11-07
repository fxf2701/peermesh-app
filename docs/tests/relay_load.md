# Relay Load Tests

These tests provide baseline latency checks for ingest/fetch under concurrent load.

## Prerequisites
- Relay server running and reachable (default: http://localhost:8080).
- Rate limits configured high enough to avoid 429s during load.

Recommended config (dev):

```
INGEST_RATE_LIMIT_PER_IP=10000
INGEST_RATE_LIMIT_PER_GID=10000
FETCH_RATE_LIMIT_PER_IP=10000
FETCH_RATE_LIMIT_PER_GID=10000
```

## Run

```bash
chmod +x server/relay/scripts/run-load-tests.sh
USE_ENV_DEV=1 ./server/relay/scripts/run-load-tests.sh
```

Optional overrides:

```
LOAD_INGEST_REQUESTS=200
LOAD_FETCH_REQUESTS=200
LOAD_CONCURRENCY=20
LOAD_MAX_P95_MS=500
LOAD_MAX_ERROR_RATE=0
```

Expected output includes:
- `PASS load_ingest`
- `PASS load_fetch`

## Baseline + regression check
Create a baseline (writes `server/tests/load_baseline.json`):

```bash
LOAD_BASELINE_WRITE=1 ./server/relay/scripts/run-load-baseline-tests.sh
```

Run regression check (fails if p95/error rate regress beyond thresholds):

```bash
./server/relay/scripts/run-load-baseline-tests.sh
```

Optional overrides:

```bash
LOAD_REGRESSION_P95_PCT=0.2
LOAD_REGRESSION_ERROR_RATE=0.01
LOAD_BASELINE_PATH=server/tests/load_baseline.json
```

## Synthetic benchmark (p95/p99)
From the repo root:

```bash
chmod +x server/relay/scripts/run-benchmark-tests.sh
./server/relay/scripts/run-benchmark-tests.sh
```

Optional overrides:

```bash
BENCH_INGEST_REQUESTS=200
BENCH_FETCH_REQUESTS=200
BENCH_CONCURRENCY=20
BENCH_MAX_P95_MS=500
BENCH_MAX_P99_MS=900
```

Expected output includes:
- `PASS bench_ingest`
- `PASS bench_fetch`

## Pagination stress test (large cursor windows)
From the repo root:

```bash
chmod +x server/relay/scripts/run-pagination-stress-tests.sh
./server/relay/scripts/run-pagination-stress-tests.sh
```

If request signatures are enabled, pass the secret:

```bash
REQUEST_SIGNATURE_ENABLED=1 REQUEST_SIGNATURE_SECRET=test-secret \
./server/relay/scripts/run-pagination-stress-tests.sh
```

Optional overrides:

```bash
PAGINATION_STRESS_EVENTS=200
PAGINATION_STRESS_PAGE_SIZE=25
PAGINATION_STRESS_MAX_PAGES=20
PAGINATION_STRESS_MAX_LATENCY_MS=2000
```

Expected output includes:
- `PASS pagination_stress`

## DB index health checks (bloat, missing indexes)
From the repo root:

```bash
chmod +x server/relay/scripts/run-db-index-health-tests.sh
./server/relay/scripts/run-db-index-health-tests.sh
```

Expected output includes:
- `PASS db_index_required`
- `PASS db_index_bloat_check` (or WARN when stats unavailable)

Optional: override timeout (seconds):

```bash
DB_INDEX_HEALTH_TEST_TIMEOUT_SECONDS=15 ./server/relay/scripts/run-db-index-health-tests.sh
```
