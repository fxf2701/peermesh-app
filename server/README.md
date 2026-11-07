# PeerMesh Relay Server

PeerMesh Relay Server is an optional store-and-forward relay node used by PeerMesh clients and federation peers.

The relay is designed for:

- Internet-assisted synchronization
- Delay-tolerant networking (DTN)
- Federation between relay nodes
- Community-operated infrastructure
- Independent self-hosted deployments

The relay is intentionally payload-agnostic and does not implement application business logic.

## Architecture Overview

```text
Android App
      |
      v
Relay Node
      |
      v
Federation Peers

Relay Node
      |
      v
Registry
```

Communication Contracts

- Android App <-> Relay Node.
  - See [docs/specs/RELAY_PROTOCOL_SPEC.md](../docs/specs/RELAY_PROTOCOL_SPEC.md)
- Relay Node <-> Federation Peers.
  - See [docs/server/FEDERATION_PROTOCOL.md](../docs/server/FEDERATION_PROTOCOL.md)
- Relay Node <-> Registry.
  - See [docs/server/REGISTRY_PROTOCOL.md](../docs/server/REGISTRY_PROTOCOL.md)

The PeerMesh ecosystem consists of three separate layers:

### Relay

Responsible for:

- event storage
- event synchronization
- ingest/fetch APIs
- federation

### Federation

Responsible for:

- relay-to-relay synchronization
- anti-entropy sync
- peer authentication
- trust-aware connectivity

### Registry

Responsible for:

- relay discovery
- trust distribution
- relay verification
- revocation and policy feeds

Registry is not a relay.

Federation is not a registry.

## Deployment Models

PeerMesh supports multiple deployment models:

### Official PeerMesh Network

Official relay infrastructure operated by PeerMesh.

### Trusted Federation Relay

Community or partner-operated relay approved to participate in official federation infrastructure.

### Independent Relay Network

Self-hosted infrastructure operated independently.

## Protocol Documentation

Before modifying the relay, read:

- [docs/specs/RELAY_PROTOCOL_SPEC.md](../docs/specs/RELAY_PROTOCOL_SPEC.md)
- [docs/server/FEDERATION_PROTOCOL.md](../docs/server/FEDERATION_PROTOCOL.md)
- [docs/server/REGISTRY_PROTOCOL.md](../docs/server/REGISTRY_PROTOCOL.md)
- [docs/SECURITY.md](../docs/SECURITY.md)
- [docs/server/NODE_OPERATOR_GUIDE.md](../docs/server/NODE_OPERATOR_GUIDE.md)

## Documentation Map

The documentation set is organized into several layers.

### Architecture

Start here if you are new to the project.

- [docs/server/INTERNET_RELAY_OPTION.md](../docs/server/INTERNET_RELAY_OPTION.md)
  - High-level architecture and deployment models.
- [docs/server/NODE_OPERATOR_GUIDE.md](../docs/server/NODE_OPERATOR_GUIDE.md)
  - Relay, Federation and Registry overview.
- [server/README.md](README.md)
  - Relay server overview.

### Protocol Specifications

Defines wire-level compatibility contracts.

- [docs/specs/RELAY_PROTOCOL_SPEC.md](../docs/specs/RELAY_PROTOCOL_SPEC.md)
  - Android App ↔ Relay protocol.
- [docs/server/FEDERATION_PROTOCOL.md](../docs/server/FEDERATION_PROTOCOL.md)
  - Relay ↔ Relay federation protocol.
- [docs/server/REGISTRY_PROTOCOL.md](../docs/server/REGISTRY_PROTOCOL.md)
  - Relay ↔ Registry protocol.
- [docs/SECURITY.md](../docs/SECURITY.md)
  - Security model and threat assumptions.

### Operations

Used by operators and DevOps engineers.

- [docs/server/NODE_OPERATOR_GUIDE.md](../docs/server/NODE_OPERATOR_GUIDE.md)
- [server/relay/docs/README.md](../server/relay/docs/README.md)
- [server/registry/docs/README.md](../server/registry/docs/README.md)
- [server/relay/docs/relay_backup_restore_runbook.md](../server/relay/docs/relay_backup_restore_runbook.md)
- [server/relay/docs/relay_deploy_rollback_runbook.md](../server/relay/docs/relay_deploy_rollback_runbook.md)
- [docs/server/RELAY_DEPLOYMENT_MODELS.md](../docs/server/RELAY_DEPLOYMENT_MODELS.md)

### Relay Admin

Used for Relay Admin UI development and operations.

- [docs/server/admin_ui_development_guideline.md](../docs/server/admin_ui_development_guideline.md)
- [docs/server/admin_ui_pages_spec.md](../docs/server/admin_ui_pages_spec.md)
- [server/relay/docs/relay_admin_ui_guide.md](../server/relay/docs/relay_admin_ui_guide.md)
- [server/relay/docs/relay_admin_operator_guide.md](../server/relay/docs/relay_admin_operator_guide.md)

Responsibilities:

- Federation
- Peer management
- Banlists
- Reputation
- Metrics
- Backup / Retention
- Operational visibility

### Registry Admin

Used for Registry service administration.

- [docs/server/registry_admin_development_guideline.md](../docs/server/registry_admin_development_guideline.md)
- [docs/server/registry_admin_pages_spec.md](../docs/server/registry_admin_pages_spec.md)
- [server/registry/docs/registry_admin_ui_guide.md](../server/registry/docs/registry_admin_ui_guide.md)
- [server/registry/docs/registry_admin_operator_guide.md](../server/registry/docs/registry_admin_operator_guide.md)

Responsibilities:

- Relay discovery
- Trust levels
- Relay verification
- Relay certificates
- Revocations
- Policy feeds

### Development Workflow

For most protocol changes:

1. Update specification document.
2. Update implementation.
3. Update tests.
4. Update operator/admin documentation.

### Which document should I read?

| Task | Read First |
|--------|--------|
| Modify Android ↔ Relay communication | [docs/specs/RELAY_PROTOCOL_SPEC.md](../docs/specs/RELAY_PROTOCOL_SPEC.md) |
| Modify federation sync | [docs/server/FEDERATION_PROTOCOL.md](../docs/server/FEDERATION_PROTOCOL.md) |
| Modify relay discovery | [docs/server/REGISTRY_PROTOCOL.md](../docs/server/REGISTRY_PROTOCOL.md) |
| Modify request signing | [docs/specs/RELAY_PROTOCOL_SPEC.md](../docs/specs/RELAY_PROTOCOL_SPEC.md) |
| Deploy a relay node | [docs/server/NODE_OPERATOR_GUIDE.md](../docs/server/NODE_OPERATOR_GUIDE.md) |
| Backup or restore a relay | [server/relay/docs/relay_backup_restore_runbook.md](../server/relay/docs/relay_backup_restore_runbook.md) |
| Deploy or rollback a release | [server/relay/docs/relay_deploy_rollback_runbook.md](../server/relay/docs/relay_deploy_rollback_runbook.md) |
| Develop Relay Admin UI | [docs/server/admin_ui_pages_spec.md](../docs/server/admin_ui_pages_spec.md) |
| Develop Registry Admin UI | [docs/server/registry_admin_pages_spec.md](../docs/server/registry_admin_pages_spec.md) |
| Review security assumptions | [docs/SECURITY.md](../docs/SECURITY.md) |

Main endpoints:

- POST `/v1/events:ingest`
- GET  `/v1/events:fetch`
- GET  `/health`
- GET  `/healthz`
- GET  `/readyz`
- GET  `/metrics`

## Security Model

The relay is **opaque by default**.

The relay stores and forwards event blobs without interpreting application semantics.

The relay does not:

- decrypt application payloads
- execute reducers
- build user identity graphs
- require plaintext access to messages

Integrity and authenticity are verified by clients.

Private deployments may optionally perform additional validation, but this is not required by the protocol.

See:

- [docs/SECURITY.md](../docs/SECURITY.md)
- [docs/specs/RELAY_PROTOCOL_SPEC.md](../docs/specs/RELAY_PROTOCOL_SPEC.md)

## Request Signing

Relay request signing uses:

```text
METHOD
REQUEST_TARGET
TIMESTAMP_SECONDS
SHA256(raw_body_bytes)
```

Headers:

```http
X-Relay-Timestamp
X-Relay-Signature
```

Properties:

- HMAC-SHA256
- hex lowercase encoding
- Unix epoch seconds (UTC)
- raw-body hashing

See:

- [docs/specs/RELAY_PROTOCOL_SPEC.md](../docs/specs/RELAY_PROTOCOL_SPEC.md)

---

## API Examples

Ingest events:

```bash
curl -s -X POST http://localhost:8080/v1/events:ingest \
  -H "Content-Type: application/json" \
  -d '{
    "events": [
      {
        "eid": "11111111-1111-1111-1111-111111111111",
        "gid": "Z29vZGd",
        "ts": 1730000000,
        "exp": 1730003600,
        "body": { "type": "CHAT.MSG", "text": "hello" },
        "sig": "abcd"
      }
    ]
  }'
```

Response:

```json
{
  "accepted": ["11111111-1111-1111-1111-111111111111"],
  "rejected": []
}
```

Fetch events:

```bash
curl -s "http://localhost:8080/v1/events:fetch?gid=Z29vZGd&limit=2"
```

Response:

```json
{
  "events": [
    {
      "eid": "11111111-1111-1111-1111-111111111111",
      "gid": "Z29vZGd",
      "ts": 1730000000,
      "exp": 1730003600,
      "body": { "type": "CHAT.MSG", "text": "hello" },
      "sig": "abcd"
    }
  ],
  "next_cursor": "eyJ0cyI6MTczMDAwMDAwMCwiZWlkIjoiMTExMTExMTEtMTExMS0xMTExLTExMTEtMTExMTExMTExMTExIn0"
}
```

Error example (invalid cursor):

```json
{
  "error": {
    "code": "invalid_cursor",
    "message": "cursor is invalid."
  }
}
```

## Observability

- `/healthz`: liveness (no DB check)
- `/readyz`: readiness (DB connectivity)
- `/metrics`: basic JSON counters
- Request logs are structured JSON and include `req_id`, `method`, `path`,
  `status`, and `duration_ms`.

## Security Operations

TLS is expected to terminate at a reverse proxy or load balancer. Example nginx
config (adjust cert paths and domain):

```nginx
server {
  listen 443 ssl;
  server_name relay.example.org;

  ssl_certificate /etc/letsencrypt/live/relay.example.org/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/relay.example.org/privkey.pem;

  location / {
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_pass http://127.0.0.1:8080;
  }
}
```

If you expose `/metrics`, restrict it to trusted IPs or bind it behind the
reverse proxy.

## Storage

Default storage is Postgres. See:
- `server/relay/config/schema.sql` for table definitions
- `server/.env.example` for environment variables

Required environment:
- `DATABASE_URL` (e.g., `postgres://user:pass@localhost:5432/relay`)
- `DB_WAIT_MAX_RETRIES` (optional, default 20)

## Environment Variables

Core:
- `PORT` (default 8080)
- `DATABASE_URL`
- `DB_WAIT_MAX_RETRIES` (default 20)
- `DB_POOL_MAX` (default 10)
- `DB_IDLE_TIMEOUT_MS` (default 30000)
- `DB_CONN_TIMEOUT_MS` (default 2000)
- `REDIS_URL` (optional, enables shared rate limit + request replay cache)
- `TRUSTED_PROXY_IPS` (comma-separated CIDR/IP allowlist)
- `IP_ALLOWLIST` (comma-separated CIDR/IP allowlist)
- `IP_DENYLIST` (comma-separated CIDR/IP denylist)
- `FEDERATION_PEER_ALLOWLIST` (comma-separated CIDR/IP allowlist)
- `FEDERATION_PEER_BANLIST` (comma-separated peer IDs or CIDR/IPs to block)
- `FEDERATION_PEERS` (comma-separated peer base URLs)
- `FEDERATION_SYNC_GIDS` (comma-separated gids to sync)
- `FEDERATION_SYNC_INTERVAL_SECONDS` (default 60)
- `FEDERATION_SYNC_MAX_BACKOFF_SECONDS` (default 300)
- `FEDERATION_SYNC_JITTER_SECONDS` (default 5)
- `FEDERATION_SYNC_LIMIT` (default 200)
- `FEDERATION_SELF_URL` (this node's base URL, used to avoid self-sync)
- `FEDERATION_SELF_ID` (identifier for loop detection; defaults to self URL)

Limits:
- `MAX_EVENTS_PER_REQUEST` (default 100)
- `MAX_EVENT_BYTES` (default 65536)
- `MAX_REQUEST_BYTES` (default 1000000)
- `FETCH_MAX_LIMIT` (default 200)

Time windows:
- `EVENT_MAX_FUTURE_SECONDS` (default 600)
- `EVENT_EXP_GRACE_SECONDS` (default 300)
- `CLEANUP_INTERVAL_SECONDS` (default 60)
- `CLEANUP_GRACE_SECONDS` (default 300)
- `CLEANUP_USE_ADVISORY_LOCK` (default 1)
- `NONCE_CACHE_TTL_SECONDS` (default 600)

  Used by:
  - event nonce replay protection
  - federation join replay protection

  Not used by relay request signing.

Rate limits:
- `RATE_LIMIT_WINDOW_SECONDS` (default 60)
- `RATE_LIMIT_BURST_WINDOW_SECONDS` (default 10)
- `INGEST_RATE_LIMIT_PER_IP` (default 120)
- `INGEST_RATE_LIMIT_PER_GID` (default 300)
- `FETCH_RATE_LIMIT_PER_IP` (default 120)
- `FETCH_RATE_LIMIT_PER_GID` (default 300)
- `INGEST_BURST_PER_IP` (default 30)
- `INGEST_BURST_PER_GID` (default 60)
- `FETCH_BURST_PER_IP` (default 30)
- `FETCH_BURST_PER_GID` (default 60)

Optional/test:
- `ENABLE_TEST_DB_ERRORS` (default 0)
- `MIGRATIONS_TEST_DATABASE_URL` (test-only)

## Environments

Environment files are now split by purpose:

* Dev/local simulation:
  * `server/.env.dev`
  * `server/docker-compose.dev.yml`
  * `server/start.sh`
  * `server/stop.sh`
* Production relay:
  * `server/relay/.env`
  * `server/relay/docker-compose.yml`
  * `server/relay/start.sh`
  * `server/relay/stop.sh`
* Production registry:
  * `server/registry/.env`
  * `server/registry/docker-compose.yml`
  * `server/registry/start.sh`
  * `server/registry/stop.sh`

## Docker Compose

Production relay:

```bash
./server/relay/start.sh --build
```

Production registry:

```bash
./server/registry/start.sh --build
```

Dev/local simulation:

```bash
./server/start.sh
./server/start.sh --build
./server/stop.sh
```

## Migrations

Create a new migration:

```bash
node scripts/create-migration.js add_events_index
```

Apply migrations:

```bash
npm run migrate:up
```

Rollback last migration:

```bash
npm run migrate:down
```

Step-based migration helper:

```bash
./server/relay/scripts/migrate.sh --up        # apply all pending
./server/relay/scripts/migrate.sh --up 2      # apply next 2 migrations
./server/relay/scripts/migrate.sh --down      # rollback 1 migration
./server/relay/scripts/migrate.sh --down 3    # rollback 3 migrations
```

Dev (two databases):

```bash
set -a
. server/.env.dev
set +a
server/relay/scripts/migrate.sh --dev --up
```

## Operational Notes

### Runtime Behavior

- Cleanup runs every `CLEANUP_INTERVAL_SECONDS`.
- Expired rows are removed when:

```text
exp <= now - CLEANUP_GRACE_SECONDS
```

- Retention is based on `exp` only.
- Event `ts` does not affect cleanup.
- Rate limits and request replay protection are in-memory per process unless `REDIS_URL` is configured.

### Large Dataset Support

For large deployments:

- Optional partitioned schema:
  - `server/relay/config/schema_partitioned.sql`
- Monthly partition creation helper:
  - `./server/relay/scripts/create-partition.sh`

Recommended cron schedule:

```bash
# Run on the first of each month at 00:10 UTC
10 0 1 * * cd /path/to/repo && ./server/relay/scripts/create-partition.sh >> /var/log/relay-partitions.log 2>&1
```

The script uses `DATABASE_URL` and prefers the running Postgres container when available.

### Logging

- Logs are structured JSON.
- Request bodies are not logged.
- Avoid logging sensitive query parameters at reverse proxies.
- Configure log rotation at the process manager, container runtime or host OS.

### Observability

Additional dashboards and monitoring examples:

- `server/relay/docs/relay_grafana_dashboards.md`

For deployment, backup and operational procedures, see the Documentation Map above.

## Registry (Optional)

Registry participation is optional.

A relay may:

- use registry only
- use federation only
- use both
- use neither

Registry participation does not imply federation participation.

See:

- `docs/server/REGISTRY_PROTOCOL.md`

---

## Federation Sync (Optional)

See:

- `docs/server/FEDERATION_PROTOCOL.md`

Relay nodes can pull events from peer nodes on a fixed interval:

1) Apply migrations (adds `federation_cursors` table):

```bash
./server/relay/scripts/migrate.sh --up
```

2) Configure peers and gids:

```
FEDERATION_PEERS=https://peer-a.example.org,https://peer-b.example.org
FEDERATION_SYNC_GIDS=Z29vZGd123,Z29vZGd456
FEDERATION_SELF_URL=https://relay.example.org
```

The relay will periodically fetch `/v1/events:fetch` from each peer and
persist per-peer cursors in `federation_cursors`. Sync retries use exponential
backoff up to `FEDERATION_SYNC_MAX_BACKOFF_SECONDS`. The `FEDERATION_SELF_URL`
and `FEDERATION_SELF_ID` are used to prevent self-sync and detect loops via
`X-Relay-Loop`.

## Docker Compose Profiles (Dev)

The dev compose file (`docker-compose.dev.yml`) uses `profiles` to keep
optional services disabled by default. Only services without profiles start
automatically.

Examples:

```bash
# Default (relay + postgres only)
docker compose -f docker-compose.dev.yml up -d

# Enable multi-instance + Redis
docker compose -f docker-compose.dev.yml --profile multi up -d

# Enable multi-instance + Redis + metrics stack
docker compose -f docker-compose.dev.yml --profile multi --profile metrics up -d
```

Using the helper scripts:

```bash
# Dev (auto enables multi by default)
./server/start.sh
./server/start.sh --build

# Dev with explicit profiles
./server/start.sh --profiles multi
./server/start.sh --profiles multi,metrics

# Stop selected profiles
./server/stop.sh --profiles multi,metrics

# Build selected profiles
./server/relay/scripts/build.sh --dev --profiles multi
```

## Systemd + Logrotate (Example)

Example unit and logrotate configs live in:
- `server/relay/config/relay.service`
- `server/relay/config/relay.logrotate`

Update the paths in `relay.service` if your install directory differs from
`/opt/disaster-app`, then copy the files to:

```bash
sudo cp server/relay/config/relay.service /etc/systemd/system/disaster-app-relay.service
sudo cp server/relay/config/relay.logrotate /etc/logrotate.d/disaster-app-relay
```

## Helm Chart (Optional)

Helm chart for Kubernetes deployments lives at `server/helm/relay`. It can
provision relay plus Postgres/Redis dependencies, and optional Ingress.

```bash
helm template relay server/helm/relay

# Example: enable ingress and set host.
helm install relay-prod server/helm/relay \
  --set ingress.enabled=true \
  --set ingress.hosts[0].host=relay.example.org
```

## Smoke Test (Fresh Install)

```bash
chmod +x server/relay/scripts/run-smoke-tests.sh
./server/relay/scripts/run-smoke-tests.sh
```

## Node Operator Guide

See:

- `docs/server/NODE_OPERATOR_GUIDE.md`
- `docs/server/RELAY_DEPLOYMENT_MODELS.md`

for production deployment, federation configuration, registry participation and operational guidance.

Important:

`REQUEST_SIGNATURE_ENABLED` should be enabled for production deployments.

If request signing is disabled and no WAF/IP allowlists are configured, the relay may accept unauthenticated public ingest/fetch traffic.

Request signing uses:

```text
METHOD
REQUEST_TARGET
TIMESTAMP_SECONDS
SHA256(raw_body_bytes)
```

See:

- `docs/specs/RELAY_PROTOCOL_SPEC.md`
