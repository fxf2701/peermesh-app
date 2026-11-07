# Relay Cursor & Fetch Tests

This doc describes how to test cursor validation, pagination, and limit caps for the relay server.

## Prerequisites
- Relay server running and reachable (default: http://localhost:8080).
- Fetch max limit configured in `server/.env` (defaults to 200).

## Run the fetch test script
From the repo root (the script will read `server/.env` if present):

```bash
chmod +x server/relay/scripts/run-fetch-tests.sh
./server/relay/scripts/run-fetch-tests.sh
```

Expected output includes:
- `PASS invalid_cursor`
- `PASS limit_cap`
- `PASS fetch_pagination`
