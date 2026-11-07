#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");

const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";
const maxRequestBytes = Number(process.env.MAX_REQUEST_BYTES || "1000000");
const ingestIpLimit = Number(process.env.INGEST_RATE_LIMIT_PER_IP || "2");
const ingestGidLimit = Number(process.env.INGEST_RATE_LIMIT_PER_GID || "1");
const fetchIpLimit = Number(process.env.FETCH_RATE_LIMIT_PER_IP || "2");
const fetchGidLimit = Number(process.env.FETCH_RATE_LIMIT_PER_GID || "1");
const ingestBurstPerIp = Number(process.env.INGEST_BURST_PER_IP || "0");
const ingestBurstPerGid = Number(process.env.INGEST_BURST_PER_GID || "0");
const trustedProxyIps = (process.env.TRUSTED_PROXY_IPS || "")
  .split(",")
  .map((entry) => entry.trim())
  .filter(Boolean);
const ipAllowlist = (process.env.IP_ALLOWLIST || "")
  .split(",")
  .map((entry) => entry.trim())
  .filter(Boolean);
const ipDenylist = (process.env.IP_DENYLIST || "")
  .split(",")
  .map((entry) => entry.trim())
  .filter(Boolean);
const federationPeerAllowlist = (process.env.FEDERATION_PEER_ALLOWLIST || "")
  .split(",")
  .map((entry) => entry.trim())
  .filter(Boolean);
const federationPeerToken = process.env.FEDERATION_TEST_PEER_TOKEN || "";

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
          resolve({ status: res.statusCode, body, headers: res.headers });
        });
      }
    );

    req.on("error", reject);
    req.write(data);
    req.end();
  });
}

function postRaw(path, data, headers = {}) {
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
        res.on("end", () => {
          resolve({ status: res.statusCode, body, headers: res.headers });
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
          resolve({ status: res.statusCode, body, headers: res.headers });
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

function firstIpv4Candidate(list) {
  for (const entry of list) {
    const [base] = entry.split("/");
    if (!base || base.includes(":")) continue;
    const parts = base.split(".");
    if (parts.length !== 4) continue;
    const nums = parts.map((part) => Number(part));
    if (nums.some((num) => !Number.isFinite(num) || num < 0 || num > 255)) {
      continue;
    }
    if (parts[3] === "0") {
      return `${parts[0]}.${parts[1]}.${parts[2]}.1`;
    }
    return base;
  }
  return null;
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
  const ipv4 = firstIpv4Candidate(ipAllowlist);
  if (ipv4) return ipv4;
  if (ipAllowlist.includes("::1")) return "::1";
  return null;
}

function pickAllowlistPair() {
  for (const entry of ipAllowlist) {
    if (!entry.includes("/")) continue;
    const [base] = entry.split("/");
    if (!base || base.includes(":")) continue;
    const parts = base.split(".");
    if (parts.length !== 4) continue;
    const first = `${parts[0]}.${parts[1]}.${parts[2]}.10`;
    const second = `${parts[0]}.${parts[1]}.${parts[2]}.11`;
    return [first, second];
  }
  const ipv4s = ipAllowlist
    .map((entry) => entry.split("/")[0])
    .filter((entry) => entry && !entry.includes(":"));
  if (ipv4s.length >= 2) {
    return [ipv4s[0], ipv4s[1]];
  }
  return null;
}

async function run() {
  const now = Math.floor(Date.now() / 1000);
  const baseEvent = {
    gid: "Z29vZGd",
    ts: now,
    exp: now + 300,
    body: { type: "CHAT.MSG" },
    sig: "abcd",
  };

  let failed = 0;

  async function withRateLimitRetry(fn, label) {
    let last;
    for (let attempt = 0; attempt < 2; attempt += 1) {
      last = await fn();
      if (last.status !== 429) return last;
      const retryAfter = Number(last.headers && last.headers["retry-after"]);
      const waitMs = Number.isFinite(retryAfter) ? retryAfter * 1000 : 1000;
      // eslint-disable-next-line no-console
      console.log(`INFO ${label} rate_limited, retrying in ${waitMs}ms`);
      await new Promise((resolve) => setTimeout(resolve, waitMs));
    }
    return last;
  }

  // Request size limit.
  try {
    let paddingSize = maxRequestBytes + 256;
    let payload = `{"events":[{"pad":"${"a".repeat(paddingSize)}"}]}`;
    if (Buffer.byteLength(payload) <= maxRequestBytes) {
      const extra = maxRequestBytes - Buffer.byteLength(payload) + 256;
      payload = `{"events":[{"pad":"${"b".repeat(paddingSize + extra)}"}]}`;
    }

    const res = await postRaw("/v1/events:ingest", payload);
    const parsed = parseJson(res.body);
    const ok =
      res.status === 413 && parsed && parsed.error && parsed.error.code === "payload_too_large";

    if (ok) {
      console.log("PASS request_size_limit");
    } else {
      failed += 1;
      console.log("FAIL request_size_limit");
      console.log(`  status: ${res.status}`);
      console.log(`  body: ${res.body}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL request_size_limit");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Nonce replay protection.
  try {
    const nonce = `nonce-${uuidv4()}`;
    const allowedForward = pickAllowlistedForwardedIp();
    if (ipAllowlist.length > 0 && !allowedForward) {
      console.log("SKIP nonce_replay (set IP_ALLOWLIST with IPv4 or ::1)");
      return;
    }
    const firstEvent = {
      ...baseEvent,
      eid: uuidv4(),
      gid: `Z29vZGd${uuidv4().slice(0, 6)}`,
      body: { type: "CHAT.MSG", nonce },
    };
    const secondEvent = {
      ...baseEvent,
      eid: uuidv4(),
      gid: `Z29vZGd${uuidv4().slice(0, 6)}`,
      body: { type: "CHAT.MSG", nonce },
    };
    const nonceHeaders = {
      "x-forwarded-for": allowedForward || "nonce-test",
    };

    const first = await withRateLimitRetry(
      () => postJson("/v1/events:ingest", { events: [firstEvent] }, nonceHeaders),
      "nonce_replay:first"
    );
    const second = await withRateLimitRetry(
      () => postJson("/v1/events:ingest", { events: [secondEvent] }, nonceHeaders),
      "nonce_replay:second"
    );

    const firstBody = parseJson(first.body);
    const secondBody = parseJson(second.body);
    const ok =
      first.status === 200 &&
      second.status === 200 &&
      firstBody &&
      Array.isArray(firstBody.accepted) &&
      firstBody.accepted[0] === firstEvent.eid &&
      secondBody &&
      Array.isArray(secondBody.rejected) &&
      secondBody.rejected[0] &&
      secondBody.rejected[0].reason === "nonce_replay";

    if (ok) {
      console.log("PASS nonce_replay");
    } else {
      failed += 1;
      console.log("FAIL nonce_replay");
      console.log(`  first: ${first.status} ${first.body}`);
      console.log(`  second: ${second.status} ${second.body}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL nonce_replay");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Ingest burst limit per IP (optional).
  if (ingestBurstPerIp > 0 && ingestIpLimit > ingestBurstPerIp) {
    try {
      const allowedForward = pickAllowlistedForwardedIp();
      if (ipAllowlist.length > 0 && !allowedForward) {
        console.log("SKIP rate_limit_ingest_burst_ip (set IP_ALLOWLIST with IPv4 or ::1)");
      } else {
        const headers = { "x-forwarded-for": allowedForward || "burst-ip" };
        let burstLimited = false;
        for (let i = 0; i < ingestBurstPerIp + 1; i += 1) {
          const event = { ...baseEvent, eid: uuidv4(), gid: `Z29vZGd${i}` };
          const res = await postJson("/v1/events:ingest", { events: [event] }, headers);
          if (res.status === 429) {
            burstLimited = true;
            break;
          }
        }
        if (burstLimited) {
          console.log("PASS rate_limit_ingest_burst_ip");
        } else {
          failed += 1;
          console.log("FAIL rate_limit_ingest_burst_ip");
          console.log("  expected 429 within attempts");
        }
      }
    } catch (err) {
      failed += 1;
      console.log("FAIL rate_limit_ingest_burst_ip");
      console.log(`  error: ${err.message || String(err)}`);
    }
  } else {
    console.log(
      "SKIP rate_limit_ingest_burst_ip (set INGEST_BURST_PER_IP and RATE_LIMIT_BURST_WINDOW_SECONDS)"
    );
  }

  // Ingest burst limit per GID (optional).
  if (ingestBurstPerGid > 0 && ingestGidLimit > ingestBurstPerGid) {
    try {
      const allowedForward = pickAllowlistedForwardedIp();
      if (ipAllowlist.length > 0 && !allowedForward) {
        console.log("SKIP rate_limit_ingest_burst_gid (set IP_ALLOWLIST with IPv4 or ::1)");
      } else {
        const headers = { "x-forwarded-for": allowedForward || "burst-gid" };
        const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;
        let burstLimited = false;
        for (let i = 0; i < ingestBurstPerGid + 1; i += 1) {
          const event = { ...baseEvent, eid: uuidv4(), gid };
          const res = await postJson("/v1/events:ingest", { events: [event] }, headers);
          if (res.status === 429) {
            burstLimited = true;
            break;
          }
        }
        if (burstLimited) {
          console.log("PASS rate_limit_ingest_burst_gid");
        } else {
          failed += 1;
          console.log("FAIL rate_limit_ingest_burst_gid");
          console.log("  expected 429 within attempts");
        }
      }
    } catch (err) {
      failed += 1;
      console.log("FAIL rate_limit_ingest_burst_gid");
      console.log(`  error: ${err.message || String(err)}`);
    }
  } else {
    console.log(
      "SKIP rate_limit_ingest_burst_gid (set INGEST_BURST_PER_GID and RATE_LIMIT_BURST_WINDOW_SECONDS)"
    );
  }

  // Trusted proxy (x-forwarded-for) behavior (optional).
  if (trustedProxyIps.length > 0 && ingestIpLimit <= 1) {
    try {
      const allowedForward = pickAllowlistPair();
      if (ipAllowlist.length > 0 && !allowedForward) {
        console.log("SKIP trusted_proxy_xff (set IP_ALLOWLIST with IPv4 CIDR)");
      } else {
        const [forwardOne, forwardTwo] = allowedForward || ["10.0.0.1", "10.0.0.2"];
        const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;
        const res1 = await postJson(
          "/v1/events:ingest",
          { events: [{ ...baseEvent, eid: uuidv4(), gid }] },
          { "x-forwarded-for": forwardOne }
        );
        const res2 = await postJson(
          "/v1/events:ingest",
          { events: [{ ...baseEvent, eid: uuidv4(), gid: `Z29vZGd${uuidv4().slice(0, 6)}` }] },
          { "x-forwarded-for": forwardTwo }
        );
        if (res1.status === 200 && res2.status === 200) {
          console.log("PASS trusted_proxy_xff");
        } else {
          failed += 1;
          console.log("FAIL trusted_proxy_xff");
          console.log(`  first: ${res1.status} ${res1.body}`);
          console.log(`  second: ${res2.status} ${res2.body}`);
        }
      }
    } catch (err) {
      failed += 1;
      console.log("FAIL trusted_proxy_xff");
      console.log(`  error: ${err.message || String(err)}`);
    }
  } else {
    console.log(
      "SKIP trusted_proxy_xff (set TRUSTED_PROXY_IPS and INGEST_RATE_LIMIT_PER_IP <= 1)"
    );
  }

  // IP denylist (optional).
  if (ipDenylist.length > 0 && trustedProxyIps.length > 0) {
    const denyIp = firstIpv4Candidate(ipDenylist);
    if (!denyIp) {
      console.log("SKIP ip_denylist (no IPv4 in IP_DENYLIST)");
    } else {
      try {
        const res = await postJson(
          "/v1/events:ingest",
          { events: [{ ...baseEvent, eid: uuidv4(), gid: `Z29vZGd${uuidv4().slice(0, 6)}` }] },
          { "x-forwarded-for": denyIp }
        );
        const body = parseJson(res.body);
        const ok = res.status === 403 && body && body.error && body.error.code === "forbidden";
        if (ok) {
          console.log("PASS ip_denylist");
        } else {
          failed += 1;
          console.log("FAIL ip_denylist");
          console.log(`  status: ${res.status}`);
          console.log(`  body: ${res.body}`);
        }
      } catch (err) {
        failed += 1;
        console.log("FAIL ip_denylist");
        console.log(`  error: ${err.message || String(err)}`);
      }
    }
  } else {
    console.log("SKIP ip_denylist (set IP_DENYLIST and TRUSTED_PROXY_IPS)");
  }

  // IP allowlist (optional).
  if (ipAllowlist.length > 0 && trustedProxyIps.length > 0) {
    const allowIp = firstIpv4Candidate(ipAllowlist);
    if (!allowIp) {
      console.log("SKIP ip_allowlist (no IPv4 in IP_ALLOWLIST)");
    } else {
      try {
        const okRes = await postJson(
          "/v1/events:ingest",
          { events: [{ ...baseEvent, eid: uuidv4(), gid: `Z29vZGd${uuidv4().slice(0, 6)}` }] },
          { "x-forwarded-for": allowIp }
        );
        const blockRes = await postJson(
          "/v1/events:ingest",
          { events: [{ ...baseEvent, eid: uuidv4(), gid: `Z29vZGd${uuidv4().slice(0, 6)}` }] },
          { "x-forwarded-for": "203.0.113.10" }
        );
        const blockBody = parseJson(blockRes.body);
        const ok =
          okRes.status !== 403 &&
          blockRes.status === 403 &&
          blockBody &&
          blockBody.error &&
          blockBody.error.code === "forbidden";
        if (ok) {
          console.log("PASS ip_allowlist");
        } else {
          failed += 1;
          console.log("FAIL ip_allowlist");
          console.log(`  allowed: ${okRes.status} ${okRes.body}`);
          console.log(`  blocked: ${blockRes.status} ${blockRes.body}`);
        }
      } catch (err) {
        failed += 1;
        console.log("FAIL ip_allowlist");
        console.log(`  error: ${err.message || String(err)}`);
      }
    }
  } else {
    console.log("SKIP ip_allowlist (set IP_ALLOWLIST and TRUSTED_PROXY_IPS)");
  }

  // Federation peer auth (optional).
  if (federationPeerToken) {
    const peerHeaders = { "x-relay-peer": "1" };
    const allowedPeerIp = firstIpv4Candidate(federationPeerAllowlist);
    if (federationPeerAllowlist.length > 0 && !allowedPeerIp) {
      console.log("SKIP federation_peer_auth (no IPv4 in FEDERATION_PEER_ALLOWLIST)");
    } else if (federationPeerAllowlist.length > 0 && trustedProxyIps.length === 0) {
      console.log("SKIP federation_peer_auth (set TRUSTED_PROXY_IPS for allowlist test)");
    } else if (
      federationPeerAllowlist.length > 0 &&
      ipAllowlist.length > 0 &&
      allowedPeerIp &&
      !matchesIpList(allowedPeerIp, ipAllowlist)
    ) {
      console.log("SKIP federation_peer_auth (IP_ALLOWLIST must include FEDERATION_PEER_ALLOWLIST)");
    } else {
      try {
        const noToken = await withRateLimitRetry(
          () => postJson("/v1/events:ingest", { events: [{ ...baseEvent, eid: uuidv4() }] }, peerHeaders),
          "federation_peer:no_token"
        );
        const noTokenBody = parseJson(noToken.body);
        const noTokenOk =
          noToken.status === 403 &&
          noTokenBody &&
          noTokenBody.error &&
          noTokenBody.error.code === "forbidden";

        const okHeaders = { ...peerHeaders, "x-relay-peer-token": federationPeerToken };
        if (allowedPeerIp) {
          okHeaders["x-forwarded-for"] = allowedPeerIp;
        }
        const withToken = await withRateLimitRetry(
          () => postJson("/v1/events:ingest", { events: [{ ...baseEvent, eid: uuidv4() }] }, okHeaders),
          "federation_peer:with_token"
        );
        const withTokenOk = withToken.status === 200;

        if (noTokenOk && withTokenOk) {
          console.log("PASS federation_peer_auth");
        } else {
          failed += 1;
          console.log("FAIL federation_peer_auth");
          console.log(`  no_token: ${noToken.status} ${noToken.body}`);
          console.log(`  with_token: ${withToken.status} ${withToken.body}`);
        }
      } catch (err) {
        failed += 1;
        console.log("FAIL federation_peer_auth");
        console.log(`  error: ${err.message || String(err)}`);
      }
    }
  } else {
    console.log("SKIP federation_peer_auth (set FEDERATION_TEST_PEER_TOKEN)");
  }

  // EID replay protection (idempotent ingest).
  try {
    const allowedForward = pickAllowlistedForwardedIp();
    if (ipAllowlist.length > 0 && !allowedForward) {
      console.log("SKIP eid_replay (set IP_ALLOWLIST with IPv4 or ::1)");
      return;
    }
    const eid = uuidv4();
    const event = {
      ...baseEvent,
      eid,
      gid: `Z29vZGd${uuidv4().slice(0, 6)}`,
      body: { type: "CHAT.MSG" },
    };
    const headers = { "x-forwarded-for": allowedForward || "eid-replay-test" };

    const res = await withRateLimitRetry(
      () => postJson("/v1/events:ingest", { events: [event, event] }, headers),
      "eid_replay"
    );
    const body = parseJson(res.body);
    const ok =
      res.status === 200 &&
      body &&
      Array.isArray(body.accepted) &&
      body.accepted[0] === eid &&
      Array.isArray(body.rejected) &&
      body.rejected[0] &&
      body.rejected[0].reason === "duplicate";

    if (ok) {
      console.log("PASS eid_replay");
    } else {
      failed += 1;
      console.log("FAIL eid_replay");
      console.log(`  status: ${res.status}`);
      console.log(`  body: ${res.body}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL eid_replay");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Ingest rate limit per IP.
  try {
    let rateLimited = false;
    for (let i = 0; i < ingestIpLimit + 1; i += 1) {
      const event = { ...baseEvent, eid: uuidv4(), gid: `Z29vZGd${i}` };
      const res = await postJson("/v1/events:ingest", { events: [event] });
      if (res.status === 429) {
        rateLimited = true;
        const parsed = parseJson(res.body);
        if (!parsed || parsed.error.code !== "rate_limited") {
          throw new Error(`unexpected 429 body: ${res.body}`);
        }
        if (!res.headers || !res.headers["retry-after"]) {
          throw new Error("missing Retry-After header");
        }
        break;
      }
    }

    if (rateLimited) {
      console.log("PASS rate_limit_ingest_ip");
    } else {
      failed += 1;
      console.log("FAIL rate_limit_ingest_ip");
      console.log("  expected 429 within attempts");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL rate_limit_ingest_ip");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Ingest rate limit per GID.
  try {
    const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;
    let rateLimited = false;
    for (let i = 0; i < ingestGidLimit + 1; i += 1) {
      const event = { ...baseEvent, eid: uuidv4(), gid };
      const res = await postJson("/v1/events:ingest", { events: [event] });
      if (res.status === 429) {
        rateLimited = true;
        const parsed = parseJson(res.body);
        if (!parsed || parsed.error.code !== "rate_limited") {
          throw new Error(`unexpected 429 body: ${res.body}`);
        }
        break;
      }
    }

    if (rateLimited) {
      console.log("PASS rate_limit_ingest_gid");
    } else {
      failed += 1;
      console.log("FAIL rate_limit_ingest_gid");
      console.log("  expected 429 within attempts");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL rate_limit_ingest_gid");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Fetch rate limit per IP.
  try {
    const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;
    let rateLimited = false;
    for (let i = 0; i < fetchIpLimit + 1; i += 1) {
      const res = await getJson(`/v1/events:fetch?gid=${gid}&limit=1`);
      if (res.status === 429) {
        rateLimited = true;
        const parsed = parseJson(res.body);
        if (!parsed || parsed.error.code !== "rate_limited") {
          throw new Error(`unexpected 429 body: ${res.body}`);
        }
        break;
      }
    }

    if (rateLimited) {
      console.log("PASS rate_limit_fetch_ip");
    } else {
      failed += 1;
      console.log("FAIL rate_limit_fetch_ip");
      console.log("  expected 429 within attempts");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL rate_limit_fetch_ip");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Fetch rate limit per GID.
  try {
    const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;
    let rateLimited = false;
    for (let i = 0; i < fetchGidLimit + 1; i += 1) {
      const res = await getJson(`/v1/events:fetch?gid=${gid}&limit=1`);
      if (res.status === 429) {
        rateLimited = true;
        const parsed = parseJson(res.body);
        if (!parsed || parsed.error.code !== "rate_limited") {
          throw new Error(`unexpected 429 body: ${res.body}`);
        }
        break;
      }
    }

    if (rateLimited) {
      console.log("PASS rate_limit_fetch_gid");
    } else {
      failed += 1;
      console.log("FAIL rate_limit_fetch_gid");
      console.log("  expected 429 within attempts");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL rate_limit_fetch_gid");
    console.log(`  error: ${err.message || String(err)}`);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
