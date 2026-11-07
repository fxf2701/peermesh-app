#!/usr/bin/env node

const { Readable } = require("stream");
const crypto = require("crypto");
const { createRouter } = require("../src/routes");
const { signFederationRequest, signPeerGrant } = require("../src/federation_push");

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
    requestSignatureRequired: true,
    requestSignatureSecret: "private-relay-secret",
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
    registryFederationSessionSigningSecret: "registry-session-secret",
    federationHeartbeatTtlSeconds: 120,
    federationConnectionCapacity: 32,
    federationMaxPeers: 0,
    registryClientAccessGrantsEnabled: false,
    clientRelaySessionsEnabled: false,
    cleanupIntervalSeconds: 0,
    enableTestDbErrors: false,
    auditLogEnabled: false,
  };
}

function createStorageStub() {
  const calls = [];
  return {
    calls,
    async ingestEvents(events) {
      calls.push({ method: "ingestEvents", events });
      return { accepted: events.map((event) => event.eid), rejected: [] };
    },
    async fetchEvents(query) {
      calls.push({ method: "fetchEvents", query });
      return { events: [], next_cursor: null };
    },
    async healthCheck() {},
    async close() {},
  };
}

function signPrivateRequest({ method, path, rawBody, timestamp, secret }) {
  const bodyHash = crypto.createHash("sha256").update(rawBody || "").digest("hex");
  return crypto
    .createHmac("sha256", secret)
    .update([method.toUpperCase(), path, String(timestamp), bodyHash].join("\n"))
    .digest("hex");
}

function createEvent() {
  const now = Math.floor(Date.now() / 1000);
  return {
    eid: "evt-1",
    gid: "group-1",
    ts: now,
    exp: now + 300,
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
  };
}

function createPeerGrant(overrides = {}) {
  const grant = {
    session_id: "fs_test",
    source_relay_id: "relay-b",
    destination_relay_id: "relay-a",
    endpoint: "http://relay-a:8080",
    allowed_gids: ["group-1"],
    permissions: ["pull_reconcile"],
    limits: {
      max_events_per_minute: 600,
      max_bytes_per_minute: 10485760,
      max_in_flight: 4,
    },
    expires_at: Math.floor(Date.now() / 1000) + 300,
    policy_version: 1,
    session_token: "session-token",
    ...overrides,
  };
  grant.registry_signature = signPeerGrant("registry-session-secret", grant);
  return grant;
}

function encodeGrant(grant) {
  return Buffer.from(JSON.stringify(grant)).toString("base64url");
}

async function callRoute(router, req) {
  const res = createMockRes();
  const done = res.wait();
  await router.route(req, res);
  await done;
  return { res, json: JSON.parse(res.body) };
}

function unsignedAppFetchReq() {
  return createMockReq({
    method: "GET",
    url: "/v1/events:fetch?gid=group-1&limit=10",
    headers: {},
    body: "",
  });
}

function unsignedAppIngestReq() {
  const body = JSON.stringify({ events: [createEvent()] });
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

function signedAppFetchReq() {
  const path = "/v1/events:fetch?gid=group-1&limit=10";
  const timestamp = Math.floor(Date.now() / 1000);
  const signature = signPrivateRequest({
    method: "GET",
    path,
    rawBody: "",
    timestamp,
    secret: "private-relay-secret",
  });
  return createMockReq({
    method: "GET",
    url: path,
    headers: {
      "x-relay-timestamp": String(timestamp),
      "x-relay-signature": signature,
    },
    body: "",
  });
}

function federationFetchReq() {
  const path = "/v1/events:fetch?gid=group-1&limit=10";
  const token = "session-token";
  const timestamp = String(Math.floor(Date.now() / 1000));
  const signature = signFederationRequest({
    token,
    method: "GET",
    path,
    timestamp,
    rawBody: "",
  });
  return createMockReq({
    method: "GET",
    url: path,
    headers: {
      "x-federation-session": "fs_test",
      "x-federation-token": token,
      "x-federation-operation": "pull_reconcile",
      "x-federation-grant": encodeGrant(createPeerGrant()),
      "x-federation-timestamp": timestamp,
      "x-federation-signature": signature,
    },
    body: "",
  });
}

async function main() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    metrics: createMetricsStub(),
  });

  try {
    const unsignedFetch = await callRoute(router, unsignedAppFetchReq());
    assert(unsignedFetch.res.statusCode === 401, "unsigned app fetch should require private signature");
    assert(unsignedFetch.json.error.code === "invalid_signature", "unsigned app fetch should return invalid_signature");
    console.log("PASS private_signature_blocks_unsigned_app_fetch");

    const unsignedIngest = await callRoute(router, unsignedAppIngestReq());
    assert(unsignedIngest.res.statusCode === 401, "unsigned app ingest should require private signature");
    assert(unsignedIngest.json.error.code === "invalid_signature", "unsigned app ingest should return invalid_signature");
    console.log("PASS private_signature_blocks_unsigned_app_ingest");

    const signedFetch = await callRoute(router, signedAppFetchReq());
    assert(signedFetch.res.statusCode === 200, "signed app fetch should pass private signature gate");
    console.log("PASS private_signature_allows_signed_app_fetch");

    const federationFetch = await callRoute(router, federationFetchReq());
    assert(federationFetch.res.statusCode === 200, "federation fetch should remain valid without x-relay-signature");
    assert(storage.calls.some((entry) => entry.method === "fetchEvents"), "federation fetch should reach storage");
    console.log("PASS private_signature_preserves_federation_fetch");
  } finally {
    await router.shutdown();
  }
}

main().catch((err) => {
  console.error("FAIL private_signature_federation_sync", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
