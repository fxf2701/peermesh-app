#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");

const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";
const timeoutMs = Number(process.env.SMOKE_TEST_TIMEOUT_MS || "15000");

function signatureHeaders(method, path, body) {
  const enabled = process.env.REQUEST_SIGNATURE_ENABLED === "1";
  const secret = process.env.REQUEST_SIGNATURE_SECRET || "";
  if (!enabled || !secret) return {};
  const ts = String(Math.floor(Date.now() / 1000));
  const payload = [method.toUpperCase(), path, ts, body || ""].join("\n");
  const signature = crypto.createHmac("sha256", secret).update(payload).digest("hex");
  return { "x-relay-signature": signature, "x-relay-timestamp": ts };
}

function requestJson(method, path, body) {
  const url = new URL(path, baseUrl);
  const payload = body ? JSON.stringify(body) : null;
  const sigHeaders = signatureHeaders(method, url.pathname + url.search, payload || "");
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname + url.search,
        method,
        headers: {
          "Content-Type": "application/json",
          ...(payload ? { "Content-Length": Buffer.byteLength(payload) } : {}),
          ...sigHeaders,
        },
        agent: false,
      },
      (res) => {
        let data = "";
        res.on("data", (chunk) => (data += chunk));
        res.on("end", () => resolve({ status: res.statusCode, body: data }));
      }
    );
    req.on("error", reject);
    if (payload) req.write(payload);
    req.end();
  });
}

function uuidv4() {
  const bytes = crypto.randomBytes(16);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join("-");
}

async function run() {
  let failed = 0;
  const watchdog = setTimeout(() => {
    console.log("FAIL smoke_test");
    console.log(`  error: test_timeout_${timeoutMs}ms`);
    process.exit(1);
  }, timeoutMs);

  try {
    console.log("smoke_test_start");
    const health = await requestJson("GET", "/healthz", null);
    if (health.status === 200) {
      console.log("PASS smoke_healthz");
    } else {
      failed += 1;
      console.log("FAIL smoke_healthz");
    }

    const ready = await requestJson("GET", "/readyz", null);
    if (ready.status === 200) {
      console.log("PASS smoke_readyz");
    } else {
      failed += 1;
      console.log("FAIL smoke_readyz");
    }

    const now = Math.floor(Date.now() / 1000);
    const event = {
      eid: uuidv4(),
      gid: "smokeGid",
      ts: now,
      exp: now + 600,
      body: { type: "SMOKE.TEST", nonce: `smoke-${uuidv4()}` },
      sig: "test",
    };

    const ingest = await requestJson("POST", "/v1/events:ingest", { events: [event] });
    let ingestOk = false;
    try {
      const payload = JSON.parse(ingest.body || "{}");
      ingestOk = ingest.status === 200 && Array.isArray(payload.accepted) && payload.accepted.includes(event.eid);
    } catch (err) {
      ingestOk = false;
    }
    if (ingestOk) {
      console.log("PASS smoke_ingest");
    } else {
      failed += 1;
      console.log("FAIL smoke_ingest");
    }

    const fetch = await requestJson(
      "GET",
      `/v1/events:fetch?gid=${event.gid}&limit=5`,
      null
    );
    let fetchOk = false;
    try {
      const payload = JSON.parse(fetch.body || "{}");
      fetchOk =
        fetch.status === 200 &&
        Array.isArray(payload.events) &&
        payload.events.some((entry) => entry && entry.eid === event.eid);
    } catch (err) {
      fetchOk = false;
    }
    if (fetchOk) {
      console.log("PASS smoke_fetch");
    } else {
      failed += 1;
      console.log("FAIL smoke_fetch");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL smoke_test");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    clearTimeout(watchdog);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
