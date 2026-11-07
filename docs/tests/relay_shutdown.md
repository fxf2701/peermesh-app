# Relay Graceful Shutdown Tests

This doc describes how to test SIGTERM/SIGINT shutdown handling for the relay server.

## Prerequisites
- Relay server dependencies installed (or run inside relay container).
- `DATABASE_URL` available (reads from `server/.env` if present).

## Run the shutdown test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-shutdown-tests.sh
./server/relay/scripts/run-shutdown-tests.sh
```

Expected output includes:
- `PASS health_before_shutdown`
- `PASS graceful_exit`
- `PASS server_closed`
