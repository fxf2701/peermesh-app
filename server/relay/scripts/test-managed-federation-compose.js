#!/usr/bin/env node
/* eslint-disable no-console */

const crypto = require("crypto");
const { isDeepStrictEqual } = require("util");

const registryBaseUrl = process.env.REGISTRY_BASE_URL || "http://127.0.0.1:8090";
const relayBaseUrl = process.env.RELAY_BASE_URL || "http://127.0.0.1:8080";
const relay2BaseUrl = process.env.RELAY_2_BASE_URL || "http://127.0.0.1:8081";
const registryAdminUsername = process.env.REGISTRY_ADMIN_USERNAME || "admin";
const registryAdminPassword = process.env.REGISTRY_ADMIN_PASSWORD || "admin1234";
const relayIds = (process.env.MANAGED_FEDERATION_RELAY_IDS || "relay,relay-2")
  .split(",")
  .map((value) => value.trim())
  .filter(Boolean);
const gid = process.env.MANAGED_FEDERATION_GID || "demo-gid";
const sessionWaitMs = Number(process.env.MANAGED_FEDERATION_SESSION_WAIT_MS || "45000");
const propagationWaitMs = Number(process.env.MANAGED_FEDERATION_PROPAGATION_WAIT_MS || "30000");
const pollIntervalMs = Number(process.env.MANAGED_FEDERATION_POLL_INTERVAL_MS || "2000");

function uuidv4() {
  const bytes = crypto.randomBytes(16);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join("-");
}

async function requestJson(baseUrl, path, { method = "GET", body, cookie, csrfToken } = {}) {
  const res = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(cookie ? { Cookie: cookie } : {}),
      ...(csrfToken ? { "X-CSRF-Token": csrfToken } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let payload = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch (_) {
    payload = null;
  }
  return { res, payload, text };
}

async function waitFor(fn, timeoutMs, label) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    const result = await fn();
    if (result) return result;
    await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
  }
  throw new Error(`timeout waiting for ${label}`);
}

async function loginRegistryAdmin() {
  const { res, payload } = await requestJson(registryBaseUrl, "/registry/admin/api/auth/login", {
    method: "POST",
    body: {
      username: registryAdminUsername,
      password: registryAdminPassword,
    },
  });
  if (res.status !== 200) {
    throw new Error(`registry admin login failed with status ${res.status}`);
  }
  const cookieHeader = res.headers.get("set-cookie");
  if (!cookieHeader) {
    throw new Error("registry admin login did not return session cookie");
  }
  const csrfToken = payload?.data?.csrf_token;
  if (!csrfToken) {
    throw new Error("registry admin login did not return csrf token");
  }
  return { cookie: cookieHeader.split(";")[0], csrfToken };
}

async function configureRelay(cookie, csrfToken, relayId) {
  const trust = await requestJson(
    registryBaseUrl,
    `/registry/admin/api/relays/${encodeURIComponent(relayId)}/trust`,
    {
      method: "PATCH",
      cookie,
      csrfToken,
      body: {
        trust_level: "trusted",
        reason: "managed federation compose smoke",
      },
    }
  );
  if (trust.res.status !== 200) {
    throw new Error(`failed to promote ${relayId} trust: ${trust.res.status}`);
  }

  const federation = await requestJson(
    registryBaseUrl,
    `/registry/admin/api/relays/${encodeURIComponent(relayId)}/federation`,
    {
      method: "PATCH",
      cookie,
      csrfToken,
      body: {
        federation_enabled: true,
        reason: "managed federation compose smoke",
      },
    }
  );
  if (federation.res.status !== 200) {
    throw new Error(`failed to enable federation for ${relayId}: ${federation.res.status}`);
  }
}

async function activeSessions(cookie) {
  const { res, payload } = await requestJson(
    registryBaseUrl,
    "/registry/admin/api/federation/sessions?status=active",
    { cookie }
  );
  if (res.status !== 200) {
    throw new Error(`federation session list failed: ${res.status}`);
  }
  return Array.isArray(payload?.data?.items) ? payload.data.items : [];
}

async function ingestEvent(event) {
  const { res, payload, text } = await requestJson(relayBaseUrl, "/v1/events:ingest", {
    method: "POST",
    body: { events: [event] },
  });
  if (res.status !== 200) {
    throw new Error(`relay ingest failed: ${res.status} ${text}`);
  }
  if (!Array.isArray(payload?.accepted) || !payload.accepted.includes(event.eid)) {
    throw new Error("relay ingest did not accept the event");
  }
}

async function fetchRelayEvent(baseUrl, eid) {
  const { res, payload } = await requestJson(
    baseUrl,
    `/v1/events:fetch?gid=${encodeURIComponent(gid)}&limit=200`
  );
  if (res.status !== 200) return null;
  return Array.isArray(payload?.events)
    ? payload.events.find((entry) => entry && entry.eid === eid) || null
    : null;
}

function deepEqual(a, b) {
  return isDeepStrictEqual(a, b);
}

async function main() {
  console.log("managed_federation_compose_smoke_start");
  const { cookie, csrfToken } = await loginRegistryAdmin();
  console.log("PASS managed_federation_registry_admin_login");

  for (const relayId of relayIds) {
    await configureRelay(cookie, csrfToken, relayId);
  }
  console.log("PASS managed_federation_registry_governance");

  const sessions = await waitFor(async () => {
    const items = await activeSessions(cookie);
    const sources = new Set(items.map((item) => item.source_relay_id || item.relay_id));
    return relayIds.every((relayId) => sources.has(relayId)) ? items : null;
  }, sessionWaitMs, "active registry-issued sessions for both relays");

  const sessionSummary = relayIds.map((relayId) => {
    const match = sessions.find((item) => (item.source_relay_id || item.relay_id) === relayId);
    return `${relayId}:${match ? match.peer_count : 0}`;
  });
  console.log(`PASS managed_federation_sessions_active ${sessionSummary.join(" ")}`);

  const now = Math.floor(Date.now() / 1000);
  const event = {
    eid: uuidv4(),
    gid,
    ts: now,
    exp: now + 600,
    body: {
      v: 1,
      enc: "AES-256-GCM",
      kid: `gk-${uuidv4().slice(0, 8)}`,
      cty: "peermesh/app-event+json",
      nonce: Buffer.from(`nonce-${uuidv4()}`).toString("base64url"),
      ct: Buffer.from(`compose-smoke-${uuidv4()}`).toString("base64url"),
      tag: Buffer.from(`tag-${uuidv4().slice(0, 12)}`).toString("base64url"),
    },
    sig: Buffer.from(`sig-${uuidv4()}`).toString("base64url"),
  };
  await ingestEvent(event);
  console.log("PASS managed_federation_ingest_source");

  const propagated = await waitFor(
    () => fetchRelayEvent(relay2BaseUrl, event.eid),
    propagationWaitMs,
    "event propagation to relay_2"
  );
  if (!propagated || !deepEqual(propagated.body, event.body) || propagated.sig !== event.sig) {
    throw new Error("propagated relay event body/sig mutated during federation");
  }
  console.log("PASS managed_federation_propagation_target");
}

main().catch((err) => {
  console.log("FAIL managed_federation_compose_smoke");
  console.log(`  error: ${err && err.message ? err.message : String(err)}`);
  process.exit(1);
});
