#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");

const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";
const secret = process.env.REQUEST_SIGNATURE_SECRET || "test-secret";
const skewSeconds = Number(process.env.REQUEST_SIGNATURE_SKEW_SECONDS || "300");
const testTimeoutMs = Number(process.env.REQUEST_SIGNATURE_TEST_TIMEOUT_MS || "10000");

function bodyHashHex(body) {
  const payload = body ? JSON.stringify(body) : "";
  return crypto.createHash("sha256").update(payload).digest("hex");
}

function sign({ method, path, body, timestamp }) {
  const payload = [
    method.toUpperCase(),
    path,
    String(timestamp),
    bodyHashHex(body),
  ].join("\n");
  return crypto.createHmac("sha256", secret).update(payload).digest("hex");
}

function requestJson(method, path, body, headers = {}) {
  const url = new URL(path, baseUrl);
  const payload = body ? JSON.stringify(body) : null;
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
          ...headers,
        },
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

async function run() {
  let failed = 0;
  const watchdog = setTimeout(() => {
    console.log("FAIL request_signature");
    console.log(`  error: test_timeout_${testTimeoutMs}ms`);
    process.exit(1);
  }, testTimeoutMs);

  try {
    console.log("request_signature_test_start");
    const signedPath = "/v1/events:fetch?gid=test-gid&limit=1";
    const baseline = await requestJson("GET", signedPath, null);
    if (baseline.status !== 401) {
      failed += 1;
      console.log("FAIL request_signature_enabled");
      console.log("  relay did not enforce signature on signed relay route (expected 401)");
      return;
    }
    const ts = Math.floor(Date.now() / 1000);
    const goodSig = sign({ method: "GET", path: signedPath, body: null, timestamp: ts });
    const ok = await requestJson("GET", signedPath, null, {
      "x-relay-timestamp": String(ts),
      "x-relay-signature": goodSig,
    });
    if (ok.status === 401) {
      failed += 1;
      console.log("FAIL request_signature_valid");
    } else {
      console.log("PASS request_signature_valid");
    }

    const replay = await requestJson("GET", signedPath, null, {
      "x-relay-timestamp": String(ts),
      "x-relay-signature": goodSig,
    });
    if (replay.status !== 401) {
      failed += 1;
      console.log("FAIL request_signature_replay");
    } else {
      console.log("PASS request_signature_replay");
    }

    const badSig = sign({ method: "GET", path: signedPath, body: null, timestamp: ts }) + "00";
    const bad = await requestJson("GET", signedPath, null, {
      "x-relay-timestamp": String(ts),
      "x-relay-signature": badSig,
    });
    if (bad.status !== 401) {
      failed += 1;
      console.log("FAIL request_signature_invalid");
    } else {
      console.log("PASS request_signature_invalid");
    }

    const oldTs = ts - (skewSeconds + 10);
    const oldSig = sign({ method: "GET", path: signedPath, body: null, timestamp: oldTs });
    const old = await requestJson("GET", signedPath, null, {
      "x-relay-timestamp": String(oldTs),
      "x-relay-signature": oldSig,
    });
    if (old.status !== 401) {
      failed += 1;
      console.log("FAIL request_signature_skew");
    } else {
      console.log("PASS request_signature_skew");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL request_signature");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    clearTimeout(watchdog);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
