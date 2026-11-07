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

function baseConfig(relayId) {
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
    federationSelfId: relayId,
    relaySelfId: relayId,
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
      { relay_id: "relay-b", endpoint: "http://relay-b:8081", trust_level: "trusted", region: "sg" },
    ],
    allowed_gids: ["group-1"],
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

async function callRoute(router, req) {
  const res = createMockRes();
  const done = res.wait();
  await router.route(req, res);
  await done;
  return { res, json: JSON.parse(res.body) };
}

function openReq({ relayId, grant }) {
  const bodyObject = {
    grant_id: grant.grant_id,
    access_token: grant.access_token,
    client_instance_id: grant.client_instance_id,
    relay_id: relayId,
    requested_gids: ["group-1"],
    requested_operations: ["fetch"],
    client_nonce: `nonce-${relayId}`,
    client_session_material: { version: 1, mode: "session_binding" },
  };
  const body = JSON.stringify(bodyObject);
  return createMockReq({
    method: "POST",
    url: "/v1/client/session:open",
    headers: {
      "content-type": "application/json",
      "content-length": String(Buffer.byteLength(body)),
      "x-relay-access-grant": encodeGrant(grant),
    },
    body,
  });
}

function createRelayHarness(relayId, { sessionsEnabled = true } = {}) {
  const config = {
    ...baseConfig(relayId),
    clientRelaySessionsEnabled: sessionsEnabled,
  };
  const clientRelaySessions = createClientRelaySessionManager({ config });
  const router = createRouter({
    config,
    storage: createStorageStub(),
    metrics: createMetricsStub(),
    clientRelaySessions,
  });
  return {
    relayId,
    router,
    clientRelaySessions,
    attempts: 0,
    async open(grant) {
      this.attempts += 1;
      return callRoute(router, openReq({ relayId, grant }));
    },
    async shutdown() {
      await router.shutdown();
      clientRelaySessions.shutdown();
    },
  };
}

async function openAcrossCandidates(grant, relayMap, { blocked = new Set() } = {}) {
  const attempts = [];
  for (const candidate of grant.candidate_relays || []) {
    const relayId = String(candidate.relay_id || "");
    if (!relayId || blocked.has(relayId)) {
      attempts.push({ relay_id: relayId, skipped: true, reason: "blocked" });
      continue;
    }
    const relay = relayMap[relayId];
    if (!relay) {
      attempts.push({ relay_id: relayId, skipped: true, reason: "missing_router" });
      continue;
    }
    const result = await relay.open(grant);
    attempts.push({
      relay_id: relayId,
      status: result.res.statusCode,
      code: result.json?.error?.code || null,
      ok: Boolean(result.json?.ok),
    });
    if (result.res.statusCode >= 200 && result.res.statusCode < 300 && result.json?.ok) {
      return { ok: true, relay_id: relayId, session: result.json.session, attempts };
    }
  }
  return { ok: false, attempts };
}

async function main() {
  const realDateNow = Date.now;
  let nowMs = 1_716_900_000_000;
  Date.now = () => nowMs;

  const relayA = createRelayHarness("relay-a");
  const relayB = createRelayHarness("relay-b");
  const relayRejected = createRelayHarness("relay-a", { sessionsEnabled: false });

  try {
    const localBlockGrant = createGrant();
    const localBlockResult = await openAcrossCandidates(
      localBlockGrant,
      { "relay-a": relayA, "relay-b": relayB },
      { blocked: new Set(["relay-a"]) }
    );
    assert(localBlockResult.ok, "local block failover should succeed on another authorized relay");
    assert(localBlockResult.relay_id === "relay-b", "local block should skip to relay-b");
    assert(localBlockResult.attempts[0].reason === "blocked", "first candidate should be locally blocked");
    console.log("PASS managed_client_failover_skips_blocked_relay");

    const rejectionGrant = createGrant();
    const rejectionResult = await openAcrossCandidates(rejectionGrant, {
      "relay-a": relayRejected,
      "relay-b": relayB,
    });
    assert(rejectionResult.ok, "failover after session open rejection should succeed");
    assert(rejectionResult.relay_id === "relay-b", "second authorized relay should accept");
    assert(
      rejectionResult.attempts.some((item) => item.relay_id === "relay-a" && item.status === 404),
      "first relay should reject session open"
    );
    console.log("PASS managed_client_failover_after_session_open_rejection");

    const expiringGrant = createGrant({ expires_at: Math.floor(Date.now() / 1000) + 30 });
    const firstFailure = await openAcrossCandidates(expiringGrant, {
      "relay-a": relayRejected,
      "relay-b": relayB,
    });
    assert(firstFailure.ok, "pre-expiry fallback should still work");
    nowMs += 31_000;
    const expiredResult = await openAcrossCandidates(expiringGrant, {
      "relay-a": relayRejected,
      "relay-b": relayB,
    });
    assert(!expiredResult.ok, "expired grant should fail failover");
    assert(
      expiredResult.attempts.some((item) => item.relay_id === "relay-b" && item.code === "grant_expired"),
      "authorized failover relay should reject expired grant"
    );
    console.log("PASS managed_client_failover_rejects_expired_grant");
  } finally {
    Date.now = realDateNow;
    await relayA.shutdown();
    await relayB.shutdown();
    await relayRejected.shutdown();
  }
}

main().catch((err) => {
  console.error("FAIL managed_client_failover", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
