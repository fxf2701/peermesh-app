const { createHmac, randomBytes, randomUUID } = require("crypto");

const TRUST_RANK = {
  community: 0,
  verified: 1,
  trusted: 2,
};

const DEFAULT_PERMISSIONS = ["push", "pull_reconcile"];

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(",")}}`;
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

function trustMeetsMinimum(actual, minimum) {
  return (TRUST_RANK[actual] || 0) >= (TRUST_RANK[minimum] || 0);
}

function effectiveFederationPolicy(config, store) {
  const policy = store.policy(config);
  return {
    minimumTrustLevel: policy.minimum_trust_level_for_federation || config.federationMinTrustLevel || "trusted",
    allowedStatuses: Array.isArray(policy.allowed_statuses_for_federation) && policy.allowed_statuses_for_federation.length > 0
      ? policy.allowed_statuses_for_federation
      : ["active"],
    sessionTtlSeconds: Number(policy.federation_session_ttl_seconds) || config.federationSessionTtlSeconds,
    maxPeersPerSession: Number(policy.max_peers_per_session) || config.federationMaxPeersPerSession,
    allowedPermissions: Array.isArray(policy.allowed_federation_permissions) && policy.allowed_federation_permissions.length > 0
      ? policy.allowed_federation_permissions
      : DEFAULT_PERMISSIONS,
    policyVersion: Number(policy.federation_policy_version) || config.federationPolicyVersion || 1,
  };
}

function sessionForSigning(session) {
  return {
    session_id: session.session_id,
    relay_id: session.relay_id,
    issued_at: session.issued_at,
    expires_at: session.expires_at,
    policy_version: session.policy_version,
    permissions: session.permissions || [],
    peers: session.peers || [],
    limits: session.limits || {},
  };
}

function signSessionGrant(secret, session) {
  return createHmac("sha256", secret).update(stableJson(sessionForSigning(session))).digest("hex");
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

function createSessionToken(secret, sessionId, sourceRelayId, destinationRelayId, context) {
  const random = randomBytes(24).toString("hex");
  const tokenPayload = stableJson({
    session_id: sessionId,
    source_relay_id: sourceRelayId,
    destination_relay_id: destinationRelayId,
    allowed_gids: context.allowedGids || [],
    permissions: context.permissions || [],
    expires_at: context.expiresAt,
    random,
  });
  const mac = createHmac("sha256", secret).update(tokenPayload).digest("hex");
  return `${random}.${mac}`;
}

function validateSessionRequest(body) {
  if (!body || typeof body !== "object") return { ok: false, code: "body_required" };
  const relayId = typeof body.relay_id === "string" ? body.relay_id.trim() : "";
  if (!relayId) return { ok: false, code: "missing_relay_id" };
  if (!/^[a-zA-Z0-9._:-]{1,128}$/.test(relayId)) return { ok: false, code: "invalid_relay_id" };

  const requestedGids = normalizeStringList(body.requested_gids, 128);
  const capabilities = normalizeStringList(body.capabilities, 64);
  const capacity = body.capacity && typeof body.capacity === "object" ? body.capacity : {};

  return {
    ok: true,
    request: {
      relay_id: relayId,
      requested_gids: requestedGids,
      capabilities,
      capacity,
    },
  };
}

function createLimits(config, capacity = {}) {
  const requestedEvents = Number(capacity.max_events_per_minute);
  const requestedBytes = Number(capacity.max_bytes_per_minute);
  const requestedPeers = Number(capacity.max_peers);
  return {
    max_events_per_minute: Math.min(
      Number.isFinite(requestedEvents) && requestedEvents > 0
        ? requestedEvents
        : config.federationDefaultMaxEventsPerMinute,
      config.federationDefaultMaxEventsPerMinute
    ),
    max_bytes_per_minute: Math.min(
      Number.isFinite(requestedBytes) && requestedBytes > 0
        ? requestedBytes
        : config.federationDefaultMaxBytesPerMinute,
      config.federationDefaultMaxBytesPerMinute
    ),
    max_in_flight: Math.min(
      Number.isFinite(requestedPeers) && requestedPeers > 0
        ? requestedPeers
        : config.federationDefaultMaxInFlight,
      config.federationDefaultMaxInFlight
    ),
  };
}

function relayIsEligible(relay, config, policy, now, store) {
  if (!relay) return { ok: false, code: "relay_not_found" };
  if (!policy.allowedStatuses.includes(relay.status)) return { ok: false, code: "relay_not_active" };
  if (now - relay.last_seen > config.activeTtlSeconds) return { ok: false, code: "relay_not_seen_recently" };
  if (!relay.federation_enabled) return { ok: false, code: "federation_not_enabled" };
  if (!trustMeetsMinimum(relay.trust_level, policy.minimumTrustLevel)) {
    return { ok: false, code: "insufficient_trust" };
  }
  if (!relay.capabilities.includes("federation")) return { ok: false, code: "missing_federation_capability" };
  if (config.federationRequireCertificate && !store.activeCertificateForRelay(relay.relay_id, now)) {
    return { ok: false, code: "missing_active_certificate" };
  }
  return { ok: true };
}

function assignPeers({ sourceRelay, requestedGids, limits, config, policy, store, now }) {
  const maxPeers = Math.min(policy.maxPeersPerSession, limits.max_in_flight);
  if (maxPeers <= 0) return [];
  return store
    .listRelays()
    .filter((candidate) => now - candidate.last_seen <= config.activeTtlSeconds)
    .filter((candidate) => candidate.relay_id !== sourceRelay.relay_id)
    .filter((candidate) => relayIsEligible(candidate, config, policy, now, store).ok)
    .slice(0, maxPeers)
    .map((candidate) => ({
      relay_id: candidate.relay_id,
      endpoint: candidate.endpoint,
      trust_level: candidate.trust_level,
      allowed_gids: requestedGids,
      permissions: policy.allowedPermissions,
    }));
}

function createFederationSessionManager({ config, store }) {
  function issue(body, now) {
    if (!config.federationSessionSigningSecret) {
      return { ok: false, status: 500, code: "session_signing_secret_not_configured" };
    }

    const validation = validateSessionRequest(body);
    if (!validation.ok) return { ok: false, status: 400, code: validation.code };
    const policy = effectiveFederationPolicy(config, store);

    const relay = store.getRelay(validation.request.relay_id);
    const eligibility = relayIsEligible(relay, config, policy, now, store);
    if (!eligibility.ok) return { ok: false, status: 403, code: eligibility.code, relayId: validation.request.relay_id };

    const limits = createLimits(config, validation.request.capacity);
    const sessionId = `fs_${randomUUID()}`;
    const requestedGids = validation.request.requested_gids;
    const baseSession = {
      session_id: sessionId,
      relay_id: relay.relay_id,
      issued_at: now,
      expires_at: now + policy.sessionTtlSeconds,
      policy_version: policy.policyVersion,
      permissions: policy.allowedPermissions,
      peers: [],
      requested_gids: requestedGids,
      limits,
      created_at: now,
      updated_at: now,
    };
    baseSession.peers = assignPeers({ sourceRelay: relay, requestedGids, limits, config, policy, store, now })
      .map((peer) => ({
        ...peer,
        session_token: createSessionToken(
          config.federationSessionSigningSecret,
          sessionId,
          relay.relay_id,
          peer.relay_id,
          {
            allowedGids: requestedGids,
            permissions: policy.allowedPermissions,
            expiresAt: baseSession.expires_at,
          }
        ),
      }))
      .map((peer) => {
        const grant = {
          session_id: sessionId,
          source_relay_id: relay.relay_id,
          destination_relay_id: peer.relay_id,
          endpoint: peer.endpoint,
          allowed_gids: peer.allowed_gids,
          permissions: peer.permissions,
          limits,
          expires_at: baseSession.expires_at,
          policy_version: baseSession.policy_version,
          session_token: peer.session_token,
        };
        return {
          ...peer,
          source_relay_id: relay.relay_id,
          expires_at: baseSession.expires_at,
          policy_version: baseSession.policy_version,
          registry_signature: signPeerGrant(config.federationSessionSigningSecret, grant),
        };
      });
    baseSession.registry_signature = signSessionGrant(config.federationSessionSigningSecret, baseSession);

    return { ok: true, status: 201, session: store.insertFederationSession(baseSession) };
  }

  function renew(sessionId, body, now) {
    if (!config.federationSessionSigningSecret) {
      return { ok: false, status: 500, code: "session_signing_secret_not_configured" };
    }
    const relayId = typeof body?.relay_id === "string" ? body.relay_id.trim() : "";
    if (!relayId) return { ok: false, status: 400, code: "missing_relay_id" };

    const existing = store.getFederationSession(sessionId);
    if (!existing) return { ok: false, status: 404, code: "session_not_found" };
    if (existing.revoked_at) return { ok: false, status: 409, code: "session_revoked" };
    if (existing.expires_at <= now) return { ok: false, status: 409, code: "session_expired" };
    if (existing.relay_id !== relayId) return { ok: false, status: 403, code: "relay_mismatch" };
    const policy = effectiveFederationPolicy(config, store);

    const relay = store.getRelay(relayId);
    const eligibility = relayIsEligible(relay, config, policy, now, store);
    if (!eligibility.ok) return { ok: false, status: 403, code: eligibility.code, relayId };

    const session = {
      ...existing,
      expires_at: now + policy.sessionTtlSeconds,
      policy_version: policy.policyVersion,
      permissions: policy.allowedPermissions,
      updated_at: now,
    };
    session.peers = assignPeers({
      sourceRelay: relay,
      requestedGids: session.requested_gids,
      limits: session.limits,
      config,
      policy,
      store,
      now,
    }).map((peer) => ({
      ...peer,
      session_token: createSessionToken(
        config.federationSessionSigningSecret,
        session.session_id,
        relay.relay_id,
        peer.relay_id,
        {
          allowedGids: session.requested_gids,
          permissions: policy.allowedPermissions,
          expiresAt: session.expires_at,
        }
      ),
    })).map((peer) => {
      const grant = {
        session_id: session.session_id,
        source_relay_id: relay.relay_id,
        destination_relay_id: peer.relay_id,
        endpoint: peer.endpoint,
        allowed_gids: peer.allowed_gids,
        permissions: peer.permissions,
        limits: session.limits,
        expires_at: session.expires_at,
        policy_version: session.policy_version,
        session_token: peer.session_token,
      };
      return {
        ...peer,
        source_relay_id: relay.relay_id,
        expires_at: session.expires_at,
        policy_version: session.policy_version,
        registry_signature: signPeerGrant(config.federationSessionSigningSecret, grant),
      };
    });
    session.registry_signature = signSessionGrant(config.federationSessionSigningSecret, session);

    return { ok: true, status: 200, session: store.updateFederationSession(session) };
  }

  function revoke(sessionId, body, now) {
    const relayId = typeof body?.relay_id === "string" ? body.relay_id.trim() : "";
    const existing = store.getFederationSession(sessionId);
    if (!existing) return { ok: false, status: 404, code: "session_not_found" };
    if (relayId && existing.relay_id !== relayId) return { ok: false, status: 403, code: "relay_mismatch" };
    return { ok: true, status: 200, session: store.revokeFederationSession(sessionId, now) };
  }

  return { issue, renew, revoke };
}

module.exports = {
  createFederationSessionManager,
  signPeerGrant,
  signSessionGrant,
  stableJson,
  validateSessionRequest,
};
