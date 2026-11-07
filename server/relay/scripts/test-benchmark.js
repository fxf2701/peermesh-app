#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");

const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";
const ingestRequests = Number(process.env.BENCH_INGEST_REQUESTS || "200");
const fetchRequests = Number(process.env.BENCH_FETCH_REQUESTS || "200");
const concurrency = Number(process.env.BENCH_CONCURRENCY || "20");
const maxP95Ms = Number(process.env.BENCH_MAX_P95_MS || "500");
const maxP99Ms = Number(process.env.BENCH_MAX_P99_MS || "900");

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

function postJson(path, payload) {
  const data = JSON.stringify(payload);
  const url = new URL(path, baseUrl);
  return new Promise((resolve, reject) => {
    const start = Date.now();
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname,
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(data),
        },
      },
      (res) => {
        res.resume();
        res.on("end", () => resolve({ status: res.statusCode, ms: Date.now() - start }));
      }
    );
    req.on("error", reject);
    req.write(data);
    req.end();
  });
}

function getJson(path) {
  const url = new URL(path, baseUrl);
  return new Promise((resolve, reject) => {
    const start = Date.now();
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname + url.search,
        method: "GET",
      },
      (res) => {
        res.resume();
        res.on("end", () => resolve({ status: res.statusCode, ms: Date.now() - start }));
      }
    );
    req.on("error", reject);
    req.end();
  });
}

function percentile(values, p) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
}

async function runBatch(total, limit, fn) {
  const results = [];
  let index = 0;
  let active = 0;

  return new Promise((resolve) => {
    const next = () => {
      if (index >= total && active === 0) {
        resolve(results);
        return;
      }
      while (active < limit && index < total) {
        const current = index;
        index += 1;
        active += 1;
        fn(current)
          .then((res) => results.push(res))
          .catch((err) => results.push({ status: 0, ms: 0, error: err.message || String(err) }))
          .finally(() => {
            active -= 1;
            next();
          });
      }
    };
    next();
  });
}

async function run() {
  let failed = 0;
  const now = Math.floor(Date.now() / 1000);
  const gid = `Z29vZGd-bench-${uuidv4().slice(0, 6)}`;

  const ingestResults = await runBatch(ingestRequests, concurrency, (idx) => {
    const event = {
      eid: uuidv4(),
      gid,
      ts: now + idx,
      exp: now + 600,
      body: { type: "CHAT.MSG", body: `bench-${idx}` },
      sig: "abcd",
    };
    return postJson("/v1/events:ingest", { events: [event] });
  });

  const ingestLatencies = ingestResults.map((res) => res.ms).filter(Boolean);
  const ingestP95 = percentile(ingestLatencies, 95);
  const ingestP99 = percentile(ingestLatencies, 99);

  if (ingestP95 <= maxP95Ms && ingestP99 <= maxP99Ms) {
    console.log("PASS bench_ingest");
    console.log(`  p95_ms: ${ingestP95}`);
    console.log(`  p99_ms: ${ingestP99}`);
  } else {
    failed += 1;
    console.log("FAIL bench_ingest");
    console.log(`  p95_ms: ${ingestP95}`);
    console.log(`  p99_ms: ${ingestP99}`);
  }

  const fetchResults = await runBatch(fetchRequests, concurrency, () =>
    getJson(`/v1/events:fetch?gid=${encodeURIComponent(gid)}&limit=50`)
  );

  const fetchLatencies = fetchResults.map((res) => res.ms).filter(Boolean);
  const fetchP95 = percentile(fetchLatencies, 95);
  const fetchP99 = percentile(fetchLatencies, 99);

  if (fetchP95 <= maxP95Ms && fetchP99 <= maxP99Ms) {
    console.log("PASS bench_fetch");
    console.log(`  p95_ms: ${fetchP95}`);
    console.log(`  p99_ms: ${fetchP99}`);
  } else {
    failed += 1;
    console.log("FAIL bench_fetch");
    console.log(`  p95_ms: ${fetchP95}`);
    console.log(`  p99_ms: ${fetchP99}`);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
