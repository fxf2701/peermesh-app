# Relay Failover Tests

These tests validate behavior when the database is temporarily unavailable.

## Prerequisites
- Relay server running and reachable (default: http://localhost:8080).
- Postgres running as Docker container `disaster-app-postgres`.

## Run (disruptive)

This test stops the Postgres container briefly.

```bash
chmod +x server/relay/scripts/run-failover-tests.sh
ALLOW_DB_DISRUPTION=1 USE_ENV_DEV=1 ./server/relay/scripts/run-failover-tests.sh
```

Expected output includes:
- `PASS /healthz 200` (before and during DB downtime)
- `PASS /readyz 503` (while DB is down)
- `PASS /readyz 200` (after DB recovers)
- `PASS failover_db_disconnect`

## Notes
- If your Postgres container has a different name, set `POSTGRES_CONTAINER`.
