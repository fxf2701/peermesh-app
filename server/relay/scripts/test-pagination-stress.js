#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");

const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";
const totalEvents = Number(process.env.PAGINATION_STRESS_EVENTS || "200");
const pageSize = Number(process.env.PAGINATION_STRESS_PAGE_SIZE || "25");
const maxPages = Number(process.env.PAGINATION_STRESS_MAX_PAGES || "20");
const maxLatencyMs = Number(process.env.PAGINATION_STRESS_MAX_LATENCY_MS || "2000");
const signatureEnabled = process.env.REQUEST_SIGNATURE_ENABLED === "1";
const signatureSecret = process.env.REQUEST_SIGNATURE_SECRET || "";

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

function postJson(path, payload, headers = {}) {
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
          ...headers,
        },
      },
      (res) => {
        let body = "";
        res.on("data", (chunk) => {
          body += chunk;
        });
        res.on("end", () => {
          resolve({ status: res.statusCode, body, ms: Date.now() - start });
        });
      }
    );

    req.on("error", reject);
    req.write(data);
    req.end();
  });
}

function getJson(path, headers = {}) {
  const url = new URL(path, baseUrl);

  return new Promise((resolve, reject) => {
    const start = Date.now();
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
          resolve({ status: res.statusCode, body, ms: Date.now() - start });
        });
      }
    );

    req.on("error", reject);
    req.end();
  });
}

function signRequest({ method, path, body }) {
  if (!signatureEnabled) return {};
  const ts = Math.floor(Date.now() / 1000);
  const payload = [
    method.toUpperCase(),
    path,
    String(ts),
    body ? JSON.stringify(body) : "",
  ].join("\n");
  const signature = crypto.createHmac("sha256", signatureSecret).update(payload).digest("hex");
  return { "x-relay-timestamp": String(ts), "x-relay-signature": signature };
}

function parseJson(body) {
  try {
    return JSON.parse(body);
  } catch (err) {
    return null;
  }
}

async function ingestBatch(gid, events, headers) {
  const payload = { events };
  const sigHeaders = signRequest({ method: "POST", path: "/v1/events:ingest", body: payload });
  const res = await postJson("/v1/events:ingest", payload, { ...headers, ...sigHeaders });
  if (res.status !== 200) {
    throw new Error(`ingest failed: ${res.status} ${res.body}`);
  }
}

async function run() {
  let failed = 0;
  const now = Math.floor(Date.now() / 1000);
  const gid = `Z29vZGd-stress-${uuidv4().slice(0, 6)}`;
  const headers = { "x-forwarded-for": "fetch-stress" };

  const events = Array.from({ length: totalEvents }).map((_, idx) => ({
    eid: uuidv4(),
    gid,
    ts: now + idx,
    exp: now + 600,
    body: { type: "CHAT.MSG", body: `stress-${idx}` },
    sig: "abcd",
  }));

  const chunks = [];
  const chunkSize = Math.max(1, Math.min(50, pageSize));
  for (let i = 0; i < events.length; i += chunkSize) {
    chunks.push(events.slice(i, i + chunkSize));
  }

  for (const chunk of chunks) {
    await ingestBatch(gid, chunk, headers);
  }

  let cursor = null;
  let pages = 0;
  let lastTs = 0;
  let totalFetched = 0;

  while (pages < maxPages) {
    const url = cursor
      ? `/v1/events:fetch?gid=${encodeURIComponent(gid)}&limit=${pageSize}&cursor=${encodeURIComponent(
          cursor
        )}`
      : `/v1/events:fetch?gid=${encodeURIComponent(gid)}&limit=${pageSize}`;
    const sigHeaders = signRequest({ method: "GET", path: url, body: null });
    const res = await getJson(url, { ...headers, ...sigHeaders });
    if (res.status !== 200) {
      failed += 1;
      console.log("FAIL pagination_stress");
      console.log(`  status: ${res.status}`);
      break;
    }
    if (res.ms > maxLatencyMs) {
      failed += 1;
      console.log("FAIL pagination_stress_latency");
      console.log(`  ms: ${res.ms}`);
      break;
    }
    const body = parseJson(res.body);
    if (!body || !Array.isArray(body.events)) {
      failed += 1;
      console.log("FAIL pagination_stress_response");
      break;
    }
    if (body.events.length === 0) break;

    for (const event of body.events) {
      if (event.ts < lastTs) {
        failed += 1;
        console.log("FAIL pagination_stress_order");
        break;
      }
      lastTs = event.ts;
    }
    totalFetched += body.events.length;
    cursor = body.next_cursor;
    pages += 1;
    if (!cursor) break;
  }

  if (failed === 0 && totalFetched >= totalEvents) {
    console.log("PASS pagination_stress");
    console.log(`  pages: ${pages}`);
    console.log(`  fetched: ${totalFetched}`);
  } else if (failed === 0) {
    failed += 1;
    console.log("FAIL pagination_stress_incomplete");
    console.log(`  fetched: ${totalFetched}`);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
