# Phase 1.1 Task List — Relay Hardening

This checklist covers production hardening beyond Phase 1 baseline.

See overlap notes in `docs/milestones/Overlaps_and_Dependencies.md`.

## 1) Abuse & Traffic Controls
- [x] Add client-side backoff hints (`Retry-After` header). (Test ok)
- [x] Add trusted proxy config for `x-forwarded-for` (allowlist CIDR). (Test ok)
- [x] Externalize rate limit store (Redis or shared DB). (Test ok)
- [x] Externalize nonce replay cache (Redis or shared DB). (Test ok)
- [x] Add per-route burst limits (ingest vs fetch). (Test ok)

## 2) Storage & Performance
- [x] Batch insert events (single INSERT with VALUES list). (Test ok)
- [x] Add DB connection pool sizing + timeouts. (Test ok)
- [x] Add background cleanup coordination (leader election or cron job). (Test ok via advisory lock)
- [x] Add optional partitioning by `exp` for large datasets. (Test ok)

## 3) Observability & Operations
- [x] Structured logging (JSON) with request IDs. (Test ok)
- [x] Metrics endpoint (Prometheus): ingest/fetch counts, latency, rate-limit hits. (Test ok)
- [x] Health/readiness split (`/healthz`, `/readyz`). (Test ok)

## 4) Security
- [x] TLS termination guidance (reverse proxy config example). (Test ok)
- [x] Allowlist federation peers + sync auth (token or mTLS). (Test ok)
- [x] Add optional IP denylist/allowlist config. (Test ok)

## 5) Federation Hardening
- [x] Add peer sync retry/backoff. (Test ok)
- [x] Add peer cursor persistence per `gid`. (Test ok)
- [x] Add anti-loop protections for server-to-server sync. (Test ok)

## 6) Tests
- [x] Load tests for ingest/fetch (baseline latency). (Test ok)
- [x] Multi-instance consistency tests for rate limits/nonce cache. (Test ok)
- [x] Failover tests (DB disconnect, cleanup recovery). (Test ok)
