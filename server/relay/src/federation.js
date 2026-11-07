// Federation sync helpers (peer-to-peer pull).

const http = require("http");
const https = require("https");
const { createHash, createHmac, randomUUID } = require("crypto");
const { decodeCursor } = require("./utils");
const { logEvent } = require("./logger");
const { createFederationTlsOptions } = require("./federation_tls");
const { createPeerGrant, signFederationRequest } = require("./federation_push");
const { isPeerMappingActive, isPeerMappingQuarantined } = require("./peer_mapping_state");

function normalizeUrl(raw) {
  return raw.replace(/\/+$/, "");
}

function redactToken(token) {
  if (!token || typeof token !== "string") return null;
  if (token.length <= 8) return "***";
  return `${token.slice(0, 4)}...${token.slice(-4)}`;
}

function computeBackoffMs(baseMs, maxMs, failures, jitterMs) {
  const exp = Math.pow(2, Math.max(0, failures));
  const capped = Math.min(maxMs, baseMs * exp);
  const jitter = jitterMs > 0 ? Math.floor(Math.random() * jitterMs) : 0;
  return capped + jitter;
}

function requestJson(url, headers, tlsOptions) {
  const client = url.protocol === "https:" ? https : http;
  return new Promise((resolve, reject) => {
    const req = client.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname + url.search,
        method: "GET",
        headers,
        ...(tlsOptions || {}),
      },
      (res) => {
        let body = "";
        res.on("data", (chunk) => {
          body += chunk;
        });
        res.on("end", () => {
          resolve({ status: res.statusCode, body });
        });
      }
    );
    req.on("error", reject);
    req.end();
  });
}

function requestJsonPost(url, headers, body, tlsOptions) {
  const client = url.protocol === "https:" ? https : http;
  return new Promise((resolve, reject) => {
    const req = client.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname + url.search,
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(body),
          ...headers,
        },
        ...(tlsOptions || {}),
      },
      (res) => {
        let resBody = "";
        res.on("data", (chunk) => {
          resBody += chunk;
        });
        res.on("end", () => {
          resolve({ status: res.statusCode, body: resBody });
        });
      }
    );
    req.on("error", reject);
    req.write(body);
    req.end();
  });
}

async function fetchRemoteStats({ peerUrl, peerToken, config, tlsOptions }) {
  const url = new URL("/v1/peers:stats", peerUrl);
  const headers = { "x-relay-peer": "1" };
  if (peerToken) {
    headers["x-relay-peer-token"] = peerToken;
  }
  if (config.federationSelfId) {
    headers["x-relay-loop"] = config.federationSelfId;
    headers["x-relay-from"] = config.federationSelfId;
  }
  try {
    const res = await requestJson(url, headers, tlsOptions);
    if (res.status !== 200) {
      return { ok: false, status: res.status, body: res.body };
    }
    const payload = JSON.parse(res.body);
    if (!payload || typeof payload !== "object") {
      return { ok: false, status: res.status, body: res.body };
    }
    const connected = Number(payload.connected);
    const capacityLimit = Number(payload.capacity_limit);
    const heartbeatTtl = Number(payload.heartbeat_ttl_seconds);
    return {
      ok: true,
      stats: {
        connected: Number.isFinite(connected) ? connected : null,
        capacity_limit: Number.isFinite(capacityLimit) ? capacityLimit : null,
        heartbeat_ttl_seconds: Number.isFinite(heartbeatTtl) ? heartbeatTtl : null,
      },
    };
  } catch (err) {
    return { ok: false, error: err.message || String(err) };
  }
}

function peerLabel(peerUrl) {
  try {
    const parsed = new URL(peerUrl);
    return parsed.host || peerUrl;
  } catch (err) {
    return peerUrl;
  }
}

function gidLabel(gid) {
  if (!gid) return "unknown";
  return createHash("sha256").update(String(gid)).digest("hex").slice(0, 8);
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function peerEndpoint(peer) {
  return peer.endpoint || peer.peer_url || peer.url || peer.relay_url || "";
}

function peerRelayId(peer) {
  return peer.relay_id || peer.peer_id || peer.destination_relay_id || peer.id || peerEndpoint(peer);
}

function peerToken(peer) {
  return peer.session_token || peer.federation_token || peer.token || peer.peer_token || "";
}

function peerPermissions(peer, session) {
  const permissions = asArray(peer.permissions).length > 0 ? peer.permissions : asArray(session.permissions);
  return permissions.map((item) => String(item));
}

function peerAllowedGids(peer, session) {
  const gids = asArray(peer.allowed_gids).length > 0
    ? peer.allowed_gids
    : asArray(peer.gids).length > 0
      ? peer.gids
      : asArray(session.allowed_gids).length > 0
        ? session.allowed_gids
        : asArray(session.requested_gids);
  return gids.map((item) => String(item)).filter(Boolean);
}

function encodeGrant(grant) {
  return Buffer.from(JSON.stringify(grant)).toString("base64url");
}

function buildSignedPayload(prefix, body, token) {
  const timestamp = String(Math.floor(Date.now() / 1000));
  const nonce = randomUUID();
  const raw = JSON.stringify(body);
  const canonical = `${prefix}\n${timestamp}\n${nonce}\n${raw}`;
  const signature = createHmac("sha256", token).update(canonical).digest("hex");
  return {
    raw,
    headers: {
      "X-Relay-Join-Signature": signature,
      "X-Relay-Join-Timestamp": timestamp,
      "X-Relay-Join-Nonce": nonce,
    },
  };
}

function compareCursor(prevCursor, nextCursor) {
  if (!prevCursor || !nextCursor) return "unknown";
  const prev = decodeCursor(prevCursor);
  const next = decodeCursor(nextCursor);
  if (!prev || !next) return "unknown";
  if (Number.isInteger(prev.order) && Number.isInteger(next.order)) {
    if (next.order < prev.order) return "regression";
    if (next.order === prev.order) return "equal";
    return "forward";
  }
  if (next.ts < prev.ts) return "regression";
  if (next.ts === prev.ts && String(next.eid) < String(prev.eid)) return "regression";
  if (next.ts === prev.ts && String(next.eid) === String(prev.eid)) return "equal";
  return "forward";
}

async function syncPeerOnce({ peerUrl, gid, config, storage, metrics, tlsOptions, peerToken }) {
  const cursor = await storage.getFederationCursor(peerUrl, gid);
  const url = new URL("/v1/events:fetch", peerUrl);
  url.searchParams.set("gid", gid);
  url.searchParams.set("limit", String(config.federationSyncLimit));
  if (cursor) {
    url.searchParams.set("cursor", cursor);
  }

  const headers = { "x-relay-peer": "1" };
  if (peerToken) {
    headers["x-relay-peer-token"] = peerToken;
  }
  if (config.federationSelfId) {
    headers["x-relay-loop"] = config.federationSelfId;
    headers["x-relay-from"] = config.federationSelfId;
  }

  const res = await requestJson(url, headers, tlsOptions);
  if (res.status !== 200) {
    const error = new Error(`peer fetch failed: ${res.status}`);
    error.status = res.status;
    error.body = res.body;
    throw error;
  }

  let payload;
  try {
    payload = JSON.parse(res.body);
  } catch (err) {
    const error = new Error("peer response invalid JSON");
    error.body = res.body;
    throw error;
  }

  if (!payload || !Array.isArray(payload.events)) {
    const error = new Error("peer response missing events");
    error.body = res.body;
    throw error;
  }

  if (payload.events.length > 0) {
    await storage.ingestEvents(payload.events);
  }

  if (payload.next_cursor) {
    const cursorComparison = compareCursor(cursor, payload.next_cursor);
    const labels = { peer: peerLabel(peerUrl), gid: gidLabel(gid) };
    if (cursorComparison === "regression" && !config.federationAllowCursorRegression) {
      if (metrics) {
        metrics.inc("federation_cursor_regressions_total", 1, labels);
      }
      logEvent("warn", "federation_cursor_regression", {
        peer: labels.peer,
        gid: labels.gid,
        prev_cursor: cursor,
        next_cursor: payload.next_cursor,
      });
      return { received: payload.events.length, nextCursor: cursor || null };
    }

    const updated = await storage.setFederationCursor(peerUrl, gid, payload.next_cursor);
    if (!updated) {
      const error = new Error("federation_cursors table missing");
      error.code = "FEDERATION_CURSOR_UNAVAILABLE";
      throw error;
    }
  }

  const labels = { peer: peerLabel(peerUrl), gid: gidLabel(gid) };
  let lagSeconds = null;
  if (metrics) {
    metrics.inc("federation_sync_success_total", 1, labels);
    const cursorForLag = payload.next_cursor || cursor;
    const decoded = decodeCursor(cursorForLag);
    if (decoded && decoded.ts) {
      lagSeconds = Math.max(0, Math.floor(Date.now() / 1000) - decoded.ts);
      metrics.setGauge("federation_cursor_lag_seconds", lagSeconds, labels);
    }
  }

  return {
    received: payload.events.length,
    nextCursor: payload.next_cursor || null,
    lagSeconds,
  };
}

async function syncManagedPeerOnce({ peer, session, gid, config, storage, metrics, tlsOptions }) {
  const peerUrl = normalizeUrl(peerEndpoint(peer));
  const token = peerToken(peer);
  if (!peerUrl || !token) {
    throw new Error("managed federation peer missing endpoint or token");
  }

  const cursor = await storage.getFederationCursor(peerUrl, gid);
  const url = new URL("/v1/events:fetch", peerUrl);
  url.searchParams.set("gid", gid);
  url.searchParams.set("limit", String(config.federationSyncLimit));
  if (cursor) {
    url.searchParams.set("cursor", cursor);
  }

  const timestamp = String(Math.floor(Date.now() / 1000));
  const requestTarget = url.pathname + url.search;
  const signature = signFederationRequest({
    token,
    method: "GET",
    path: requestTarget,
    timestamp,
    rawBody: "",
  });
  const grant = createPeerGrant({ session, peer });
  const headers = {
    "X-Federation-Session": session.session_id,
    "X-Federation-Token": token,
    "X-Federation-Operation": "pull_reconcile",
    "X-Federation-Grant": encodeGrant(grant),
    "X-Federation-Timestamp": timestamp,
    "X-Federation-Signature": signature,
    "X-Federation-Source": session.relay_id || config.federationSelfId || "",
    "X-Federation-Destination": peerRelayId(peer),
  };

  const res = await requestJson(url, headers, tlsOptions);
  if (res.status !== 200) {
    const error = new Error(`managed peer fetch failed: ${res.status}`);
    error.status = res.status;
    error.body = res.body;
    throw error;
  }

  let payload;
  try {
    payload = JSON.parse(res.body);
  } catch (err) {
    const error = new Error("managed peer response invalid JSON");
    error.body = res.body;
    throw error;
  }

  if (!payload || !Array.isArray(payload.events)) {
    const error = new Error("managed peer response missing events");
    error.body = res.body;
    throw error;
  }

  if (payload.events.length > 0) {
    await storage.ingestEvents(payload.events);
  }

  if (payload.next_cursor) {
    const cursorComparison = compareCursor(cursor, payload.next_cursor);
    const labels = { peer: peerLabel(peerUrl), gid: gidLabel(gid) };
    if (cursorComparison === "regression" && !config.federationAllowCursorRegression) {
      if (metrics) {
        metrics.inc("federation_cursor_regressions_total", 1, labels);
      }
      logEvent("warn", "federation_cursor_regression", {
        peer: labels.peer,
        gid: labels.gid,
        prev_cursor: cursor,
        next_cursor: payload.next_cursor,
      });
      return { received: payload.events.length, nextCursor: cursor || null };
    }

    const updated = await storage.setFederationCursor(peerUrl, gid, payload.next_cursor);
    if (!updated) {
      const error = new Error("federation_cursors table missing");
      error.code = "FEDERATION_CURSOR_UNAVAILABLE";
      throw error;
    }
  }

  const labels = { peer: peerLabel(peerUrl), gid: gidLabel(gid) };
  let lagSeconds = null;
  if (metrics) {
    metrics.inc("federation_sync_success_total", 1, labels);
    const cursorForLag = payload.next_cursor || cursor;
    const decoded = decodeCursor(cursorForLag);
    if (decoded && decoded.ts) {
      lagSeconds = Math.max(0, Math.floor(Date.now() / 1000) - decoded.ts);
      metrics.setGauge("federation_cursor_lag_seconds", lagSeconds, labels);
    }
  }

  return {
    received: payload.events.length,
    nextCursor: payload.next_cursor || null,
    lagSeconds,
  };
}

function createFederationSync({ config, storage, metrics, cooldown, adminState, registrySessions }) {
  const staticPeers = Array.from(
    new Set(
      config.federationPeers
        .map(normalizeUrl)
        .filter((peer) => peer.length > 0)
    )
  );
  const defaultGids = config.federationSyncGids;
  const baseIntervalMs = Math.max(1000, config.federationSyncIntervalSeconds * 1000);
  const maxBackoffMs = Math.max(baseIntervalMs, config.federationSyncMaxBackoffSeconds * 1000);
  const jitterMs = Math.max(0, config.federationSyncJitterSeconds * 1000);
  const maxInFlightPerPeer = Math.max(1, config.federationSyncMaxInFlightPerPeer || 1);
  const maxQueuePerPeer = Math.max(0, config.federationSyncMaxQueuePerPeer || 0);
  const selfUrl = config.federationSelfUrl ? normalizeUrl(config.federationSelfUrl) : "";
  const selfId = config.federationSelfId || selfUrl;
  const heartbeatIntervalMs = Math.max(
    10000,
    Math.floor((config.federationHeartbeatTtlSeconds || 120) * 1000 * 0.5)
  );
  const handshakeIntervalMs = Math.max(
    60000,
    Math.floor((config.federationHandshakeIntervalSeconds || 60) * 1000)
  );
  const heartbeatFailureThreshold = Math.max(
    1,
    Number(config.federationHeartbeatFailureThreshold || 5)
  );
  const heartbeatBackoffMs = Math.max(
    0,
    Number(config.federationHeartbeatBackoffSeconds || 30) * 1000
  );
  const state = new Map();
  const peerStates = new Map();
  const targets = new Map();
  const heartbeatFailures = new Map();
  const heartbeatBackoffUntil = new Map();
  let stopped = false;
  let refreshTimer = null;
  let handshakeTimer = null;
  let heartbeatTimer = null;

  function key(peer, gid) {
    return `${peer}|${gid}`;
  }

  function shouldSkipPeer(peer) {
    return selfUrl && peer === selfUrl;
  }

  function getPeerState(peer) {
    const existing = peerStates.get(peer);
    if (existing) return existing;
    const created = {
      inFlight: 0,
      queue: [],
      syncFailures: 0,
      backpressureDrops: 0,
      heartbeatFailures: 0,
      quarantined: false,
    };
    peerStates.set(peer, created);
    return created;
  }

  function computePeerAbuseScore(peerState) {
    if (!peerState) return 0;
    const quarantinePenalty = peerState.quarantined ? Math.max(3, heartbeatFailureThreshold) : 0;
    return (
      Math.max(0, peerState.syncFailures || 0) +
      Math.max(0, peerState.heartbeatFailures || 0) +
      Math.max(0, peerState.backpressureDrops || 0) +
      quarantinePenalty
    );
  }

  function updatePeerRuntimeMetrics(peer, peerState) {
    if (!metrics || !peerState) return;
    const labels = { peer: peerLabel(peer) };
    const queueRemaining = peerState.quarantined ? 0 : Math.max(0, maxQueuePerPeer - peerState.queue.length);
    const inFlightRemaining = peerState.quarantined ? 0 : Math.max(0, maxInFlightPerPeer - peerState.inFlight);
    metrics.setGauge("federation_peer_abuse_score", computePeerAbuseScore(peerState), labels);
    metrics.setGauge("federation_peer_quarantined", peerState.quarantined ? 1 : 0, labels);
    metrics.setGauge("federation_peer_quota_remaining", inFlightRemaining, {
      ...labels,
      quota: "in_flight",
    });
    metrics.setGauge("federation_peer_quota_remaining", queueRemaining, {
      ...labels,
      quota: "queue",
    });
  }

  function notePeerPolicyState(peer, mapping) {
    if (!peer) return;
    const peerState = getPeerState(peer);
    peerState.quarantined = Boolean(mapping && isPeerMappingQuarantined(mapping));
    updatePeerRuntimeMetrics(peer, peerState);
  }

  function updateBackpressureMetrics(peer, peerState) {
    if (metrics) {
      const labels = { peer: peerLabel(peer) };
      metrics.setGauge("federation_backpressure_in_flight", peerState.inFlight, labels);
      metrics.setGauge("federation_backpressure_queue_size", peerState.queue.length, labels);
    }
    updatePeerRuntimeMetrics(peer, peerState);
    if (adminState && adminState.updateBackpressure) {
      adminState.updateBackpressure({
        peerUrl: peer,
        inFlight: peerState.inFlight,
        queue: peerState.queue.length,
      });
    }
  }

  function recordHeartbeatSuccess(peer) {
    const peerState = getPeerState(peer);
    peerState.heartbeatFailures = 0;
    heartbeatFailures.delete(peer);
    heartbeatBackoffUntil.delete(peer);
    updatePeerRuntimeMetrics(peer, peerState);
  }

  function recordHeartbeatFailure(peer) {
    const failures = (heartbeatFailures.get(peer) || 0) + 1;
    heartbeatFailures.set(peer, failures);
    const peerState = getPeerState(peer);
    peerState.heartbeatFailures = failures;
    updatePeerRuntimeMetrics(peer, peerState);
    return failures;
  }

  function isTargetActive(peer, gid) {
    return targets.has(key(peer, gid));
  }

  function activeManagedSession() {
    return registrySessions && registrySessions.getActiveSession
      ? registrySessions.getActiveSession()
      : null;
  }

  async function sendSignedRequest({ peerUrl, endpoint, prefix, token, bodyExtras }) {
    const selfUrl = config.federationSelfUrl ? normalizeUrl(config.federationSelfUrl) : "";
    const selfId = config.federationSelfId || selfUrl;
    if (!selfUrl || !selfId || !token) return { ok: false, error: "missing_identity" };
    const body = { peer_url: selfUrl, self_id: selfId, ...(bodyExtras || {}) };
    const payload = buildSignedPayload(prefix, body, token);
    const url = new URL(endpoint, peerUrl);
    const tlsOptions =
      config.federationMtlsEnabled && peerUrl.startsWith("https:")
        ? createFederationTlsOptions(config)
        : null;
    const res = await requestJsonPost(url, payload.headers, payload.raw, tlsOptions);
    if (res.status !== 200) {
      return { ok: false, error: `status_${res.status}`, body: res.body };
    }
    try {
      return { ok: true, data: JSON.parse(res.body) };
    } catch (err) {
      return { ok: false, error: "invalid_json", body: res.body };
    }
  }

  async function ensureRotatedToken(mapping) {
    if (!mapping || !mapping.peer_url) return null;
    const expiresAt = mapping.rotated_expires_at
      ? Date.parse(mapping.rotated_expires_at)
      : null;
    if (mapping.rotated_token && (!Number.isFinite(expiresAt) || expiresAt > Date.now())) {
      return mapping.rotated_token;
    }
    return runHandshake(mapping);
  }

  async function runHandshake(mapping) {
    if (!mapping || !mapping.peer_url) return null;
    if (!mapping.peer_token || (!storage.storeRotatedToken && !storage.issuePeerRotatedToken)) {
      return null;
    }
    const peer = normalizeUrl(mapping.peer_url);
    const now = Date.now();
    let joinToken = mapping.join_token;
    const joinExpiresAt = mapping.join_token_expires_at
      ? Date.parse(mapping.join_token_expires_at)
      : null;
    const joinExpiringSoon = !joinToken ||
      (Number.isFinite(joinExpiresAt) && joinExpiresAt <= now + handshakeIntervalMs);
    if (joinExpiringSoon && storage.updatePeerJoinToken) {
      const joinTtl = Math.max(60, config.federationJoinTokenTtlSeconds || 600);
      const newJoinToken = randomUUID();
      const newJoinExpiresAt = new Date(now + joinTtl * 1000).toISOString();
      const updated = await storage.updatePeerJoinToken({
        peerUrl: peer,
        joinToken: newJoinToken,
        joinTokenExpiresAt: newJoinExpiresAt,
      });
      if (updated && updated.join_token) {
        joinToken = updated.join_token;
        logEvent("info", "federation_join_token_refreshed", {
          peer: peerLabel(peer),
          join_token: redactToken(joinToken),
          join_token_expires_at: newJoinExpiresAt,
        });
      }
    }
    logEvent("info", "federation_join_send", { peer: peerLabel(peer) });
    const joinResult = await sendSignedRequest({
      peerUrl: peer,
      endpoint: "/v1/peers:join",
      prefix: "JOIN",
      token: mapping.peer_token,
      bodyExtras: joinToken ? { peer_join_token: joinToken } : null,
    });
    if (!joinResult.ok) {
      logEvent("warn", "federation_join_failed", {
        peer: peerLabel(peer),
        error: joinResult.error,
        body: joinResult.body,
      });
    }
    logEvent("info", "federation_handshake_send", { peer: peerLabel(peer) });
    const handshakeResult = await sendSignedRequest({
      peerUrl: peer,
      endpoint: "/v1/peers:handshake",
      prefix: "HANDSHAKE",
      token: mapping.peer_token,
      bodyExtras: joinToken ? { peer_join_token: joinToken } : null,
    });
    if (!handshakeResult.ok) {
      logEvent("warn", "federation_handshake_failed", {
        peer: peerLabel(peer),
        error: handshakeResult.error,
        body: handshakeResult.body,
      });
      return null;
    }
    const rotatedToken = handshakeResult.data && handshakeResult.data.rotated_token;
    const expiresIn = Number(handshakeResult.data && handshakeResult.data.expires_in) || 300;
    const joinTokenFromHandshake = handshakeResult.data && handshakeResult.data.join_token;
    const joinExpiresIn =
      Number(handshakeResult.data && handshakeResult.data.join_token_expires_in) || null;
    if (!rotatedToken) return null;
    const rotatedExpiresAt = new Date(Date.now() + expiresIn * 1000).toISOString();
    await storage.storeRotatedToken({ peerUrl: peer, rotatedToken, rotatedExpiresAt });
    logEvent("info", "federation_handshake_ok", {
      peer: peerLabel(peer),
      rotated_token: redactToken(rotatedToken),
      rotated_expires_at: rotatedExpiresAt,
      join_token: redactToken(joinTokenFromHandshake),
      join_token_expires_in: joinExpiresIn,
    });
    return rotatedToken;
  }

  async function loadAutoSyncTargets() {
    const nextTargets = new Map();
    const managedSession = activeManagedSession();
    if (config.registryFederationSessionsEnabled && managedSession) {
      for (const peer of asArray(managedSession.peers)) {
        const peerUrl = normalizeUrl(peerEndpoint(peer));
        const token = peerToken(peer);
        if (!peerUrl || !token || shouldSkipPeer(peerUrl)) continue;
        const permissions = peerPermissions(peer, managedSession);
        if (permissions.length > 0 && !permissions.includes("pull_reconcile")) continue;
        for (const gid of peerAllowedGids(peer, managedSession)) {
          nextTargets.set(key(peerUrl, gid), {
            peer: peerUrl,
            gid,
            peerToken: token,
            managedPeer: peer,
            managedSession,
          });
        }
      }

      targets.clear();
      nextTargets.forEach((value, entryKey) => {
        targets.set(entryKey, value);
      });

      for (const [stateKey, entry] of state.entries()) {
        if (targets.has(stateKey)) continue;
        if (entry && entry.timer) clearTimeout(entry.timer);
        state.delete(stateKey);
      }
      return;
    }

    if (config.registryFederationSessionsEnabled) {
      targets.clear();
      for (const entry of state.values()) {
        if (entry && entry.timer) clearTimeout(entry.timer);
      }
      state.clear();
      return;
    }

    staticPeers.forEach((peer) => {
      if (shouldSkipPeer(peer)) return;
      defaultGids.forEach((gid) => {
        if (!gid) return;
        nextTargets.set(key(peer, gid), {
          peer,
          gid,
          peerToken: "",
        });
      });
    });

    const now = Date.now();
    if (storage.listPeerMappings) {
      const mappings = await storage.listPeerMappings();
      for (const mapping of mappings) {
        const peer = normalizeUrl(mapping && mapping.peer_url ? mapping.peer_url : "");
        if (peer) notePeerPolicyState(peer, mapping);
        if (!mapping || !isPeerMappingActive(mapping) || !mapping.auto_sync) continue;
        if (!mapping.joined_at) continue;
        const rotatedToken = await ensureRotatedToken(mapping);
        if (!rotatedToken) continue;
        if (mapping.rotated_token === rotatedToken && mapping.rotated_expires_at) {
          const expiresAt = Date.parse(mapping.rotated_expires_at);
          if (Number.isFinite(expiresAt) && expiresAt <= now) continue;
        }
        if (!peer || shouldSkipPeer(peer)) return;
        const gids = Array.isArray(mapping.sync_gids) && mapping.sync_gids.length > 0
          ? mapping.sync_gids
          : defaultGids;
        gids.forEach((gid) => {
          if (!gid) return;
          nextTargets.set(key(peer, gid), {
            peer,
            gid,
            peerToken: rotatedToken,
          });
        });
      }
    }

    targets.clear();
    nextTargets.forEach((value, entryKey) => {
      targets.set(entryKey, value);
    });

    for (const [stateKey, entry] of state.entries()) {
      if (targets.has(stateKey)) continue;
      if (entry && entry.timer) clearTimeout(entry.timer);
      state.delete(stateKey);
    }
  }

  function schedule(peer, gid, delayMs) {
    if (stopped) return;
    if (!isTargetActive(peer, gid)) return;
    const stateKey = key(peer, gid);
    const entry = state.get(stateKey) || { failures: 0, timer: null, queued: false };
    if (entry.timer) clearTimeout(entry.timer);
    entry.timer = setTimeout(() => enqueueRun(peer, gid), delayMs);
    state.set(stateKey, entry);
  }

  function enqueueRun(peer, gid) {
    if (stopped) return;
    if (shouldSkipPeer(peer)) return;
    if (!isTargetActive(peer, gid)) return;
    if (cooldown && cooldown.isActive && cooldown.isActive()) {
      const delayMs = Math.max(
        baseIntervalMs,
        (cooldown.retryAfterSeconds ? cooldown.retryAfterSeconds() : 1) * 1000
      );
      schedule(peer, gid, delayMs);
      return;
    }
    const stateKey = key(peer, gid);
    const entry = state.get(stateKey) || { failures: 0, timer: null, queued: false };
    if (entry.queued) return;
    const peerState = getPeerState(peer);
    if (peerState.inFlight >= maxInFlightPerPeer) {
      if (peerState.queue.length >= maxQueuePerPeer) {
        if (metrics) {
          metrics.inc("federation_backpressure_drops_total", 1, { peer: peerLabel(peer) });
        }
        peerState.backpressureDrops += 1;
        updatePeerRuntimeMetrics(peer, peerState);
        logEvent("warn", "federation_backpressure_drop", {
          peer: peerLabel(peer),
          gid: gidLabel(gid),
          in_flight: peerState.inFlight,
          queue_size: peerState.queue.length,
        });
        schedule(peer, gid, baseIntervalMs);
        return;
      }
      entry.queued = true;
      peerState.queue.push({ peer, gid });
      state.set(stateKey, entry);
      updateBackpressureMetrics(peer, peerState);
      return;
    }

    run(peer, gid, entry);
  }

  async function run(peer, gid, entry) {
    if (stopped) return;
    if (shouldSkipPeer(peer)) return;
    if (!isTargetActive(peer, gid)) return;
    const stateKey = key(peer, gid);
    const current = entry || state.get(stateKey) || { failures: 0, timer: null, queued: false };
    const labels = { peer: peerLabel(peer), gid: gidLabel(gid) };
    const peerState = getPeerState(peer);
    current.queued = false;
    peerState.inFlight += 1;
    updateBackpressureMetrics(peer, peerState);

    try {
      const tlsOptions =
        config.federationMtlsEnabled && peer.startsWith("https:")
          ? createFederationTlsOptions(config)
          : null;
      const target = targets.get(stateKey);
      const result = target && target.managedSession
        ? await syncManagedPeerOnce({
          peer: target.managedPeer,
          session: target.managedSession,
          gid,
          config,
          storage,
          metrics,
          tlsOptions,
        })
        : await syncPeerOnce({
          peerUrl: peer,
          gid,
          config,
          storage,
          metrics,
          tlsOptions,
          peerToken: target ? target.peerToken : null,
        });
      if (adminState && adminState.recordSuccess) {
        adminState.recordSuccess({ peerUrl: peer, lagSeconds: result.lagSeconds });
      }
      current.failures = 0;
      peerState.syncFailures = 0;
      updatePeerRuntimeMetrics(peer, peerState);
      schedule(peer, gid, computeBackoffMs(baseIntervalMs, maxBackoffMs, 0, jitterMs));
    } catch (err) {
      if (err && err.code === "FEDERATION_CURSOR_UNAVAILABLE") {
        logEvent("warn", "federation_sync_disabled", { error: err.message || String(err) });
        stopped = true;
        return;
      }
      current.failures += 1;
      const delayMs = computeBackoffMs(baseIntervalMs, maxBackoffMs, current.failures, jitterMs);
      if (metrics) {
        metrics.inc("federation_sync_fail_total", 1, labels);
        metrics.inc("federation_backoff_total", 1, labels);
      }
      if (adminState && adminState.recordFailure) {
        adminState.recordFailure({ peerUrl: peer, error: err.message || String(err) });
      }
      peerState.syncFailures = current.failures;
      updatePeerRuntimeMetrics(peer, peerState);
      logEvent("warn", "federation_sync_failed", {
        peer: labels.peer,
        gid: labels.gid,
        error: err.message || String(err),
      });
      schedule(peer, gid, delayMs);
    } finally {
      peerState.inFlight = Math.max(0, peerState.inFlight - 1);
      updateBackpressureMetrics(peer, peerState);
      const next = peerState.queue.shift();
      if (next) {
        const nextKey = key(next.peer, next.gid);
        const nextEntry = state.get(nextKey) || { failures: 0, timer: null, queued: false };
        nextEntry.queued = false;
        state.set(nextKey, nextEntry);
        updateBackpressureMetrics(next.peer, peerState);
        enqueueRun(next.peer, next.gid);
      }
    }
  }

  function start() {
    if (!config.registryFederationSessionsEnabled && staticPeers.length === 0 && !storage.listPeerMappings) return;
    const refreshIntervalMs = Math.max(5000, baseIntervalMs);
    const syncStart = async () => {
      try {
        await loadAutoSyncTargets();
      } catch (err) {
        logEvent("warn", "federation_auto_sync_load_failed", { error: err.message || String(err) });
        return;
      }
      if (targets.size === 0) return;
      if (adminState && adminState.ensurePeer) {
        targets.forEach((target) => adminState.ensurePeer(target.peer));
      }
      if (!config.registryFederationSessionsEnabled && adminState && adminState.updateRemoteStats) {
        const peerTokens = new Map();
        targets.forEach((target) => {
          if (!peerTokens.has(target.peer)) {
            peerTokens.set(target.peer, target.peerToken || "");
          }
        });
        for (const [peer, peerToken] of peerTokens.entries()) {
          const tlsOptions =
            config.federationMtlsEnabled && peer.startsWith("https:")
              ? createFederationTlsOptions(config)
              : null;
          const result = await fetchRemoteStats({ peerUrl: peer, peerToken, config, tlsOptions });
          adminState.updateRemoteStats({
            peerUrl: peer,
            stats: result.ok ? result.stats : null,
          });
        }
      }
      targets.forEach((target) => {
        schedule(target.peer, target.gid, computeBackoffMs(baseIntervalMs, maxBackoffMs, 0, jitterMs));
      });
    };
    syncStart();
    refreshTimer = setInterval(syncStart, refreshIntervalMs);
    if (config.registryFederationSessionsEnabled) return;
    if (!handshakeTimer) {
      handshakeTimer = setInterval(async () => {
        if (stopped) return;
        if (!storage.listPeerMappings) return;
        let mappings = [];
        try {
          mappings = await storage.listPeerMappings();
        } catch (err) {
          logEvent("warn", "federation_handshake_load_failed", {
            error: err.message || String(err),
          });
          return;
        }
        const now = Date.now();
        for (const mapping of mappings) {
          if (!mapping || !isPeerMappingActive(mapping) || !mapping.peer_token) continue;
          const peer = normalizeUrl(mapping.peer_url || "");
          if (!peer || shouldSkipPeer(peer)) continue;
          const backoffUntil = heartbeatBackoffUntil.get(peer);
          if (backoffUntil && backoffUntil > now) {
            logEvent("info", "federation_heartbeat_backoff_active", {
              peer: peerLabel(peer),
              backoff_seconds: Math.max(0, Math.ceil((backoffUntil - now) / 1000)),
            });
            continue;
          }
          const rotatedExpiresAt = mapping.rotated_expires_at
            ? Date.parse(mapping.rotated_expires_at)
            : null;
          const joinExpiresAt = mapping.join_token_expires_at
            ? Date.parse(mapping.join_token_expires_at)
            : null;
          const rotatedExpired = !mapping.rotated_token ||
            (Number.isFinite(rotatedExpiresAt) && rotatedExpiresAt <= now);
          const joinExpiringSoon = !mapping.join_token ||
            (Number.isFinite(joinExpiresAt) && joinExpiresAt <= now + handshakeIntervalMs);
          if (!rotatedExpired && !joinExpiringSoon) continue;
          const rotatedToken = await runHandshake(mapping);
          if (!rotatedToken) {
            logEvent("warn", "federation_handshake_retry_failed", {
              peer: peerLabel(peer),
            });
          }
        }
      }, handshakeIntervalMs);
    }
    if (!heartbeatTimer) {
      heartbeatTimer = setInterval(async () => {
        if (stopped) return;
        if (!storage.listPeerMappings || !selfUrl || !selfId) return;
        let mappings = [];
        try {
          mappings = await storage.listPeerMappings();
        } catch (err) {
          logEvent("warn", "federation_heartbeat_load_failed", {
            error: err.message || String(err),
          });
          return;
        }
        const now = Date.now();
        for (const mapping of mappings) {
          if (!mapping || !isPeerMappingActive(mapping) || !mapping.joined_at) continue;
          const peer = normalizeUrl(mapping.peer_url || "");
          if (!peer || shouldSkipPeer(peer)) continue;
          const backoffUntil = heartbeatBackoffUntil.get(peer);
          if (backoffUntil && backoffUntil > now) continue;
          if (!mapping.rotated_token) continue;
          const expiresAt = mapping.rotated_expires_at
            ? Date.parse(mapping.rotated_expires_at)
            : null;
          if (Number.isFinite(expiresAt) && expiresAt <= Date.now()) continue;
          const rotatedToken = mapping.rotated_token;
          logEvent("info", "federation_heartbeat_send", { peer: peerLabel(peer) });
          const result = await sendSignedRequest({
            peerUrl: peer,
            endpoint: "/v1/peers:heartbeat",
            prefix: "HEARTBEAT",
            token: rotatedToken,
          });
          if (!result.ok) {
            logEvent("warn", "federation_heartbeat_failed", {
              peer: peerLabel(peer),
              error: result.error || "unknown",
              body: result.body,
            });
            const failures = recordHeartbeatFailure(peer);
            if (failures >= heartbeatFailureThreshold) {
              heartbeatFailures.delete(peer);
              heartbeatBackoffUntil.set(peer, now + heartbeatBackoffMs);
              logEvent("warn", "federation_peer_offline_detected", {
                peer: peerLabel(peer),
                failures,
                backoff_seconds: Math.round(heartbeatBackoffMs / 1000),
              });
              if (storage.clearPeerRotatedToken) {
                await storage.clearPeerRotatedToken({ peerUrl: peer });
                logEvent("warn", "federation_rotated_token_revoked", {
                  peer: peerLabel(peer),
                });
              }
            }
            continue;
          }
          recordHeartbeatSuccess(peer);
          logEvent("info", "federation_heartbeat_ok", { peer: peerLabel(peer) });
        }
      }, heartbeatIntervalMs);
    }
  }

  function stop() {
    stopped = true;
    for (const entry of state.values()) {
      if (entry.timer) clearTimeout(entry.timer);
    }
    if (refreshTimer) {
      clearInterval(refreshTimer);
      refreshTimer = null;
    }
    if (handshakeTimer) {
      clearInterval(handshakeTimer);
      handshakeTimer = null;
    }
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }
    heartbeatFailures.clear();
    heartbeatBackoffUntil.clear();
    state.clear();
    peerStates.clear();
    targets.clear();
  }

  async function triggerResync(peerUrl) {
    if (!peerUrl || !storage.getPeerMappingByUrl) return false;
    const normalized = normalizeUrl(peerUrl);
    const mapping = await storage.getPeerMappingByUrl(normalized);
    notePeerPolicyState(normalized, mapping);
    if (!mapping || !isPeerMappingActive(mapping)) return false;
    const gids = Array.isArray(mapping.sync_gids) && mapping.sync_gids.length > 0
      ? mapping.sync_gids
      : defaultGids;
    if (!gids.length) return false;
    const rotatedToken = await ensureRotatedToken(mapping);
    if (!rotatedToken) return false;
    const tlsOptions =
      config.federationMtlsEnabled && normalized.startsWith("https:")
        ? createFederationTlsOptions(config)
        : null;
    await Promise.all(
      gids.map((gid) =>
        syncPeerOnce({
          peerUrl: normalized,
          gid,
          config,
          storage,
          metrics,
          tlsOptions,
          peerToken: rotatedToken,
        }).catch((err) => {
          logEvent("warn", "federation_resync_failed", {
            peer: peerLabel(normalized),
            gid: gidLabel(gid),
            error: err && err.message ? err.message : "unknown",
          });
        })
      )
    );
    logEvent("info", "federation_resync_triggered", { peer: peerLabel(normalized) });
    return true;
  }

  function clearHeartbeatBackoff(peerUrl) {
    if (!peerUrl) return false;
    const normalized = normalizeUrl(peerUrl);
    heartbeatFailures.delete(normalized);
    heartbeatBackoffUntil.delete(normalized);
    logEvent("info", "federation_backoff_cleared", { peer: peerLabel(normalized) });
    return true;
  }

  return { start, stop, triggerResync, clearHeartbeatBackoff };
}

module.exports = { createFederationSync, syncPeerOnce, computeBackoffMs };
