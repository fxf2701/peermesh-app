# Admin UI smoke checklist

Goal: quick sanity pass for login, dashboard, banlist CRUD, and reputation CRUD.

Prereqs:
- Relay running with admin auth configured.
- Admin UI reachable at `/admin`.

Checklist:

1) Login / Session
- Open `/admin` and log in.
- Expected: redirect to `/admin/dashboard` and session banner shows username.
- Logout and verify redirect to `/admin/login`.

2) Dashboard
- Open `/admin/dashboard`.
- Expected: Operator Snapshot values load (no "API not ready" errors).
- Metrics Detail: switch domains and confirm table updates.
- Backup Status / Retention / Alerts cards render without errors.

3) Banlist CRUD
- Open `/admin/banlist`.
- Create: add a peer_id ban with a reason.
- Expected: row appears with status active.
- Update: toggle enabled off/on.
- Expected: status updates in table.
- Delete: remove the entry.
- Expected: row disappears.

4) Reputation CRUD
- Open `/admin/reputation`.
- Create: add peer_id, score, note.
- Expected: row appears.
- Update: edit score/note.
- Expected: row updates.
- Delete: remove the entry.
- Expected: row disappears.

