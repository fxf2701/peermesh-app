#!/usr/bin/env node

const { Readable } = require("stream");
const { createHmac, createHash } = require("crypto");
const { createRouter } = require("../src/routes");
const { createClientRelaySessionManager } = require("../src/client_relay_sessions");
const { signClientAccessGrant } = require("../src/client_access_grants");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function sha256Hex(value) {
  return createHash("sha256").update(value || "").digest("hex");
}

function signRequest({ secret, method, path, body, timestamp }) {
  const canonical = [String(method).toUpperCase(), path, String(timestamp), sha256Hex(body)].join("\n");
  return createHmac("sha256", secret).update(canonical).digest("hex");
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
    requestSignatureRequired: true,
    requestSignatureSecret: "test-signature-secret",
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
    clientRelaySessionsEnabled: true,
    clientRelaySessionBindingSecret: "relay-session-secret",
    clientRelaySessionTtlSeconds: 120,
    clientRelaySessionCleanupIntervalSeconds: 0,
    clientRelaySessionMaxPerClient: 8,
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

function createGrant(overrides = {}) {
  const grant = {
    grant_id: "cg_test",
    issued_at: Math.floor(Date.now() / 1000),
    expires_at: Math.floor(Date.now() / 1000) + 300,
    policy_version: 1,
    client_instance_id: "android-device-01",
    candidate_relays: [
      { relay_id: "relay-a", endpoint: "http://relay-a:8080", trust_level: "trusted", region: "vn-south" },
    ],
    allowed_gids: ["group-1", "group-2"],
    permissions: ["ingest", "fetch"],
    limits: { max_events_per_minute: 120, max_bytes_per_minute: 1048576 },
    access_token: "registry-access-token",
    connection_mode: "client_selects_and_relay_establishes_session",
    ...overrides,
  };
  grant.registry_signature = signClientAccessGrant("registry-client-grant-secret", grant);
  return grant;
}

function encodeGrant(grant) {
  return Buffer.from(JSON.stringify(grant)).toString("base64url");
}

function signedPost({ path, bodyObject, grant, includeSignature = true }) {
  const body = JSON.stringify(bodyObject || {});
  const headers = {
    "content-type": "application/json",
    "content-length": String(Buffer.byteLength(body)),
  };
  if (grant) headers["x-relay-access-grant"] = encodeGrant(grant);
  if (includeSignature) {
    const timestamp = String(Math.floor(Date.now() / 1000));
    headers["x-relay-timestamp"] = timestamp;
    headers["x-relay-signature"] = signRequest({
      secret: "test-signature-secret",
      method: "POST",
      path,
      body,
      timestamp,
    });
  }
  return createMockReq({
    method: "POST",
    url: path,
    headers,
    body,
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
  const config = baseConfig();
  const clientRelaySessions = createClientRelaySessionManager({ config });
  const router = createRouter({
    config,
    storage: createStorageStub(),
    metrics,
    clientRelaySessions,
  });

  try {
    const managedUnsignedOpen = await callRoute(
      router,
      signedPost({
        path: "/v1/client/session:open",
        bodyObject: {
          grant_id: "cg_test",
          access_token: "registry-access-token",
          client_instance_id: "android-device-01",
          relay_id: "relay-a",
          requested_gids: ["group-1"],
          requested_operations: ["fetch"],
          client_nonce: "n1",
          client_session_material: { version: 1, mode: "session_binding" },
        },
        grant: createGrant(),
        includeSignature: false,
      })
    );
    assert(managedUnsignedOpen.res.statusCode === 201, "managed open should not require relay signature");
    assert(managedUnsignedOpen.json.ok === true, "managed open should succeed without relay signature");
    console.log("PASS client_relay_session_route_managed_open_without_signature");
    const unsignedSessionId = managedUnsignedOpen.json.session.session_id;

    const unsignedRenew = await callRoute(
      router,
      createMockReq({
        method: "POST",
        url: `/v1/client/session/${encodeURIComponent(unsignedSessionId)}:renew`,
        headers: {
          "content-type": "application/json",
          "content-length": String(
            Buffer.byteLength(
              JSON.stringify({
                access_token: "registry-access-token",
                client_instance_id: "android-device-01",
                requested_gids: ["group-1", "group-2"],
                requested_operations: ["ingest", "fetch"],
                client_session_material: { version: 1, mode: "session_binding" },
              })
            )
          ),
          "x-relay-access-grant": encodeGrant(createGrant()),
          "x-client-relay-session": unsignedSessionId,
          "x-client-relay-session-token": managedUnsignedOpen.json.session.session_binding_token,
        },
        body: JSON.stringify({
          access_token: "registry-access-token",
          client_instance_id: "android-device-01",
          requested_gids: ["group-1", "group-2"],
          requested_operations: ["ingest", "fetch"],
          client_session_material: { version: 1, mode: "session_binding" },
        }),
      })
    );
    assert(unsignedRenew.res.statusCode === 200, "managed renew should not require relay signature");
    console.log("PASS client_relay_session_route_managed_renew_without_signature");

    const unsignedClose = await callRoute(
      router,
      createMockReq({
        method: "POST",
        url: `/v1/client/session/${encodeURIComponent(unsignedSessionId)}:close`,
        headers: {
          "content-type": "application/json",
          "content-length": String(Buffer.byteLength(JSON.stringify({ client_instance_id: "android-device-01" }))),
          "x-client-relay-session": unsignedSessionId,
          "x-client-relay-session-token": unsignedRenew.json.session.session_binding_token,
        },
        body: JSON.stringify({
          client_instance_id: "android-device-01",
        }),
      })
    );
    assert(unsignedClose.res.statusCode === 200, "managed close should not require relay signature");
    console.log("PASS client_relay_session_route_managed_close_without_signature");

    const opened = await callRoute(
      router,
      signedPost({
        path: "/v1/client/session:open",
        bodyObject: {
          grant_id: "cg_test",
          access_token: "registry-access-token",
          client_instance_id: "android-device-01",
          relay_id: "relay-a",
          requested_gids: ["group-1"],
          requested_operations: ["fetch"],
          client_nonce: "n2",
          client_session_material: { version: 1, mode: "session_binding" },
        },
        grant: createGrant(),
      })
    );
    assert(opened.res.statusCode === 201, "session open should return 201");
    assert(opened.json.ok === true, "session open should succeed");
    const sessionId = opened.json.session.session_id;
    assert(sessionId.startsWith("crs_"), "session id should be returned");
    console.log("PASS client_relay_session_route_open");

    const renewed = await callRoute(
      router,
      signedPost({
        path: `/v1/client/session/${encodeURIComponent(sessionId)}:renew`,
        bodyObject: {
          access_token: "registry-access-token",
          client_instance_id: "android-device-01",
          requested_gids: ["group-1", "group-2"],
          requested_operations: ["ingest", "fetch"],
          client_session_material: { version: 1, mode: "session_binding" },
        },
        grant: createGrant(),
      })
    );
    assert(renewed.res.statusCode === 200, "session renew should return 200");
    assert(renewed.json.session.session_id === sessionId, "renew should preserve session id");
    console.log("PASS client_relay_session_route_renew");

    const closed = await callRoute(
      router,
      signedPost({
        path: `/v1/client/session/${encodeURIComponent(sessionId)}:close`,
        bodyObject: {
          client_instance_id: "android-device-01",
        },
        grant: null,
      })
    );
    assert(closed.res.statusCode === 200, "session close should return 200");
    assert(closed.json.ok === true, "session close should succeed");
    console.log("PASS client_relay_session_route_close");

    const renewMissingGrant = await callRoute(
      router,
      signedPost({
        path: `/v1/client/session/${encodeURIComponent(sessionId)}:renew`,
        bodyObject: {
          access_token: "registry-access-token",
          client_instance_id: "android-device-01",
        },
        grant: null,
      })
    );
    assert(renewMissingGrant.res.statusCode === 401, "renew without grant should return 401");
    assert(
      renewMissingGrant.json.error.code === "client_access_grant_required",
      "renew without grant code should match"
    );
    console.log("PASS client_relay_session_route_requires_grant_for_renew");
  } finally {
    await router.shutdown();
    clientRelaySessions.shutdown();
  }
}

main().catch((err) => {
  console.error("FAIL client_relay_session_routes", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
