// Push-first managed federation data-plane helpers.

const { createHash, createHmac, timingSafeEqual } = require("crypto");
const { logEvent } = require("./logger");

function nowEpochSeconds() {
  return Math.floor(Date.now() / 1000);
}

function normalizeBaseUrl(raw) {
  return String(raw || "").replace(/\/+$/, "");
}

function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}

function signFederationRequest({ token, method, path, timestamp, rawBody }) {
  const bodyHash = sha256Hex(rawBody || "");
  const canonical = [String(method || "POST").toUpperCase(), path, String(timestamp), bodyHash].join("\n");
  return createHmac("sha256", token).update(canonical).digest("hex");
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function safeEqualHex(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const aBuffer = Buffer.from(a, "hex");
  const bBuffer = Buffer.from(b, "hex");
  return aBuffer.length === bBuffer.length && timingSafeEqual(aBuffer, bBuffer);
}

function peerGrantForSigning(grant) {
  return {
    session_id: grant.session_id,
    source_relay_id: grant.source_relay_id,
    destination_relay_id: grant.destination_relay_id,
    endpoint: grant.endpoint,
    allowed_gids: grant.allowed_gids || [],
    permissions: grant.permissions || [],
    limits: grant.limits || {},
    expires_at: grant.expires_at,
    policy_version: grant.policy_version,
    session_token: grant.session_token,
  };
}

function signPeerGrant(secret, grant) {
  return createHmac("sha256", secret).update(stableJson(peerGrantForSigning(grant))).digest("hex");
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

function eventAllowedForPeer(event, peer, session) {
  const permissions = peerPermissions(peer, session);
  if (permissions.length > 0 && !permissions.includes("push")) return false;

  const allowedGids = peerAllowedGids(peer, session);
  if (allowedGids.length === 0) return true;
  return allowedGids.includes(String(event.gid || ""));
}

function createPeerGrant({ session, peer }) {
  return {
    session_id: session.session_id,
    source_relay_id: session.relay_id || peer.source_relay_id || "",
    destination_relay_id: peerRelayId(peer),
    endpoint: peerEndpoint(peer),
    allowed_gids: peerAllowedGids(peer, session),
    permissions: peerPermissions(peer, session),
    limits: peer.limits || session.limits || {},
    expires_at: peer.expires_at || session.expires_at,
    policy_version: peer.policy_version || session.policy_version,
    session_token: peerToken(peer),
    registry_signature: peer.registry_signature || "",
  };
}

function validateFederationGrant({
  config,
  grant,
  sessionId,
  token,
  operation,
  gids,
  sourceRelayId,
  destinationRelayId,
  nowSeconds,
}) {
  if (!config.registryFederationSessionSigningSecret) {
    return { ok: false, code: "registry_session_signing_secret_missing" };
  }
  if (!grant || typeof grant !== "object") {
    return { ok: false, code: "federation_grant_required" };
  }
  if (grant.session_id !== sessionId) return { ok: false, code: "session_mismatch" };
  if (grant.session_token !== token) return { ok: false, code: "token_mismatch" };
  if (sourceRelayId && grant.source_relay_id !== sourceRelayId) {
    return { ok: false, code: "source_relay_mismatch" };
  }
  if (destinationRelayId && grant.destination_relay_id !== destinationRelayId) {
    return { ok: false, code: "destination_relay_mismatch" };
  }
  if (!grant.registry_signature) return { ok: false, code: "missing_registry_signature" };

  const expectedGrantSignature = signPeerGrant(config.registryFederationSessionSigningSecret, grant);
  if (!safeEqualHex(grant.registry_signature, expectedGrantSignature)) {
    return { ok: false, code: "invalid_registry_signature" };
  }

  if (Number(grant.expires_at) <= nowSeconds) return { ok: false, code: "session_expired" };
  if (String(grant.destination_relay_id || "") !== String(config.federationSelfId || "")) {
    return { ok: false, code: "wrong_destination_relay" };
  }
  if (!String(grant.source_relay_id || "")) return { ok: false, code: "missing_source_relay" };

  const requiredOperation = String(operation || "push");
  const permissions = asArray(grant.permissions).map((item) => String(item));
  if (permissions.length > 0 && !permissions.includes(requiredOperation)) {
    return { ok: false, code: "operation_not_allowed" };
  }

  const allowedGids = asArray(grant.allowed_gids).map((item) => String(item)).filter(Boolean);
  if (allowedGids.length > 0) {
    for (const gid of asArray(gids)) {
      if (!allowedGids.includes(String(gid || ""))) {
        return { ok: false, code: "gid_not_allowed" };
      }
    }
  }

  return { ok: true, sourceRelayId: grant.source_relay_id };
}

function validateFederationPushGrant({
  config,
  grant,
  sessionId,
  token,
  events,
  sourceRelayId,
  destinationRelayId,
  nowSeconds,
}) {
  return validateFederationGrant({
    config,
    grant,
    sessionId,
    token,
    operation: "push",
    gids: asArray(events).map((event) => event && event.gid),
    sourceRelayId,
    destinationRelayId,
    nowSeconds,
  });
}

function createFederationPush({
  config,
  registrySessions,
  metrics,
  fetchImpl = global.fetch,
  onLog = logEvent,
  nowSeconds = nowEpochSeconds,
} = {}) {
  const queue = [];
  let timer = null;
  let inFlight = 0;
  let stopped = false;
  let drops = 0;
  let success = 0;
  let failure = 0;
  let lastError = "";

  const maxQueue = Math.max(0, config.federationPushMaxQueue || 1000);
  const maxBatchEvents = Math.max(1, config.federationPushMaxBatchEvents || 100);
  const retryBaseMs = Math.max(100, config.federationPushRetryBaseMs || 500);
  const retryMaxMs = Math.max(retryBaseMs, config.federationPushRetryMaxMs || 30000);
  const workers = Math.max(1, config.federationPushWorkers || 1);

  function enabled() {
    return config.federationPushEnabled === true && typeof fetchImpl === "function";
  }

  function nextBackoffMs(attempt) {
    const exp = Math.pow(2, Math.max(0, attempt));
    return Math.min(retryMaxMs, retryBaseMs * exp);
  }

  function activeSession() {
    return registrySessions && registrySessions.getActiveSession
      ? registrySessions.getActiveSession()
      : null;
  }

  function status() {
    return {
      enabled: enabled(),
      queue_depth: queue.length,
      in_flight: inFlight,
      drops,
      success,
      failure,
      last_error: lastError,
    };
  }

  function setMetrics() {
    if (!metrics) return;
    metrics.setGauge("federation_push_queue_depth", queue.length);
    metrics.setGauge("federation_push_in_flight", inFlight);
  }

  function enqueue(events, { source = "ingest" } = {}) {
    if (!enabled()) return { queued: 0, skipped: true, reason: "disabled" };
    const session = activeSession();
    if (!session || !Array.isArray(session.peers) || session.peers.length === 0) {
      return { queued: 0, skipped: true, reason: "no_active_session" };
    }

    const candidates = asArray(events).filter(Boolean);
    let queued = 0;
    for (const peer of session.peers) {
      const endpoint = peerEndpoint(peer);
      const token = peerToken(peer);
      if (!endpoint || !token) continue;
      const peerEvents = candidates.filter((event) => eventAllowedForPeer(event, peer, session));
      if (peerEvents.length === 0) continue;

      for (let index = 0; index < peerEvents.length; index += maxBatchEvents) {
        if (queue.length >= maxQueue) {
          drops += 1;
          if (metrics) metrics.inc("federation_push_drops_total");
          onLog("warn", "federation_push_queue_full", { endpoint, queue_depth: queue.length });
          continue;
        }
        queue.push({
          endpoint,
          relay_id: peerRelayId(peer),
          token,
          session_id: session.session_id,
          peer_grant: createPeerGrant({ session, peer }),
          events: peerEvents.slice(index, index + maxBatchEvents),
          attempt: 0,
          next_attempt_at_ms: Date.now(),
          source,
        });
        queued += 1;
      }
    }

    setMetrics();
    if (queued > 0 && metrics) metrics.inc("federation_push_enqueued_total", queued);
    return { queued, skipped: false };
  }

  async function pushItem(item) {
    const path = "/v1/federation/events:push";
    const rawBody = JSON.stringify({
      session_id: item.session_id,
      source_relay_id: item.peer_grant.source_relay_id,
      destination_relay_id: item.peer_grant.destination_relay_id,
      peer_grant: item.peer_grant,
      events: item.events,
    });
    const timestamp = String(nowSeconds());
    const signature = signFederationRequest({
      token: item.token,
      method: "POST",
      path,
      timestamp,
      rawBody,
    });
    const response = await fetchImpl(`${normalizeBaseUrl(item.endpoint)}${path}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Federation-Session": item.session_id,
        "X-Federation-Token": item.token,
        "X-Federation-Timestamp": timestamp,
        "X-Federation-Signature": signature,
      },
      body: rawBody,
    });
    const text = await response.text();
    if (!response.ok) {
      const error = new Error(`federation push failed: ${response.status}`);
      error.status = response.status;
      error.body = text;
      throw error;
    }
    return text ? JSON.parse(text) : null;
  }

  async function drainOne() {
    if (stopped || inFlight >= workers || queue.length === 0) return false;
    const nowMs = Date.now();
    const index = queue.findIndex((item) => item.next_attempt_at_ms <= nowMs);
    if (index < 0) return false;
    const item = queue.splice(index, 1)[0];
    inFlight += 1;
    setMetrics();
    try {
      const payload = await pushItem(item);
      success += 1;
      lastError = "";
      if (metrics) metrics.inc("federation_push_success_total");
      onLog("info", "federation_push_ok", {
        peer: item.relay_id,
        endpoint: item.endpoint,
        session_id: item.session_id,
        events: item.events.length,
      });
      return payload;
    } catch (err) {
      failure += 1;
      lastError = err.message || String(err);
      if (metrics) metrics.inc("federation_push_fail_total");
      item.attempt += 1;
      item.next_attempt_at_ms = Date.now() + nextBackoffMs(item.attempt);
      if (queue.length < maxQueue) {
        queue.push(item);
      } else {
        drops += 1;
        if (metrics) metrics.inc("federation_push_drops_total");
      }
      onLog("warn", "federation_push_failed", {
        peer: item.relay_id,
        endpoint: item.endpoint,
        session_id: item.session_id,
        events: item.events.length,
        attempt: item.attempt,
        error: lastError,
      });
      return null;
    } finally {
      inFlight -= 1;
      setMetrics();
    }
  }

  function tick() {
    if (stopped) return;
    for (let i = 0; i < workers; i += 1) {
      drainOne().catch((err) => {
        lastError = err.message || String(err);
      });
    }
  }

  function start() {
    if (!enabled()) {
      onLog("info", "federation_push_disabled");
      return;
    }
    stopped = false;
    const intervalMs = Math.max(100, config.federationPushIntervalMs || 500);
    timer = setInterval(tick, intervalMs);
    onLog("info", "federation_push_started", { interval_ms: intervalMs, workers });
  }

  function stop() {
    stopped = true;
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  return { enabled, start, stop, enqueue, status, _pushItem: pushItem };
}

module.exports = {
  createFederationPush,
  createPeerGrant,
  signPeerGrant,
  signFederationRequest,
  validateFederationGrant,
  validateFederationPushGrant,
};
