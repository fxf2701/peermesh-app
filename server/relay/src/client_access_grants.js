const { createHmac, timingSafeEqual } = require("crypto");

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

function safeEqualHex(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  try {
    const aBuffer = Buffer.from(a, "hex");
    const bBuffer = Buffer.from(b, "hex");
    return aBuffer.length === bBuffer.length && timingSafeEqual(aBuffer, bBuffer);
  } catch (_) {
    return false;
  }
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
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

function decodeClientAccessGrantHeader(value) {
  if (!value) return null;
  const trimmed = String(value).trim();
  if (!trimmed) return null;
  try {
    if (trimmed.startsWith("{")) {
      const parsed = JSON.parse(trimmed);
      return parsed && typeof parsed === "object" ? parsed : null;
    }
    const raw = Buffer.from(trimmed, "base64url").toString("utf8");
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch (_) {
    return null;
  }
}

function relayMatchesGrant(config, grant) {
  const selfId = String(config.relaySelfId || config.federationSelfId || "").trim();
  if (!selfId) return false;
  const candidates = asArray(grant.candidate_relays);
  return candidates.some((item) => item && String(item.relay_id || "").trim() === selfId);
}

function validateClientAccessGrant({
  config,
  grant,
  accessToken,
  operation,
  gids,
  nowSeconds,
}) {
  if (!config.registryClientAccessGrantSigningSecret) {
    return { ok: false, code: "registry_client_access_signing_secret_missing" };
  }
  if (!grant || typeof grant !== "object") {
    return { ok: false, code: "client_access_grant_required" };
  }
  if (!grant.grant_id || typeof grant.grant_id !== "string") {
    return { ok: false, code: "invalid_client_access_grant" };
  }
  if (!grant.registry_signature || typeof grant.registry_signature !== "string") {
    return { ok: false, code: "missing_registry_signature" };
  }
  if (!accessToken || typeof accessToken !== "string") {
    return { ok: false, code: "client_access_token_required" };
  }
  if (String(grant.access_token || "") !== accessToken) {
    return { ok: false, code: "client_access_token_mismatch" };
  }
  const expectedSignature = signClientAccessGrant(
    config.registryClientAccessGrantSigningSecret,
    grant
  );
  if (!safeEqualHex(grant.registry_signature, expectedSignature)) {
    return { ok: false, code: "invalid_registry_signature" };
  }
  if (Number(grant.expires_at) <= nowSeconds) {
    return { ok: false, code: "grant_expired" };
  }
  if (!relayMatchesGrant(config, grant)) {
    return { ok: false, code: "relay_not_authorized" };
  }

  const permissions = asArray(grant.permissions).map((item) => String(item));
  if (operation && permissions.length > 0 && !permissions.includes(String(operation || ""))) {
    return { ok: false, code: "operation_not_allowed" };
  }

  const allowedGids = asArray(grant.allowed_gids).map((item) => String(item)).filter(Boolean);
  if (allowedGids.length > 0 && asArray(gids).length > 0) {
    for (const gid of asArray(gids)) {
      if (!allowedGids.includes(String(gid || ""))) {
        return { ok: false, code: "gid_not_allowed" };
      }
    }
  }

  return {
    ok: true,
    grantId: grant.grant_id,
    clientInstanceId: String(grant.client_instance_id || ""),
  };
}

module.exports = {
  decodeClientAccessGrantHeader,
  signClientAccessGrant,
  validateClientAccessGrant,
};
