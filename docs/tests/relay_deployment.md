# Relay Deployment Tests

This doc covers basic validation for the development and production compose profiles.

## Run the compose profile test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-compose-profiles-tests.sh

# Production (default)
./server/relay/scripts/run-compose-profiles-tests.sh

# Development profiles
./server/relay/scripts/run-compose-profiles-tests.sh --dev
```

Expected output includes:
- `PASS compose_profiles_multi`
- `PASS compose_profiles_metrics`
- `PASS compose_prometheus_config`

## Run the systemd + logrotate test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-systemd-logrotate-tests.sh
./server/relay/scripts/run-systemd-logrotate-tests.sh
```

Expected output includes:
- `PASS systemd_unit_valid`
- `PASS logrotate_valid`

## Run the Helm chart stub test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-helm-chart-tests.sh
./server/relay/scripts/run-helm-chart-tests.sh
```

Expected output includes:
- `PASS helm_chart_metadata`
- `PASS helm_chart_values`
- `PASS helm_chart_deployment`
- `PASS helm_chart_service`

## Run the smoke test script
From the repo root:

```bash
chmod +x server/relay/scripts/run-smoke-tests.sh
./server/relay/scripts/run-smoke-tests.sh
```

Expected output includes:
- `PASS smoke_healthz`
- `PASS smoke_readyz`
- `PASS smoke_ingest`
- `PASS smoke_fetch`
