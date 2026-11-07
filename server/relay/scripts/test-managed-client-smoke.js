#!/usr/bin/env node

const { createServer } = require("http");
const { createRouter } = require("../src/routes");
const { createClientRelaySessionManager } = require("../src/client_relay_sessions");
const { signClientAccessGrant } = require("../src/client_access_grants");
const crypto = require("crypto");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForHealth(baseUrl, child = null, attempts = 60) {
  for (let i = 0; i < attempts; i += 1) {
    if (child && child.exitCode !== null) {
      throw new Error(`service exited early with code ${child.exitCode}`);
    }
    try {
      const res = await fetch(`${baseUrl}/healthz`);
      if (res.ok) return;
    } catch (_) {
      // startup race
    }
    await wait(100);
  }
  throw new Error(`service ${baseUrl} did not become healthy`);
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

function bodyHashHex(rawBody) {
  return crypto.createHash("sha256").update(rawBody || "").digest("hex");
}

function signRelayRequest({ secret, method, pathName, rawBody }) {
  const timestamp = String(Math.floor(Date.now() / 1000));
  const canonical = [String(method).toUpperCase(), pathName, timestamp, bodyHashHex(rawBody)].join("\n");
  const signature = crypto.createHmac("sha256", secret).update(canonical).digest("hex");
  return {
    "X-Relay-Timestamp": timestamp,
    "X-Relay-Signature": signature,
  };
}

function createInMemoryStorage() {
  const events = [];
  const fetchCalls = [];
  return {
    events,
    fetchCalls,
    async ingestEvents(items) {
      for (const event of items) {
        if (!events.some((existing) => existing.eid === event.eid)) {
          events.push({ ...event });
        }
      }
      events.sort((a, b) => (a.ts - b.ts) || a.eid.localeCompare(b.eid));
      return { accepted: items.map((item) => item.eid), rejected: [] };
    },
    async fetchEvents({ gid, cursorDecoded, limit }) {
      fetchCalls.push({ gid, cursorDecoded, limit, now: Math.floor(Date.now() / 1000) });
      let filtered = events.filter((item) => item.gid === gid && item.exp > Math.floor(Date.now() / 1000));
      if (cursorDecoded) {
        filtered = filtered.filter((item) => item.ts > cursorDecoded.ts || (item.ts === cursorDecoded.ts && item.eid > cursorDecoded.eid));
      }
      const effectiveLimit = Number.isFinite(Number(limit)) && Number(limit) > 0 ? Number(limit) : filtered.length;
      const selected = filtered.slice(0, effectiveLimit);
      const last = selected[selected.length - 1];
      const nextCursor = last
        ? Buffer.from(JSON.stringify({ ts: last.ts, eid: last.eid })).toString("base64url")
        : null;
      return { events: selected, next_cursor: nextCursor };
    },
    async healthCheck() {},
    async close() {},
  };
}

function relayConfig({ relayId, port, requestSignatureSecret }) {
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
    requestSignatureSecret,
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
    port,
  };
}

function startRelayHarness({ relayId, port, requestSignatureSecret }) {
  const config = relayConfig({ relayId, port, requestSignatureSecret });
  const storage = createInMemoryStorage();
  const clientRelaySessions = createClientRelaySessionManager({ config });
  const router = createRouter({
    config,
    storage,
    metrics: createMetricsStub(),
    clientRelaySessions,
  });
  const server = createServer((req, res) => router.route(req, res));
  return {
    relayId,
    baseUrl: `http://127.0.0.1:${port}`,
    storage,
    clientRelaySessions,
    router,
    server,
    start() {
      return new Promise((resolve) => server.listen(port, "127.0.0.1", resolve));
    },
    async stop() {
      await new Promise((resolve) => server.close(resolve));
      await router.shutdown();
      clientRelaySessions.shutdown();
    },
  };
}

function createRegistryStub({ port }) {
  const relays = new Map();
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, `http://127.0.0.1:${port}`);
    if (req.method === "GET" && url.pathname === "/healthz") {
      res.statusCode = 200;
      res.setHeader("Content-Type", "application/json");
      return res.end(JSON.stringify({ status: "ok" }));
    }

    let body = "";
    for await (const chunk of req) body += chunk;
    let json = {};
    try {
      json = body ? JSON.parse(body) : {};
    } catch (_) {
      json = {};
    }

    if (req.method === "POST" && url.pathname === "/v1/registry/relays:heartbeat") {
      relays.set(String(json.relay_id || ""), {
        relay_id: String(json.relay_id || ""),
        endpoint: String(json.endpoint || ""),
        region: json.region || null,
        capabilities: Array.isArray(json.capabilities) ? json.capabilities.slice() : [],
        last_seen: Math.floor(Date.now() / 1000),
      });
      res.statusCode = 200;
      res.setHeader("Content-Type", "application/json");
      return res.end(JSON.stringify({ ok: true }));
    }

    if (req.method === "POST" && url.pathname === "/v1/registry/client/sessions") {
      const requestedTransport = json.network_hints && typeof json.network_hints.transport === "string"
        ? json.network_hints.transport
        : null;
      const requestedRegion = json.network_hints && typeof json.network_hints.region === "string"
        ? json.network_hints.region
        : null;
      const candidates = [...relays.values()]
        .filter((item) => item.capabilities.includes("store_forward"))
        .filter((item) => !requestedTransport || String(item.endpoint).startsWith(`${requestedTransport}://`))
        .sort((a, b) => {
          const aRegion = requestedRegion && a.region === requestedRegion ? 1 : 0;
          const bRegion = requestedRegion && b.region === requestedRegion ? 1 : 0;
          if (aRegion !== bRegion) return bRegion - aRegion;
          return String(a.relay_id).localeCompare(String(b.relay_id));
        })
        .slice(0, 2)
        .map((item) => ({
          relay_id: item.relay_id,
          endpoint: item.endpoint,
          trust_level: "trusted",
          region: item.region,
        }));

      const grant = {
        grant_id: `cg_${crypto.randomUUID()}`,
        issued_at: Math.floor(Date.now() / 1000),
        expires_at: Math.floor(Date.now() / 1000) + 300,
        policy_version: 1,
        client_instance_id: String(json.client_instance_id || ""),
        candidate_relays: candidates,
        allowed_gids: Array.isArray(json.requested_gids) ? json.requested_gids.slice() : [],
        permissions: Array.isArray(json.requested_operations) ? json.requested_operations.slice() : [],
        limits: {
          max_events_per_minute: 120,
          max_bytes_per_minute: 1048576,
        },
        access_token: "registry-access-token",
        connection_mode: "client_selects_and_relay_establishes_session",
      };
      grant.registry_signature = signClientAccessGrant("test-client-grant-secret", grant);
      res.statusCode = 201;
      res.setHeader("Content-Type", "application/json");
      return res.end(JSON.stringify({ ok: true, grant }));
    }

    res.statusCode = 404;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ error: { code: "not_found" } }));
  });

  return {
    baseUrl: `http://127.0.0.1:${port}`,
    start() {
      return new Promise((resolve) => server.listen(port, "127.0.0.1", resolve));
    },
    async stop() {
      await new Promise((resolve) => server.close(resolve));
    },
  };
}

async function requestJson(baseUrl, pathName, { method = "GET", body, headers = {} } = {}) {
  const url = new URL(pathName, baseUrl);
  const rawBody = body ? JSON.stringify(body) : "";
  const response = await fetch(url, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(rawBody ? { "Content-Length": String(Buffer.byteLength(rawBody)) } : {}),
      ...headers,
    },
    body: rawBody || undefined,
  });
  const text = await response.text();
  let payload = {};
  try {
    payload = text ? JSON.parse(text) : {};
  } catch (_) {
    payload = {};
  }
  return { response, payload, text };
}

async function registryApi(baseUrl, pathName, { method = "GET", body } = {}) {
  return requestJson(baseUrl, pathName, { method, body });
}

function createEvent(gid = "group-1") {
  const now = Math.floor(Date.now() / 1000);
  return {
    eid: `evt-${crypto.randomUUID()}`,
    gid,
    ts: now,
    exp: now + 3600,
    body: {
      v: 1,
      enc: "AES-256-GCM",
      kid: "gk1",
      cty: "peermesh/app-event+json",
      nonce: Buffer.from(`nonce-${gid}-${now}`).toString("base64url"),
      ct: Buffer.from(`ciphertext-${gid}-${now}`).toString("base64url"),
      tag: Buffer.from(`tag-${gid}-${now}`).toString("base64url"),
    },
    sig: "sig",
  };
}

async function main() {
  const registryPort = 22000 + Math.floor(Math.random() * 500);
  const requestSignatureSecret = "relay-app-signature-secret";
  const registry = createRegistryStub({ port: registryPort });

  const relayA = startRelayHarness({
    relayId: "relay-a",
    port: 23000 + Math.floor(Math.random() * 500),
    requestSignatureSecret,
  });
  const relayB = startRelayHarness({
    relayId: "relay-b",
    port: 23500 + Math.floor(Math.random() * 500),
    requestSignatureSecret,
  });

  try {
    await Promise.all([registry.start(), relayA.start(), relayB.start()]);
    await waitForHealth(registry.baseUrl);

    for (const relay of [relayA, relayB]) {
      const heartbeat = await registryApi(registry.baseUrl, "/v1/registry/relays:heartbeat", {
        method: "POST",
        body: {
          relay_id: relay.relayId,
          public_key: `${relay.relayId}-public-key`,
          endpoint: relay.baseUrl,
          region: relay.relayId === "relay-a" ? "vn-south" : "sg",
          capabilities: ["store_forward", "capsule_push", "capsule_pull"],
          version: "test",
        },
      });
      assert(heartbeat.response.status === 200, `heartbeat should succeed for ${relay.relayId}`);
    }

    const grantIssue = await registryApi(registry.baseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "android-device-01",
        requested_gids: ["group-1"],
        requested_operations: ["ingest", "fetch"],
        network_hints: {
          region: "vn-south",
          transport: "http",
        },
      },
    });
    assert(grantIssue.response.status === 201, "registry grant issue should succeed");
    const grant = grantIssue.payload.grant;
    assert(Array.isArray(grant.candidate_relays) && grant.candidate_relays.length >= 1, "grant should include candidates");
    assert(
      grant.registry_signature === signClientAccessGrant("test-client-grant-secret", grant),
      "registry grant signature should validate"
    );
    console.log("PASS managed_client_smoke_grant_issue");

    const selected = grant.candidate_relays[0];
    const selectedRelay = selected.relay_id === "relay-a" ? relayA : relayB;
    const openBody = {
      grant_id: grant.grant_id,
      access_token: grant.access_token,
      client_instance_id: grant.client_instance_id,
      relay_id: selected.relay_id,
      requested_gids: ["group-1"],
      requested_operations: ["ingest", "fetch"],
      client_nonce: "smoke-client-nonce",
      client_session_material: { version: 1, mode: "session_binding" },
    };
    const openRaw = JSON.stringify(openBody);
    const openRes = await requestJson(selectedRelay.baseUrl, "/v1/client/session:open", {
      method: "POST",
      body: openBody,
      headers: {
        ...signRelayRequest({
          secret: requestSignatureSecret,
          method: "POST",
          pathName: "/v1/client/session:open",
          rawBody: openRaw,
        }),
        "X-Relay-Access-Grant": Buffer.from(JSON.stringify(grant)).toString("base64url"),
      },
    });
    assert(openRes.response.status === 201, "session open should succeed");
    const session = openRes.payload.session;
    assert(session && session.session_id, "session open should return session");
    console.log("PASS managed_client_smoke_session_open");

    const event = createEvent("group-1");
    const ingestBody = { events: [event] };
    const ingestRaw = JSON.stringify(ingestBody);
    const ingestRes = await requestJson(selectedRelay.baseUrl, "/v1/events:ingest", {
      method: "POST",
      body: ingestBody,
      headers: {
        ...signRelayRequest({
          secret: requestSignatureSecret,
          method: "POST",
          pathName: "/v1/events:ingest",
          rawBody: ingestRaw,
        }),
        "X-Relay-Access-Grant": Buffer.from(JSON.stringify(grant)).toString("base64url"),
        "X-Relay-Access-Token": grant.access_token,
        "X-Client-Relay-Session": session.session_id,
        "X-Client-Relay-Session-Token": session.session_binding_token,
      },
    });
    assert(ingestRes.response.status === 200, "managed client ingest should succeed");
    assert(Array.isArray(ingestRes.payload.accepted) && ingestRes.payload.accepted.includes(event.eid), "ingest should accept event");
    console.log("PASS managed_client_smoke_ingest");

    const fetchPath = `/v1/events:fetch?gid=${encodeURIComponent(event.gid)}&limit=10`;
    const fetchRes = await requestJson(selectedRelay.baseUrl, fetchPath, {
      method: "GET",
      headers: {
        ...signRelayRequest({
          secret: requestSignatureSecret,
          method: "GET",
          pathName: fetchPath,
          rawBody: "",
        }),
        "X-Relay-Access-Grant": Buffer.from(JSON.stringify(grant)).toString("base64url"),
        "X-Relay-Access-Token": grant.access_token,
        "X-Client-Relay-Session": session.session_id,
        "X-Client-Relay-Session-Token": session.session_binding_token,
      },
    });
    assert(fetchRes.response.status === 200, "managed client fetch should succeed");
    assert(
      Array.isArray(fetchRes.payload.events) && fetchRes.payload.events.some((item) => item.eid === event.eid),
      `fetch should return ingested event; payload=${JSON.stringify(fetchRes.payload)} stored=${JSON.stringify(selectedRelay.storage.events)} fetch_calls=${JSON.stringify(selectedRelay.storage.fetchCalls)}`
    );
    console.log("PASS managed_client_smoke_fetch");
  } finally {
    await registry.stop();
    await relayA.stop();
    await relayB.stop();
  }
}

main().catch((err) => {
  console.error("FAIL managed_client_smoke", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
