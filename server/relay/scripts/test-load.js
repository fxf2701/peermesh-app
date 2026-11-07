#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");

const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";
const ingestRequests = Number(process.env.LOAD_INGEST_REQUESTS || "50");
const fetchRequests = Number(process.env.LOAD_FETCH_REQUESTS || "50");
const concurrency = Number(process.env.LOAD_CONCURRENCY || "10");
const maxP95Ms = Number(process.env.LOAD_MAX_P95_MS || "500");
const maxErrorRate = Number(process.env.LOAD_MAX_ERROR_RATE || "0");

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
        res.on("end", () => {
          resolve({ status: res.statusCode, ms: Date.now() - start });
        });
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
        res.on("end", () => {
          resolve({ status: res.statusCode, ms: Date.now() - start });
        });
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

function summarizeStatuses(results) {
  const counts = new Map();
  for (const res of results) {
    const key = res.status || 0;
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  const summary = Array.from(counts.entries())
    .sort((a, b) => b[1] - a[1])
    .map(([status, count]) => `${status}:${count}`)
    .join(" ");
  return summary || "none";
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
          .catch((err) =>
            results.push({ status: 0, ms: 0, error: err.message || String(err) })
          )
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
  const gid = `Z29vZGd-load-${uuidv4().slice(0, 6)}`;
  let summary = null;

  const ingestResults = await runBatch(ingestRequests, concurrency, (idx) => {
    const event = {
      eid: uuidv4(),
      gid,
      ts: now + idx,
      exp: now + 600,
      body: { type: "CHAT.MSG", body: `load-${idx}` },
      sig: "abcd",
    };
    return postJson("/v1/events:ingest", { events: [event] });
  });

  const ingestLatencies = ingestResults.map((res) => res.ms).filter(Boolean);
  const ingestErrors = ingestResults.filter((res) => res.status !== 200).length;
  const ingestErrorRate = ingestRequests ? ingestErrors / ingestRequests : 0;
  const ingestP95 = percentile(ingestLatencies, 95);

  if (ingestErrorRate <= maxErrorRate && ingestP95 <= maxP95Ms) {
    console.log("PASS load_ingest");
    console.log(`  p95_ms: ${ingestP95}`);
    console.log(`  error_rate: ${ingestErrorRate.toFixed(3)}`);
  } else {
    failed += 1;
    console.log("FAIL load_ingest");
    console.log(`  p95_ms: ${ingestP95}`);
    console.log(`  error_rate: ${ingestErrorRate.toFixed(3)}`);
    console.log(`  status_counts: ${summarizeStatuses(ingestResults)}`);
  }

  const fetchResults = await runBatch(fetchRequests, concurrency, () =>
    getJson(`/v1/events:fetch?gid=${encodeURIComponent(gid)}&limit=10`)
  );

  const fetchLatencies = fetchResults.map((res) => res.ms).filter(Boolean);
  const fetchErrors = fetchResults.filter((res) => res.status !== 200).length;
  const fetchErrorRate = fetchRequests ? fetchErrors / fetchRequests : 0;
  const fetchP95 = percentile(fetchLatencies, 95);

  if (fetchErrorRate <= maxErrorRate && fetchP95 <= maxP95Ms) {
    console.log("PASS load_fetch");
    console.log(`  p95_ms: ${fetchP95}`);
    console.log(`  error_rate: ${fetchErrorRate.toFixed(3)}`);
  } else {
    failed += 1;
    console.log("FAIL load_fetch");
    console.log(`  p95_ms: ${fetchP95}`);
    console.log(`  error_rate: ${fetchErrorRate.toFixed(3)}`);
    console.log(`  status_counts: ${summarizeStatuses(fetchResults)}`);
  }

  summary = {
    ingest: {
      p95_ms: ingestP95,
      error_rate: Number(ingestErrorRate.toFixed(5)),
      requests: ingestRequests,
    },
    fetch: {
      p95_ms: fetchP95,
      error_rate: Number(fetchErrorRate.toFixed(5)),
      requests: fetchRequests,
    },
  };
  if (process.env.LOAD_OUTPUT_JSON === "1") {
    console.log(`LOAD_RESULT_JSON ${JSON.stringify(summary)}`);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
