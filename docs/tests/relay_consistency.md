# Relay Consistency Tests

These tests verify shared rate limits and nonce replay across multiple relay
instances using a shared Redis backend.

## Prerequisites
- Two relay instances running and reachable.
- Both instances configured with the same `REDIS_URL`.
- Rate limits set low enough to trigger, but not blocked by IP limits.

Recommended config (dev, shared Redis):

```
REDIS_URL=redis://redis:6379
INGEST_RATE_LIMIT_PER_GID=3
INGEST_RATE_LIMIT_PER_IP=1000
TRUSTED_PROXY_IPS=127.0.0.1
IP_ALLOWLIST=127.0.0.1
```

If you run isolated Redis per relay (e.g. `redis_2` for `relay_2`), this test
should fail by design because limits and nonce caches are no longer shared.

## Run

```bash
chmod +x server/relay/scripts/run-consistency-tests.sh
RELAY_BASE_URLS=http://localhost:8080,http://localhost:8081 \
USE_ENV_DEV=1 ./server/relay/scripts/run-consistency-tests.sh
```

Expected output includes:
- `PASS shared_rate_limit_gid`
- `PASS shared_nonce_replay`

## Notes
- If you run the test inside a container, use URLs reachable from that container
  (e.g. `http://host.docker.internal:8080`).
