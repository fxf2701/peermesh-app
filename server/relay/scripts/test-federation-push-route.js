#!/usr/bin/env node

const { Readable } = require("stream");
const { isDeepStrictEqual } = require("util");
const { createRouter } = require("../src/routes");
const { signFederationRequest, signPeerGrant } = require("../src/federation_push");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function deepEqual(a, b) {
  return isDeepStrictEqual(a, b);
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
    counters: {},
    inc(name, value = 1) {
      this.counters[name] = (this.counters[name] || 0) + value;
    },
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
    registryFederationSessionSigningSecret: "registry-session-secret",
    federationHeartbeatTtlSeconds: 120,
    federationConnectionCapacity: 32,
    federationMaxPeers: 0,

    cleanupIntervalSeconds: 0,
    enableTestDbErrors: false,
    auditLogEnabled: false,
  };
}

function createEvent(overrides = {}) {
  const now = Math.floor(Date.now() / 1000);
  return {
    eid: "evt-1",
    gid: "group-1",
    ts: now,
    exp: now + 3600,
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
    ...overrides,
  };
}

function createPeerGrant(overrides = {}) {
  const grant = {
    session_id: "fs_test",
    source_relay_id: "relay-b",
    destination_relay_id: "relay-a",
    endpoint: "http://relay-a:8080",
    allowed_gids: ["group-1"],
    permissions: ["push"],
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

function signedPushRequest({ bodyObject, token = "session-token", sessionId = "fs_test" }) {
  const body = JSON.stringify(bodyObject);
  const timestamp = String(Math.floor(Date.now() / 1000));
  const signature = signFederationRequest({
    token,
    method: "POST",
    path: "/v1/federation/events:push",
    timestamp,
    rawBody: body,
  });
  return createMockReq({
    method: "POST",
    url: "/v1/federation/events:push",
    body,
    headers: {
      "content-type": "application/json",
      "content-length": String(Buffer.byteLength(body)),
      "x-federation-session": sessionId,
      "x-federation-token": token,
      "x-federation-timestamp": timestamp,
      "x-federation-signature": signature,
    },
  });
}

function encodeGrant(grant) {
  return Buffer.from(JSON.stringify(grant)).toString("base64url");
}

function signedFetchRequest({
  gid = "group-1",
  token = "session-token",
  sessionId = "fs_test",
  grant = createPeerGrant({ permissions: ["pull_reconcile"] }),
  operation = "pull_reconcile",
  signatureToken = token,
}) {
  const url = `/v1/events:fetch?gid=${encodeURIComponent(gid)}&limit=10`;
  const timestamp = String(Math.floor(Date.now() / 1000));
  const signature = signFederationRequest({
    token: signatureToken,
    method: "GET",
    path: url,
    timestamp,
    rawBody: "",
  });
  return createMockReq({
    method: "GET",
    url,
    body: "",
    headers: {
      "x-federation-session": sessionId,
      "x-federation-token": token,
      "x-federation-operation": operation,
      "x-federation-grant": encodeGrant(grant),
      "x-federation-timestamp": timestamp,
      "x-federation-signature": signature,
    },
  });
}

function createStorageStub() {
  const calls = [];

  return {
    calls,

    async ingestEvents(events) {
      calls.push({ method: "ingestEvents", events });
      return {
        accepted: events.map((event) => event.eid),
        rejected: [],
      };
    },

    async fetchEvents(query) {
      calls.push({ method: "fetchEvents", query });
      return { events: [], next_cursor: null };
    },

    async healthCheck() {},
    async close() {},
  };
}

function createDeferredIngestStorageStub() {
  const calls = [];
  let release = null;
  return {
    calls,
    async ingestEvents(events) {
      calls.push({ method: "ingestEvents", events });
      await new Promise((resolve) => {
        release = resolve;
      });
      return {
        accepted: events.map((event) => event.eid),
        rejected: [],
      };
    },
    async fetchEvents(query) {
      calls.push({ method: "fetchEvents", query });
      return { events: [], next_cursor: null };
    },
    async healthCheck() {},
    async close() {},
    release() {
      if (release) release();
    },
  };
}

async function callRoute(router, req) {
  const res = createMockRes();
  const done = res.wait();
  await router.route(req, res);
  await done;

  let json = null;
  try {
    json = JSON.parse(res.body);
  } catch (_) {
    json = null;
  }

  return { res, json };
}

async function testFederationPushAccepted() {
  const storage = createStorageStub();
  const metrics = createMetricsStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics,
  });

  const req = signedPushRequest({
    bodyObject: {
      session_id: "fs_test",
      source_relay_id: "relay-b",
      destination_relay_id: "relay-a",
      peer_grant: createPeerGrant(),
      events: [createEvent()],
    },
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 202, "valid federation push should return 202");
  assert(json.accepted.includes("evt-1"), "evt-1 should be accepted");
  assert(storage.calls.length === 1, "storage.ingestEvents should be called once");
  assert(storage.calls[0].events.length === 1, "one event should be forwarded to storage");
  assert(
    deepEqual(storage.calls[0].events[0].body, createEvent().body),
    "encrypted body should be forwarded without mutation"
  );
  assert(metrics.counters.federation_push_ingest_total === 1, "ingest metric should increment");

  await router.shutdown();
  console.log("PASS federation_push_route_accepts_signed_push");
}

async function testFederationPushRejectsMissingHeaders() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const body = JSON.stringify({ events: [] });

  const req = createMockReq({
    method: "POST",
    url: "/v1/federation/events:push",
    body,
    headers: {
      "content-type": "application/json",
      "content-length": String(Buffer.byteLength(body)),
    },
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 401, "missing federation headers should return 401");
  assert(json.error.code === "federation_session_required", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_push_route_rejects_missing_headers");
}

async function testFederationPushRejectsBadSignature() {
  const storage = createStorageStub();
  const metrics = createMetricsStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics,
  });

  const body = JSON.stringify({ events: [] });
  const timestamp = String(Math.floor(Date.now() / 1000));

  const req = createMockReq({
    method: "POST",
    url: "/v1/federation/events:push",
    body,
    headers: {
      "content-type": "application/json",
      "content-length": String(Buffer.byteLength(body)),
      "x-federation-session": "fs_test",
      "x-federation-token": "session-token",
      "x-federation-timestamp": timestamp,
      "x-federation-signature": "00".repeat(32),
    },
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 401, "bad signature should return 401");
  assert(json.error.code === "invalid_federation_signature", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");
  assert(metrics.counters.federation_push_validation_failures_total === 1, "failure metric should increment");

  await router.shutdown();
  console.log("PASS federation_push_route_rejects_bad_signature");
}

async function testFederationPushRejectsCallerSuppliedToken() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const req = signedPushRequest({
    token: "attacker-token",
    bodyObject: {
      session_id: "fs_test",
      source_relay_id: "relay-b",
      destination_relay_id: "relay-a",
      peer_grant: createPeerGrant({ session_token: "session-token" }),
      events: [createEvent()],
    },
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 403, "wrong token should return 403");
  assert(json.error.code === "token_mismatch", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_push_route_rejects_caller_supplied_token");
}

async function testFederationPushRejectsTamperedGrantToken() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const grant = createPeerGrant();
  grant.session_token = "attacker-token";

  const req = signedPushRequest({
    token: "attacker-token",
    bodyObject: {
      session_id: "fs_test",
      source_relay_id: "relay-b",
      destination_relay_id: "relay-a",
      peer_grant: grant,
      events: [createEvent()],
    },
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 403, "tampered grant token should return 403");
  assert(json.error.code === "invalid_registry_signature", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_push_route_rejects_tampered_grant_token");
}

async function testFederationPushRejectsTamperedGrantScope() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const grant = createPeerGrant();
  grant.allowed_gids = ["group-1", "group-2"];

  const req = signedPushRequest({
    bodyObject: {
      session_id: "fs_test",
      source_relay_id: "relay-b",
      destination_relay_id: "relay-a",
      peer_grant: grant,
      events: [createEvent({ gid: "group-2" })],
    },
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 403, "tampered grant scope should return 403");
  assert(json.error.code === "invalid_registry_signature", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_push_route_rejects_tampered_grant_scope");
}

async function testFederationPushRejectsUnauthorizedGid() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const req = signedPushRequest({
    bodyObject: {
      session_id: "fs_test",
      source_relay_id: "relay-b",
      destination_relay_id: "relay-a",
      peer_grant: createPeerGrant({ allowed_gids: ["group-1"] }),
      events: [createEvent({ gid: "group-2" })],
    },
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 403, "unauthorized gid should return 403");
  assert(json.error.code === "gid_not_allowed", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_push_route_rejects_unauthorized_gid");
}

async function testFederationPushRejectsExpiredGrant() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const req = signedPushRequest({
    bodyObject: {
      session_id: "fs_test",
      source_relay_id: "relay-b",
      destination_relay_id: "relay-a",
      peer_grant: createPeerGrant({ expires_at: Math.floor(Date.now() / 1000) - 1 }),
      events: [createEvent()],
    },
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 403, "expired grant should return 403");
  assert(json.error.code === "session_expired", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_push_route_rejects_expired_grant");
}

async function testFederationPushRejectsWrongDestination() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const req = signedPushRequest({
    bodyObject: {
      session_id: "fs_test",
      source_relay_id: "relay-b",
      destination_relay_id: "relay-a",
      peer_grant: createPeerGrant({ destination_relay_id: "relay-c" }),
      events: [createEvent()],
    },
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 403, "wrong destination should return 403");
  assert(json.error.code === "destination_relay_mismatch", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_push_route_rejects_wrong_destination");
}

async function testFederationPushRejectsNotForThisRelay() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const req = signedPushRequest({
    bodyObject: {
      session_id: "fs_test",
      source_relay_id: "relay-b",
      destination_relay_id: "relay-c",
      peer_grant: createPeerGrant({ destination_relay_id: "relay-c" }),
      events: [createEvent()],
    },
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 403, "grant for another relay should return 403");
  assert(json.error.code === "wrong_destination_relay", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_push_route_rejects_not_for_this_relay");
}

async function testFederationPushRejectsRevokedSession() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
    registrySessions: {
      isSessionRevoked(sessionId) {
        return sessionId === "fs_test";
      },
    },
  });

  const req = signedPushRequest({
    bodyObject: {
      session_id: "fs_test",
      source_relay_id: "relay-b",
      destination_relay_id: "relay-a",
      peer_grant: createPeerGrant(),
      events: [createEvent()],
    },
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 403, "revoked session should return 403");
  assert(json.error.code === "session_revoked", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_push_route_rejects_revoked_session");
}

async function testFederationPushRejectsEventRateLimit() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const req = signedPushRequest({
    bodyObject: {
      session_id: "fs_test",
      source_relay_id: "relay-b",
      destination_relay_id: "relay-a",
      peer_grant: createPeerGrant({
        limits: { max_events_per_minute: 1, max_bytes_per_minute: 10485760, max_in_flight: 4 },
      }),
      events: [createEvent(), createEvent({ eid: "evt-2", nonce: undefined, body: { ...createEvent().body, nonce: Buffer.from("nonce-evt-2").toString("base64url") } })],
    },
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 429, "event quota overflow should return 429");
  assert(json.error.code === "session_event_rate_limited", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_push_route_rejects_event_rate_limit");
}

async function testFederationPushRejectsByteRateLimit() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const req = signedPushRequest({
    bodyObject: {
      session_id: "fs_test",
      source_relay_id: "relay-b",
      destination_relay_id: "relay-a",
      peer_grant: createPeerGrant({
        limits: { max_events_per_minute: 600, max_bytes_per_minute: 64, max_in_flight: 4 },
      }),
      events: [createEvent()],
    },
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 429, "byte quota overflow should return 429");
  assert(json.error.code === "session_byte_rate_limited", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_push_route_rejects_byte_rate_limit");
}

async function testFederationPushRejectsInFlightLimit() {
  const storage = createDeferredIngestStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const firstReq = signedPushRequest({
    bodyObject: {
      session_id: "fs_test",
      source_relay_id: "relay-b",
      destination_relay_id: "relay-a",
      peer_grant: createPeerGrant({
        limits: { max_events_per_minute: 600, max_bytes_per_minute: 10485760, max_in_flight: 1 },
      }),
      events: [createEvent()],
    },
  });
  const firstRes = createMockRes();
  const firstDone = firstRes.wait();
  router.route(firstReq, firstRes);

  await new Promise((resolve) => setImmediate(resolve));

  const secondReq = signedPushRequest({
    bodyObject: {
      session_id: "fs_test",
      source_relay_id: "relay-b",
      destination_relay_id: "relay-a",
      peer_grant: createPeerGrant({
        limits: { max_events_per_minute: 600, max_bytes_per_minute: 10485760, max_in_flight: 1 },
      }),
      events: [createEvent({ eid: "evt-2", body: { ...createEvent().body, nonce: Buffer.from("nonce-evt-2").toString("base64url") } })],
    },
  });

  const { res, json } = await callRoute(router, secondReq);

  assert(res.statusCode === 429, "in-flight quota overflow should return 429");
  assert(json.error.code === "session_in_flight_limited", "error code should match");

  storage.release();
  await firstDone;
  await router.shutdown();
  console.log("PASS federation_push_route_rejects_in_flight_limit");
}

async function testFederationFetchAcceptsScopedGrant() {
  const storage = createStorageStub();
  storage.fetchEvents = async function fetchEvents(query) {
    this.calls.push({ method: "fetchEvents", query });
    return { events: [createEvent()], next_cursor: null };
  };
  const metrics = createMetricsStub();
  const router = createRouter({
    config: { ...baseConfig(), requestSignatureRequired: true },
    storage,
    redis: null,
    metrics,
  });

  const req = signedFetchRequest({
    grant: createPeerGrant({ permissions: ["pull_reconcile"], allowed_gids: ["group-1"] }),
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 200, "valid federation fetch should return 200");
  assert(Array.isArray(json.events), "fetch response should include events array");
  assert(json.events.length === 1, "fetch response should include returned event");
  assert(storage.calls.length === 1, "storage.fetchEvents should be called once");
  assert(storage.calls[0].method === "fetchEvents", "fetchEvents should be called");
  assert(metrics.counters.federation_reconciliation_total === 1, "reconciliation metric should increment");
  assert(metrics.counters.federation_reconciliation_events_total === 1, "reconciliation event metric should increment");

  await router.shutdown();
  console.log("PASS federation_fetch_route_accepts_scoped_grant");
}

async function testFederationFetchRejectsUnauthorizedGid() {
  const storage = createStorageStub();
  const metrics = createMetricsStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics,
  });

  const req = signedFetchRequest({
    gid: "group-2",
    grant: createPeerGrant({ permissions: ["pull_reconcile"], allowed_gids: ["group-1"] }),
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 403, "unauthorized fetch gid should return 403");
  assert(json.error.code === "gid_not_allowed", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");
  assert(
    metrics.counters.federation_reconciliation_validation_failures_total === 1,
    "reconciliation failure metric should increment"
  );

  await router.shutdown();
  console.log("PASS federation_fetch_route_rejects_unauthorized_gid");
}

async function testFederationFetchRejectsWrongOperation() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const req = signedFetchRequest({
    operation: "push",
    grant: createPeerGrant({ permissions: ["pull_reconcile"], allowed_gids: ["group-1"] }),
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 403, "wrong fetch operation should return 403");
  assert(json.error.code === "operation_not_allowed", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_fetch_route_rejects_wrong_operation");
}

async function testFederationFetchRejectsTamperedGrantPermissions() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const grant = createPeerGrant({ permissions: ["push"], allowed_gids: ["group-1"] });
  grant.permissions = ["push", "pull_reconcile"];

  const req = signedFetchRequest({
    grant,
    operation: "pull_reconcile",
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 403, "tampered fetch permissions should return 403");
  assert(json.error.code === "invalid_registry_signature", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_fetch_route_rejects_tampered_grant_permissions");
}

async function testFederationFetchRejectsTamperedGrantDestination() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
  });

  const grant = createPeerGrant({ permissions: ["pull_reconcile"], allowed_gids: ["group-1"] });
  grant.destination_relay_id = "relay-z";

  const req = signedFetchRequest({
    grant,
    operation: "pull_reconcile",
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 403, "tampered fetch destination should return 403");
  assert(json.error.code === "invalid_registry_signature", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_fetch_route_rejects_tampered_grant_destination");
}

async function testFederationFetchRejectsRevokedSession() {
  const storage = createStorageStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics: createMetricsStub(),
    registrySessions: {
      isSessionRevoked(sessionId) {
        return sessionId === "fs_test";
      },
    },
  });

  const req = signedFetchRequest({
    grant: createPeerGrant({ permissions: ["pull_reconcile"], allowed_gids: ["group-1"] }),
  });

  const { res, json } = await callRoute(router, req);

  assert(res.statusCode === 403, "revoked fetch session should return 403");
  assert(json.error.code === "session_revoked", "error code should match");
  assert(storage.calls.length === 0, "storage should not be called");

  await router.shutdown();
  console.log("PASS federation_fetch_route_rejects_revoked_session");
}

async function testFederationFetchRejectsCumulativeEventRateLimit() {
  const storage = {
    calls: [],
    async ingestEvents(events) {
      this.calls.push({ method: "ingestEvents", events });
      return { accepted: events.map((event) => event.eid), rejected: [] };
    },
    async fetchEvents(query) {
      this.calls.push({ method: "fetchEvents", query });
      return { events: [createEvent()], next_cursor: null };
    },
    async healthCheck() {},
    async close() {},
  };
  const metrics = createMetricsStub();
  const router = createRouter({
    config: baseConfig(),
    storage,
    redis: null,
    metrics,
  });

  const grant = createPeerGrant({
    permissions: ["pull_reconcile"],
    allowed_gids: ["group-1"],
    limits: { max_events_per_minute: 1, max_bytes_per_minute: 10485760, max_in_flight: 4 },
  });

  const first = await callRoute(router, signedFetchRequest({ grant }));
  assert(first.res.statusCode === 200, "first fetch should succeed");

  const second = await callRoute(router, signedFetchRequest({ grant }));
  assert(second.res.statusCode === 429, "second fetch in same window should be limited");
  assert(second.json.error.code === "session_event_rate_limited", "error code should match");
  assert(metrics.counters.federation_reconciliation_total === 2, "reconciliation metric should count both authorized attempts");
  assert(metrics.counters.federation_session_limit_rejections_total === 1, "session limit rejection metric should increment");

  await router.shutdown();
  console.log("PASS federation_fetch_route_rejects_cumulative_event_rate_limit");
}

async function main() {
  await testFederationPushAccepted();
  await testFederationPushRejectsMissingHeaders();
  await testFederationPushRejectsBadSignature();
  await testFederationPushRejectsCallerSuppliedToken();
  await testFederationPushRejectsTamperedGrantToken();
  await testFederationPushRejectsTamperedGrantScope();
  await testFederationPushRejectsUnauthorizedGid();
  await testFederationPushRejectsExpiredGrant();
  await testFederationPushRejectsWrongDestination();
  await testFederationPushRejectsNotForThisRelay();
  await testFederationPushRejectsRevokedSession();
  await testFederationPushRejectsEventRateLimit();
  await testFederationPushRejectsByteRateLimit();
  await testFederationPushRejectsInFlightLimit();
  await testFederationFetchAcceptsScopedGrant();
  await testFederationFetchRejectsUnauthorizedGid();
  await testFederationFetchRejectsWrongOperation();
  await testFederationFetchRejectsTamperedGrantPermissions();
  await testFederationFetchRejectsTamperedGrantDestination();
  await testFederationFetchRejectsRevokedSession();
  await testFederationFetchRejectsCumulativeEventRateLimit();
  console.log("PASS federation_push_route_all");
}

main().catch((err) => {
  console.error("FAIL federation_push_route", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
