# Peer Banlist UI/DB (Phase 1.3 Tech Note)

## Goal
Move peer banlist from static env config to a dynamic, operator-managed list
stored in DB and editable via a Settings UI, without requiring relay restarts.

## Scope
- CRUD for banlist entries (peer ID or IP/CIDR).
- Apply banlist in federation ingress (fetch/ingest) at request time.
- Audit changes and provide a safe rollback path.

## Non-Goals
- Automated reputation scoring (still manual).
- Distributed consensus on bans between relays.

## Data Model (Proposed)
Table: `federation_peer_bans`
- `id` UUID (PK)
- `type` ENUM('peer_id','ip','cidr')
- `value` TEXT (peer ID or IP/CIDR)
- `reason` TEXT NULL
- `created_by` TEXT (operator identity)
- `created_at` TIMESTAMP WITH TIME ZONE
- `updated_at` TIMESTAMP WITH TIME ZONE
- `expires_at` TIMESTAMP WITH TIME ZONE NULL
- `active` BOOLEAN DEFAULT true

Indexes:
- (`type`, `value`, `active`)
- (`expires_at`)

## API (Proposed)
Auth: admin token or mTLS client cert
- `GET /admin/banlist`
- `POST /admin/banlist` (create)
- `PATCH /admin/banlist/:id` (toggle active, update reason, extend expiry)
- `DELETE /admin/banlist/:id` (hard delete optional)

## Runtime Behavior
1) On peer request with `x-relay-peer`, resolve `peer_id` from `x-relay-from`
   and `ip` from connection metadata.
2) Check banlist for active match (peer_id or ip/cidr).
3) If banned, return 403 and log audit event.

## UI (Proposed)
Settings > Federation > Banlist:
- Table with filters (active/expired/type).
- Add ban entry modal (type + value + reason + expiry).
- Quick toggle active/inactive.
- Search by peer ID or IP/CIDR.

## Audit Logging
All admin changes to banlist produce an audit log entry:
- `action`: banlist_create / banlist_update / banlist_delete
- `actor`, `target`, `reason`, `timestamp`

## Migration Plan
1) Add table + indexes.
2) Add admin endpoints with auth.
3) Add UI panel.
4) Deprecate env `FEDERATION_PEER_BANLIST` or treat as static baseline.

## Rollback Plan
- Keep env-based banlist active as fallback.
- Feature flag to disable DB-backed checks if needed.

## Open Questions
- What is the operator auth model (token vs mTLS)?
- Should bans replicate to peers or stay local only?
- Do we need rate-limit alerts on frequent banlist changes?
