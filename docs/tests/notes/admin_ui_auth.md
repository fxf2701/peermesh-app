# Admin UI Auth Test Notes

Status: not tested yet.

## Why not tested

- Requires the admin UI endpoints to be exercised via HTTP with a running relay.
- Needs a local admin auth file and a login flow test.

## Preconditions

- Relay running with admin auth enabled.
- Admin auth file created with `server/relay/scripts/reset-admin-password.sh`.
- Access from an allowed IP (if `IP_ALLOWLIST` is set).
- Relay running with `http://localhost:8080` reachable.

## Test Steps

1) Create admin auth file:

```bash
server/relay/scripts/reset-admin-password.sh admin1234
```

2) Login:

```bash
curl -s -c /tmp/admin.cookies -X POST http://localhost:8080/admin/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin1234"}'
```

3) Change password:

```bash
curl -s -b /tmp/admin.cookies -X POST http://localhost:8080/admin/api/auth/change-password \
  -H "Content-Type: application/json" \
  -d '{"current_password":"admin1234","new_password":"admin5678"}'
```

4) Logout:

```bash
curl -s -b /tmp/admin.cookies -X POST http://localhost:8080/admin/api/auth/logout
```

Automated test:

```bash
RELAY_BASE_URL=http://localhost:8080 \
ADMIN_PASSWORD=admin1234 \
node server/relay/scripts/test-banlist-dynamic.js
```

Shell wrapper:

```bash
RELAY_BASE_URL=http://localhost:8080 \
ADMIN_PASSWORD=admin1234 \
server/relay/scripts/run-banlist-dynamic-tests.sh
```

Smoke test (UI):

- Open `http://localhost:8080/admin`
- Login with admin credentials
- Click Logout and confirm the session clears
