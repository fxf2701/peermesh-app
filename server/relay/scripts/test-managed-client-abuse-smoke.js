#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const { spawn } = require("child_process");
const { Readable } = require("stream");
const { createServer } = require("http");
const { createRouter } = require("../src/routes");
const { createClientRelaySessionManager } = require("../src/client_relay_sessions");
const { signClientAccessGrant } = require("../src/client_access_grants");

const TEST_ADMIN_PASSWORD_HASH =
  "scrypt$16384$8$1$c880d2df7323d9fdfc95083fd301508c$2b4a5142d0b35d012e7df68ae6e8f368d81bf48af40f8cb594fe7ad379cd9ca2";

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForHealth(baseUrl, child = null, attempts = 60) {
  for (let index = 0; index < attempts; index += 1) {
    if (child && child.exitCode !== null) {
      throw new Error(`service exited early with code ${child.exitCode}`);
    }
    try {
      const response = await fetch(`${baseUrl}/healthz`);
      if (response.ok) return;
    } catch (_) {
      // startup race
    }
    await wait(100);
  }
  throw new Error(`service ${baseUrl} did not become healthy`);
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

function baseRelayConfig(relayId) {
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
    registryClientAccessGrantSigningSecret: "test-client-grant-secret",
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

function createRelayHarness(relayId) {
  const config = baseRelayConfig(relayId);
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
      const req = createMockReq({
        method: "POST",
        url: "/v1/client/session:open",
        headers: {
          "content-type": "application/json",
          "content-length": String(Buffer.byteLength(body)),
          "x-relay-access-grant": Buffer.from(JSON.stringify(grant)).toString("base64url"),
        },
        body,
      });
      const res = createMockRes();
      const done = res.wait();
      await router.route(req, res);
      await done;
      return { res, json: JSON.parse(res.body) };
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

async function api(baseUrl, pathName, { method = "GET", body } = {}) {
  const response = await fetch(`${baseUrl}${pathName}`, {
    method,
    headers: {
      "Content-Type": "application/json",
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await response.json().catch(() => ({}));
  return { response, payload };
}

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "managed-client-abuse-smoke-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const registryPort = 22000 + Math.floor(Math.random() * 1000);
  const registryBaseUrl = `http://127.0.0.1:${registryPort}`;
  const registryChild = spawn(process.execPath, ["src/index.js"], {
    cwd: path.join(__dirname, "..", "..", "registry"),
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(registryPort),
      REGISTRY_DB_PATH: dbPath,
      REGISTRY_SHARED_SECRET: "test-registry-secret",
      REGISTRY_REQUIRE_SIGNATURE: "0",
      REGISTRY_ADMIN_USERNAME: "admin",
      REGISTRY_ADMIN_PASSWORD_HASH: TEST_ADMIN_PASSWORD_HASH,
      REGISTRY_CLIENT_ACCESS_GRANT_SIGNING_SECRET: "test-client-grant-secret",
      REGISTRY_CLIENT_ACCESS_MAX_CANDIDATES: "2",
      REGISTRY_CLIENT_ACCESS_GRANT_TTL_SECONDS: "300",
      REGISTRY_CLIENT_ACCESS_MIN_TRUST_LEVEL: "community",
      REGISTRY_CLIENT_ABUSE_PAIR_COOLDOWN_SECONDS: "1800",
      REGISTRY_CLIENT_ABUSE_DEPRIORITIZE_WINDOW_SECONDS: "3600",
      REGISTRY_CLIENT_ABUSE_SINGLE_REPORT_WEIGHT: "1",
      REGISTRY_CLIENT_ABUSE_DISTINCT_CLIENT_BONUS: "2",
      REGISTRY_CLIENT_ABUSE_CORROBORATION_CLIENT_THRESHOLD: "2",
      REGISTRY_CLIENT_ABUSE_CORROBORATION_SCORE_THRESHOLD: "3",
    },
    stdio: "ignore",
  });

  const relayA = createRelayHarness("relay-a");
  const relayB = createRelayHarness("relay-b");

  try {
    await waitForHealth(registryBaseUrl, registryChild);

    for (const relay of [
      { relayId: relayA.relayId, endpoint: "http://relay-a.test", region: "vn-south" },
      { relayId: relayB.relayId, endpoint: "http://relay-b.test", region: "sg" },
    ]) {
      const heartbeat = await api(registryBaseUrl, "/v1/registry/relays:heartbeat", {
        method: "POST",
        body: {
          relay_id: relay.relayId,
          public_key: `${relay.relayId}-public-key`,
          endpoint: relay.endpoint,
          region: relay.region,
          capabilities: ["store_forward"],
          version: "test",
        },
      });
      assert(heartbeat.response.status === 200, `heartbeat should succeed for ${relay.relayId}`);
    }

    const grantInitial = await api(registryBaseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "android-device-01",
        requested_gids: ["group-1"],
        requested_operations: ["fetch"],
        network_hints: { region: "vn-south", transport: "http" },
      },
    });
    assert(grantInitial.response.status === 201, "initial grant should succeed");
    const initialGrant = grantInitial.payload.grant;
    assert(
      initialGrant.registry_signature === signClientAccessGrant("test-client-grant-secret", initialGrant),
      "initial grant signature should validate"
    );
    assert(
      initialGrant.candidate_relays.length === 2 &&
        initialGrant.candidate_relays[0].relay_id === "relay-a" &&
        initialGrant.candidate_relays[1].relay_id === "relay-b",
      "initial grant should prioritize relay-a then relay-b"
    );
    console.log("PASS managed_client_abuse_smoke_initial_grant");

    const localBlockResult = await openAcrossCandidates(initialGrant, {
      "relay-a": relayA,
      "relay-b": relayB,
    }, { blocked: new Set(["relay-a"]) });
    assert(localBlockResult.ok, "client should fail over to relay-b when relay-a is locally blocked");
    assert(localBlockResult.relay_id === "relay-b", "blocked client should open session on relay-b");
    assert(localBlockResult.attempts[0].reason === "blocked", "first candidate should be skipped by local block");
    console.log("PASS managed_client_abuse_smoke_local_block");

    const report = await api(registryBaseUrl, "/v1/registry/client/reports", {
      method: "POST",
      body: {
        grant_id: initialGrant.grant_id,
        relay_id: "relay-a",
        client_instance_id: initialGrant.client_instance_id,
        reason: "invalid_session_behavior",
        timestamp: Math.floor(Date.now() / 1000),
        counters: {
          rejected_frames: 1,
        },
      },
    });
    assert(report.response.status === 201, "client abuse report should be accepted");
    assert(report.payload.selection_effect.blocked_for_client === true, "report should activate pair cooldown");
    console.log("PASS managed_client_abuse_smoke_report_accept");

    const grantAfterReport = await api(registryBaseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "android-device-01",
        requested_gids: ["group-1"],
        requested_operations: ["fetch"],
        network_hints: { region: "vn-south", transport: "http" },
      },
    });
    assert(grantAfterReport.response.status === 201, "grant after report should succeed");
    const sameClientGrant = grantAfterReport.payload.grant;
    assert(
      sameClientGrant.candidate_relays.length === 1 &&
        sameClientGrant.candidate_relays[0].relay_id === "relay-b",
      "same client should no longer receive relay-a during pair cooldown"
    );
    console.log("PASS managed_client_abuse_smoke_pair_cooldown");

    const otherClientGrant = await api(registryBaseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "android-device-02",
        requested_gids: ["group-1"],
        requested_operations: ["fetch"],
        network_hints: { region: "vn-south", transport: "http" },
      },
    });
    assert(otherClientGrant.response.status === 201, "other client grant should succeed");
    assert(
      otherClientGrant.payload.grant.candidate_relays.some((item) => item.relay_id === "relay-a"),
      "single report should not suppress relay-a globally"
    );
    console.log("PASS managed_client_abuse_smoke_single_report_not_global");
  } finally {
    await relayA.shutdown();
    await relayB.shutdown();
    registryChild.kill("SIGTERM");
    await new Promise((resolve) => registryChild.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL managed_client_abuse_smoke", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
