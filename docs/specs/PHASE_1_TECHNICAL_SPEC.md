# Phase 1 Technical Specification
## Opaque Relay Server

---

## 1. Purpose & Scope

Phase 1 builds an **opaque relay server** that stores and forwards event blobs
without decrypting or verifying MAC/signatures. The relay enables internet sync
for clients and federation between independent operators.

### In scope
- HTTP APIs: ingest/fetch/health
- Validation: envelope shape, `eid/gid` format, `ts/exp` sanity
- Abuse controls: rate limits, request size limits, TTL cleanup
- Cursor-based pagination
- Minimal observability (health + request logs)
- Graceful shutdown

### Out of scope
- Server-side MAC verification (reserved for Phase 3)
- Decryption or content inspection
- User accounts / authentication
- Web dashboard / analytics

---

## 2. Data Model (Opaque Envelope)

Relay stores events as opaque blobs with a minimal envelope:
- `eid` (UUIDv4)
- `gid` (base32/base64url)
- `ts` (epoch seconds)
- `exp` (epoch seconds)
- `body` (opaque object)
- `sig` (integrity field, validated by clients)

Retention is based on `exp` only.

---

## 3. API Endpoints

### POST `/v1/events:ingest`
- Body: `{ "events": [event, ...] }`
- Behavior:
  - Validate envelope shape and limits
  - Drop expired events (`exp <= now - GRACE`)
  - Deduplicate by `eid`
  - Optional nonce replay protection (if `body.nonce` present)

### GET `/v1/events:fetch`
- Query: `gid`, `cursor` (optional), `limit` (optional)
- Behavior:
  - Return events ordered by `(ts ASC, eid ASC)`
  - Cursor is opaque base64url of `{ts,eid}`
  - Enforce server-side max limit

### GET `/health`
- Returns `{ status, db }`
- 503 if DB connectivity fails

---

## 4. Validation & Limits

- Required fields: `eid`, `gid`, `ts`, `exp`, `body`, `sig`
- `eid` must be UUIDv4; `gid` base32/base64url
- `exp > now - EVENT_EXP_GRACE_SECONDS`
- `ts <= now + EVENT_MAX_FUTURE_SECONDS`
- Request/event size limits enforced

---

## 5. Abuse Controls

- Rate limits per IP and per `gid` (ingest/fetch)
- Request size limits at router level
- Optional nonce replay cache (in-memory, TTL)

---

## 6. Storage & TTL

- Postgres storage with indexes:
  - `events_exp_idx` on `exp`
  - `events_gid_ts_eid_idx` on `(gid, ts, eid)`
- Cleanup job deletes rows where `exp <= now - CLEANUP_GRACE_SECONDS`

---

## 7. Federation (Server-to-Server)

- Servers exchange opaque events using the same ingest/fetch semantics
- Federation is optional and operator-controlled
- No shared identity or global registry required

---

## 8. Operational Notes

- Relay is opaque: integrity checks happen on clients
- Rate limiting and nonce cache are per-process (single-node by default)
- For multi-node deployments, use external rate-limit/nonce storage

