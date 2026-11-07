#!/usr/bin/env node

const { Readable } = require("stream");
const { createRouter } = require("../src/routes");
const { createFederationSync } = require("../src/federation");

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
    federationSelfUrl: "http://relay-a:8080",
    federationPeers: [],
    federationSyncPeers: [],
    federationSyncGids: ["group-1"],
    federationSyncIntervalSeconds: 30,
    federationSyncMaxBackoffSeconds: 120,
    federationSyncJitterSeconds: 0,
    federationSyncMaxInFlightPerPeer: 1,
    federationSyncMaxQueuePerPeer: 0,
    federationHandshakeIntervalSeconds: 60,
    federationHeartbeatTtlSeconds: 120,
    federationHeartbeatFailureThreshold: 3,
    federationHeartbeatBackoffSeconds: 30,
    federationConnectionCapacity: 32,
    federationMaxPeers: 0,
    federationMtlsEnabled: false,
    registryFederationSessionsEnabled: false,
    registryFederationSessionSigningSecret: "registry-session-secret",
    registryClientAccessGrantsEnabled: false,
    clientRelaySessionsEnabled: false,
    cleanupIntervalSeconds: 0,
    enableTestDbErrors: false,
    auditLogEnabled: false,
  };
}

function quarantinedMapping() {
  return {
    id: 1,
    peer_url: "http://relay-b:8080",
    peer_token: "peer-token",
    rotated_token: "rotated-token",
    rotated_expires_at: new Date(Date.now() + 60_000).toISOString(),
    sync_gids: ["group-1"],
    enabled: true,
    auto_sync: true,
    peer_state: "quarantined",
    quarantine_reason: "manual review",
    quarantined_until: new Date(Date.now() + 60_000).toISOString(),
    self_id: "relay-b",
    joined_at: new Date().toISOString(),
    last_seen_at: new Date().toISOString(),
  };
}

function createStorageStub() {
  const mapping = quarantinedMapping();
  return {
    async getPeerMappingBySelfId(selfId) {
      return selfId === "relay-b" ? mapping : null;
    },
    async getPeerMappingByUrl(peerUrl) {
      return peerUrl === "http://relay-b:8080" ? mapping : null;
    },
    async listPeerMappings() {
      return [mapping];
    },
    async fetchEvents() {
      return { events: [], next_cursor: null };
    },
    async ingestEvents() {
      return { accepted: [], rejected: [] };
    },
    async countActivePeerConnections() {
      return 0;
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

async function testQuarantinedPeerStatsRoute() {
  const router = createRouter({
    config: baseConfig(),
    storage: createStorageStub(),
    metrics: createMetricsStub(),
  });
  try {
    const req = createMockReq({
      method: "GET",
      url: "/v1/peers:stats",
      headers: {
        "x-relay-peer": "1",
        "x-relay-from": "relay-b",
        "x-relay-peer-token": "rotated-token",
      },
      body: "",
    });
    const { res, json } = await callRoute(router, req);
    assert(res.statusCode === 403, "quarantined peer stats should be rejected");
    assert(json.error.code === "peer_quarantined", "quarantined peer should return peer_quarantined");
    console.log("PASS peer_quarantine_blocks_peer_stats");
  } finally {
    await router.shutdown();
  }
}

async function testQuarantinedPeerIngestRoute() {
  const router = createRouter({
    config: baseConfig(),
    storage: createStorageStub(),
    metrics: createMetricsStub(),
  });
  try {
    const body = JSON.stringify({
      events: [
        {
          eid: "11111111-1111-4111-8111-111111111111",
          gid: "group-1",
          ts: Math.floor(Date.now() / 1000),
          exp: Math.floor(Date.now() / 1000) + 300,
          body: { type: "CHAT.MSG" },
          sig: "sig",
        },
      ],
    });
    const req = createMockReq({
      method: "POST",
      url: "/v1/events:ingest",
      headers: {
        "content-type": "application/json",
        "content-length": String(Buffer.byteLength(body)),
        "x-relay-peer": "1",
        "x-relay-from": "relay-b",
        "x-relay-peer-token": "rotated-token",
      },
      body,
    });
    const { res, json } = await callRoute(router, req);
    assert(res.statusCode === 403, "quarantined peer ingest should be rejected");
    assert(json.error.code === "peer_quarantined", "quarantined peer ingest should return peer_quarantined");
    console.log("PASS peer_quarantine_blocks_peer_ingest");
  } finally {
    await router.shutdown();
  }
}

async function testQuarantinedPeerResyncSkipped() {
  const storage = createStorageStub();
  const federationSync = createFederationSync({
    config: baseConfig(),
    storage,
    metrics: createMetricsStub(),
    cooldown: null,
    adminState: null,
    registrySessions: null,
  });
  const ok = await federationSync.triggerResync("http://relay-b:8080");
  assert(ok === false, "triggerResync should skip quarantined peer");
  federationSync.stop();
  console.log("PASS peer_quarantine_skips_trigger_resync");
}

async function main() {
  await testQuarantinedPeerStatsRoute();
  await testQuarantinedPeerIngestRoute();
  await testQuarantinedPeerResyncSkipped();
}

main().catch((err) => {
  console.error("FAIL peer_quarantine_state", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
