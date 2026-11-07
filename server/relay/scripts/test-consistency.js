#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");

const baseUrls = (process.env.RELAY_BASE_URLS || "")
  .split(",")
  .map((entry) => entry.trim())
  .filter(Boolean);
const redisUrl = process.env.REDIS_URL || "";
const ingestGidLimit = Number(process.env.INGEST_RATE_LIMIT_PER_GID || "3");
const ingestIpLimit = Number(process.env.INGEST_RATE_LIMIT_PER_IP || "1000");
const trustedProxyIps = (process.env.TRUSTED_PROXY_IPS || "")
  .split(",")
  .map((entry) => entry.trim())
  .filter(Boolean);
const ipAllowlist = (process.env.IP_ALLOWLIST || "")
  .split(",")
  .map((entry) => entry.trim())
  .filter(Boolean);

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

function ipToInt(ip) {
  const parts = ip.split(".");
  if (parts.length !== 4) return null;
  const nums = parts.map((part) => Number(part));
  if (nums.some((num) => !Number.isFinite(num) || num < 0 || num > 255)) {
    return null;
  }
  return (
    (nums[0] << 24) +
    (nums[1] << 16) +
    (nums[2] << 8) +
    nums[3]
  ) >>> 0;
}

function matchesIpList(ip, list) {
  if (!ip || list.length === 0) return false;
  const normalized = ip.startsWith("::ffff:") ? ip.slice(7) : ip;
  for (const entry of list) {
    if (entry.includes("/")) {
      const [cidrIp, maskBitsRaw] = entry.split("/");
      const maskBits = Number(maskBitsRaw);
      const ipInt = ipToInt(normalized);
      const cidrInt = ipToInt(cidrIp);
      if (ipInt === null || cidrInt === null) continue;
      const mask = maskBits === 0 ? 0 : (~0 << (32 - maskBits)) >>> 0;
      if ((ipInt & mask) === (cidrInt & mask)) return true;
    } else if (normalized === entry || ip === entry) {
      return true;
    }
  }
  return false;
}

function pickAllowlistedForwardedIp() {
  if (ipAllowlist.length === 0) return null;
  for (const entry of ipAllowlist) {
    const base = entry.split("/")[0];
    if (!base || base.includes(":")) continue;
    return base === "0.0.0.0" ? "127.0.0.1" : base;
  }
  if (ipAllowlist.includes("::1")) return "::1";
  return null;
}

function postJson(baseUrl, path, payload, headers = {}) {
  const data = JSON.stringify(payload);
  const url = new URL(path, baseUrl);

  return new Promise((resolve, reject) => {
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
        res.on("end", () => resolve({ status: res.statusCode, body, headers: res.headers }));
      }
    );
    req.on("error", reject);
    req.write(data);
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

async function withRateLimitRetry(fn, label) {
  let last;
  for (let attempt = 0; attempt < 2; attempt += 1) {
    last = await fn();
    if (last.status !== 429) return last;
    const retryAfter = Number(last.headers && last.headers["retry-after"]);
    const waitMs = Number.isFinite(retryAfter) ? retryAfter * 1000 : 1000;
    console.log(`INFO ${label} rate_limited, retrying in ${waitMs}ms`);
    await new Promise((resolve) => setTimeout(resolve, waitMs));
  }
  return last;
}

async function run() {
  let failed = 0;

  if (baseUrls.length < 2) {
    console.log("SKIP federation_consistency (set RELAY_BASE_URLS with 2+ entries)");
    return;
  }
  if (!redisUrl) {
    console.log("SKIP federation_consistency (set REDIS_URL for shared state)");
    return;
  }
  if (ipAllowlist.length > 0 && trustedProxyIps.length === 0) {
    console.log("SKIP federation_consistency (set TRUSTED_PROXY_IPS when IP_ALLOWLIST is set)");
    return;
  }

  const now = Math.floor(Date.now() / 1000);
  const gid = `Z29vZGd-consistency-${uuidv4().slice(0, 6)}`;
  const allowedForward = pickAllowlistedForwardedIp();
  const headers = {};
  if (allowedForward && trustedProxyIps.length > 0 && matchesIpList(allowedForward, ipAllowlist)) {
    headers["x-forwarded-for"] = allowedForward;
  }

  try {
    let rateLimited = false;
    for (let i = 0; i < ingestGidLimit + 1; i += 1) {
      const baseUrl = baseUrls[i % baseUrls.length];
      const event = {
        eid: uuidv4(),
        gid,
        ts: now + i,
        exp: now + 600,
        body: { type: "CHAT.MSG" },
        sig: "abcd",
      };
      const res = await postJson(baseUrl, "/v1/events:ingest", { events: [event] }, headers);
      if (res.status === 429) {
        rateLimited = true;
        break;
      }
      if (res.status !== 200) {
        throw new Error(`unexpected status ${res.status}: ${res.body}`);
      }
    }

    if (rateLimited) {
      console.log("PASS shared_rate_limit_gid");
    } else {
      failed += 1;
      console.log("FAIL shared_rate_limit_gid");
      console.log("  expected 429 across instances");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL shared_rate_limit_gid");
    console.log(`  error: ${err.message || String(err)}`);
  }

  try {
    if (ingestIpLimit < 2) {
      console.log("SKIP shared_nonce_replay (INGEST_RATE_LIMIT_PER_IP too low)");
    } else {
      const nonce = `nonce-${uuidv4()}`;
      const eventA = {
        eid: uuidv4(),
        gid: `Z29vZGd${uuidv4().slice(0, 6)}`,
        ts: now,
        exp: now + 600,
        body: { type: "CHAT.MSG", nonce },
        sig: "abcd",
      };
      const eventB = {
        eid: uuidv4(),
        gid: `Z29vZGd${uuidv4().slice(0, 6)}`,
        ts: now + 1,
        exp: now + 600,
        body: { type: "CHAT.MSG", nonce },
        sig: "abcd",
      };
      const first = await withRateLimitRetry(
        () => postJson(baseUrls[0], "/v1/events:ingest", { events: [eventA] }, headers),
        "shared_nonce:first"
      );
      const second = await withRateLimitRetry(
        () => postJson(baseUrls[1], "/v1/events:ingest", { events: [eventB] }, headers),
        "shared_nonce:second"
      );
      const secondBody = parseJson(second.body);
      const ok =
        first.status === 200 &&
        second.status === 200 &&
        secondBody &&
        Array.isArray(secondBody.rejected) &&
        secondBody.rejected[0] &&
        secondBody.rejected[0].reason === "nonce_replay";
      if (ok) {
        console.log("PASS shared_nonce_replay");
      } else {
        failed += 1;
        console.log("FAIL shared_nonce_replay");
        console.log(`  first: ${first.status} ${first.body}`);
        console.log(`  second: ${second.status} ${second.body}`);
      }
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL shared_nonce_replay");
    console.log(`  error: ${err.message || String(err)}`);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
