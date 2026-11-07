# Relay Storage & TTL Tests

This doc describes how to test TTL cleanup and index presence for the relay server.

## Prerequisites
- Relay server running and reachable (default: http://localhost:8080).
- Postgres running with the relay schema applied.
- Relay configured with a short cleanup interval/grace for tests.

## Configure relay for TTL tests
Set low values in `server/.env` so cleanup happens quickly:

```
CLEANUP_INTERVAL_SECONDS=2
CLEANUP_GRACE_SECONDS=1
```

Restart relay after changes:

```bash
docker compose up -d --build
```

## Run the TTL test script
From the repo root (the script will read `server/.env` if present):

```bash
chmod +x server/relay/scripts/run-ttl-tests.sh
./server/relay/scripts/run-ttl-tests.sh
```

Expected output includes:
- `PASS indexes_present`
- `PASS ingest_setup`
- `PASS rows_inserted`
- `PASS cleanup_expired`

## Notes
- The test checks that expired rows are deleted based on `exp` only. Events
  with old `ts` but future `exp` should remain.
