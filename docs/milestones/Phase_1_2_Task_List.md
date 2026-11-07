# Phase 1.2 Task List — Relay Production Hardening

This checklist focuses on operational reliability, security depth, and
production observability beyond Phase 1.1.

See overlap notes in `docs/milestones/Overlaps_and_Dependencies.md`.

## 1) Observability & Ops
- [x] Export Prometheus metrics (histograms for latency, counters by status).
  - Done: `/metrics` exposes latency histograms and status counters with labels.
  - Note: extends Phase 1.1 metrics endpoint coverage with richer series.
- [x] Add log redaction rules (automatic stripping for sensitive fields).
  - Done: known sensitive fields are removed or masked in all logs.
- [x] Add metrics for DB pool saturation + queue time.
  - Done: metrics include pool in-use, idle, and queue wait time.
  - Note: extends Phase 1.1 metrics endpoint to cover DB health signals.
- [x] Add federation sync metrics (success/fail, cursor lag, backoff count).
  - Done: expose peer-level counts and lag so ops can see stalls at a glance.
- [x] Add structured error budget summary (SLO-style logging).
  - Done: periodic log line reports error rate vs SLO window.
  - Note: builds on Phase 1.1 structured logging.
- [x] Add periodic self-check to validate partition presence.
  - Done: periodic check logs/metrics failures when partitions are missing.
  - Note: extends Phase 1.1 optional partitioning by exp.

## 2) Reliability & Resilience
- [x] Add startup validation for required tables/migrations.
  - Done: relay refuses to start and logs a clear error if schema is missing.
- [x] Add background cleanup watchdog (detect stuck cleanup).
  - Done: watchdog flags cleanup stalls with logs/metrics.
  - Note: extends Phase 1 cleanup job + Phase 1.1 cleanup coordination.
- [x] Add DB reconnect/backoff policy with circuit breaker.
  - Done: transient DB errors trigger backoff and open/close breaker states.
  - Note: distinct from Phase 1.1 federation backoff; this targets DB connectivity.
- [x] Add adaptive cooldown on DB saturation (load shedding/backpressure).
  - Done: ingest/federation are throttled when DB queue or wait time crosses threshold.
  - Notes: see `docs/tech_notes/DB_Cooldown_On_Saturation.md`.
- [x] Add retry queue for failed ingests (optional, bounded).
  - Done: bounded retry queue with max attempts and drop logging.
- [x] Add partition auto-create (monthly) with backfill option.
  - Done: job creates next partitions and can backfill missing months.
  - Note: extends Phase 1.1 optional partitioning by exp.

## 3) Security & Abuse Hardening
- [x] Add configurable WAF/edge rules guidance (nginx/traefik examples).
  - Done: docs include working nginx/traefik snippets with recommended rules.
- [x] Add alert on anomalous rate-limit spikes.
  - Done: spike detection emits alert log/metric when thresholds are exceeded.
- [x] Add audit log option (append-only, integrity-protected).
  - Done: append-only audit log with hash chaining and rotation guidance.
- [x] Add request signature verification mode (private relay).
  - Done: peer requests are verified and rejected on invalid signatures.
- [x] Add mTLS option for federation peer sync.
  - Done: config supports mutual TLS auth between peers with cert validation.

## 4) Performance & Capacity
- [x] Add load test baselines + regression thresholds in CI.
  - Done: CI fails when latency/throughput regress past agreed baselines.
- [x] Add synthetic benchmark for ingest vs fetch (p95 + p99).
  - Done: benchmark script outputs p95/p99 for ingest and fetch paths.
- [x] Add pagination stress test with large cursor windows.
  - Done: test validates large cursors and stable pagination order.
- [x] Add DB index health checks (bloat, missing indexes).
  - Done: check reports bloat/missing indexes with actionable output.

## 5) Deployment & Automation
- [x] Add compose profile for multi-instance + Redis + metrics stack.
  - Done: compose profile boots multiple relays plus Redis/metrics services.
- [x] Add example systemd unit + logrotate config.
  - Done: example unit and logrotate files are runnable as-is.
- [x] Add Helm chart stub (optional).
  - Done: chart templates render and deploy a minimal relay stack.
- [x] Add smoke-test script for fresh installs.
  - Done: script validates health, migrations, and basic ingest/fetch.

## 6) Federation Hardening (Advanced)
- [x] Add peer cursor persistence durability checks.
  - Done: detects cursor regressions/mismatch and logs peer state.
- [x] Add peer sync scheduling with per-peer backpressure.
  - Done: per-peer concurrency and queue caps prevent overload.
- [x] Add anti-loop replay prevention across hop chains.
  - Done: hop-chain header rejects repeats across multi-hop paths.
- [x] Add peer reputation/ban list (manual).
  - Done: operator can manually block peers via config or list file.
  - Note: Phase 1.3 candidate to store banlist in DB and expose Settings UI for dynamic updates.
