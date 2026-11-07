# Relay Error Handling & Observability Tests

This doc describes how to test the health endpoint output for relay observability.

## Prerequisites
- Relay server running and reachable (default: http://localhost:8080).

## Run the observability test script
From the repo root (the script will read `server/.env` if present):

```bash
chmod +x server/relay/scripts/run-observability-tests.sh
./server/relay/scripts/run-observability-tests.sh
```

## Run the Prometheus metrics test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-metrics-tests.sh
./server/relay/scripts/run-metrics-tests.sh
```

## Run the log redaction test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-log-redaction-tests.sh
./server/relay/scripts/run-log-redaction-tests.sh
```

## Run the error budget summary test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-error-budget-tests.sh
./server/relay/scripts/run-error-budget-tests.sh
```

Expected output includes:
- `PASS health_ok`
- `PASS db_error_mapping` (requires test mode)
- `PASS ready_ok`
- `PASS metrics_ok`
- `PASS metrics_prometheus_export`
- `PASS log_redaction`
- `PASS error_budget_summary_log`

Note: `/metrics` returns Prometheus text format. The metrics test also asserts federation backpressure series (`federation_backpressure_in_flight`, `federation_backpressure_queue_size`, `federation_backpressure_drops_total`).

## DB error mapping test mode
To enable DB error mapping tests, set this in `server/.env` and restart relay:

```
ENABLE_TEST_DB_ERRORS=1
```
