# Relay Federation Hardening Tests

This doc covers peer sync retry/backoff, cursor persistence, and loop header checks.

## Prerequisites
- Postgres running with migrations applied (includes `federation_cursors`).
- Relay dependencies installed (or run tests inside the relay container).

Apply migrations:

```bash
./server/relay/scripts/migrate.sh --up
```

## Run the tests

```bash
chmod +x server/relay/scripts/run-federation-tests.sh
USE_ENV_DEV=1 ./server/relay/scripts/run-federation-tests.sh
```

Expected output includes:
- `PASS federation_sync_cursor`
- `PASS federation_cursor_durability`
- `PASS federation_backoff`
- `PASS federation_backpressure_limits`
- `PASS federation_backpressure_drop`
- `PASS federation_backpressure_requests`
- `PASS federation_hop_chain_repeat`
- `PASS federation_hop_chain_self`
- `PASS federation_hop_chain_ok`
- `PASS federation_peer_banned_ip`
- `PASS federation_peer_banned_id`
- `PASS federation_peer_allowed`

## Notes
- The test spins up a local mock peer and pulls events twice to confirm cursor persistence.
- Cursor durability checks prevent cursor regressions from overwriting newer state.
- The mock peer checks for the `X-Relay-Loop` header to ensure loop metadata is attached.
- The backpressure test enforces per-peer in-flight and queue limits.
- Hop-chain validation rejects repeated entries (anti-loop replay).
- Peer ban list blocks requests by peer ID or source IP.
