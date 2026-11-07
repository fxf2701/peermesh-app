#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const { randomUUID } = require("crypto");

const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";

function getJson(path, headers = {}) {
  const url = new URL(path, baseUrl);

  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname + url.search,
        method: "GET",
        headers,
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

function parseJson(body) {
  try {
    return JSON.parse(body);
  } catch (err) {
    return null;
  }
}

async function run() {
  let failed = 0;

  // Health endpoint includes db status.
  try {
    const res = await getJson("/health");
    const body = parseJson(res.body);
    const ok =
      res.status === 200 &&
      body &&
      body.status === "ok" &&
      body.db === "ok";
    if (ok) {
      console.log("PASS health_ok");
    } else {
      failed += 1;
      console.log("FAIL health_ok");
      console.log(`  status: ${res.status}`);
      console.log(`  body: ${res.body}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL health_ok");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Readiness endpoint includes db status.
  try {
    const res = await getJson("/readyz");
    const body = parseJson(res.body);
    const ok =
      res.status === 200 &&
      body &&
      body.status === "ok" &&
      body.db === "ok";
    if (ok) {
      console.log("PASS ready_ok");
    } else {
      failed += 1;
      console.log("FAIL ready_ok");
      console.log(`  status: ${res.status}`);
      console.log(`  body: ${res.body}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL ready_ok");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Metrics endpoint basic presence (Prometheus format).
  try {
    const res = await getJson("/metrics");
    const ok =
      res.status === 200 &&
      res.body.includes("requests_total") &&
      res.body.includes("http_request_duration_ms_bucket");
    if (ok) {
      console.log("PASS metrics_ok");
    } else {
      failed += 1;
      console.log("FAIL metrics_ok");
      console.log(`  status: ${res.status}`);
      console.log(`  body: ${res.body}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL metrics_ok");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // DB error mapping (requires ENABLE_TEST_DB_ERRORS=1 on relay).
  try {
    const gidA = `Z29vZGd${randomUUID().slice(0, 6)}`;
    const gidB = `Z29vZGd${randomUUID().slice(0, 6)}`;
    const gidC = `Z29vZGd${randomUUID().slice(0, 6)}`;

    const badRequest = await getJson(`/v1/events:fetch?gid=${gidA}`, {
      "x-forwarded-for": "db-error-test-1",
      "x-test-db-error": "22P02",
    });
    const badBody = parseJson(badRequest.body);
    const badOk =
      badRequest.status === 400 &&
      badBody &&
      badBody.error &&
      badBody.error.code === "invalid_request";

    const conflict = await getJson(`/v1/events:fetch?gid=${gidB}`, {
      "x-forwarded-for": "db-error-test-2",
      "x-test-db-error": "23505",
    });
    const conflictBody = parseJson(conflict.body);
    const conflictOk =
      conflict.status === 409 &&
      conflictBody &&
      conflictBody.error &&
      conflictBody.error.code === "conflict";

    const serverErr = await getJson(`/v1/events:fetch?gid=${gidC}`, {
      "x-forwarded-for": "db-error-test-3",
      "x-test-db-error": "XX000",
    });
    const serverBody = parseJson(serverErr.body);
    const serverOk =
      serverErr.status === 500 &&
      serverBody &&
      serverBody.error &&
      serverBody.error.code === "db_error";

    if (badOk && conflictOk && serverOk) {
      console.log("PASS db_error_mapping");
    } else {
      failed += 1;
      console.log("FAIL db_error_mapping");
      console.log(`  bad: ${badRequest.status} ${badRequest.body}`);
      console.log(`  conflict: ${conflict.status} ${conflict.body}`);
      console.log(`  server: ${serverErr.status} ${serverErr.body}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL db_error_mapping");
    console.log(`  error: ${err.message || String(err)}`);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
