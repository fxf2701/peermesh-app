const { signRegistryRequest } = require("./registry_request_signing");

function nowEpochSeconds() {
  return Math.floor(Date.now() / 1000);
}

function normalizeBaseUrl(raw) {
  return String(raw || "").replace(/\/+$/, "");
}

function relayCapabilities() {
  return ["store_forward", "capsule_push", "capsule_pull", "federation"];
}

function sessionIsActive(session, now = nowEpochSeconds()) {
  return Boolean(session && !session.revoked_at && Number(session.expires_at) > now);
}

function createRegistrySessionClient({
  config,
  fetchImpl = global.fetch,
  onLog = () => {},
  nowSeconds = nowEpochSeconds,
} = {}) {
  let timer = null;
  let inFlight = null;
  let activeSession = null;
  let lastError = "";
  let lastIssueAt = null;
  let lastRenewAt = null;
  let lastRevocationsAt = null;
  let nextRefreshAt = null;
  let nextRevocationsRefreshAt = null;
  let revocationsInFlight = null;
  let stopped = false;
  const revokedSessions = new Map();

  function activeSessionRevoked() {
    return Boolean(activeSession && revokedSessions.has(String(activeSession.session_id || "")));
  }

  function enabled() {
    return (
      config.registryFederationSessionsEnabled === true &&
      !!config.registryUrl &&
      !!config.registrySharedSecret &&
      !!config.federationSelfId &&
      typeof fetchImpl === "function"
    );
  }

  function selfRelayId() {
    return config.federationSelfId || config.federationSelfUrl || "relay";
  }

  function capacity() {
    return {
      max_peers: config.registryFederationSessionMaxPeers || config.federationMaxPeers || 4,
      max_events_per_minute: config.registryFederationSessionMaxEventsPerMinute || 600,
      max_bytes_per_minute: config.registryFederationSessionMaxBytesPerMinute || 10485760,
    };
  }

  function status() {
    const now = nowSeconds();
    return {
      enabled: enabled(),
      status: activeSessionRevoked()
        ? "revoked"
        : activeSession && sessionIsActive(activeSession, now)
          ? "active"
          : lastError
            ? "error"
            : "idle",
      relay_id: selfRelayId(),
      registry_url: config.registryUrl || "",
      session: activeSession ? { ...activeSession } : null,
      active: sessionIsActive(activeSession, now) && !activeSessionRevoked(),
      last_error: lastError,
      last_issue_at: lastIssueAt,
      last_renew_at: lastRenewAt,
      last_revocations_at: lastRevocationsAt,
      next_refresh_at: nextRefreshAt,
      next_revocations_refresh_at: nextRevocationsRefreshAt,
      revoked_sessions_cached: revokedSessions.size,
    };
  }

  async function signedJson(path, body) {
    const rawBody = JSON.stringify(body || {});
    const timestamp = String(nowSeconds());
    const signature = signRegistryRequest({
      secret: config.registrySharedSecret,
      method: "POST",
      path,
      timestamp,
      rawBody,
    });
    const response = await fetchImpl(`${normalizeBaseUrl(config.registryUrl)}${path}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Registry-Timestamp": timestamp,
        "X-Registry-Signature": signature,
      },
      body: rawBody,
    });
    const text = await response.text();
    let payload = null;
    try {
      payload = text ? JSON.parse(text) : null;
    } catch (_) {
      payload = null;
    }
    if (!response.ok) {
      const code = payload?.error?.code || `http_${response.status}`;
      const err = new Error(code);
      err.status = response.status;
      err.code = code;
      err.payload = payload;
      throw err;
    }
    return payload;
  }

  async function getJson(path) {
    const response = await fetchImpl(`${normalizeBaseUrl(config.registryUrl)}${path}`, {
      method: "GET",
    });
    const text = await response.text();
    let payload = null;
    try {
      payload = text ? JSON.parse(text) : null;
    } catch (_) {
      payload = null;
    }
    if (!response.ok) {
      const code = payload?.error?.code || `http_${response.status}`;
      const err = new Error(code);
      err.status = response.status;
      err.code = code;
      err.payload = payload;
      throw err;
    }
    return payload;
  }

  function scheduleNext(session, now) {
    if (!sessionIsActive(session, now)) {
      nextRefreshAt = now + Math.max(5, config.registryFederationSessionRetrySeconds || 30);
      return;
    }
    const issuedAt = Number(session.issued_at || now);
    const expiresAt = Number(session.expires_at || now);
    const midpoint = issuedAt + Math.max(1, Math.floor((expiresAt - issuedAt) / 2));
    const renewAhead = Math.max(5, config.registryFederationSessionRenewAheadSeconds || 60);
    nextRefreshAt = Math.min(midpoint, Math.max(now + 1, expiresAt - renewAhead));
  }

  async function issueSession() {
    const now = nowSeconds();
    const payload = await signedJson("/v1/registry/federation/sessions", {
      relay_id: selfRelayId(),
      capabilities: relayCapabilities(),
      requested_gids: config.federationSyncGids || [],
      capacity: capacity(),
    });
    activeSession = payload.session || null;
    lastError = "";
    lastIssueAt = now;
    scheduleNext(activeSession, now);
    onLog("info", "registry_federation_session_issued", {
      session_id: activeSession?.session_id || null,
      peers: Array.isArray(activeSession?.peers) ? activeSession.peers.length : 0,
      expires_at: activeSession?.expires_at || null,
    });
    return activeSession;
  }

  async function renewSession() {
    const now = nowSeconds();
    if (!activeSession || !activeSession.session_id) return issueSession();
    const payload = await signedJson(
      `/v1/registry/federation/sessions/${encodeURIComponent(activeSession.session_id)}:renew`,
      { relay_id: selfRelayId() }
    );
    activeSession = payload.session || null;
    lastError = "";
    lastRenewAt = now;
    scheduleNext(activeSession, now);
    onLog("info", "registry_federation_session_renewed", {
      session_id: activeSession?.session_id || null,
      peers: Array.isArray(activeSession?.peers) ? activeSession.peers.length : 0,
      expires_at: activeSession?.expires_at || null,
    });
    return activeSession;
  }

  async function refresh({ force = false } = {}) {
    if (!enabled()) return null;
    if (inFlight) return inFlight;

    const operation = (async () => {
      const now = nowSeconds();
      try {
        if (!force && activeSession && sessionIsActive(activeSession, now) && nextRefreshAt && now < nextRefreshAt) {
          return activeSession;
        }
        if (activeSession && sessionIsActive(activeSession, now)) {
          return await renewSession();
        }
        return await issueSession();
      } catch (err) {
        lastError = err.code || err.message || String(err);
        if (!sessionIsActive(activeSession, now)) activeSession = null;
        nextRefreshAt = now + Math.max(5, config.registryFederationSessionRetrySeconds || 30);
        onLog("warn", "registry_federation_session_failed", {
          error: lastError,
          status: err.status || null,
          next_refresh_at: nextRefreshAt,
        });
        return activeSession;
      }
    })();

    inFlight = operation;
    try {
      return await operation;
    } finally {
      if (inFlight === operation) inFlight = null;
    }
  }

  async function refreshRevocations({ force = false } = {}) {
    if (!enabled()) return null;
    if (revocationsInFlight) return revocationsInFlight;

    const operation = (async () => {
      const now = nowSeconds();
      const pollSeconds = Math.max(5, config.registryFederationRevocationsPollSeconds || 60);
      if (!force && nextRevocationsRefreshAt && now < nextRevocationsRefreshAt) {
        return revokedSessions;
      }
      try {
        const payload = await getJson("/v1/registry/federation/revocations");
        revokedSessions.clear();
        const items = Array.isArray(payload?.items)
          ? payload.items
          : Array.isArray(payload?.revoked_sessions)
            ? payload.revoked_sessions.map((sessionId) => ({ session_id: sessionId }))
            : [];
        for (const item of items) {
          const sessionId = String(item.session_id || item);
          if (sessionId) revokedSessions.set(sessionId, item);
        }
        if (activeSessionRevoked()) {
          const revoked = revokedSessions.get(String(activeSession.session_id || ""));
          activeSession = {
            ...activeSession,
            revoked_at: revoked && revoked.revoked_at ? revoked.revoked_at : now,
          };
          nextRefreshAt = now + 1;
          onLog("warn", "registry_federation_active_session_revoked", {
            session_id: activeSession.session_id,
            revoked_at: activeSession.revoked_at,
          });
        }
        lastRevocationsAt = now;
        nextRevocationsRefreshAt = now + pollSeconds;
        return revokedSessions;
      } catch (err) {
        lastError = err.code || err.message || String(err);
        nextRevocationsRefreshAt = now + pollSeconds;
        onLog("warn", "registry_federation_revocations_failed", {
          error: lastError,
          status: err.status || null,
          next_refresh_at: nextRevocationsRefreshAt,
        });
        return revokedSessions;
      }
    })();

    revocationsInFlight = operation;
    try {
      return await operation;
    } finally {
      if (revocationsInFlight === operation) revocationsInFlight = null;
    }
  }

  async function revoke(reason = "relay_shutdown") {
    if (!activeSession || !activeSession.session_id || !enabled()) return null;
    const sessionId = activeSession.session_id;
    try {
      const payload = await signedJson(
        `/v1/registry/federation/sessions/${encodeURIComponent(sessionId)}:revoke`,
        { relay_id: selfRelayId(), reason }
      );
      activeSession = payload.session || null;
      lastError = "";
      onLog("info", "registry_federation_session_revoked", { session_id: sessionId });
      return activeSession;
    } catch (err) {
      lastError = err.code || err.message || String(err);
      onLog("warn", "registry_federation_session_revoke_failed", {
        session_id: sessionId,
        error: lastError,
      });
      return activeSession;
    }
  }

  function tick() {
    if (stopped) return;
    refresh().catch((err) => {
      lastError = err.message || String(err);
    });
    refreshRevocations().catch((err) => {
      lastError = err.message || String(err);
    });
  }

  function start() {
    if (!enabled()) {
      onLog("info", "registry_federation_sessions_disabled");
      return;
    }
    stopped = false;
    onLog("info", "registry_federation_sessions_started", {
      registry_url: config.registryUrl,
      relay_id: selfRelayId(),
    });
    tick();
    const intervalSeconds = Math.max(5, config.registryFederationSessionRefreshIntervalSeconds || 30);
    timer = setInterval(tick, intervalSeconds * 1000);
  }

  function stop() {
    stopped = true;
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  return {
    enabled,
    start,
    stop,
    refresh,
    revoke,
    status,
    refreshRevocations,
    isSessionRevoked: (sessionId) => revokedSessions.has(String(sessionId || "")),
    getActiveSession: () => {
      if (!sessionIsActive(activeSession, nowSeconds())) return null;
      if (activeSessionRevoked()) return null;
      return activeSession;
    },
  };
}

module.exports = { createRegistrySessionClient, sessionIsActive };
