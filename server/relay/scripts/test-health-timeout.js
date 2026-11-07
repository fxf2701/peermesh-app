#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const { createRouter } = require("../src/routes");
const { createMetrics } = require("../src/metrics");
const { loadConfig } = require("../src/config");

function getText(baseUrl, path) {
  const url = new URL(path, baseUrl);
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname + url.search,
        method: "GET",
      },
      (res) => {
        let body = "";
        res.on("data", (chunk) => {
          body += chunk;
        });
        res.on("end", () => {
          resolve({ status: res.statusCode, body });
        });
      }
    );
    req.on("error", reject);
    req.end();
  });
}

function parseMetricValue(body, name) {
  const line = body
    .split("\n")
    .find((entry) => entry.startsWith(`${name} `) || entry.startsWith(`${name}{`));
  if (!line) return null;
  const parts = line.trim().split(" ");
  const value = Number(parts[parts.length - 1]);
  return Number.isFinite(value) ? value : null;
}

async function run() {
  const config = loadConfig();
  config.redisUrl = "";
  config.healthCheckDbTimeoutMs = 1;
  const metrics = createMetrics();
  const storage = {
    async healthCheck() {
      const err = new Error("timeout");
      err.code = "HEALTH_DB_TIMEOUT";
      throw err;
    },
  };

  const router = createRouter({ config, storage, metrics });
  const server = http.createServer((req, res) => router.route(req, res));

  await new Promise((resolve) => server.listen(0, resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;

  let failed = 0;
  try {
    const health = await getText(baseUrl, "/health");
    if (health.status !== 503) {
      failed += 1;
      console.log("FAIL health_timeout_status");
      console.log(`  status: ${health.status}`);
    } else {
      console.log("PASS health_timeout_status");
    }

    const metricsRes = await getText(baseUrl, "/metrics");
    const timeouts = parseMetricValue(metricsRes.body, "health_db_check_timeouts_total");
    if (timeouts && timeouts >= 1) {
      console.log("PASS health_timeout_metric");
    } else {
      failed += 1;
      console.log("FAIL health_timeout_metric");
      console.log(`  value: ${timeouts}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL health_timeout_test");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
