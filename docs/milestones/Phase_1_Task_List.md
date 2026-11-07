# Phase 1 Task List — Opaque Relay Server

This checklist consolidates the relay server tasks from `docs/tasks/relay_server_task_list.md`
and serves as the Phase 1 task list.

See overlap notes in `docs/milestones/Overlaps_and_Dependencies.md`.

## 1) Validation & Integrity
- [x] Validate ingest payload shape and required fields (`eid`, `gid`, `ts`, `exp`, `body`, `sig`).
- [x] Validate `eid` format (UUID) and `gid` format (expected base32/base64url).
- [x] Enforce `ts/exp` sanity (no far future, `exp > now`, skew window).
- [x] Add request-level limits (max events per request, max bytes per event).
- [x] Return structured errors for validation failures.

## 2) Security & Abuse Controls
- [x] Rate limit ingest and fetch (per IP / per `gid`).
- [x] Enforce request size limits at router level.
- [x] Add replay protection beyond `eid` (optional nonce cache if stored in event body).

## 3) Storage & TTL Maintenance
- [x] Add periodic cleanup job to delete expired events.
- [x] Ensure indexes support cleanup query (`exp`) and fetch (`gid, ts, eid`).
- [x] Define retention behavior for `exp` vs `ts` edge cases.

## 4) Cursor & Fetch Semantics
- [x] Validate cursor format; return error on invalid cursor (avoid silent reset).
- [x] Guarantee stable ordering and deterministic pagination.
- [x] Add max `limit` cap (server-side enforcement).

## 5) Error Handling & Observability
- [x] Map DB errors to HTTP errors (400/409/500) with safe messages.
- [x] Add request logging (minimal, no sensitive data).
- [x] Add basic health info in `/health` (db connectivity).

## 6) Graceful Shutdown
- [x] Handle SIGTERM/SIGINT, drain HTTP server.
- [x] Close Postgres pool on shutdown.

## 7) Tests
- [x] Ingest validation tests (missing fields, expired, duplicate).
- [x] Fetch pagination tests (cursor progression, ordering).
- [x] Migration runner tests (up/down).

## 8) Documentation
- [x] Add API examples (ingest/fetch request/response) to `server/README.md`.
- [x] Document env vars (`DATABASE_URL`, `DB_WAIT_MAX_RETRIES`, limits).
- [x] Add operational notes (cleanup schedule, expected data growth).
