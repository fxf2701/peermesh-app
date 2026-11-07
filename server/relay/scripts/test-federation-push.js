#!/usr/bin/env node

const {
  createFederationPush,
  signPeerGrant,
  signFederationRequest,
} = require("../src/federation_push");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function jsonResponse(status, payload) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(payload),
  };
}

function createFetchStub() {
  const calls = [];
  return {
    calls,
    fetch: async (url, options) => {
      calls.push({ url, options });

      return jsonResponse(202, {
        ok: true,
        accepted: ["evt-1"],
        rejected: [],
      });
    },
  };
}

function createMetricsStub() {
  return {
    counters: {},
    gauges: {},

    inc(name, value = 1) {
      this.counters[name] = (this.counters[name] || 0) + value;
    },

    setGauge(name, value) {
      this.gauges[name] = value;
    },
  };
}

async function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitUntil(predicate, timeoutMs, message) {
  const started = Date.now();

  while (Date.now() - started < timeoutMs) {
    if (predicate()) return;
    await sleep(20);
  }

  throw new Error(message);
}

async function testSigning() {
  const rawBody = JSON.stringify({
    events: [{ eid: "evt-1" }],
  });

  const sig1 = signFederationRequest({
    token: "token-a",
    method: "POST",
    path: "/v1/federation/events:push",
    timestamp: "1000",
    rawBody,
  });

  const sig2 = signFederationRequest({
    token: "token-a",
    method: "POST",
    path: "/v1/federation/events:push",
    timestamp: "1000",
    rawBody,
  });

  assert(sig1 === sig2, "signature must be deterministic");
  assert(/^[a-f0-9]{64}$/.test(sig1), "signature must be sha256 hex");

  console.log("PASS federation_push_signature");
}

async function testNoSession() {
  const fetchStub = createFetchStub();

  const push = createFederationPush({
    config: {
      federationPushEnabled: true,
    },
    registrySessions: {
      getActiveSession() {
        return null;
      },
    },
    fetchImpl: fetchStub.fetch,
  });

  const result = push.enqueue([
    {
      eid: "evt-1",
      gid: "group-1",
    },
  ]);

  assert(result.skipped === true, "should skip without session");
  assert(result.reason === "no_active_session");

  console.log("PASS federation_push_no_session");
}

async function testRevokedSessionStopsFanout() {
  const fetchStub = createFetchStub();

  const push = createFederationPush({
    config: {
      federationPushEnabled: true,
    },
    registrySessions: {
      getActiveSession() {
        return null;
      },
    },
    fetchImpl: fetchStub.fetch,
  });

  const result = push.enqueue([
    {
      eid: "evt-1",
      gid: "group-1",
    },
  ]);

  assert(result.skipped === true, "revoked session should not enqueue");
  assert(result.reason === "no_active_session", "revoked session should be treated as inactive");
  assert(fetchStub.calls.length === 0, "no outbound push should occur");

  console.log("PASS federation_push_revoked_session_stops_fanout");
}

async function testFanout() {
  const fetchStub = createFetchStub();
  const metrics = createMetricsStub();

  const session = {
    session_id: "fs_test",
    relay_id: "relay-a",
    expires_at: 1300,
    policy_version: 1,

    peers: [
      {
        source_relay_id: "relay-a",
        relay_id: "relay-b",
        endpoint: "http://relay-b:8080",
        allowed_gids: ["group-1"],
        permissions: ["push"],
        session_token: "token-b",
        expires_at: 1300,
        policy_version: 1,
      },

      {
        source_relay_id: "relay-a",
        relay_id: "relay-c",
        endpoint: "http://relay-c:8080",
        allowed_gids: ["group-2"],
        permissions: ["push"],
        session_token: "token-c",
        expires_at: 1300,
        policy_version: 1,
      },

      {
        source_relay_id: "relay-a",
        relay_id: "relay-d",
        endpoint: "http://relay-d:8080",
        allowed_gids: ["group-1"],
        permissions: ["pull_reconcile"],
        session_token: "token-d",
        expires_at: 1300,
        policy_version: 1,
      },
    ],
  };
  for (const peer of session.peers) {
    peer.registry_signature = signPeerGrant("registry-secret", {
      session_id: session.session_id,
      source_relay_id: session.relay_id,
      destination_relay_id: peer.relay_id,
      endpoint: peer.endpoint,
      allowed_gids: peer.allowed_gids,
      permissions: peer.permissions,
      limits: session.limits,
      expires_at: peer.expires_at,
      policy_version: peer.policy_version,
      session_token: peer.session_token,
    });
  }

  const push = createFederationPush({
    config: {
      federationPushEnabled: true,
      federationPushWorkers: 2,
      federationPushIntervalMs: 100,
      federationPushMaxBatchEvents: 2,
      federationPushMaxQueue: 100,
    },

    registrySessions: {
      getActiveSession() {
        return session;
      },
    },

    metrics,
    fetchImpl: fetchStub.fetch,
    nowSeconds: () => 1000,
  });

  const result = push.enqueue([
    { eid: "evt-1", gid: "group-1" },
    { eid: "evt-2", gid: "group-1" },
    { eid: "evt-3", gid: "group-1" },
    { eid: "evt-4", gid: "group-2" },
  ]);

  assert(result.skipped === false);

  push.start();

  await waitUntil(
    () => fetchStub.calls.length === 3,
    3000,
    "expected 3 push requests"
  );

  push.stop();

  assert(fetchStub.calls.length === 3);

  const urls = fetchStub.calls.map((c) => c.url);

  assert(
    urls.filter((u) =>
      u.includes("relay-b:8080/v1/federation/events:push")
    ).length === 2,
    "relay-b should receive 2 batches"
  );

  assert(
    urls.filter((u) =>
      u.includes("relay-c:8080/v1/federation/events:push")
    ).length === 1,
    "relay-c should receive 1 batch"
  );

  assert(
    !urls.some((u) => u.includes("relay-d")),
    "relay-d must not receive pushes"
  );

  const headers = fetchStub.calls[0].options.headers;
  const requestBody = JSON.parse(fetchStub.calls[0].options.body);

  assert(headers["X-Federation-Session"] === "fs_test");
  assert(headers["X-Federation-Timestamp"] === "1000");
  assert(typeof headers["X-Federation-Signature"] === "string");
  assert(requestBody.peer_grant.registry_signature, "push body should include registry-signed peer grant");
  assert(requestBody.source_relay_id === "relay-a", "push body should include source relay");

  console.log("PASS federation_push_fanout");
}

async function main() {
  await testSigning();
  await testNoSession();
  await testRevokedSessionStopsFanout();
  await testFanout();

  console.log("PASS federation_push_all");
}

main().catch((err) => {
  console.error(
    "FAIL federation_push",
    err && err.stack ? err.stack : String(err)
  );
  process.exit(1);
});
