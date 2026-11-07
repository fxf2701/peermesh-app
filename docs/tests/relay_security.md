# Relay Security & Abuse Controls Tests

This doc describes how to test rate limits, request size limits, and nonce replay
protection for the relay server.

## Prerequisites
- Relay server running and reachable (default: http://localhost:8080).
- Postgres running with the relay schema applied.

## Configure relay for deterministic tests
Set low limits so tests trigger quickly. Update `server/.env` and restart the
relay container:

```
RATE_LIMIT_WINDOW_SECONDS=60
RATE_LIMIT_BURST_WINDOW_SECONDS=10
INGEST_RATE_LIMIT_PER_IP=2
INGEST_RATE_LIMIT_PER_GID=1
FETCH_RATE_LIMIT_PER_IP=2
FETCH_RATE_LIMIT_PER_GID=1
INGEST_BURST_PER_IP=2
INGEST_BURST_PER_GID=1
FETCH_BURST_PER_IP=2
FETCH_BURST_PER_GID=1
MAX_REQUEST_BYTES=2000
NONCE_CACHE_MAX_ENTRIES=10000
NONCE_CACHE_TTL_SECONDS=600
TRUSTED_PROXY_IPS=127.0.0.1
IP_ALLOWLIST=
IP_DENYLIST=
FEDERATION_PEER_ALLOWLIST=
```

Restart relay after changes:

```bash
docker compose up -d --build
```

If you want to test Redis-backed rate limits/nonce cache, start dev stack with Redis:

```bash
./server/start.sh --dev --build
```

## Run the security tests
From the repo root:

```bash
chmod +x server/relay/scripts/run-security-tests.sh
MAX_REQUEST_BYTES=2000 \
INGEST_RATE_LIMIT_PER_IP=2 INGEST_RATE_LIMIT_PER_GID=1 \
FETCH_RATE_LIMIT_PER_IP=2 FETCH_RATE_LIMIT_PER_GID=1 \
./server/relay/scripts/run-security-tests.sh
```

Expected output includes:
- `PASS request_size_limit`
- `PASS nonce_replay`
- `PASS rate_limit_ingest_ip`
- `PASS rate_limit_ingest_gid`
- `PASS rate_limit_fetch_ip`
- `PASS rate_limit_fetch_gid`
- `PASS rate_limit_ingest_burst_ip` (optional)
- `PASS rate_limit_ingest_burst_gid` (optional)
- `PASS trusted_proxy_xff` (optional)
- `PASS ip_denylist` (optional)
- `PASS ip_allowlist` (optional)
- `PASS federation_peer_auth` (optional)

## Validate edge/WAF guidance doc
From the repo root:

```bash
chmod +x server/relay/scripts/run-waf-guidance-tests.sh
./server/relay/scripts/run-waf-guidance-tests.sh
```

Expected output includes:
- `PASS waf_guidance_doc`

## Run the rate-limit spike alert test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-rate-limit-spike-tests.sh
./server/relay/scripts/run-rate-limit-spike-tests.sh
```

Expected output includes:
- `PASS rate_limit_spike_trigger`
- `PASS rate_limit_spike_cooldown`

Optional: override timeout (seconds):

```bash
RATE_LIMIT_SPIKE_TEST_TIMEOUT_SECONDS=12 ./server/relay/scripts/run-rate-limit-spike-tests.sh
```

## Run the audit log test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-audit-log-tests.sh
./server/relay/scripts/run-audit-log-tests.sh
```

Expected output includes:
- `PASS audit_log_verify`

Optional: override timeout (seconds):

```bash
AUDIT_LOG_TEST_TIMEOUT_SECONDS=12 ./server/relay/scripts/run-audit-log-tests.sh
```

## Run the request signature test script
From the repo root (signature mode must be enabled):

```bash
chmod +x server/relay/scripts/run-request-signature-tests.sh
REQUEST_SIGNATURE_ENABLED=1 \
REQUEST_SIGNATURE_SECRET=test-secret \
./server/relay/scripts/run-request-signature-tests.sh
```

Note: ensure the relay process/container is restarted with
`REQUEST_SIGNATURE_ENABLED=1` so it actually enforces signatures.

Expected output includes:
- `PASS request_signature_valid`
- `PASS request_signature_invalid`
- `PASS request_signature_skew`

Optional: override timeout (seconds):

```bash
REQUEST_SIGNATURE_TEST_TIMEOUT_SECONDS=12 \
REQUEST_SIGNATURE_ENABLED=1 \
REQUEST_SIGNATURE_SECRET=test-secret \
./server/relay/scripts/run-request-signature-tests.sh
```

## Run the federation mTLS config test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-federation-mtls-tests.sh
./server/relay/scripts/run-federation-mtls-tests.sh
```

Expected output includes:
- `PASS federation_mtls_config`

Optional: override timeout (seconds):

```bash
FEDERATION_MTLS_TEST_TIMEOUT_SECONDS=12 ./server/relay/scripts/run-federation-mtls-tests.sh
```

## Optional security-specific tests

These checks are skipped unless you set the related env vars and restart the relay.

### IP denylist/allowlist
Set both a trusted proxy and CIDR allow/deny lists:

```
TRUSTED_PROXY_IPS=127.0.0.1
IP_DENYLIST=10.10.0.0/24
IP_ALLOWLIST=10.20.0.0/24
```

### Federation peer auth
Enable the shared token and allowlist:

```
TRUSTED_PROXY_IPS=127.0.0.1
FEDERATION_PEER_ALLOWLIST=10.30.0.0/24
```

## Notes
- These tests assume no concurrent traffic. If other clients are hitting the
  relay, rate limit tests may fail.
- If you override limits in `server/.env`, keep the env values passed to the
  test script in sync.
