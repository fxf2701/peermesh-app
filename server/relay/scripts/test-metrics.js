#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");

const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";
const requestTimeoutMs = Number(process.env.METRICS_TEST_TIMEOUT_MS || "5000");

function signatureHeaders(method, path, body) {
  const enabled = process.env.REQUEST_SIGNATURE_ENABLED === "1";
  const secret = process.env.REQUEST_SIGNATURE_SECRET || "";
  if (!enabled || !secret) return {};
  const ts = String(Math.floor(Date.now() / 1000));
  const payload = [method.toUpperCase(), path, ts, body || ""].join("\n");
  const signature = crypto.createHmac("sha256", secret).update(payload).digest("hex");
  return { "x-relay-signature": signature, "x-relay-timestamp": ts };
}

function getText(path) {
  const url = new URL(path, baseUrl);
  const headers = signatureHeaders("GET", url.pathname + url.search, "");

  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname + url.search,
        method: "GET",
        agent: false,
        headers,
      },
      (res) => {
        let body = "";
        res.on("data", (chunk) => {
          body += chunk;
        });
        res.on("end", () => {
          resolve({ status: res.statusCode, headers: res.headers, body });
        });
      }
    );

    req.setTimeout(requestTimeoutMs, () => {
      req.destroy(new Error(`request_timeout_${requestTimeoutMs}ms`));
    });
    req.on("error", reject);
    req.end();
  });
}

function hasLine(body, prefix) {
  return body.split("\n").some((line) => line.startsWith(prefix));
}

function parsePromValue(body, linePrefix) {
  const line = body.split("\n").find((entry) => entry.startsWith(linePrefix));
  if (!line) return null;
  const parts = line.trim().split(/\s+/);
  const value = Number(parts[parts.length - 1]);
  return Number.isFinite(value) ? value : null;
}

async function run() {
  let failed = 0;
  const watchdog = setTimeout(() => {
    console.log("FAIL metrics_prometheus_export");
    console.log(`  error: test_timeout_${requestTimeoutMs}ms`);
    process.exit(1);
  }, requestTimeoutMs);

  try {
    console.log("metrics_test_start");
    console.log("metrics_test_health_start");
    await getText("/healthz");
    console.log("metrics_test_health_ok");
    let healthOk = false;
    try {
      const health = await getText("/health");
      healthOk = health.status === 200;
    } catch (err) {
      healthOk = false;
    }
    console.log("metrics_test_metrics_start");
    const res = await getText("/metrics");
    console.log("metrics_test_metrics_ok");
    const contentType = res.headers["content-type"] || "";
    const okStatus = res.status === 200;
    const okType = contentType.includes("text/plain");
    const okHelp = hasLine(res.body, "# HELP requests_total");
    const okTypeLine = hasLine(res.body, "# TYPE http_request_duration_ms histogram");
    const okBucket = res.body.includes("http_request_duration_ms_bucket");
    const okStatusCounter = res.body.includes('http_responses_total{status="200"}');
    const okDbPool =
      res.body.includes("db_pool_total") &&
      res.body.includes("db_pool_in_use") &&
      res.body.includes("db_pool_idle") &&
      res.body.includes("db_pool_waiting");
    const okDbWaitHist = res.body.includes("db_pool_wait_ms_bucket");
    const okFederation = res.body.includes("federation_sync_success_total");
    const okFederationLag = res.body.includes("federation_cursor_lag_seconds");
    const okFederationBackpressure =
      res.body.includes("federation_backpressure_in_flight") &&
      res.body.includes("federation_backpressure_queue_size") &&
      res.body.includes("federation_backpressure_drops_total");
    const okPartitionMissing = res.body.includes("partition_missing");
    const okHealthDbHist = res.body.includes("health_db_check_ms_bucket");
    const okHealthDbTimeouts = res.body.includes("health_db_check_timeouts_total");
    const okHealthDbFailures = res.body.includes("health_db_check_failures_total");
    const okHealthDb = healthOk ? okHealthDbHist : okHealthDbTimeouts || okHealthDbFailures;
    const dbWaitInf = parsePromValue(res.body, 'db_pool_wait_ms_bucket{le="+Inf"}');
    const dbWaitCount = parsePromValue(res.body, "db_pool_wait_ms_count");
    const okDbWaitInfCount = dbWaitInf !== null && dbWaitCount !== null && dbWaitInf === dbWaitCount;

    if (
      okStatus &&
      okType &&
      okHelp &&
      okTypeLine &&
      okBucket &&
      okStatusCounter &&
      okDbPool &&
      okDbWaitHist &&
      okFederation &&
      okFederationLag &&
      okFederationBackpressure &&
      okPartitionMissing &&
      okHealthDb &&
      okDbWaitInfCount
    ) {
      console.log("PASS metrics_prometheus_export");
    } else {
      failed += 1;
      console.log("FAIL metrics_prometheus_export");
      console.log(`  status: ${res.status}`);
      console.log(`  content-type: ${contentType}`);
      console.log(`  has_help: ${okHelp}`);
      console.log(`  has_histogram_type: ${okTypeLine}`);
      console.log(`  has_histogram_bucket: ${okBucket}`);
      console.log(`  has_status_counter: ${okStatusCounter}`);
      console.log(`  has_db_pool: ${okDbPool}`);
      console.log(`  has_db_wait_histogram: ${okDbWaitHist}`);
      console.log(`  has_federation_metrics: ${okFederation}`);
      console.log(`  has_federation_lag: ${okFederationLag}`);
      console.log(`  has_federation_backpressure: ${okFederationBackpressure}`);
      console.log(`  has_partition_missing: ${okPartitionMissing}`);
      console.log(`  health_check_ok: ${healthOk}`);
      console.log(`  has_health_db_histogram: ${okHealthDbHist}`);
      console.log(`  has_health_db_timeouts: ${okHealthDbTimeouts}`);
      console.log(`  has_health_db_failures: ${okHealthDbFailures}`);
      console.log(`  has_db_wait_inf_equals_count: ${okDbWaitInfCount}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL metrics_prometheus_export");
    console.log(`  error: ${err.message || String(err)}`);
  }

  clearTimeout(watchdog);
  if (failed > 0) {
    process.exit(1);
  }
}

run();
