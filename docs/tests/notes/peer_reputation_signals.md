# Peer Reputation Signals Test Notes

Status: not tested yet.

## Why not tested

- Requires admin API endpoints for reputation CRUD.
- Requires backing storage for reputation records.

## Preconditions

- Relay running with admin auth enabled.
- Reputation endpoints available (`/admin/api/reputation`).

## Validation Steps

- Create a reputation entry with score/note.
- Fetch list and confirm entry is present.
- Update score/note and verify changes.
- Delete entry and confirm removal.

## Automated Test

```bash
RELAY_BASE_URL=http://localhost:8080 \
ADMIN_PASSWORD=admin1234 \
server/relay/scripts/run-reputation-crud-tests.sh
```
