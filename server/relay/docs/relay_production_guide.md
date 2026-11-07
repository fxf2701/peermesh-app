# Relay Production Guide (Community Ops)

This guide targets community operators running a self-hosted public relay.

## Production Readiness Checklist

- **Security:** enable request signatures (`REQUEST_SIGNATURE_REQUIRED=1`) or mTLS
  for federation peers; do not expose ingest/fetch without auth or IP controls.
- **Edge/WAF:** apply reverse proxy rules (rate limits, size limits, IP allowlist)
  and restrict `/metrics` and `/readyz` to internal networks.
- **Redis:** enable Redis for shared rate limits and nonce cache if running more
  than one relay instance.
- **Backups:** schedule Postgres backups and verify restore procedures.
- **Monitoring:** scrape `/metrics`, set alerts for DB saturation, error budget,
  federation sync failures, and rate-limit spikes.
- **Secrets:** store credentials and tokens in a secret manager; avoid plaintext
  in git or shell history.

## SLO/SLA Targets (Operations)

These are suggested targets for community relays; adjust based on capacity.

### Availability
- **Relay API availability:** 99.5% monthly (ingest/fetch endpoints).
- **Readiness availability:** 99.0% monthly (`/readyz`).

### Latency
- **Ingest latency p95:** < 500 ms.
- **Fetch latency p95:** < 800 ms.

### Error Budget
- **Error rate:** < 2% over 10 minutes (aligns with alert rule).
- **Error budget burn:** investigate if sustained > 2% for 10m.

### Federation Health
- **Cursor lag:** < 5 minutes (300s).
- **Sync failures:** no sustained failures > 10 minutes.

### Database Saturation
- **Pool in-use ratio:** < 90%.
- **Pool wait avg:** < 200 ms.
- **Pool waiting:** < 5 connections.

## Alert Thresholds (Ops)

Recommended alert thresholds used by the alert rules template:

- DB pool in-use ratio > 90% for 5m.
- DB pool wait avg > 200ms for 5m.
- DB pool waiting > 5 for 5m.
- Error rate > 2% for 10m.
- Federation cursor lag > 300s for 10m.
- Federation sync failures > 0 for 10m.
- Federation cursor regression > 0 for 15m.
## 1) Prepare the host
- Install Docker + Docker Compose (or run via systemd if not using Docker).
- Ensure firewall rules allow only required ports (typically 443 and 22).
- Provision a domain and TLS certificate (recommended via reverse proxy).

## 2) Configure relay env
Create `server/relay/.env` with production values for a standalone relay:

```bash
PORT=8080
SERVER_MODE=community
DATABASE_URL=postgres://user:pass@db-host:5432/relay
REDIS_URL=redis://redis:6379

REQUEST_SIGNATURE_REQUIRED=1
REQUEST_SIGNATURE_SECRET=change-me
REQUEST_SIGNATURE_SKEW_SECONDS=300

REGISTRY_HEARTBEAT_ENABLED=0
REGISTRY_CLIENT_ACCESS_GRANTS_ENABLED=0
CLIENT_RELAY_SESSIONS_ENABLED=0
REGISTRY_FEDERATION_SESSIONS_ENABLED=0

FEDERATION_PEER_ALLOWLIST=10.0.0.0/8,192.168.0.0/16
FEDERATION_PEER_BANLIST=
```

Notes:
- Use strong secrets and store them in a secret manager if possible.
- The baseline production profile is an independent relay.
- Enable registry knobs only if you intentionally join a registry-managed deployment.
- Before first Admin UI login, initialize `ADMIN_AUTH_FILE` on the VPS:

```bash
cd server/relay
cp config/admin_auth.example.json config/admin_auth.json
./scripts/reset-admin-password.sh 'change-this-admin-password'
```

- The reset script prefers local `node`, but will fall back to `docker compose run` if the host is Docker-only.
- `config/admin_auth.json` is operator-managed state and should remain on the VPS across deploys.
- If you enable federation, configure `FEDERATION_PEERS` + `FEDERATION_SYNC_GIDS`.
- For federation traffic, require TLS end-to-end; use mTLS for peer-to-peer
  authentication when available.
- Keep production runtime files under `server/relay/`:
  - `server/relay/.env`
  - `server/relay/docker-compose.yml`
  - `server/relay/start.sh`
  - `server/relay/stop.sh`
- Keep operator-managed persistent directories on the VPS:
  - `server/relay/config/`
  - `server/relay/data/`
  - `server/relay/backups/`
  - `server/relay/logs/`

## 3) Kubernetes (Helm) deployment
Helm chart lives at `server/helm/relay` and can provision relay + Postgres/Redis,
plus optional Ingress. Secrets are injected via a Kubernetes Secret.

Quick example:

```bash
helm install relay-prod server/helm/relay \
  --set ingress.enabled=true \
  --set ingress.hosts[0].host=relay.example.org \
  --set secrets.databaseUrl="postgres://user:pass@relay-prod-postgres:5432/relay" \
  --set secrets.redisUrl="redis://relay-prod-redis:6379"
```

Notes:
- To use external DB/Redis, disable the bundled services:
  `--set postgres.enabled=false --set redis.enabled=false`
- To supply your own Secret, set `secrets.existingSecret` and add
  `DATABASE_URL` / `REDIS_URL` keys to that secret.

Example with Ingress TLS + annotations:

```bash
helm install relay-prod server/helm/relay \
  --set ingress.enabled=true \
  --set ingress.className=nginx \
  --set ingress.hosts[0].host=relay.example.org \
  --set ingress.hosts[0].paths[0].path=/ \
  --set ingress.hosts[0].paths[0].pathType=Prefix \
  --set ingress.tls[0].hosts[0]=relay.example.org \
  --set ingress.tls[0].secretName=relay-tls \
  --set ingress.annotations."nginx\.ingress\.kubernetes\.io/proxy-body-size"=10m \
  --set ingress.annotations."nginx\.ingress\.kubernetes\.io/ssl-redirect"=true
```

Note: Before deploying to a real cluster, run a quick render check:
`helm template relay server/helm/relay`

## 4) Deploy (Docker)

```bash
server/relay/start.sh --build
```

For metrics stack (optional):

```bash
cd server/relay
docker compose --env-file .env -f docker-compose.yml --profile metrics up -d
```

## Optional: enable registry participation

Only turn these on if this relay should participate in a registry-managed
ecosystem:

```bash
REGISTRY_HEARTBEAT_ENABLED=1
REGISTRY_URL=https://registry.example.com
REGISTRY_SHARED_SECRET=change-me

REGISTRY_CLIENT_ACCESS_GRANTS_ENABLED=1
REGISTRY_CLIENT_ACCESS_GRANT_SIGNING_SECRET=change-me
CLIENT_RELAY_SESSIONS_ENABLED=1
CLIENT_RELAY_SESSION_BINDING_SECRET=change-me

REGISTRY_FEDERATION_SESSIONS_ENABLED=1
REGISTRY_FEDERATION_SESSION_SIGNING_SECRET=change-me
```

These settings are not required for a standalone self-hosted relay.

## 5) Reverse proxy / WAF
Place a reverse proxy (nginx/traefik) in front of relay:
- Terminate TLS at the proxy.
- Enforce rate limits and request size limits.
- Restrict `/metrics` and `/readyz` to internal IPs only.
- Allow only needed paths: `/healthz`, `/readyz`, `/metrics`, `/v1/events:ingest`, `/v1/events:fetch`.

See `relay_edge_waf_guidance.md` for examples.

## 6) Operations
- **Monitoring:** scrape `/metrics` and set alerts for DB saturation, error budget,
  federation sync failures, and rate-limit spikes.

Alert rules template: `server/relay/config/relay_alert_rules.yml`.

Smoke test (Prometheus rule load):

```bash
cd server/relay
docker compose --env-file .env -f docker-compose.yml --profile metrics up -d prometheus
docker exec -i peermesh-relay-prometheus /bin/sh -c "promtool check rules /etc/prometheus/alert_rules.yml"
```
- **Backups:** schedule Postgres backups and test restores.
- **Rotation:** if not using Docker logs, configure logrotate/systemd.
- **Updates:** deploy with rolling restarts; validate with smoke tests.
- **Deploy/Rollback:** see `relay_deploy_rollback_runbook.md`.

## 7) Smoke test after deploy

```bash
server/relay/scripts/run-smoke-tests.sh
```

Expected:
- `PASS smoke_healthz`
- `PASS smoke_readyz`
- `PASS smoke_ingest`
- `PASS smoke_fetch`

## Relay Network Operations (Seed + Join)

To bootstrap a relay network, keep at least one **seed relay** online. A seed relay is
structurally identical to other relays; it is simply started first and listed as a peer
for new servers.

Minimal join steps for a new relay:

1) Start or identify a seed relay (public IP/domain).
2) Configure the new relay with:
   - `FEDERATION_PEERS` = comma-separated seed URLs
   - `FEDERATION_SYNC_GIDS` = gids you want to sync
   - `FEDERATION_PUBLIC_TOKEN` for join + handshake (rotated token required for sync)
   - mTLS certs (optional, if enabled)
3) Start the relay; it will sync and store cursors for each peer/gid.

Example env:

```
FEDERATION_PEERS=https://peer-a.example.org,https://peer-b.example.org
FEDERATION_SYNC_GIDS=Z29vZGd123,Z29vZGd456
FEDERATION_PUBLIC_TOKEN=shared-public-token
FEDERATION_SELF_URL=https://relay.example.org
```

Notes:
- Keep 1–2 seeds online for reliability and bootstrap.
- New relays can join via any reachable peer, not only the original seed.
- After join, relays use stored cursors to resume sync even if a peer goes offline.
- Rotated tokens from handshake are required for federation fetch/ingest and heartbeat.
- Recommended connectivity bounds: **min 2 peers**, **max 10 peers** per relay for stability.
