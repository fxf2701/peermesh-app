# Relay Validation Tests

This doc describes how to run the ingest validation test script for the relay server.

## Prerequisites
- Postgres running with the relay schema applied.
- Relay server running and reachable (default: http://localhost:8080).
- Node.js available for running the test script.

## Run the relay server locally
From the repo root:

```bash
cd server
npm install
DATABASE_URL=postgres://postgres:postgres@localhost:5432/relay npm run migrate:up
DATABASE_URL=postgres://postgres:postgres@localhost:5432/relay npm start
```

## Run the test script
From the repo root:

```bash
cd server
npm run test:validation
```

If the relay server is on a different base URL:

```bash
cd server
RELAY_BASE_URL=http://localhost:8080 npm run test:validation
```

## Run from docker host
Use the helper script to run tests from a docker host. It will:
- Prefer running inside the relay container (default name `disaster-app-relay`).
- Fall back to local `npm run test:validation` if the container is not running.
- Run a health check before the test with retry/backoff.

```bash
chmod +x server/relay/scripts/run-validation-tests.sh
RELAY_CONTAINER=disaster-app-relay RELAY_BASE_URL=http://localhost:8080 \
  ./server/relay/scripts/run-validation-tests.sh
```

Health check tuning:

```bash
HEALTH_CHECK_RETRIES=8 HEALTH_CHECK_BASE_DELAY_MS=300 \
  ./server/relay/scripts/run-validation-tests.sh
```

## Docker compose note
The current relay Docker image does not copy the `migrations/` folder, so
`docker compose up` can fail during the migration step. Workarounds:

1) Run Postgres in docker and the relay locally (steps above).
2) Run a relay container with the migrations directory mounted:

```bash
docker compose up -d postgres
docker run -d --name disaster-app-relay -p 8080:8080 \
  --network disaster-app_default \
  -e DATABASE_URL=postgres://postgres:postgres@postgres:5432/relay \
  -v "$PWD/server/relay/migrations:/app/migrations" \
  disaster-app-relay
```

Then run the test script as shown above.
