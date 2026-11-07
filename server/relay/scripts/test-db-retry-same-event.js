#!/usr/bin/env node

const { Readable } = require("stream");
const { createRouter } = require("../src/routes");

function assert(condition, message) {
  if (!condition) throw new Error(message);
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
    requestSignatureRequired: false,
    requestSignatureSecret: "",
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
  const persisted = new Set();
  return {
    attempts: 0,
    async ingestEvents(events) {
      this.attempts += 1;
      if (this.attempts === 1) {
        const err = new Error("temporary db failure");
        err.code = "DB_TRANSIENT";
        throw err;
      }
      const accepted = [];
      const rejected = [];
      for (const event of events) {
        if (persisted.has(event.eid)) {
          rejected.push({ eid: event.eid, reason: "duplicate" });
          continue;
        }
        persisted.add(event.eid);
        accepted.push(event.eid);
      }
      return { accepted, rejected };
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

function createIngestReq(body) {
  return createMockReq({
    method: "POST",
    url: "/v1/events:ingest",
    headers: {
      "content-type": "application/json",
      "content-length": String(Buffer.byteLength(body)),
    },
    body,
  });
}

async function main() {
  const body = JSON.stringify({
    events: [
      {
        eid: "22222222-2222-4222-8222-222222222222",
        gid: "group-1",
        ts: Math.floor(Date.now() / 1000),
        exp: Math.floor(Date.now() / 1000) + 300,
        body: {
          v: 1,
          enc: "AES-256-GCM",
          kid: "gk1",
          cty: "peermesh/app-event+json",
          nonce: Buffer.from("nonce-evt-2").toString("base64url"),
          ct: Buffer.from("ciphertext-evt-2").toString("base64url"),
          tag: Buffer.from("tag-evt-2").toString("base64url"),
        },
        sig: "sig",
      },
    ],
  });
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    metrics: createMetricsStub(),
  });

  try {
    const first = await callRoute(router, createIngestReq(body));
    assert(first.res.statusCode === 500, "first ingest should fail with transient db error");
    assert(first.json.error.code === "db_error", "first ingest should map to db_error");

    const second = await callRoute(router, createIngestReq(body));
    assert(second.res.statusCode === 200, "retry of same event should succeed");
    assert(second.json.accepted.length === 1, "retry should accept the event once");
    assert(second.json.rejected.length === 0, "retry should not reject event");

    const third = await callRoute(router, createIngestReq(body));
    assert(third.res.statusCode === 200, "third ingest should complete with duplicate result");
    assert(third.json.accepted.length === 0, "duplicate retry should not accept event again");
    assert(third.json.rejected.length === 1, "duplicate retry should reject one event");
    assert(third.json.rejected[0].reason === "duplicate", "duplicate retry should be marked duplicate");

    console.log("PASS db_retry_same_event_regression");
  } finally {
    await router.shutdown();
  }
}

main().catch((err) => {
  console.error("FAIL db_retry_same_event_regression");
  console.error(err && err.stack ? err.stack : String(err));
  process.exit(1);
});
