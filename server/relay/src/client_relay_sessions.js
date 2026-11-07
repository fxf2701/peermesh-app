const { createHmac, randomBytes, randomUUID, timingSafeEqual } = require("crypto");
const { validateClientAccessGrant } = require("./client_access_grants");

function nowEpochSeconds() {
  return Math.floor(Date.now() / 1000);
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

function normalizeStringList(value, maxItems = 64) {
  if (!Array.isArray(value)) return [];
  const seen = new Set();
  const result = [];
  for (const item of value) {
    if (typeof item !== "string") continue;
    const trimmed = item.trim();
    if (!trimmed || seen.has(trimmed)) continue;
    seen.add(trimmed);
    result.push(trimmed);
    if (result.length >= maxItems) break;
  }
  return result;
}

function safeEqualText(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const aBuffer = Buffer.from(a);
  const bBuffer = Buffer.from(b);
  return aBuffer.length === bBuffer.length && timingSafeEqual(aBuffer, bBuffer);
}

function selfRelayId(config) {
  return String(config.relaySelfId || config.federationSelfId || "").trim();
}

function sessionIsActive(session, now = nowEpochSeconds()) {
  return Boolean(session && !session.closed_at && Number(session.expires_at) > now);
}

function allowedScopeIntersection(grantValues, requestedValues) {
  const allowed = normalizeStringList(grantValues, 256);
  const requested = normalizeStringList(requestedValues, 256);
  if (allowed.length === 0 && requested.length === 0) return [];
  if (allowed.length === 0) return requested;
  if (requested.length === 0) return allowed;
  const allowedSet = new Set(allowed);
  return requested.filter((item) => allowedSet.has(item));
}

function createSessionBindingToken(secret, session) {
  const random = randomBytes(24).toString("hex");
  const payload = stableJson({
    session_id: session.session_id,
    grant_id: session.grant_id,
    client_instance_id: session.client_instance_id,
    relay_id: session.relay_id,
    allowed_gids: session.allowed_gids,
    permissions: session.permissions,
    expires_at: session.expires_at,
    random,
  });
  const mac = createHmac("sha256", secret).update(payload).digest("hex");
  return `${random}.${mac}`;
}

function createClientRelaySessionManager({
  config,
  onLog = () => {},
  nowSeconds = nowEpochSeconds,
} = {}) {
  const sessions = new Map();
  let cleanupTimer = null;

  function enabled() {
    return config.clientRelaySessionsEnabled === true;
  }

  function ttlSeconds() {
    return Math.max(30, Number(config.clientRelaySessionTtlSeconds || 300));
  }

  function cleanupExpired(now = nowSeconds()) {
    for (const [sessionId, session] of sessions.entries()) {
      if (!sessionIsActive(session, now)) {
        sessions.delete(sessionId);
      }
    }
  }

  if (enabled() && Number(config.clientRelaySessionCleanupIntervalSeconds || 30) > 0) {
    cleanupTimer = setInterval(
      () => cleanupExpired(nowSeconds()),
      Math.max(5, Number(config.clientRelaySessionCleanupIntervalSeconds || 30)) * 1000
    );
  }

  function issueSession({
    grant,
    accessToken,
    clientInstanceId,
    requestedGids,
    requestedOperations,
    clientNonce,
    clientSessionMaterial,
    now,
    existingSessionId = null,
  }) {
    const currentNow = Number(now || nowSeconds());
    const grantValidation = validateClientAccessGrant({
      config,
      grant,
      accessToken,
      nowSeconds: currentNow,
    });
    if (!grantValidation.ok) {
      return { ok: false, status: 403, code: grantValidation.code };
    }

    if (String(clientInstanceId || "").trim() !== String(grant.client_instance_id || "").trim()) {
      return { ok: false, status: 403, code: "client_instance_mismatch" };
    }

    const allowedGids = allowedScopeIntersection(grant.allowed_gids, requestedGids);
    if (allowedGids.length === 0 && normalizeStringList(grant.allowed_gids, 256).length > 0) {
      return { ok: false, status: 403, code: "gid_not_allowed" };
    }

    const allowedPermissions = allowedScopeIntersection(grant.permissions, requestedOperations);
    if (allowedPermissions.length === 0) {
      return { ok: false, status: 403, code: "operation_not_allowed" };
    }

    const relayId = selfRelayId(config);
    if (!relayId) {
      return { ok: false, status: 500, code: "relay_identity_not_configured" };
    }

    const maxPerClient = Math.max(1, Number(config.clientRelaySessionMaxPerClient || 8));
    const activeForClient = [...sessions.values()].filter(
      (item) => item.client_instance_id === String(clientInstanceId || "").trim() && sessionIsActive(item, currentNow)
    );
    if (!existingSessionId && activeForClient.length >= maxPerClient) {
      return { ok: false, status: 429, code: "too_many_client_sessions" };
    }

    const grantExpiresAt = Number(grant.expires_at || 0);
    const expiresAt = Math.min(currentNow + ttlSeconds(), grantExpiresAt);
    if (expiresAt <= currentNow) {
      return { ok: false, status: 403, code: "grant_expired" };
    }

    const session = {
      session_id: existingSessionId || `crs_${randomUUID()}`,
      relay_id: relayId,
      grant_id: String(grant.grant_id || ""),
      client_instance_id: String(clientInstanceId || "").trim(),
      allowed_gids: allowedGids,
      permissions: allowedPermissions,
      issued_at: currentNow,
      expires_at: expiresAt,
      relay_nonce: randomBytes(18).toString("base64url"),
      session_binding_token: "",
      relay_session_material:
        clientSessionMaterial && typeof clientSessionMaterial === "object"
          ? {
              version: Number(clientSessionMaterial.version) || 1,
              mode: String(clientSessionMaterial.mode || "session_binding"),
            }
          : { version: 1, mode: "session_binding" },
      client_session_material:
        clientSessionMaterial && typeof clientSessionMaterial === "object"
          ? { ...clientSessionMaterial }
          : { version: 1, mode: "session_binding" },
      client_nonce: typeof clientNonce === "string" ? clientNonce.trim() || null : null,
      access_grant_expires_at: grantExpiresAt,
      access_grant_scope: {
        grant_id: String(grant.grant_id || ""),
        policy_version: Number(grant.policy_version || 1),
        allowed_gids: normalizeStringList(grant.allowed_gids, 256),
        permissions: normalizeStringList(grant.permissions, 16),
      },
      access_grant_snapshot: {
        ...grant,
        candidate_relays: asArray(grant.candidate_relays).map((item) => ({ ...item })),
        allowed_gids: normalizeStringList(grant.allowed_gids, 256),
        permissions: normalizeStringList(grant.permissions, 16),
        limits:
          grant.limits && typeof grant.limits === "object" ? { ...grant.limits } : {},
      },
      created_at: currentNow,
      updated_at: currentNow,
      closed_at: null,
    };
    session.session_binding_token = createSessionBindingToken(
      config.clientRelaySessionBindingSecret,
      session
    );
    sessions.set(session.session_id, session);
    cleanupExpired(currentNow);
    return { ok: true, status: existingSessionId ? 200 : 201, session };
  }

  function open(request, grant, now = nowSeconds()) {
    if (!enabled()) return { ok: false, status: 404, code: "client_relay_sessions_disabled" };
    if (!config.clientRelaySessionBindingSecret) {
      return { ok: false, status: 500, code: "client_relay_session_binding_secret_missing" };
    }
    if (!request || typeof request !== "object") {
      return { ok: false, status: 400, code: "body_required" };
    }
    if (String(request.relay_id || "").trim() !== selfRelayId(config)) {
      return { ok: false, status: 403, code: "wrong_destination_relay" };
    }
    return issueSession({
      grant,
      accessToken: request.access_token,
      clientInstanceId: request.client_instance_id,
      requestedGids: request.requested_gids,
      requestedOperations: request.requested_operations,
      clientNonce: request.client_nonce,
      clientSessionMaterial: request.client_session_material,
      now,
    });
  }

  function renew(sessionId, request, grant, now = nowSeconds()) {
    if (!enabled()) return { ok: false, status: 404, code: "client_relay_sessions_disabled" };
    const existing = sessions.get(String(sessionId || ""));
    if (!existing) return { ok: false, status: 404, code: "session_not_found" };
    if (!sessionIsActive(existing, now)) {
      sessions.delete(existing.session_id);
      return { ok: false, status: 409, code: "session_expired" };
    }
    if (!request || typeof request !== "object") {
      return { ok: false, status: 400, code: "body_required" };
    }
    if (
      String(request.client_instance_id || "").trim() &&
      String(request.client_instance_id || "").trim() !== existing.client_instance_id
    ) {
      return { ok: false, status: 403, code: "client_instance_mismatch" };
    }
    if (grant && String(grant.grant_id || "") !== existing.grant_id) {
      return { ok: false, status: 403, code: "grant_mismatch" };
    }
    return issueSession({
      grant: grant || existing.access_grant_snapshot,
      accessToken: request.access_token || existing.access_grant_snapshot?.access_token,
      clientInstanceId: existing.client_instance_id,
      requestedGids: request.requested_gids,
      requestedOperations: request.requested_operations,
      clientNonce: request.client_nonce || existing.client_nonce,
      clientSessionMaterial: request.client_session_material || existing.client_session_material,
      now,
      existingSessionId: existing.session_id,
    });
  }

  function close(sessionId, request = {}, now = nowSeconds()) {
    if (!enabled()) return { ok: false, status: 404, code: "client_relay_sessions_disabled" };
    const existing = sessions.get(String(sessionId || ""));
    if (!existing) return { ok: false, status: 404, code: "session_not_found" };
    if (
      String(request.client_instance_id || "").trim() &&
      String(request.client_instance_id || "").trim() !== existing.client_instance_id
    ) {
      return { ok: false, status: 403, code: "client_instance_mismatch" };
    }
    const closed = { ...existing, closed_at: Number(now), updated_at: Number(now) };
    sessions.delete(existing.session_id);
    return { ok: true, status: 200, session: closed };
  }

  function validateRequestBinding({
    sessionId,
    sessionToken,
    grantId,
    clientInstanceId,
    operation,
    gids,
    now = nowSeconds(),
  }) {
    const session = sessions.get(String(sessionId || ""));
    if (!session) return { ok: false, code: "client_session_required" };
    if (!sessionIsActive(session, now)) {
      sessions.delete(session.session_id);
      return { ok: false, code: "client_session_expired" };
    }
    if (!sessionToken || !safeEqualText(String(session.session_binding_token || ""), String(sessionToken || ""))) {
      return { ok: false, code: "client_session_token_mismatch" };
    }
    if (grantId && String(grantId) !== String(session.grant_id || "")) {
      return { ok: false, code: "grant_mismatch" };
    }
    if (clientInstanceId && String(clientInstanceId) !== String(session.client_instance_id || "")) {
      return { ok: false, code: "client_instance_mismatch" };
    }
    const permissions = normalizeStringList(session.permissions, 16);
    if (operation && permissions.length > 0 && !permissions.includes(String(operation))) {
      return { ok: false, code: "operation_not_allowed" };
    }
    const allowedGids = normalizeStringList(session.allowed_gids, 256);
    if (allowedGids.length > 0) {
      for (const gid of asArray(gids)) {
        if (!allowedGids.includes(String(gid || ""))) {
          return { ok: false, code: "gid_not_allowed" };
        }
      }
    }
    return { ok: true, session };
  }

  function get(sessionId) {
    const session = sessions.get(String(sessionId || ""));
    return session ? { ...session } : null;
  }

  function stats(now = nowSeconds()) {
    cleanupExpired(now);
    return {
      enabled: enabled(),
      relay_id: selfRelayId(config),
      active_sessions: [...sessions.values()].filter((item) => sessionIsActive(item, now)).length,
    };
  }

  function shutdown() {
    if (cleanupTimer) {
      clearInterval(cleanupTimer);
      cleanupTimer = null;
    }
    sessions.clear();
    onLog("info", "client_relay_sessions_stopped", { relay_id: selfRelayId(config) || null });
  }

  return {
    enabled,
    open,
    renew,
    close,
    get,
    stats,
    validateRequestBinding,
    shutdown,
  };
}

module.exports = {
  createClientRelaySessionManager,
  sessionIsActive,
};
