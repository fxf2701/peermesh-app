#!/usr/bin/env node

const { Readable } = require("stream");
const { createRouter } = require("../src/routes");
const { createClientRelaySessionManager } = require("../src/client_relay_sessions");
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
    ingestCalls: [],
    fetchCalls: [],
    async ingestEvents(events) {
      this.ingestCalls.push(events);
      return { accepted: events.map((event) => event.eid), rejected: [] };
    },
    async fetchEvents(query) {
      this.fetchCalls.push(query);
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

function createEvent(gid = "group-1") {
  const now = Math.floor(Date.now() / 1000);
  return {
    eid: `evt-${gid}`,
    gid,
    ts: now,
    exp: now + 3600,
    body: {
      v: 1,
      enc: "AES-256-GCM",
      kid: "gk1",
      cty: "peermesh/app-event+json",
      nonce: Buffer.from(`nonce-${gid}`).toString("base64url"),
      ct: Buffer.from(`ciphertext-${gid}`).toString("base64url"),
      tag: Buffer.from(`tag-${gid}`).toString("base64url"),
    },
    sig: "sig",
  };
}

async function callRoute(router, req) {
  const res = createMockRes();
  const done = res.wait();
  await router.route(req, res);
  await done;
  return { res, json: JSON.parse(res.body) };
}

function ingestReq({ grant, accessToken, sessionId, sessionToken, gid = "group-1" }) {
  const body = JSON.stringify({ events: [createEvent(gid)] });
  const headers = {
    "content-type": "application/json",
    "content-length": String(Buffer.byteLength(body)),
    "x-relay-access-grant": encodeGrant(grant),
    "x-relay-access-token": accessToken,
  };
  if (sessionId) headers["x-client-relay-session"] = sessionId;
  if (sessionToken) headers["x-client-relay-session-token"] = sessionToken;
  return createMockReq({
    method: "POST",
    url: "/v1/events:ingest",
    headers,
    body,
  });
}

function fetchReq({ grant, accessToken, sessionId, sessionToken, gid = "group-1" }) {
  const headers = {
    "x-relay-access-grant": encodeGrant(grant),
    "x-relay-access-token": accessToken,
  };
  if (sessionId) headers["x-client-relay-session"] = sessionId;
  if (sessionToken) headers["x-client-relay-session-token"] = sessionToken;
  return createMockReq({
    method: "GET",
    url: `/v1/events:fetch?gid=${encodeURIComponent(gid)}&limit=10`,
    headers,
    body: "",
  });
}

async function main() {
  const realDateNow = Date.now;
  let nowMs = 1_716_900_000_000;
  Date.now = () => nowMs;
  const config = baseConfig();
  const storage = createStorageStub();
  const clientRelaySessions = createClientRelaySessionManager({ config });
  const router = createRouter({
    config,
    storage,
    metrics: createMetricsStub(),
    clientRelaySessions,
  });
  const grant = createGrant();
  const opened = clientRelaySessions.open(
    {
      grant_id: grant.grant_id,
      access_token: grant.access_token,
      client_instance_id: grant.client_instance_id,
      relay_id: "relay-a",
      requested_gids: ["group-1"],
      requested_operations: ["fetch", "ingest"],
      client_nonce: "n1",
      client_session_material: { version: 1, mode: "session_binding" },
    },
    grant,
    Math.floor(Date.now() / 1000)
  );
  assert(opened.ok, "setup session open should succeed");

  try {
    const missingSession = await callRoute(
      router,
      ingestReq({
        grant,
        accessToken: grant.access_token,
      })
    );
    assert(missingSession.res.statusCode === 401, "missing session should return 401");
    assert(missingSession.json.error.code === "client_session_required", "missing session code should match");
    console.log("PASS managed_client_data_plane_requires_session");

    const missingGrant = await callRoute(
      router,
      createMockReq({
        method: "POST",
        url: "/v1/events:ingest",
        headers: {
          "content-type": "application/json",
          "content-length": String(Buffer.byteLength(JSON.stringify({ events: [createEvent("group-1")] }))),
          "x-relay-access-token": grant.access_token,
          "x-client-relay-session": opened.session.session_id,
          "x-client-relay-session-token": opened.session.session_binding_token,
        },
        body: JSON.stringify({ events: [createEvent("group-1")] }),
      })
    );
    assert(missingGrant.res.statusCode === 401, "missing grant should return 401");
    assert(missingGrant.json.error.code === "client_access_grant_required", "missing grant code should match");
    console.log("PASS managed_client_data_plane_rejects_missing_grant");

    const validIngest = await callRoute(
      router,
      ingestReq({
        grant,
        accessToken: grant.access_token,
        sessionId: opened.session.session_id,
        sessionToken: opened.session.session_binding_token,
      })
    );
    assert(validIngest.res.statusCode === 200, "valid ingest should succeed");
    console.log("PASS managed_client_data_plane_accepts_valid_ingest");

    const expiredGrant = await callRoute(
      router,
      ingestReq({
        grant: createGrant({ expires_at: Math.floor(Date.now() / 1000) - 1 }),
        accessToken: grant.access_token,
        sessionId: opened.session.session_id,
        sessionToken: opened.session.session_binding_token,
      })
    );
    assert(expiredGrant.res.statusCode === 403, "expired grant should return 403");
    assert(expiredGrant.json.error.code === "grant_expired", "expired grant code should match");
    console.log("PASS managed_client_data_plane_rejects_expired_grant");

    const wrongRelayGrant = await callRoute(
      router,
      fetchReq({
        grant: createGrant({
          candidate_relays: [{ relay_id: "relay-b", endpoint: "http://relay-b:8080" }],
        }),
        accessToken: grant.access_token,
        sessionId: opened.session.session_id,
        sessionToken: opened.session.session_binding_token,
      })
    );
    assert(wrongRelayGrant.res.statusCode === 403, "wrong relay grant should return 403");
    assert(wrongRelayGrant.json.error.code === "relay_not_authorized", "wrong relay grant code should match");
    console.log("PASS managed_client_data_plane_rejects_wrong_relay_grant");

    const outOfScopeGrant = await callRoute(
      router,
      fetchReq({
        grant: createGrant({ allowed_gids: ["group-9"] }),
        accessToken: grant.access_token,
        sessionId: opened.session.session_id,
        sessionToken: opened.session.session_binding_token,
      })
    );
    assert(outOfScopeGrant.res.statusCode === 403, "out of scope grant should return 403");
    assert(outOfScopeGrant.json.error.code === "gid_not_allowed", "out of scope grant code should match");
    console.log("PASS managed_client_data_plane_rejects_out_of_scope_grant");

    const wrongToken = await callRoute(
      router,
      fetchReq({
        grant,
        accessToken: grant.access_token,
        sessionId: opened.session.session_id,
        sessionToken: `${opened.session.session_binding_token}x`,
      })
    );
    assert(wrongToken.res.statusCode === 403, "wrong session token should return 403");
    assert(wrongToken.json.error.code === "client_session_token_mismatch", "wrong session token code should match");
    console.log("PASS managed_client_data_plane_rejects_wrong_session_token");

    const outOfScopeSession = await callRoute(
      router,
      ingestReq({
        grant,
        accessToken: grant.access_token,
        sessionId: opened.session.session_id,
        sessionToken: opened.session.session_binding_token,
        gid: "group-2",
      })
    );
    assert(outOfScopeSession.res.statusCode === 403, "session gid mismatch should return 403");
    assert(outOfScopeSession.json.error.code === "gid_not_allowed", "session gid mismatch code should match");
    console.log("PASS managed_client_data_plane_rejects_session_scope_mismatch");

    const mismatchedGrant = await callRoute(
      router,
      fetchReq({
        grant: createGrant({
          grant_id: "cg_other",
          access_token: "registry-access-token-other",
        }),
        accessToken: "registry-access-token-other",
        sessionId: opened.session.session_id,
        sessionToken: opened.session.session_binding_token,
      })
    );
    assert(mismatchedGrant.res.statusCode === 403, "grant mismatch should return 403");
    assert(mismatchedGrant.json.error.code === "grant_mismatch", "grant mismatch code should match");
    console.log("PASS managed_client_data_plane_rejects_grant_session_mismatch");

    nowMs += 121000;
    const expiredSession = await callRoute(
      router,
      fetchReq({
        grant,
        accessToken: grant.access_token,
        sessionId: opened.session.session_id,
        sessionToken: opened.session.session_binding_token,
      })
    );
    assert(expiredSession.res.statusCode === 403, "expired session should return 403");
    assert(expiredSession.json.error.code === "client_session_expired", "expired session code should match");
    console.log("PASS managed_client_data_plane_rejects_expired_session");

    assert(storage.ingestCalls.length === 1, "only valid ingest should reach storage");
  } finally {
    Date.now = realDateNow;
    await router.shutdown();
    clientRelaySessions.shutdown();
  }
}

main().catch((err) => {
  console.error("FAIL managed_client_data_plane_route", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
