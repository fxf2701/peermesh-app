# Relay Migration Tests

This doc describes how to test migration up/down safely.

## Important
The migration test drops the `events` and `schema_migrations` tables.
Use a dedicated test database.

## Prerequisites
- Postgres running.
- A dedicated test database (e.g. `relay_test`).
- `MIGRATIONS_TEST_DATABASE_URL` set in `server/.env` or exported.

Example `server/.env` entry:

```
MIGRATIONS_TEST_DATABASE_URL=postgres://postgres:postgres@postgres:5432/relay_test
```

## Run the migration test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-migration-tests.sh
./server/relay/scripts/run-migration-tests.sh
```

Expected output includes:
- `PASS migrate_up`
- `PASS migrations_recorded`
- `PASS migrate_down`
- `PASS migrations_cleared`
