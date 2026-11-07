#!/usr/bin/env node

const { Readable } = require("stream");
const { createRouter } = require("../src/routes");
const { signClientAccessGrant } = require("../src/client_access_grants");

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
    relaySelfId: "relay-a",
    registryClientAccessGrantsEnabled: true,
    registryClientAccessGrantSigningSecret: "registry-client-grant-secret",
    registryFederationSessionSigningSecret: "registry-session-secret",
    federationHeartbeatTtlSeconds: 120,
    federationConnectionCapacity: 32,
    federationMaxPeers: 0,
    cleanupIntervalSeconds: 0,
    enableTestDbErrors: false,
    auditLogEnabled: false,
  };
}

function createStorageStub() {
  return {
    async ingestEvents(events) {
      return { accepted: events.map((event) => event.eid), rejected: [] };
    },
    async fetchEvents() {
      return { events: [], next_cursor: null };
    },
    async healthCheck() {},
    async close() {},
  };
}

function encodeGrant(grant) {
  return Buffer.from(JSON.stringify(grant)).toString("base64url");
}

function createGrant(overrides = {}) {
  const grant = {
    grant_id: "cg_test",
    issued_at: Math.floor(Date.now() / 1000),
    expires_at: Math.floor(Date.now() / 1000) + 300,
    policy_version: 1,
    client_instance_id: "android-device-01",
    candidate_relays: [
      {
        relay_id: "relay-a",
        endpoint: "http://relay-a:8080",
        trust_level: "trusted",
        region: "vn-south",
      },
    ],
    allowed_gids: ["group-1"],
    permissions: ["ingest", "fetch"],
    limits: {
      max_events_per_minute: 120,
      max_bytes_per_minute: 1048576,
    },
    access_token: "client-access-token",
    connection_mode: "client_selects_and_relay_establishes_session",
    ...overrides,
  };
  grant.registry_signature = signClientAccessGrant(
    "registry-client-grant-secret",
    grant
  );
  return grant;
}

function ingestReq({ grant, accessToken = "client-access-token" } = {}) {
  const body = JSON.stringify({
    events: [
      {
        eid: "evt-1",
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
  const headers = {
    "content-type": "application/json",
    "content-length": String(Buffer.byteLength(body)),
  };
  if (grant) headers["x-relay-access-grant"] = encodeGrant(grant);
  if (accessToken) headers["x-relay-access-token"] = accessToken;
  return createMockReq({
    method: "POST",
    url: "/v1/events:ingest",
    headers,
    body,
  });
}

function fetchReq({ grant, accessToken = "client-access-token", gid = "group-1" } = {}) {
  const headers = {};
  if (grant) headers["x-relay-access-grant"] = encodeGrant(grant);
  if (accessToken) headers["x-relay-access-token"] = accessToken;
  return createMockReq({
    method: "GET",
    url: `/v1/events:fetch?gid=${encodeURIComponent(gid)}&limit=10`,
    headers,
    body: "",
  });
}

async function callRoute(router, req) {
  const res = createMockRes();
  const done = res.wait();
  await router.route(req, res);
  await done;
  return { res, json: JSON.parse(res.body) };
}

async function main() {
  const metrics = createMetricsStub();

  {
    const router = createRouter({
      config: baseConfig(),
      storage: createStorageStub(),
      metrics,
    });
    const { res, json } = await callRoute(router, ingestReq());
    assert(res.statusCode === 401, "missing grant should return 401");
    assert(json.error.code === "client_access_grant_required", "missing grant code should match");
    console.log("PASS client_access_grant_route_requires_grant");
    await router.shutdown();
  }

  {
    const router = createRouter({
      config: baseConfig(),
      storage: createStorageStub(),
      metrics,
    });
    const { res } = await callRoute(router, ingestReq({ grant: createGrant() }));
    assert(res.statusCode === 200, "valid grant should allow ingest");
    console.log("PASS client_access_grant_route_accepts_valid_grant");
    await router.shutdown();
  }

  {
    const router = createRouter({
      config: baseConfig(),
      storage: createStorageStub(),
      metrics,
    });
    const grant = createGrant({
      candidate_relays: [{ relay_id: "relay-b", endpoint: "http://relay-b:8080" }],
    });
    const { res, json } = await callRoute(router, ingestReq({ grant }));
    assert(res.statusCode === 403, "wrong relay should return 403");
    assert(json.error.code === "relay_not_authorized", "wrong relay code should match");
    console.log("PASS client_access_grant_route_rejects_wrong_relay");
    await router.shutdown();
  }

  {
    const router = createRouter({
      config: baseConfig(),
      storage: createStorageStub(),
      metrics,
    });
    const grant = createGrant({ expires_at: Math.floor(Date.now() / 1000) - 1 });
    const { res, json } = await callRoute(router, ingestReq({ grant }));
    assert(res.statusCode === 403, "expired grant should return 403");
    assert(json.error.code === "grant_expired", "expired grant code should match");
    console.log("PASS client_access_grant_route_rejects_expired_grant");
    await router.shutdown();
  }

  {
    const router = createRouter({
      config: baseConfig(),
      storage: createStorageStub(),
      metrics,
    });
    const grant = createGrant({ allowed_gids: ["group-2"] });
    const { res, json } = await callRoute(router, ingestReq({ grant }));
    assert(res.statusCode === 403, "out of scope gid should return 403");
    assert(json.error.code === "gid_not_allowed", "gid scope code should match");
    console.log("PASS client_access_grant_route_rejects_gid_out_of_scope");
    await router.shutdown();
  }

  {
    const router = createRouter({
      config: baseConfig(),
      storage: createStorageStub(),
      metrics,
    });
    const grant = createGrant({ permissions: ["ingest"] });
    const { res, json } = await callRoute(router, fetchReq({ grant }));
    assert(res.statusCode === 403, "disallowed fetch should return 403");
    assert(json.error.code === "operation_not_allowed", "fetch permission code should match");
    console.log("PASS client_access_grant_route_rejects_disallowed_operation");
    await router.shutdown();
  }

  {
    const router = createRouter({
      config: {
        ...baseConfig(),
        registryClientAccessGrantsEnabled: false,
      },
      storage: createStorageStub(),
      metrics,
    });
    const { res } = await callRoute(router, ingestReq());
    assert(res.statusCode === 200, "disabled grant enforcement should allow ingest");
    console.log("PASS client_access_grant_route_disabled_mode_allows_direct_ingest");
    await router.shutdown();
  }
}

main().catch((err) => {
  console.error("FAIL client_access_grant_route", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
