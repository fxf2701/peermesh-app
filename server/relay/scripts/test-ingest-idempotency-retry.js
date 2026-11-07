#!/usr/bin/env node

const { Readable } = require("stream");
const crypto = require("crypto");
const { createRouter } = require("../src/routes");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function bodyHashHex(rawBody) {
  const buffer = Buffer.isBuffer(rawBody) ? rawBody : Buffer.from(rawBody || "", "utf8");
  return crypto.createHash("sha256").update(buffer).digest("hex");
}

function signRequest({ secret, method, path, rawBody, timestamp }) {
  const payload = [
    method.toUpperCase(),
    path,
    String(timestamp),
    bodyHashHex(rawBody),
  ].join("\n");
  return crypto.createHmac("sha256", secret).update(payload).digest("hex");
}

function createMockReq({ method, url, headers, body }) {
  const req = new Readable({
    read() {
      this.push(body || "");
      this.push(null);
    },
  });
  req.method = method;
  req.url = url;
  req.headers = headers || {};
  req.socket = { remoteAddress: "127.0.0.1" };
  return req;
}

function createMockRes() {
  return {
    statusCode: 200,
    headers: {},
    body: "",
    setHeader(name, value) {
      this.headers[name.toLowerCase()] = value;
    },
    end(chunk) {
      if (chunk) this.body += chunk;
      if (this._resolve) this._resolve();
    },
    on(event, cb) {
      if (event === "finish") this._finish = cb;
    },
    wait() {
      return new Promise((resolve) => {
        this._resolve = () => {
          if (this._finish) this._finish();
          resolve();
        };
      });
    },
  };
}

function createMetricsStub() {
  return {
    inc() {},
    observe() {},
    setGauge() {},
    renderPrometheus() {
      return "";
    },
  };
}

function baseConfig() {
  return {
    redisUrl: "",
    dataDir: "./data",
    trustedProxyIps: [],
    ipAllowlist: [],
    ipDenylist: [],
    maxRequestBytes: 1000000,
    maxEventsPerRequest: 100,
    maxEventBytes: 65536,
    eventMaxFutureSeconds: 600,
    eventExpGraceSeconds: 300,
    requestSignatureRequired: true,
    requestSignatureSecret: "request-signature-secret",
    requestSignatureSkewSeconds: 300,
    requestSignatureReplayMaxEntries: 1000,
    requestSignatureReplayTtlSeconds: 300,
    ingestRateLimitPerIp: 1000,
    ingestRateLimitPerGid: 1000,
    fetchRateLimitPerIp: 1000,
    fetchRateLimitPerGid: 1000,
    ingestBurstPerIp: 1000,
    ingestBurstPerGid: 1000,
    fetchBurstPerIp: 1000,
    fetchBurstPerGid: 1000,
    rateLimitWindowSeconds: 60,
    rateLimitBurstWindowSeconds: 10,
    federationJoinRateLimit: 1000,
    federationJoinRateLimitWindowSeconds: 60,
    federationHandshakeRateLimit: 1000,
    federationHandshakeRateLimitWindowSeconds: 60,
    federationRequireTls: false,
    federationPeerBanlist: [],
    federationPeerAllowlist: [],
    federationSelfId: "relay-a",
    relaySelfId: "relay-a",
    registryClientAccessGrantsEnabled: false,
    clientRelaySessionsEnabled: false,
    registryFederationSessionSigningSecret: "registry-session-secret",
    federationHeartbeatTtlSeconds: 120,
    federationConnectionCapacity: 32,
    federationMaxPeers: 0,
    cleanupIntervalSeconds: 0,
    enableTestDbErrors: false,
    auditLogEnabled: false,
    ingestRetryEnabled: false,
  };
}

function createStorageStub() {
  return {
    attempts: 0,
    async ingestEvents(events) {
      this.attempts += 1;
      if (this.attempts === 1) {
        const err = new Error("temporary db failure");
        err.code = "DB_TRANSIENT";
        throw err;
      }
      return { accepted: events.map((event) => event.eid), rejected: [] };
    },
    async fetchEvents() {
      return { events: [], next_cursor: null };
    },
    async healthCheck() {},
    async close() {},
  };
}

async function callRoute(router, req) {
  const res = createMockRes();
  const done = res.wait();
  await router.route(req, res);
  await done;
  return { res, json: JSON.parse(res.body) };
}

function createSignedIngestReq({ body, secret, timestamp, signature }) {
  return createMockReq({
    method: "POST",
    url: "/v1/events:ingest",
    headers: {
      "content-type": "application/json",
      "content-length": String(Buffer.byteLength(body)),
      "x-relay-timestamp": String(timestamp),
      "x-relay-signature": signature,
    },
    body,
  });
}

async function main() {
  const body = JSON.stringify({
    events: [
      {
        eid: "11111111-1111-4111-8111-111111111111",
        gid: "group-1",
        ts: Math.floor(Date.now() / 1000),
        exp: Math.floor(Date.now() / 1000) + 300,
        body: {
          v: 1,
          enc: "AES-256-GCM",
          kid: "gk1",
          cty: "peermesh/app-event+json",
          nonce: Buffer.from("nonce-evt-1").toString("base64url"),
          ct: Buffer.from("ciphertext-evt-1").toString("base64url"),
          tag: Buffer.from("tag-evt-1").toString("base64url"),
        },
        sig: "sig",
      },
    ],
  });
  const timestamp = Math.floor(Date.now() / 1000);
  const signature = signRequest({
    secret: "request-signature-secret",
    method: "POST",
    path: "/v1/events:ingest",
    rawBody: body,
    timestamp,
  });
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    metrics: createMetricsStub(),
  });

  try {
    const first = await callRoute(
      router,
      createSignedIngestReq({ body, timestamp, signature })
    );
    assert(first.res.statusCode === 500, "first request should surface transient db failure");
    assert(first.json.error.code === "db_error", "first request should map to db_error");

    const second = await callRoute(
      router,
      createSignedIngestReq({ body, timestamp, signature })
    );
    assert(second.res.statusCode === 200, "retry after transient db failure should succeed");
    assert(Array.isArray(second.json.accepted) && second.json.accepted.length === 1, "retry should accept event");

    const third = await callRoute(
      router,
      createSignedIngestReq({ body, timestamp, signature })
    );
    assert(third.res.statusCode === 401, "replay after successful retry should be rejected");
    assert(third.json.error.code === "signature_replay", "replay should return signature_replay");

    console.log("PASS ingest_idempotency_retry_signature_commit");
  } finally {
    await router.shutdown();
  }
}

main().catch((err) => {
  console.error("FAIL ingest_idempotency_retry_signature_commit");
  console.error(err && err.message ? err.message : String(err));
  process.exit(1);
});
