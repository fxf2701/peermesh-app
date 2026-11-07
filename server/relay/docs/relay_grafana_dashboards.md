# Relay Grafana Dashboards

This bundle provides a minimal Grafana dashboard for relay ops.

## Files

- Dashboard JSON: `server/relay/config/grafana/dashboards/relay_ops.json`
- Grafana provisioning: `server/relay/config/grafana/provisioning/dashboards/relay.yml`
- Grafana datasource: `server/relay/config/grafana/provisioning/datasources/relay.yml`

## Docker Compose

Grafana loads the dashboard automatically when using the metrics profile:

```bash
cd server/relay
docker compose --env-file .env -f docker-compose.yml --profile metrics up -d grafana
```

Open Grafana and look for the **Relay Ops** dashboard.

If the dashboard does not appear, restart Grafana:

```bash
cd server/relay
docker compose --env-file .env -f docker-compose.yml --profile metrics restart grafana
```

## Open and Use Grafana

1) Open Grafana in browser:

`http://localhost:3000` if you are on the VPS host, or the HTTPS admin/metrics entrypoint if you publish Grafana behind a reverse proxy.

2) Login with:

- Username: `admin`
- Password: value of `GF_SECURITY_ADMIN_PASSWORD` from `server/relay/.env`

3) Open the **Relay Ops** dashboard:

- Dashboards → Browse → **Relay Ops** → Browse → **Relay Ops Overview**

4) Use time range selector to inspect spikes and errors.

## Minimal Structure

Grafana is configured to show a single folder **Relay Ops** with one dashboard.
The Prometheus datasource is auto-provisioned as default.
