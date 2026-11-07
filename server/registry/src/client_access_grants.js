const { createHmac, randomBytes, randomUUID } = require("crypto");
const { effectiveClientAbusePolicy } = require("./client_abuse_reports");

const TRUST_RANK = {
  community: 0,
  verified: 1,
  trusted: 2,
};

const DEFAULT_PERMISSIONS = ["ingest", "fetch"];
const CONNECTION_MODE = "client_selects_and_relay_establishes_session";

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

function effectiveClientAccessPolicy(config, store) {
  const policy = store.policy(config);
  const allowedStatuses =
    Array.isArray(policy.allowed_statuses_for_client_access) &&
    policy.allowed_statuses_for_client_access.length > 0
      ? policy.allowed_statuses_for_client_access
      : ["active"];
  const allowedPermissions =
    Array.isArray(policy.allowed_client_access_permissions) &&
    policy.allowed_client_access_permissions.length > 0
      ? policy.allowed_client_access_permissions.filter((item) => DEFAULT_PERMISSIONS.includes(item))
      : DEFAULT_PERMISSIONS;
  return {
    minimumTrustLevel:
      policy.minimum_trust_level_for_client_access ||
      config.clientAccessMinTrustLevel ||
      "verified",
    allowedStatuses,
    denyRelays: Array.isArray(policy.deny_relays) ? policy.deny_relays : [],
    suspendedRelays: Array.isArray(policy.suspended_relays) ? policy.suspended_relays : [],
    grantTtlSeconds:
      Number.isFinite(Number(policy.client_access_grant_ttl_seconds))
        ? Math.max(60, Math.floor(Number(policy.client_access_grant_ttl_seconds)))
        : config.clientAccessGrantTtlSeconds,
    maxCandidates:
      Number.isFinite(Number(policy.max_candidates_per_client_access_grant))
        ? Math.max(1, Math.floor(Number(policy.max_candidates_per_client_access_grant)))
        : config.clientAccessMaxCandidates,
    allowedPermissions,
    policyVersion:
      Number.isFinite(Number(policy.client_access_policy_version))
        ? Math.max(1, Math.floor(Number(policy.client_access_policy_version)))
        : config.clientAccessPolicyVersion || 1,
    preferSameRegion:
      typeof policy.prefer_same_region_for_client_access === "boolean"
        ? policy.prefer_same_region_for_client_access
        : config.clientAccessPreferSameRegion !== false,
    limits: {
      max_events_per_minute: config.clientAccessDefaultMaxEventsPerMinute,
      max_bytes_per_minute: config.clientAccessDefaultMaxBytesPerMinute,
    },
  };
}

function validateClientAccessRequest(body) {
  if (!body || typeof body !== "object") return { ok: false, code: "body_required" };
  const clientInstanceId =
    typeof body.client_instance_id === "string" ? body.client_instance_id.trim() : "";
  if (!clientInstanceId) return { ok: false, code: "missing_client_instance_id" };
  if (!/^[a-zA-Z0-9._:-]{1,128}$/.test(clientInstanceId)) {
    return { ok: false, code: "invalid_client_instance_id" };
  }

  const requestedGids = normalizeStringList(body.requested_gids, 128);
  if (requestedGids.length === 0) return { ok: false, code: "missing_requested_gids" };

  const requestedOperations = normalizeStringList(body.requested_operations, 8);
  if (requestedOperations.length === 0) return { ok: false, code: "missing_requested_operations" };
  if (requestedOperations.some((item) => !DEFAULT_PERMISSIONS.includes(item))) {
    return { ok: false, code: "invalid_requested_operations" };
  }

  const networkHintValidation = normalizeNetworkHints(body.network_hints);
  if (!networkHintValidation.ok) return { ok: false, code: networkHintValidation.code };

  return {
    ok: true,
    request: {
      client_instance_id: clientInstanceId,
      requested_gids: requestedGids,
      requested_operations: requestedOperations,
      network_hints: networkHintValidation.network_hints,
    },
  };
}

function normalizeNetworkHints(input) {
  const networkHints = input && typeof input === "object"
    ? {
        region: typeof input.region === "string" ? input.region.trim() || null : null,
        transport:
          typeof input.transport === "string" ? input.transport.trim().toLowerCase() || null : null,
      }
    : {};

  if (networkHints.transport && !["http", "https"].includes(networkHints.transport)) {
    return { ok: false, code: "invalid_network_transport" };
  }

  return { ok: true, network_hints: networkHints };
}

function relaySupportsRequest(relay, request) {
  const transport = request.network_hints.transport;
  if (transport && !String(relay.endpoint || "").toLowerCase().startsWith(`${transport}://`)) {
    return false;
  }
  return Array.isArray(relay.capabilities) && relay.capabilities.includes("store_forward");
}

function relayIsEligible(relay, request, policy, config, now) {
  if (!relay) return false;
  if (policy.denyRelays.includes(relay.relay_id)) return false;
  if (policy.suspendedRelays.includes(relay.relay_id)) return false;
  if (!policy.allowedStatuses.includes(relay.status)) return false;
  if (now - relay.last_seen > config.activeTtlSeconds) return false;
  if (!trustMeetsMinimum(relay.trust_level, policy.minimumTrustLevel)) return false;
  if (!relaySupportsRequest(relay, request)) return false;
  return true;
}

function selectCandidateRelays({ request, policy, config, store, now }) {
  const abusePolicy = effectiveClientAbusePolicy(config, store);
  const requestedRegion = request.network_hints.region || null;
  return store
    .listRelays()
    .filter((relay) => relayIsEligible(relay, request, policy, config, now))
    .map((relay) => {
      const latestClientReport = store.getClientRelayLatestAbuseReport(
        request.client_instance_id,
        relay.relay_id,
        now - abusePolicy.pairCooldown
      );
      const pairCooldownUntil = latestClientReport
        ? latestClientReport.created_at + abusePolicy.pairCooldown
        : null;
      const blockedForClient = Boolean(pairCooldownUntil && pairCooldownUntil > now);
      const abuseReports = store.listRelayAbuseReports(
        relay.relay_id,
        now - abusePolicy.deprioritizeWindow
      );
      const distinctClients = new Set();
      let abuseScore = 0;
      for (const report of abuseReports) {
        const age = Math.max(0, now - report.created_at);
        if (age >= abusePolicy.deprioritizeWindow) continue;
        distinctClients.add(report.client_instance_id);
        abuseScore +=
          abusePolicy.singleReportWeight * (1 - age / abusePolicy.deprioritizeWindow);
      }
      if (distinctClients.size > 1) {
        abuseScore += (distinctClients.size - 1) * abusePolicy.distinctClientBonus;
      }
      const corroborated =
        distinctClients.size >= abusePolicy.corroborationClientThreshold ||
        abuseScore >= abusePolicy.corroborationScoreThreshold;
      return {
        relay,
        blockedForClient,
        abuseScore: Number(abuseScore.toFixed(4)),
        corroborated,
      };
    })
    .filter((item) => !item.blockedForClient)
    .filter((item) => !item.corroborated)
    .sort((left, right) => {
      if (left.abuseScore !== right.abuseScore) return left.abuseScore - right.abuseScore;
      if (policy.preferSameRegion && requestedRegion) {
        const leftRegionMatch = left.relay.region === requestedRegion ? 1 : 0;
        const rightRegionMatch = right.relay.region === requestedRegion ? 1 : 0;
        if (leftRegionMatch !== rightRegionMatch) return rightRegionMatch - leftRegionMatch;
      }
      const trustDelta =
        (TRUST_RANK[right.relay.trust_level] || 0) - (TRUST_RANK[left.relay.trust_level] || 0);
      if (trustDelta !== 0) return trustDelta;
      if (left.relay.last_seen !== right.relay.last_seen) {
        return right.relay.last_seen - left.relay.last_seen;
      }
      return left.relay.relay_id.localeCompare(right.relay.relay_id);
    })
    .slice(0, policy.maxCandidates)
    .map(({ relay }) => ({
      relay_id: relay.relay_id,
      endpoint: relay.endpoint,
      trust_level: relay.trust_level,
      region: relay.region || null,
    }));
}

function grantForSigning(grant) {
  return {
    grant_id: grant.grant_id,
    issued_at: grant.issued_at,
    expires_at: grant.expires_at,
    policy_version: grant.policy_version,
    client_instance_id: grant.client_instance_id,
    candidate_relays: grant.candidate_relays || [],
    allowed_gids: grant.allowed_gids || [],
    permissions: grant.permissions || [],
    limits: grant.limits || {},
    access_token: grant.access_token,
    connection_mode: grant.connection_mode,
  };
}

function signClientAccessGrant(secret, grant) {
  return createHmac("sha256", secret).update(stableJson(grantForSigning(grant))).digest("hex");
}

function createAccessToken(secret, grant) {
  const random = randomBytes(24).toString("hex");
  const payload = stableJson({
    grant_id: grant.grant_id,
    client_instance_id: grant.client_instance_id,
    candidate_relays: (grant.candidate_relays || []).map((item) => item.relay_id),
    allowed_gids: grant.allowed_gids || [],
    permissions: grant.permissions || [],
    expires_at: grant.expires_at,
    random,
  });
  const mac = createHmac("sha256", secret).update(payload).digest("hex");
  return `${random}.${mac}`;
}

function createClientAccessGrantManager({ config, store }) {
  function requestFromGrant(grant) {
    return {
      client_instance_id: grant.client_instance_id,
      requested_gids: Array.isArray(grant.allowed_gids) ? grant.allowed_gids : [],
      requested_operations: Array.isArray(grant.permissions) ? grant.permissions : [],
      network_hints: grant.network_hints && typeof grant.network_hints === "object" ? grant.network_hints : {},
    };
  }

  function issue(body, now) {
    if (!config.clientAccessGrantSigningSecret) {
      return { ok: false, status: 500, code: "client_access_grant_signing_secret_not_configured" };
    }

    const validation = validateClientAccessRequest(body);
    if (!validation.ok) return { ok: false, status: 400, code: validation.code };
    const request = validation.request;
    const policy = effectiveClientAccessPolicy(config, store);
    const candidateRelays = selectCandidateRelays({ request, policy, config, store, now });
    if (candidateRelays.length === 0) {
      return { ok: false, status: 503, code: "no_candidate_relays" };
    }
    const grantedPermissions = request.requested_operations.filter((item) => policy.allowedPermissions.includes(item));
    if (grantedPermissions.length === 0) {
      return { ok: false, status: 403, code: "no_allowed_permissions" };
    }

    const grant = {
      grant_id: `cg_${randomUUID()}`,
      issued_at: now,
      expires_at: now + policy.grantTtlSeconds,
      policy_version: policy.policyVersion,
      client_instance_id: request.client_instance_id,
      candidate_relays: candidateRelays,
      allowed_gids: request.requested_gids,
      permissions: grantedPermissions,
      limits: policy.limits,
      access_token: "",
      registry_signature: "",
      connection_mode: CONNECTION_MODE,
      network_hints: request.network_hints,
      created_at: now,
      updated_at: now,
    };
    grant.access_token = createAccessToken(config.clientAccessGrantSigningSecret, grant);
    grant.registry_signature = signClientAccessGrant(config.clientAccessGrantSigningSecret, grant);

    return { ok: true, status: 201, grant: store.insertClientAccessGrant(grant) };
  }

  function renew(grantId, body, now) {
    if (!config.clientAccessGrantSigningSecret) {
      return { ok: false, status: 500, code: "client_access_grant_signing_secret_not_configured" };
    }

    const existing = store.getClientAccessGrant(grantId);
    if (!existing) return { ok: false, status: 404, code: "grant_not_found" };
    if (existing.revoked_at) return { ok: false, status: 409, code: "grant_revoked" };
    if (existing.expires_at <= now) return { ok: false, status: 409, code: "grant_expired" };

    const nextBody = body && typeof body === "object" ? body : {};
    const baseRequest = requestFromGrant(existing);
    const requestedClientInstanceId =
      typeof nextBody.client_instance_id === "string" && nextBody.client_instance_id.trim()
        ? nextBody.client_instance_id.trim()
        : existing.client_instance_id;
    if (requestedClientInstanceId !== existing.client_instance_id) {
      return { ok: false, status: 403, code: "client_instance_mismatch" };
    }

    const requestedGids = Array.isArray(nextBody.requested_gids)
      ? normalizeStringList(nextBody.requested_gids, 128)
      : baseRequest.requested_gids;
    if (requestedGids.length === 0) return { ok: false, status: 400, code: "missing_requested_gids" };

    const requestedOperations = Array.isArray(nextBody.requested_operations)
      ? normalizeStringList(nextBody.requested_operations, 8)
      : baseRequest.requested_operations;
    if (requestedOperations.length === 0) return { ok: false, status: 400, code: "missing_requested_operations" };
    if (requestedOperations.some((item) => !DEFAULT_PERMISSIONS.includes(item))) {
      return { ok: false, status: 400, code: "invalid_requested_operations" };
    }

    const networkHintValidation = Object.prototype.hasOwnProperty.call(nextBody, "network_hints")
      ? normalizeNetworkHints(nextBody.network_hints)
      : { ok: true, network_hints: baseRequest.network_hints };
    if (!networkHintValidation.ok) {
      return { ok: false, status: 400, code: networkHintValidation.code };
    }

    const request = {
      client_instance_id: existing.client_instance_id,
      requested_gids: requestedGids,
      requested_operations: requestedOperations,
      network_hints: networkHintValidation.network_hints,
    };
    const policy = effectiveClientAccessPolicy(config, store);
    const candidateRelays = selectCandidateRelays({ request, policy, config, store, now });
    if (candidateRelays.length === 0) {
      return { ok: false, status: 503, code: "no_candidate_relays" };
    }
    const grantedPermissions = requestedOperations.filter((item) => policy.allowedPermissions.includes(item));
    if (grantedPermissions.length === 0) {
      return { ok: false, status: 403, code: "no_allowed_permissions" };
    }

    const grant = {
      ...existing,
      issued_at: now,
      expires_at: now + policy.grantTtlSeconds,
      policy_version: policy.policyVersion,
      candidate_relays: candidateRelays,
      allowed_gids: requestedGids,
      permissions: grantedPermissions,
      limits: policy.limits,
      access_token: "",
      registry_signature: "",
      connection_mode: CONNECTION_MODE,
      network_hints: request.network_hints,
      updated_at: now,
    };
    grant.access_token = createAccessToken(config.clientAccessGrantSigningSecret, grant);
    grant.registry_signature = signClientAccessGrant(config.clientAccessGrantSigningSecret, grant);

    return { ok: true, status: 200, grant: store.updateClientAccessGrant(grant) };
  }

  return { issue, renew };
}

module.exports = {
  CONNECTION_MODE,
  createClientAccessGrantManager,
  signClientAccessGrant,
};
