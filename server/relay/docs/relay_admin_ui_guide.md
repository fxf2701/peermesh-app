# Relay Admin UI Technical Guide

This document defines the internal web admin UI for Relay operations (banlist,
peer reputation, federation management, and relay operations).

Important:

Registry management is intentionally out of scope for this Admin UI.

Registry functionality belongs to a separate Registry service and separate Registry Admin UI.

See:

- server/REGISTRY_PROTOCOL.md
- Registry production docs entrypoint: `server/registry/docs/README.md`
- `server/registry/docs/registry_admin_ui_guide.md`
- `server/registry/docs/registry_admin_operator_guide.md`

For Relay operators, this file should normally be read after `README.md` and `relay_admin_operator_guide.md` in the same folder.

## 1) Scope

Admin UI covers:

- Federation peer management.
- Banlist CRUD (DB-backed) for federation peers and IP ranges.
- Peer reputation annotations (manual scores/notes).
- Operational visibility (read-only views of metrics, health, retention, backup and incident state).

Admin UI does not cover:

- Registry administration.
- Relay trust assignment.
- Relay verification.
- Relay certificate issuance.
- Revocation feed management.
- Registry policy management.

Out of scope:
- Public user UI.
- Auth/SSO integration beyond file-based admin auth and IP allowlist.
- Large SPA build system (can be added later).

## 2) Architecture Overview

The Relay Admin UI manages a single relay node and its federation relationships.

It is not a Registry control plane.

Registry responsibilities include:

- relay discovery
- trust levels
- relay verification
- certificate management
- revocation feeds
- policy feeds

Those functions belong to Registry Admin UI and Registry APIs.

Relay Admin UI focuses on:

- federation
- peer connectivity
- operations
- governance
- monitoring
- security

### Request Flow

1) Operator accesses `/admin` in browser.
2) Static HTML/JS loads and calls `/admin/api/*` endpoints.
3) Admin API validates session (and client IP if allowlist is set), then executes storage operations.
4) All admin actions are written to audit log.

## 3) File/Folder Structure

Current relay layout:

- `server/relay/src/routes.js` (mount relay admin routes or delegate)
- `server/relay/src/routes_admin.js` (relay admin API + static handler)
- `server/relay/src/admin_auth.js` (admin auth helpers)
- `server/relay/src/audit_log.js` (relay audit logging)
- `server/relay/src/public/admin/index.html` (entry page)
- `server/relay/src/public/admin/assets/` (css/js)

## 4) Routing

### Static

- `GET /admin` -> serve `index.html`
- `GET /admin/assets/*` -> serve static files

### API

- `GET /admin/api/banlist`
- `POST /admin/api/banlist`
- `PATCH /admin/api/banlist/:id`
- `DELETE /admin/api/banlist/:id`

- `GET /admin/api/reputation`
- `POST /admin/api/reputation`
- `PATCH /admin/api/reputation/:id`
- `DELETE /admin/api/reputation/:id`

- `GET /admin/api/ops/health` (optional read-only snapshot)

All admin API routes require `adminAuth()`.

## 5) Data Model (DB)

### Banlist

Proposed table (DB-backed):
- `id` (uuid or serial)
- `type` (enum: `peer_id` or `cidr`)
- `value` (peer identifier or CIDR string)
- `reason` (string)
- `enabled` (boolean)
- `created_at`, `updated_at`
- `created_by` (string, from admin identity if available)

### Reputation

Proposed table:
- `id` (uuid or serial)
- `peer_id` (string)
- `score` (integer range, ex: -5..5)
- `note` (string)
- `created_at`, `updated_at`
- `created_by`

## 6) Admin Auth

Admin auth is file-based and session-oriented for internal use:

- Admin account stored in `ADMIN_AUTH_FILE` (default relay config file under `server/relay/config/admin_auth.json`).
- Login issues an `HttpOnly` session cookie (`admin_session`).
- Logout clears the session cookie.
- Password changes update the auth file on disk.
- IP allowlist still applies (`IP_ALLOWLIST` + `TRUSTED_PROXY_IPS`).

Auth endpoints:

- `POST /admin/api/auth/login` (`username`, `password`)
- `POST /admin/api/auth/logout`
- `POST /admin/api/auth/change-password` (`current_password`, `new_password`)

Reset password from terminal (shell script):

```bash
server/relay/scripts/reset-admin-password.sh <new_password>
```

If omitted, it resets to `ADMIN_DEFAULT_PASSWORD` or `admin`.

Initialize the auth file:

```bash
server/relay/scripts/reset-admin-password.sh admin1234
```

## 7) Validation & Error Handling

- Validate request body for required fields and type/format.
- Enforce max lengths for `reason` and `note`.
- Return consistent error JSON:

```
{ "error": { "code": "invalid_request", "message": "..." } }
```

## 8) Audit Logging

- Use existing audit logger (`server/relay/src/audit_log.js`).
- For each admin action, log:
  - action, resource, actor (if available)
  - request id, IP, status code
  - payload summary (no secrets)

## 9) UI Behavior

- Simple multi-panel layout:
  - Banlist table + add/edit modal
  - Reputation table + add/edit modal
  - Ops status (read-only)
- Prefer small, vanilla JS modules to avoid build tooling.
- All API calls use `fetch` with JSON payloads.
- Provide basic toast/alert on success/failure.

## 10) Security Notes

- Keep `/admin` behind a reverse proxy with additional auth if possible.
- Do not expose admin UI publicly.
- Restrict `/metrics` to internal networks.
- Federation join policy:
  - Public entry tokens allow self-register join requests.
  - Join requests require HMAC signature using the public token.
  - Successful join creates/updates a mapping in pending state.
  - Handshake issues a per-peer rotated token; only rotated tokens can pull/push data.
  - Rate limit join/handshake endpoints (config via `FEDERATION_JOIN_RATE_LIMIT`, `FEDERATION_HANDSHAKE_RATE_LIMIT`).
  - TLS is required when `FEDERATION_REQUIRE_TLS=1`; layer mTLS through `FEDERATION_MTLS_*`.
  - Connection capacity is enforced via `FEDERATION_CONNECTION_CAPACITY`, and only peers with heartbeats in the last `FEDERATION_HEARTBEAT_TTL_SECONDS` count toward the limit.
  - Peer access is limited to federation endpoints until ACL exists.
  - Banlist still applies to all peer traffic.
  - Auto-sync is only enabled after a successful handshake; disabling it stops pulls until you manually re-enable it, and the system will never re-enable it automatically.
  - Operator flow reminder: after adding a peer mapping, the remote peer must complete the handshake to receive a rotated token. Only then should auto-sync be enabled, and once you turn auto-sync off it stays off until you explicitly enable it again.

### Join Security (HMAC)

Federation join requests currently use a federation-specific signing flow.

The canonical request-signing model used by Relay Protocol and Registry Protocol is:

```text
METHOD
REQUEST_TARGET
TIMESTAMP_SECONDS
SHA256(raw_body_bytes)
```

See:

- specs/RELAY_PROTOCOL_SPEC.md
- server/REGISTRY_PROTOCOL.md

Federation-specific join authentication may use additional handshake semantics and rotated-token flows defined by FEDERATION_PROTOCOL.md.

## 11) Deployment

- Static assets live in the repo and are served by the relay process.
- No extra build step is required.
- If a SPA build is introduced later, the output should still land in
  `server/relay/src/public/admin/` and be served by the same static handler.

## 12) Testing

- Unit tests for admin auth and payload validation.
- Integration tests for CRUD endpoints.
- Manual smoke test:
  1) Load `/admin`.
  2) Create banlist entry, confirm it blocks a peer.
  3) Add reputation note, verify it shows in UI and API.

## 13) Future Upgrade Path

- Move UI to Vite (React/Vue/Svelte) if complexity grows.
- Switch admin auth to OIDC/SSO if needed.
- Add RBAC roles (read-only ops vs admin).
