const { createServer } = require("http");
const { randomUUID } = require("crypto");
const fs = require("fs");
const pathModule = require("path");
const { loadConfig } = require("./config");
const { openDatabase, createRegistryStore } = require("./db");
const { verifyRegistrySignature } = require("./auth");
const { sendJson, readBody } = require("./http");
const { createRegistryAdminAuth } = require("./admin_auth");
const { createFederationSessionManager } = require("./federation_sessions");
const { createClientAccessGrantManager } = require("./client_access_grants");
const { createClientAbuseReportManager } = require("./client_abuse_reports");
const { createClientFeedbackManager } = require("./client_feedback");
const { createClientModerationReportManager } = require("./client_moderation_reports");
const { createClientBetaProgramManager, normalizeBetaProgramConfig } = require("./client_beta_program");

const config = loadConfig();
const db = openDatabase(config.dbPath);
const store = createRegistryStore(db);
const adminAuth = createRegistryAdminAuth(config);
const federationSessions = createFederationSessionManager({ config, store });
const clientAccessGrants = createClientAccessGrantManager({ config, store });
const clientAbuseReports = createClientAbuseReportManager({ config, store });
const clientFeedback = createClientFeedbackManager({ store });
const clientModerationReports = createClientModerationReportManager({ store });
const clientBetaProgram = createClientBetaProgramManager({ config, store });
const publicAdminRoot = pathModule.join(__dirname, "public", "admin-registry");
const ADMIN_ROOT = "/admin";
const LEGACY_ADMIN_ROOT = "/registry/admin";

function nowEpochSeconds() {
  return Math.floor(Date.now() / 1000);
}

function log(level, message, meta = {}) {
  const line = {
    ts: new Date().toISOString(),
    level,
    message,
    ...meta,
  };
  console.log(JSON.stringify(line));
}

function requestSourceIp(req) {
  const forwarded = req.headers["x-forwarded-for"];
  if (typeof forwarded === "string" && forwarded.trim()) {
    return forwarded.split(",")[0].trim();
  }
  return req.socket?.remoteAddress || null;
}

function validateRelayPayload(body) {
  if (!body || typeof body !== "object") {
    return { ok: false, error: "body_required" };
  }
  const relayId = typeof body.relay_id === "string" ? body.relay_id.trim() : "";
  const publicKey = typeof body.public_key === "string" ? body.public_key.trim() : "";
  const endpoint = typeof body.endpoint === "string" ? body.endpoint.trim() : "";
  const region = typeof body.region === "string" ? body.region.trim() : null;
  const version = typeof body.version === "string" ? body.version.trim() : null;
  const capabilities = Array.isArray(body.capabilities)
    ? body.capabilities.filter((item) => typeof item === "string" && item.trim()).map((item) => item.trim())
    : [];

  if (!relayId) return { ok: false, error: "missing_relay_id" };
  if (!/^[a-zA-Z0-9._:-]{1,128}$/.test(relayId)) return { ok: false, error: "invalid_relay_id" };
  if (!publicKey) return { ok: false, error: "missing_public_key" };
  if (!endpoint) return { ok: false, error: "missing_endpoint" };
  if (!/^https?:\/\//i.test(endpoint)) return { ok: false, error: "invalid_endpoint" };
  if (capabilities.length > 32) return { ok: false, error: "too_many_capabilities" };

  return {
    ok: true,
    relay: {
      relay_id: relayId,
      public_key: publicKey,
      endpoint,
      region,
      capabilities,
      version,
    },
  };
}

function normalizeStringList(value, { allowed = null, max = 64 } = {}) {
  if (!Array.isArray(value)) return [];
  const seen = new Set();
  const items = [];
  for (const entry of value) {
    if (typeof entry !== "string") continue;
    const trimmed = entry.trim();
    if (!trimmed || seen.has(trimmed)) continue;
    if (allowed && !allowed.includes(trimmed)) continue;
    seen.add(trimmed);
    items.push(trimmed);
    if (items.length >= max) break;
  }
  return items;
}

function applyRegistryDeploymentModeBaseline(next, mode) {
  next.registry_deployment_mode = mode;
  if (mode === "private_self_hosted") {
    next.minimum_trust_level_for_auto_sync = "community";
    next.minimum_trust_level_for_federation = "community";
    return;
  }
  next.minimum_trust_level_for_auto_sync = "verified";
  next.minimum_trust_level_for_federation = "trusted";
}

function validatePolicyPatch(body, currentPolicy) {
  if (!body || typeof body !== "object") {
    return { ok: false, code: "body_required", message: "Policy body is required." };
  }
  const next = { ...currentPolicy };

  if ("deny_relays" in body) {
    next.deny_relays = normalizeStringList(body.deny_relays, { max: 256 });
  }
  if ("suspended_relays" in body) {
    next.suspended_relays = normalizeStringList(body.suspended_relays, { max: 256 });
  }
  if ("registry_deployment_mode" in body) {
    const value = String(body.registry_deployment_mode || "").trim();
    if (!["official_managed", "private_self_hosted"].includes(value)) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid registry deployment mode." };
    }
    applyRegistryDeploymentModeBaseline(next, value);
  }
  if ("minimum_trust_level_for_auto_sync" in body) {
    const value = String(body.minimum_trust_level_for_auto_sync || "").trim();
    if (!["community", "verified", "trusted"].includes(value)) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid auto-sync trust level." };
    }
    next.minimum_trust_level_for_auto_sync = value;
  }
  if ("allowed_statuses_for_discovery" in body) {
    const value = normalizeStringList(body.allowed_statuses_for_discovery, {
      allowed: ["active", "suspended", "revoked", "banned"],
      max: 8,
    });
    if (value.length === 0) {
      return { ok: false, code: "invalid_policy_field", message: "Discovery statuses cannot be empty." };
    }
    next.allowed_statuses_for_discovery = value;
  }
  if ("minimum_trust_level_for_client_access" in body) {
    const value = String(body.minimum_trust_level_for_client_access || "").trim();
    if (!["community", "verified", "trusted"].includes(value)) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid client access trust level." };
    }
    next.minimum_trust_level_for_client_access = value;
  }
  if ("allowed_statuses_for_client_access" in body) {
    const value = normalizeStringList(body.allowed_statuses_for_client_access, {
      allowed: ["active", "suspended", "revoked", "banned"],
      max: 8,
    });
    if (value.length === 0) {
      return { ok: false, code: "invalid_policy_field", message: "Client access statuses cannot be empty." };
    }
    next.allowed_statuses_for_client_access = value;
  }
  if ("client_access_grant_ttl_seconds" in body) {
    const value = Number(body.client_access_grant_ttl_seconds);
    if (!Number.isFinite(value) || value < 60 || value > 86400) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid client access grant TTL." };
    }
    next.client_access_grant_ttl_seconds = Math.floor(value);
  }
  if ("max_candidates_per_client_access_grant" in body) {
    const value = Number(body.max_candidates_per_client_access_grant);
    if (!Number.isFinite(value) || value < 1 || value > 32) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid max client access candidates." };
    }
    next.max_candidates_per_client_access_grant = Math.floor(value);
  }
  if ("allowed_client_access_permissions" in body) {
    const value = normalizeStringList(body.allowed_client_access_permissions, {
      allowed: ["ingest", "fetch"],
      max: 8,
    });
    if (value.length === 0) {
      return { ok: false, code: "invalid_policy_field", message: "Client access permissions cannot be empty." };
    }
    next.allowed_client_access_permissions = value;
  }
  if ("client_access_policy_version" in body) {
    const value = Number(body.client_access_policy_version);
    if (!Number.isFinite(value) || value < 1 || value > 2147483647) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid client access policy version." };
    }
    next.client_access_policy_version = Math.floor(value);
  }
  if ("prefer_same_region_for_client_access" in body) {
    if (typeof body.prefer_same_region_for_client_access !== "boolean") {
      return { ok: false, code: "invalid_policy_field", message: "Invalid client access region preference flag." };
    }
    next.prefer_same_region_for_client_access = body.prefer_same_region_for_client_access;
  }
  if ("client_abuse_report_acceptance_window_seconds" in body) {
    const value = Number(body.client_abuse_report_acceptance_window_seconds);
    if (!Number.isFinite(value) || value < 60 || value > 86400) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid client abuse acceptance window." };
    }
    next.client_abuse_report_acceptance_window_seconds = Math.floor(value);
  }
  if ("client_abuse_deprioritize_window_seconds" in body) {
    const value = Number(body.client_abuse_deprioritize_window_seconds);
    if (!Number.isFinite(value) || value < 60 || value > 86400 * 30) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid client abuse deprioritize window." };
    }
    next.client_abuse_deprioritize_window_seconds = Math.floor(value);
  }
  if ("client_abuse_pair_cooldown_seconds" in body) {
    const value = Number(body.client_abuse_pair_cooldown_seconds);
    if (!Number.isFinite(value) || value < 60 || value > 86400 * 7) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid client abuse pair cooldown." };
    }
    next.client_abuse_pair_cooldown_seconds = Math.floor(value);
  }
  if ("client_abuse_single_report_weight" in body) {
    const value = Number(body.client_abuse_single_report_weight);
    if (!Number.isFinite(value) || value < 0 || value > 1000) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid client abuse single-report weight." };
    }
    next.client_abuse_single_report_weight = value;
  }
  if ("client_abuse_distinct_client_bonus" in body) {
    const value = Number(body.client_abuse_distinct_client_bonus);
    if (!Number.isFinite(value) || value < 0 || value > 1000) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid client abuse distinct-client bonus." };
    }
    next.client_abuse_distinct_client_bonus = value;
  }
  if ("client_abuse_duplicate_bucket_seconds" in body) {
    const value = Number(body.client_abuse_duplicate_bucket_seconds);
    if (!Number.isFinite(value) || value < 60 || value > 86400) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid client abuse duplicate bucket." };
    }
    next.client_abuse_duplicate_bucket_seconds = Math.floor(value);
  }
  if ("client_abuse_rate_limit_window_seconds" in body) {
    const value = Number(body.client_abuse_rate_limit_window_seconds);
    if (!Number.isFinite(value) || value < 60 || value > 86400) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid client abuse rate-limit window." };
    }
    next.client_abuse_rate_limit_window_seconds = Math.floor(value);
  }
  if ("client_abuse_max_reports_per_client_window" in body) {
    const value = Number(body.client_abuse_max_reports_per_client_window);
    if (!Number.isFinite(value) || value < 1 || value > 1000) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid per-client abuse report cap." };
    }
    next.client_abuse_max_reports_per_client_window = Math.floor(value);
  }
  if ("client_abuse_max_reports_per_ip_window" in body) {
    const value = Number(body.client_abuse_max_reports_per_ip_window);
    if (!Number.isFinite(value) || value < 1 || value > 5000) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid per-IP abuse report cap." };
    }
    next.client_abuse_max_reports_per_ip_window = Math.floor(value);
  }
  if ("client_abuse_max_reports_per_grant_window" in body) {
    const value = Number(body.client_abuse_max_reports_per_grant_window);
    if (!Number.isFinite(value) || value < 1 || value > 1000) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid per-grant abuse report cap." };
    }
    next.client_abuse_max_reports_per_grant_window = Math.floor(value);
  }
  if ("client_abuse_corroboration_client_threshold" in body) {
    const value = Number(body.client_abuse_corroboration_client_threshold);
    if (!Number.isFinite(value) || value < 1 || value > 1000) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid abuse corroboration client threshold." };
    }
    next.client_abuse_corroboration_client_threshold = Math.floor(value);
  }
  if ("client_abuse_corroboration_score_threshold" in body) {
    const value = Number(body.client_abuse_corroboration_score_threshold);
    if (!Number.isFinite(value) || value < 0 || value > 100000) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid abuse corroboration score threshold." };
    }
    next.client_abuse_corroboration_score_threshold = value;
  }
  if ("minimum_trust_level_for_federation" in body) {
    const value = String(body.minimum_trust_level_for_federation || "").trim();
    if (!["community", "verified", "trusted"].includes(value)) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid federation trust level." };
    }
    next.minimum_trust_level_for_federation = value;
  }
  if ("allowed_statuses_for_federation" in body) {
    const value = normalizeStringList(body.allowed_statuses_for_federation, {
      allowed: ["active", "suspended", "revoked", "banned"],
      max: 8,
    });
    if (value.length === 0) {
      return { ok: false, code: "invalid_policy_field", message: "Federation statuses cannot be empty." };
    }
    next.allowed_statuses_for_federation = value;
  }
  if ("federation_session_ttl_seconds" in body) {
    const value = Number(body.federation_session_ttl_seconds);
    if (!Number.isFinite(value) || value < 60 || value > 86400) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid federation session TTL." };
    }
    next.federation_session_ttl_seconds = Math.floor(value);
  }
  if ("max_peers_per_session" in body) {
    const value = Number(body.max_peers_per_session);
    if (!Number.isFinite(value) || value < 0 || value > 64) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid max peers per session." };
    }
    next.max_peers_per_session = Math.floor(value);
  }
  if ("allowed_federation_permissions" in body) {
    const value = normalizeStringList(body.allowed_federation_permissions, {
      allowed: ["push", "pull_reconcile"],
      max: 8,
    });
    if (value.length === 0) {
      return { ok: false, code: "invalid_policy_field", message: "Federation permissions cannot be empty." };
    }
    next.allowed_federation_permissions = value;
  }
  if ("federation_policy_version" in body) {
    const value = Number(body.federation_policy_version);
    if (!Number.isFinite(value) || value < 1 || value > 2147483647) {
      return { ok: false, code: "invalid_policy_field", message: "Invalid federation policy version." };
    }
    next.federation_policy_version = Math.floor(value);
  }

  return { ok: true, policy: next };
}

function normalizeOptionalBetaString(value, fieldName, maxLength = 200) {
  if (value === null || value === undefined) return null;
  if (typeof value !== "string") {
    throw new Error(`${fieldName} must be a string or null.`);
  }
  const trimmed = value.trim();
  if (!trimmed) return null;
  return trimmed.slice(0, maxLength);
}

function validateBetaProgramPatch(body, currentConfig) {
  if (!body || typeof body !== "object") {
    return { ok: false, code: "body_required", message: "Beta Program body is required." };
  }
  try {
    const next = { ...currentConfig };
    if ("enabled" in body) {
      if (typeof body.enabled !== "boolean") {
        return { ok: false, code: "invalid_beta_program_field", message: "enabled must be a boolean." };
      }
      next.enabled = body.enabled;
    }
    if ("season" in body) next.season = normalizeOptionalBetaString(body.season, "season");
    if ("endsAt" in body) next.endsAt = normalizeOptionalBetaString(body.endsAt, "endsAt");
    if ("latestVersion" in body) next.latestVersion = normalizeOptionalBetaString(body.latestVersion, "latestVersion");
    if ("minimumSupportedVersion" in body) {
      next.minimumSupportedVersion = normalizeOptionalBetaString(body.minimumSupportedVersion, "minimumSupportedVersion");
    }
    if ("changelog" in body) {
      if (body.changelog === null) {
        next.changelog = [];
      } else if (!Array.isArray(body.changelog)) {
        return { ok: false, code: "invalid_beta_program_field", message: "changelog must be an array of strings." };
      } else {
        next.changelog = body.changelog;
      }
    }
    return { ok: true, betaProgram: normalizeBetaProgramConfig(next) };
  } catch (err) {
    return { ok: false, code: "invalid_beta_program_field", message: err.message || "Invalid Beta Program config." };
  }
}

function sendAdminError(res, statusCode, code, message) {
  return sendJson(res, statusCode, { ok: false, error: { code, message } });
}

function readAdminBody(req) {
  return readBody(req, config.maxBodyBytes).then(({ json }) => json || {});
}

function serveFile(res, filePath, contentType) {
  try {
    const body = fs.readFileSync(filePath);
    res.statusCode = 200;
    res.setHeader("Content-Type", contentType);
    res.setHeader("Content-Length", body.length);
    res.end(body);
    return true;
  } catch (err) {
    return false;
  }
}

function normalizeAdminPath(path) {
  if (path === LEGACY_ADMIN_ROOT) return ADMIN_ROOT;
  if (path.startsWith(`${LEGACY_ADMIN_ROOT}/`)) {
    return `${ADMIN_ROOT}${path.slice(LEGACY_ADMIN_ROOT.length)}`;
  }
  return path;
}

function serveRegistryAdminStatic(req, res, path) {
  if (req.method !== "GET") return false;
  if (path === "/admin" || path === "/admin/") {
    return serveFile(res, pathModule.join(publicAdminRoot, "index.html"), "text/html; charset=utf-8");
  }
  if (path === "/admin/federation") {
    return serveFile(res, pathModule.join(publicAdminRoot, "federation.html"), "text/html; charset=utf-8");
  }
  if (path.startsWith("/admin/federation/sessions/")) {
    return serveFile(res, pathModule.join(publicAdminRoot, "federation_session_details.html"), "text/html; charset=utf-8");
  }
  if (path === "/admin/moderation-reports") {
    return serveFile(res, pathModule.join(publicAdminRoot, "moderation_reports.html"), "text/html; charset=utf-8");
  }
  if (path.startsWith("/admin/moderation-reports/")) {
    return serveFile(res, pathModule.join(publicAdminRoot, "moderation_report_details.html"), "text/html; charset=utf-8");
  }
  if (path === "/admin/feedback") {
    return serveFile(res, pathModule.join(publicAdminRoot, "feedback.html"), "text/html; charset=utf-8");
  }
  if (path.startsWith("/admin/feedback/")) {
    return serveFile(res, pathModule.join(publicAdminRoot, "feedback_details.html"), "text/html; charset=utf-8");
  }
  if (path === "/admin/beta-program") {
    return serveFile(res, pathModule.join(publicAdminRoot, "beta_program.html"), "text/html; charset=utf-8");
  }
  if (path === "/admin/relays") {
    return serveFile(res, pathModule.join(publicAdminRoot, "relays.html"), "text/html; charset=utf-8");
  }
  if (path.startsWith("/admin/relays/")) {
    return serveFile(res, pathModule.join(publicAdminRoot, "relay_details.html"), "text/html; charset=utf-8");
  }
  if (path === "/admin/certificates") {
    return serveFile(res, pathModule.join(publicAdminRoot, "certificates.html"), "text/html; charset=utf-8");
  }
  if (path === "/admin/metrics") {
    return serveFile(res, pathModule.join(publicAdminRoot, "metrics.html"), "text/html; charset=utf-8");
  }
  if (path.startsWith("/admin/assets/")) {
    const relative = path.slice("/admin/assets/".length);
    if (relative.includes("..") || relative.includes("/") && relative.split("/").some((part) => part === "..")) {
      return sendAdminError(res, 400, "invalid_path", "Invalid asset path.");
    }
    const filePath = pathModule.join(publicAdminRoot, "assets", relative);
    const ext = pathModule.extname(filePath);
    const contentType = ext === ".css"
      ? "text/css; charset=utf-8"
      : "application/javascript; charset=utf-8";
    return serveFile(res, filePath, contentType);
  }
  return false;
}

function requireRegistryAdmin(req, res) {
  const session = adminAuth.sessionForRequest(req);
  if (!session) {
    sendAdminError(res, 401, "unauthorized", "Admin session required.");
    return null;
  }
  return session;
}

function requireRegistryAdminCsrf(req, res, session) {
  if (adminAuth.verifyCsrf(req, session)) return true;
  sendAdminError(res, 403, "csrf_invalid", "Valid admin CSRF token required.");
  return false;
}

function relaySummary(relay, now) {
  return {
    ...relay,
    active: relay.status === "active" && now - relay.last_seen <= config.activeTtlSeconds,
  };
}

function trustHistoryItem(entry, storeRef) {
  const relayId = entry.resource_id || null;
  const relay = relayId ? storeRef.getRelay(relayId) : null;
  const resultingTrustLevel = entry.action === "relay_trust_promoted"
    ? "trusted"
    : entry.action === "relay_verified"
      ? "verified"
      : entry.action === "relay_trust_demoted"
        ? "community"
        : null;
  return {
    id: entry.id,
    relay_id: relayId,
    actor: entry.actor || null,
    action: entry.action,
    resulting_trust_level: resultingTrustLevel,
    current_trust_level: relay?.trust_level || null,
    current_status: relay?.status || null,
    reason: entry.reason || null,
    created_at: entry.created_at,
  };
}

function requireAdminReason(res, body, actionLabel) {
  const reason = typeof body?.reason === "string" ? body.reason.trim() : "";
  if (!reason) {
    sendAdminError(res, 400, "reason_required", `${actionLabel} requires an explicit reason.`);
    return null;
  }
  return reason;
}

function parseLimit(value, fallback = 100, max = 500) {
  return Math.min(Math.max(Number(value) || fallback, 1), max);
}

function parseOffset(value, fallback = 0) {
  return Math.max(Number(value) || fallback, 0);
}

function normalizeSort(value, allowed, fallback) {
  const sort = String(value || "").trim().toLowerCase();
  return allowed.includes(sort) ? sort : fallback;
}

function normalizeOrder(value, fallback = "desc") {
  const order = String(value || "").trim().toLowerCase();
  return order === "asc" || order === "desc" ? order : fallback;
}

function compareValues(left, right) {
  if (left == null && right == null) return 0;
  if (left == null) return -1;
  if (right == null) return 1;
  if (typeof left === "number" && typeof right === "number") return left - right;
  return String(left).localeCompare(String(right));
}

function sortItems(items, field, order, selector) {
  const direction = order === "asc" ? 1 : -1;
  return [...items].sort((a, b) => {
    const result = compareValues(selector(a, field), selector(b, field));
    if (result !== 0) return result * direction;
    return compareValues(
      a.relay_id || a.feedback_id || a.report_id || a.id || a.resource_id || "",
      b.relay_id || b.feedback_id || b.report_id || b.id || b.resource_id || ""
    );
  });
}

function filterRelayItems(items, query, trustLevel, status) {
  const needle = String(query || "").trim().toLowerCase();
  return items.filter((item) => {
    if (trustLevel && item.trust_level !== trustLevel) return false;
    if (status && item.status !== status) return false;
    if (!needle) return true;
    return [item.relay_id, item.endpoint, item.region, item.trust_level, item.status]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(needle));
  });
}

function filterAuditItems(items, { actor, action, resourceType, resourceId, query }) {
  const needle = String(query || "").trim().toLowerCase();
  return items.filter((item) => {
    if (actor && item.actor !== actor) return false;
    if (action && item.action !== action) return false;
    if (resourceType && item.resource_type !== resourceType) return false;
    if (resourceId && item.resource_id !== resourceId) return false;
    if (!needle) return true;
    return [item.actor, item.action, item.resource_type, item.resource_id, item.reason, item.result]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(needle));
  });
}

function csvCell(value) {
  const text = value == null ? "" : String(value);
  return `"${text.replace(/"/g, "\"\"")}"`;
}

function encodeAuditCsv(items) {
  const header = ["id", "created_at", "actor", "action", "resource_type", "resource_id", "result", "reason"];
  const rows = items.map((item) => [
    item.id,
    item.created_at,
    item.actor,
    item.action,
    item.resource_type,
    item.resource_id,
    item.result,
    item.reason,
  ]);
  return [header, ...rows].map((row) => row.map(csvCell).join(",")).join("\n");
}

function encodeAuditJsonl(items) {
  return items.map((item) => JSON.stringify(item)).join("\n");
}

function requestExternalProto(req) {
  const forwarded = req.headers["x-forwarded-proto"];
  if (typeof forwarded === "string" && forwarded.trim()) {
    return forwarded.split(",")[0].trim();
  }
  return req.socket?.encrypted ? "https" : "http";
}

function requestExternalHost(req) {
  const forwarded = req.headers["x-forwarded-host"];
  if (typeof forwarded === "string" && forwarded.trim()) {
    return forwarded.split(",")[0].trim();
  }
  return String(req.headers.host || `127.0.0.1:${config.port}`).trim();
}

function buildRegistryBaseUrl(req) {
  return `${requestExternalProto(req)}://${requestExternalHost(req)}`;
}

function sanitizeFilenameFragment(value, fallback = "relay") {
  const normalized = String(value || "")
    .trim()
    .replace(/[^a-zA-Z0-9._-]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return normalized || fallback;
}

function appendOnboardingSection(lines, title, nextLines) {
  if (!Array.isArray(nextLines) || nextLines.length === 0) return;
  lines.push("");
  lines.push(`# ${title}`);
  lines.push(...nextLines);
}

function buildRelayOnboardingBundle(req, options = {}) {
  const relayId = String(options.relayId || "").trim();
  const includeHeartbeat = Boolean(options.includeHeartbeat);
  const includeClientAccess = Boolean(options.includeClientAccess);
  const includeFederationSessions = Boolean(options.includeFederationSessions);

  if (!includeHeartbeat && !includeClientAccess && !includeFederationSessions) {
    return {
      ok: false,
      code: "bundle_sections_required",
      message: "Select at least one onboarding bundle section.",
    };
  }

  if (includeHeartbeat && !config.sharedSecret) {
    return {
      ok: false,
      code: "registry_shared_secret_unavailable",
      message: "REGISTRY_SHARED_SECRET is not configured in the registry runtime.",
    };
  }

  if (includeClientAccess && !config.clientAccessGrantSigningSecret) {
    return {
      ok: false,
      code: "client_access_secret_unavailable",
      message: "REGISTRY_CLIENT_ACCESS_GRANT_SIGNING_SECRET is not configured in the registry runtime.",
    };
  }

  if (includeFederationSessions && !config.federationSessionSigningSecret) {
    return {
      ok: false,
      code: "federation_session_secret_unavailable",
      message: "REGISTRY_FEDERATION_SESSION_SIGNING_SECRET is not configured in the registry runtime.",
    };
  }

  const baseUrl = buildRegistryBaseUrl(req);
  const generatedAt = new Date().toISOString();
  const sections = [];
  const lines = [
    "# PeerMesh relay onboarding bundle",
    "# Handle this file as a secret. Do not commit it to git or share it over insecure channels.",
    `# Generated at ${generatedAt}`,
    relayId ? `# Intended relay identifier: ${relayId}` : "# Intended relay identifier: <set by operator>",
    "",
    `REGISTRY_URL=${baseUrl}`,
  ];

  if (includeHeartbeat) {
    sections.push("heartbeat");
    appendOnboardingSection(lines, "Registry heartbeat and signed registry API access", [
      "REGISTRY_HEARTBEAT_ENABLED=1",
      `REGISTRY_SHARED_SECRET=${config.sharedSecret}`,
      "# Optional relay metadata for registry listings:",
      relayId ? `RELAY_SELF_ID=${relayId}` : "RELAY_SELF_ID=",
      "REGISTRY_ADVERTISED_ENDPOINT=https://relay.example.com",
      "REGISTRY_RELAY_REGION=",
    ]);
  }

  if (includeClientAccess) {
    sections.push("client_access");
    appendOnboardingSection(lines, "Managed client access grants", [
      "REGISTRY_CLIENT_ACCESS_GRANTS_ENABLED=1",
      `REGISTRY_CLIENT_ACCESS_GRANT_SIGNING_SECRET=${config.clientAccessGrantSigningSecret}`,
      "CLIENT_RELAY_SESSIONS_ENABLED=1",
      "# Relay-local secret: generate on the relay operator side before start.",
      "# Example: openssl rand -hex 32",
      "CLIENT_RELAY_SESSION_BINDING_SECRET=",
    ]);
  }

  if (includeFederationSessions) {
    sections.push("federation_sessions");
    appendOnboardingSection(lines, "Registry-managed federation sessions", [
      "REGISTRY_FEDERATION_SESSIONS_ENABLED=1",
      `REGISTRY_FEDERATION_SESSION_SIGNING_SECRET=${config.federationSessionSigningSecret}`,
      "# Optional federation identity:",
      relayId ? `FEDERATION_SELF_ID=${relayId}` : "FEDERATION_SELF_ID=",
      "FEDERATION_SELF_URL=https://relay.example.com",
      "# mTLS material is operator-managed and is not exported by the registry.",
      "FEDERATION_MTLS_ENABLED=0",
      "# FEDERATION_MTLS_CERT_PATH=/run/peermesh-secrets/federation/relay.crt",
      "# FEDERATION_MTLS_KEY_PATH=/run/peermesh-secrets/federation/relay.key",
      "# FEDERATION_MTLS_CA_PATH=/run/peermesh-secrets/federation/ca.crt",
    ]);
  }

  const filenameBase = sanitizeFilenameFragment(relayId || "relay-onboarding");
  return {
    ok: true,
    filename: `${filenameBase}-registry-onboarding.env`,
    relay_id: relayId || null,
    registry_url: baseUrl,
    generated_at: generatedAt,
    sections,
    content: `${lines.join("\n")}\n`,
  };
}

function sendText(res, statusCode, body, contentType, filename = null) {
  const payload = Buffer.from(body, "utf8");
  res.statusCode = statusCode;
  res.setHeader("Content-Type", contentType);
  if (filename) {
    res.setHeader("Content-Disposition", `attachment; filename="${filename}"`);
  }
  res.setHeader("Content-Length", payload.length);
  res.end(payload);
}

function paginateItems(items, limit, offset) {
  return {
    total: items.length,
    items: items.slice(offset, offset + limit),
  };
}

function federationSessionStatus(session, now) {
  if (!session) return "unknown";
  if (session.revoked_at) return "revoked";
  if (session.expires_at <= now) return "expired";
  const expiringWindow = Math.max(30, Math.min(120, Math.floor(config.federationSessionTtlSeconds * 0.2)));
  if (session.expires_at <= now + expiringWindow) return "expiring";
  return "active";
}

function summarizeFederationSession(session, now) {
  const peers = Array.isArray(session.peers) ? session.peers : [];
  return {
    ...session,
    source_relay_id: session.relay_id,
    peer_count: peers.length,
    status: federationSessionStatus(session, now),
    peers: peers.map((peer) => ({
      relay_id: peer.relay_id,
      endpoint: peer.endpoint,
      trust_level: peer.trust_level || null,
      allowed_gids: Array.isArray(peer.allowed_gids) ? peer.allowed_gids : [],
      permissions: Array.isArray(peer.permissions) ? peer.permissions : [],
      expires_at: peer.expires_at || session.expires_at,
      policy_version: peer.policy_version || session.policy_version,
    })),
  };
}

function buildFederationTopology(now) {
  const relays = store.listRelays().map((relay) => relaySummary(relay, now));
  const sessions = store.listFederationSessions(500).map((session) => summarizeFederationSession(session, now));
  const activeSessions = sessions.filter((session) => session.status === "active" || session.status === "expiring");
  const nodeMap = new Map();
  const edges = [];

  relays.forEach((relay) => {
    nodeMap.set(relay.relay_id, {
      relay_id: relay.relay_id,
      endpoint: relay.endpoint,
      region: relay.region,
      trust_level: relay.trust_level,
      status: relay.status,
      federation_enabled: relay.federation_enabled,
      active: relay.active,
      assigned_peer_count: 0,
      active_session_count: 0,
      inbound_session_count: 0,
      last_session_issue: null,
    });
  });

  activeSessions.forEach((session) => {
    const source = nodeMap.get(session.relay_id);
    if (source) {
      source.active_session_count += 1;
      source.assigned_peer_count += session.peer_count;
      source.last_session_issue = Math.max(source.last_session_issue || 0, session.issued_at || 0);
    }
    session.peers.forEach((peer) => {
      const destination = nodeMap.get(peer.relay_id);
      if (destination) {
        destination.inbound_session_count += 1;
      }
      edges.push({
        session_id: session.session_id,
        source_relay_id: session.relay_id,
        destination_relay_id: peer.relay_id,
        permissions: peer.permissions,
        allowed_gids: peer.allowed_gids,
        expires_at: session.expires_at,
        status: session.status,
      });
    });
  });

  return {
    generated_at: now,
    active_session_count: activeSessions.length,
    edge_count: edges.length,
    nodes: Array.from(nodeMap.values()).sort((a, b) => a.relay_id.localeCompare(b.relay_id)),
    edges,
    unassigned_relays: Array.from(nodeMap.values())
      .filter((relay) => relay.federation_enabled && relay.active && relay.assigned_peer_count === 0)
      .map((relay) => relay.relay_id),
  };
}

async function readSignedRegistryBody(req, url, now) {
  const { raw, json } = await readBody(req, config.maxBodyBytes);
  const auth = verifyRegistrySignature({
    config,
    store,
    method: req.method,
    path: `${url.pathname}${url.search}`,
    rawBody: raw,
    headers: req.headers,
    now,
  });
  return { raw, json, auth };
}

async function handleRegistryAdmin(req, res, path, url) {
  if (serveRegistryAdminStatic(req, res, path)) return true;

  if (req.method === "POST" && path === "/admin/api/auth/login") {
    const body = await readAdminBody(req);
    const sessionId = adminAuth.login(body.username, body.password);
    if (!sessionId) return sendAdminError(res, 401, "unauthorized", "Invalid credentials.");
    const session = adminAuth.sessionForRequest({
      headers: {
        cookie: `registry_admin_session=${sessionId}`,
      },
    });
    res.setHeader(
      "Set-Cookie",
      adminAuth.buildSessionCookie(sessionId, config.adminSessionTtlSeconds)
    );
    return sendJson(res, 200, {
      ok: true,
      data: { username: config.adminUsername, csrf_token: session ? session.csrfToken : null },
    });
  }

  if (req.method === "POST" && path === "/admin/api/auth/logout") {
    const session = requireRegistryAdmin(req, res);
    if (!session) return true;
    if (!requireRegistryAdminCsrf(req, res, session)) return true;
    adminAuth.logout(req);
    res.setHeader("Set-Cookie", adminAuth.buildClearCookie());
    return sendJson(res, 200, { ok: true, data: {} });
  }

  if (req.method === "GET" && path === "/admin/api/auth/me") {
    const session = requireRegistryAdmin(req, res);
    if (!session) return true;
    return sendJson(res, 200, {
      ok: true,
      data: { username: session.username, csrf_token: session.csrfToken },
    });
  }

  if (!path.startsWith("/admin/api/")) return false;
  const session = requireRegistryAdmin(req, res);
  if (!session) return true;
  if (req.method !== "GET" && req.method !== "HEAD") {
    if (!requireRegistryAdminCsrf(req, res, session)) return true;
  }

  const now = nowEpochSeconds();

  if (req.method === "GET" && path === "/admin/api/dashboard") {
    const dashboard = store.dashboard(now, config.activeTtlSeconds);
    return sendJson(res, 200, {
      ok: true,
      data: {
        now,
        ttl_seconds: config.activeTtlSeconds,
        ...dashboard.counts,
        health: dashboard.health,
      },
    });
  }

  if (req.method === "GET" && path === "/admin/api/metrics") {
    const dashboard = store.dashboard(now, config.activeTtlSeconds);
    return sendJson(res, 200, {
      ok: true,
      data: {
        now,
        window_seconds: 60,
        heartbeats_per_minute: dashboard.counts.heartbeats_per_minute,
        heartbeats_per_second: Number((dashboard.counts.heartbeats_per_minute / 60).toFixed(3)),
        discovery_requests_per_minute: dashboard.counts.discovery_requests_per_minute,
        discovery_requests_per_second: Number((dashboard.counts.discovery_requests_per_minute / 60).toFixed(3)),
        certificate_issues: dashboard.counts.certificate_issues,
        certificate_revocations: dashboard.counts.certificate_revocations,
        certificates_active: dashboard.counts.certificates_active,
        policy_updates: dashboard.counts.policy_updates,
        audit_events: dashboard.counts.audit_events,
        federation_session_issues: dashboard.counts.federation_session_issues,
        federation_session_denials: dashboard.counts.federation_session_denials,
        federation_sessions_active: dashboard.counts.federation_sessions_active,
        federation_sessions_expired: dashboard.counts.federation_sessions_expired,
        federation_sessions_revoked: dashboard.counts.federation_sessions_revoked,
      },
    });
  }

  if (req.method === "GET" && path === "/admin/api/relays") {
    const limit = parseLimit(url.searchParams.get("limit"), 100, 500);
    const offset = parseOffset(url.searchParams.get("offset"), 0);
    const sort = normalizeSort(url.searchParams.get("sort"), ["relay_id", "last_seen", "trust_level", "status", "region", "created_at"], "relay_id");
    const order = normalizeOrder(url.searchParams.get("order"), sort === "relay_id" ? "asc" : "desc");
    const query = String(url.searchParams.get("q") || "").trim();
    const trustLevel = String(url.searchParams.get("trust_level") || "").trim();
    const status = String(url.searchParams.get("status") || "").trim();
    const relays = store.listRelays().map((relay) => relaySummary(relay, now));
    const filtered = filterRelayItems(relays, query, trustLevel, status);
    const sorted = sortItems(filtered, sort, order, (item, field) => item[field]);
    const page = paginateItems(sorted, limit, offset);
    return sendJson(res, 200, {
      ok: true,
      data: {
        items: page.items,
        total: page.total,
        limit,
        offset,
        sort,
        order,
        q: query,
        trust_level: trustLevel || null,
        status: status || null,
      },
    });
  }

  if (req.method === "GET" && path === "/admin/api/trust") {
    const limit = parseLimit(url.searchParams.get("limit"), 100, 500);
    const offset = parseOffset(url.searchParams.get("offset"), 0);
    const relayId = String(url.searchParams.get("relay_id") || "").trim();
    const actor = String(url.searchParams.get("actor") || "").trim();
    const actions = new Set(["relay_trust_promoted", "relay_trust_demoted", "relay_verified"]);
    const items = store
      .listAudit(500)
      .filter((entry) => actions.has(entry.action))
      .filter((entry) => !relayId || entry.resource_id === relayId)
      .filter((entry) => !actor || entry.actor === actor)
      .map((entry) => trustHistoryItem(entry, store));
    const page = paginateItems(items, limit, offset);
    return sendJson(res, 200, {
      ok: true,
      data: {
        items: page.items,
        total: page.total,
        limit,
        offset,
        relay_id: relayId || null,
        actor: actor || null,
      },
    });
  }

  if (req.method === "GET" && path.startsWith("/admin/api/relays/")) {
    const relayId = decodeURIComponent(path.slice("/admin/api/relays/".length));
    const details = store.relayDetails(relayId);
    if (!details) return sendAdminError(res, 404, "relay_not_found", "Relay not found.");
    return sendJson(res, 200, {
      ok: true,
      data: {
        item: relaySummary(details.relay, now),
        certificate: details.certificate,
        audit: details.audit,
      },
    });
  }

  const trustMatch = path.match(/^\/admin\/api\/relays\/([^/]+)\/trust$/);
  if (req.method === "PATCH" && trustMatch) {
    const relayId = decodeURIComponent(trustMatch[1]);
    const body = await readAdminBody(req);
    const reason = requireAdminReason(res, body, "Trust change");
    if (!reason) return true;
    const trustLevel = String(body.trust_level || "");
    if (!["community", "verified", "trusted"].includes(trustLevel)) {
      return sendAdminError(res, 400, "invalid_trust_level", "Invalid trust level.");
    }
    const relay = store.updateRelayTrust(relayId, trustLevel, now);
    if (!relay) return sendAdminError(res, 404, "relay_not_found", "Relay not found.");
    const action = trustLevel === "trusted"
      ? "relay_trust_promoted"
      : trustLevel === "community"
        ? "relay_trust_demoted"
        : "relay_verified";
    store.recordAudit({
      actor: session.username,
      action,
      resourceType: "relay",
      resourceId: relayId,
      reason,
      now,
    });
    return sendJson(res, 200, { ok: true, data: { item: relaySummary(relay, now) } });
  }

  const statusMatch = path.match(/^\/admin\/api\/relays\/([^/]+)\/status$/);
  if (req.method === "PATCH" && statusMatch) {
    const relayId = decodeURIComponent(statusMatch[1]);
    const body = await readAdminBody(req);
    const reason = requireAdminReason(res, body, "Status change");
    if (!reason) return true;
    const status = String(body.status || "");
    if (!["active", "suspended", "revoked", "banned"].includes(status)) {
      return sendAdminError(res, 400, "invalid_status", "Invalid relay status.");
    }
    const relay = store.updateRelayStatus(relayId, status, now);
    if (!relay) return sendAdminError(res, 404, "relay_not_found", "Relay not found.");
    const action = status === "active"
      ? "relay_reinstated"
      : status === "suspended"
        ? "relay_suspended"
        : status === "revoked"
          ? "relay_revoked"
          : "relay_banned";
    store.recordAudit({
      actor: session.username,
      action,
      resourceType: "relay",
      resourceId: relayId,
      reason,
      now,
    });
    return sendJson(res, 200, { ok: true, data: { item: relaySummary(relay, now) } });
  }

  const federationMatch = path.match(/^\/admin\/api\/relays\/([^/]+)\/federation$/);
  if (req.method === "PATCH" && federationMatch) {
    const relayId = decodeURIComponent(federationMatch[1]);
    const body = await readAdminBody(req);
    const relay = store.updateRelayFederation(relayId, Boolean(body.federation_enabled), now);
    if (!relay) return sendAdminError(res, 404, "relay_not_found", "Relay not found.");
    store.recordAudit({
      actor: session.username,
      action: "relay_federation_updated",
      resourceType: "relay",
      resourceId: relayId,
      reason: body.reason,
      now,
    });
    return sendJson(res, 200, { ok: true, data: { item: relaySummary(relay, now) } });
  }

  if (req.method === "GET" && path === "/admin/api/revocations") {
    return sendJson(res, 200, { ok: true, data: store.revocations(now) });
  }

  if (req.method === "POST" && path === "/admin/api/revocations/rebuild") {
    store.recordAudit({
      actor: session.username,
      action: "revocation_feed_rebuilt",
      resourceType: "revocation_feed",
      resourceId: "default",
      now,
    });
    return sendJson(res, 200, { ok: true, data: store.rebuildRevocations(now) });
  }

  if (req.method === "GET" && path === "/admin/api/policy") {
    return sendJson(res, 200, { ok: true, data: store.policy(config) });
  }

  if (req.method === "PATCH" && path === "/admin/api/policy") {
    const body = await readAdminBody(req);
    const currentPolicy = store.policy(config);
    const validation = validatePolicyPatch(body, currentPolicy);
    if (!validation.ok) {
      return sendAdminError(res, 400, validation.code, validation.message);
    }
    store.recordAudit({
      actor: session.username,
      action: "policy_updated",
      resourceType: "policy",
      resourceId: "default",
      reason: body.reason,
      now,
    });
    return sendJson(res, 200, { ok: true, data: store.updatePolicy(validation.policy, now, config) });
  }

  if (req.method === "GET" && path === "/admin/api/beta-program") {
    return sendJson(res, 200, { ok: true, data: store.betaProgram(config) });
  }

  if (req.method === "PATCH" && path === "/admin/api/beta-program") {
    const body = await readAdminBody(req);
    const reason = requireAdminReason(res, body, "Beta Program update");
    if (!reason) return true;
    const currentBetaProgram = store.betaProgram(config);
    const validation = validateBetaProgramPatch(body, currentBetaProgram);
    if (!validation.ok) {
      return sendAdminError(res, 400, validation.code, validation.message);
    }
    store.recordAudit({
      actor: session.username,
      action: "beta_program_updated",
      resourceType: "beta_program",
      resourceId: "default",
      reason,
      now,
    });
    return sendJson(res, 200, { ok: true, data: store.updateBetaProgram(validation.betaProgram, now, config) });
  }

  if (req.method === "POST" && path === "/admin/api/onboarding/relay-bundle") {
    const body = await readAdminBody(req);
    const reason = requireAdminReason(res, body, "Relay onboarding bundle export");
    if (!reason) return true;
    const relayId = String(body.relay_id || "").trim();
    const bundle = buildRelayOnboardingBundle(req, {
      relayId,
      includeHeartbeat: body.include_heartbeat !== false,
      includeClientAccess: body.include_client_access === true,
      includeFederationSessions: body.include_federation_sessions === true,
    });
    if (!bundle.ok) {
      return sendAdminError(res, 400, bundle.code, bundle.message);
    }
    store.recordAudit({
      actor: session.username,
      action: "relay_onboarding_bundle_exported",
      resourceType: "relay",
      resourceId: relayId || "pending",
      result: bundle.sections.join(","),
      reason,
      now,
    });
    return sendJson(res, 200, {
      ok: true,
      data: bundle,
    });
  }

  if (req.method === "GET" && path === "/admin/api/federation/sessions") {
    const limit = parseLimit(url.searchParams.get("limit"), 100, 500);
    const offset = parseOffset(url.searchParams.get("offset"), 0);
    const sort = normalizeSort(
      url.searchParams.get("sort"),
      ["issued_at", "expires_at", "status", "source_relay_id", "peer_count", "session_id"],
      "issued_at"
    );
    const order = normalizeOrder(url.searchParams.get("order"), "desc");
    const relayId = String(url.searchParams.get("relay_id") || "").trim();
    const statusFilter = String(url.searchParams.get("status") || "").trim().toLowerCase();
    const items = store
      .listFederationSessions(500)
      .map((sessionItem) => summarizeFederationSession(sessionItem, now))
      .filter((sessionItem) => !relayId || sessionItem.relay_id === relayId)
      .filter((sessionItem) => !statusFilter || sessionItem.status === statusFilter);
    const normalized = items.map((sessionItem) => ({
      ...sessionItem,
      source_relay_id: sessionItem.source_relay_id || sessionItem.relay_id || null,
    }));
    const sorted = sortItems(normalized, sort, order, (item, field) => item[field]);
    const page = paginateItems(sorted, limit, offset);
    const recentDenials = store
      .listAudit(250)
      .filter((entry) => entry.action === "federation_session_denied")
      .slice(0, 25);
    return sendJson(res, 200, {
      ok: true,
      data: {
        items: page.items,
        total: page.total,
        limit,
        offset,
        sort,
        order,
        topology: buildFederationTopology(now),
        recent_denials: recentDenials,
      },
    });
  }

  if (req.method === "GET" && path === "/admin/api/client/moderation-reports") {
    const limit = parseLimit(url.searchParams.get("limit"), 100, 500);
    const offset = parseOffset(url.searchParams.get("offset"), 0);
    const sort = normalizeSort(
      url.searchParams.get("sort"),
      ["received_at", "status", "target_type", "group_id", "reported_peer_id", "report_id"],
      "received_at"
    );
    const order = normalizeOrder(url.searchParams.get("order"), "desc");
    const statusFilter = String(url.searchParams.get("status") || "").trim();
    const targetType = String(url.searchParams.get("target_type") || "").trim();
    const groupId = String(url.searchParams.get("group_id") || "").trim();
    const reportedPeerId = String(url.searchParams.get("reported_peer_id") || "").trim();
    const items = store
      .listClientModerationReports(500)
      .filter((item) => !statusFilter || item.status === statusFilter)
      .filter((item) => !targetType || item.target_type === targetType)
      .filter((item) => !groupId || item.group_id === groupId)
      .filter((item) => !reportedPeerId || item.reported_peer_id === reportedPeerId);
    const sorted = sortItems(items, sort, order, (item, field) => item[field]);
    const page = paginateItems(sorted, limit, offset);
    return sendJson(res, 200, {
      ok: true,
      data: {
        items: page.items,
        total: page.total,
        limit,
        offset,
        sort,
        order,
        status: statusFilter || null,
        target_type: targetType || null,
        group_id: groupId || null,
        reported_peer_id: reportedPeerId || null,
      },
    });
  }

  if (req.method === "GET" && path === "/admin/api/client/feedback") {
    const limit = parseLimit(url.searchParams.get("limit"), 100, 500);
    const offset = parseOffset(url.searchParams.get("offset"), 0);
    const sort = normalizeSort(
      url.searchParams.get("sort"),
      ["received_at", "created_at", "category", "source", "feedback_id"],
      "received_at"
    );
    const order = normalizeOrder(url.searchParams.get("order"), "desc");
    const category = String(url.searchParams.get("category") || "").trim().toLowerCase();
    const query = String(url.searchParams.get("q") || "").trim().toLowerCase();
    const items = store
      .listClientFeedback(500)
      .filter((item) => !category || item.category === category)
      .filter((item) => {
        if (!query) return true;
        return [
          item.feedback_id,
          item.category,
          item.message,
          item.app_version,
          item.build_number,
          item.android_version,
          item.device_model,
          item.local_device_id,
          item.source,
        ]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(query));
      });
    const sorted = sortItems(items, sort, order, (item, field) => item[field]);
    const page = paginateItems(sorted, limit, offset);
    return sendJson(res, 200, {
      ok: true,
      data: {
        items: page.items,
        total: page.total,
        limit,
        offset,
        sort,
        order,
        category: category || null,
        q: query || null,
      },
    });
  }

  const adminClientModerationReportMatch = path.match(/^\/admin\/api\/client\/moderation-reports\/([^/]+)$/);
  if (req.method === "GET" && adminClientModerationReportMatch) {
    const reportId = decodeURIComponent(adminClientModerationReportMatch[1]);
    const item = store.getClientModerationReport(reportId);
    if (!item) {
      return sendAdminError(res, 404, "report_not_found", "Client moderation report not found.");
    }
    const audit = store.listAudit(250).filter((entry) => {
      return entry.resource_type === "client_moderation_report" && entry.resource_id === reportId;
    });
    return sendJson(res, 200, {
      ok: true,
      data: {
        item,
        notes: store.listClientModerationReportNotes(reportId),
        audit,
      },
    });
  }

  const adminClientFeedbackMatch = path.match(/^\/admin\/api\/client\/feedback\/([^/]+)$/);
  if (req.method === "GET" && adminClientFeedbackMatch) {
    const feedbackId = decodeURIComponent(adminClientFeedbackMatch[1]);
    const item = store.getClientFeedback(feedbackId);
    if (!item) {
      return sendAdminError(res, 404, "feedback_not_found", "Client feedback not found.");
    }
    const audit = store.listAudit(250).filter((entry) => {
      return entry.resource_type === "client_feedback" && entry.resource_id === feedbackId;
    });
    return sendJson(res, 200, {
      ok: true,
      data: {
        item,
        audit,
      },
    });
  }

  const adminClientModerationReportStatusMatch = path.match(/^\/admin\/api\/client\/moderation-reports\/([^/]+)\/status$/);
  if (req.method === "PATCH" && adminClientModerationReportStatusMatch) {
    const reportId = decodeURIComponent(adminClientModerationReportStatusMatch[1]);
    const body = await readAdminBody(req);
    const reason = requireAdminReason(res, body, "Moderation report status change");
    if (!reason) return true;
    const result = clientModerationReports.updateStatus(reportId, body, session.username, now);
    if (!result.ok) {
      return sendAdminError(
        res,
        result.status,
        result.code,
        result.code === "report_not_found"
          ? "Client moderation report not found."
          : "Invalid moderation report status."
      );
    }
    store.recordAudit({
      actor: session.username,
      action: "client_moderation_report_status_changed",
      resourceType: "client_moderation_report",
      resourceId: reportId,
      reason,
      result: result.report.status,
      now,
    });
    return sendJson(res, 200, {
      ok: true,
      data: {
        item: result.report,
      },
    });
  }

  const adminClientModerationReportNotesMatch = path.match(/^\/admin\/api\/client\/moderation-reports\/([^/]+)\/notes$/);
  if (req.method === "POST" && adminClientModerationReportNotesMatch) {
    const reportId = decodeURIComponent(adminClientModerationReportNotesMatch[1]);
    const body = await readAdminBody(req);
    const result = clientModerationReports.addNote(reportId, body, session.username, now);
    if (!result.ok) {
      return sendAdminError(
        res,
        result.status,
        result.code,
        result.code === "report_not_found"
          ? "Client moderation report not found."
          : "Moderation report note is required."
      );
    }
    store.recordAudit({
      actor: session.username,
      action: "client_moderation_report_note_added",
      resourceType: "client_moderation_report",
      resourceId: reportId,
      now,
    });
    return sendJson(res, 201, {
      ok: true,
      data: {
        item: result.note,
      },
    });
  }

  const adminFederationSessionMatch = path.match(/^\/admin\/api\/federation\/sessions\/([^/]+)$/);
  if (req.method === "GET" && adminFederationSessionMatch) {
    const sessionId = decodeURIComponent(adminFederationSessionMatch[1]);
    const sessionItem = store.getFederationSession(sessionId);
    if (!sessionItem) return sendAdminError(res, 404, "session_not_found", "Federation session not found.");
    const relay = store.getRelay(sessionItem.relay_id);
    const audit = store.listAudit(250).filter((entry) => {
      return (
        (entry.resource_type === "federation_session" && entry.resource_id === sessionId) ||
        (entry.resource_type === "relay" &&
          entry.resource_id === sessionItem.relay_id &&
          String(entry.action || "").startsWith("federation_session_"))
      );
    });
    return sendJson(res, 200, {
      ok: true,
      data: {
        item: summarizeFederationSession(sessionItem, now),
        relay: relay ? relaySummary(relay, now) : null,
        audit,
      },
    });
  }

  const adminFederationRevokeMatch = path.match(/^\/admin\/api\/federation\/sessions\/([^/]+)\/revoke$/);
  if (req.method === "POST" && adminFederationRevokeMatch) {
    const body = await readAdminBody(req);
    const reason = requireAdminReason(res, body, "Federation session revocation");
    if (!reason) return true;
    const sessionId = decodeURIComponent(adminFederationRevokeMatch[1]);
    const result = federationSessions.revoke(sessionId, {}, now);
    if (!result.ok) {
      return sendAdminError(
        res,
        result.status || 400,
        result.code || "session_revoke_failed",
        "Federation session revocation failed."
      );
    }
    store.recordAudit({
      actor: session.username,
      action: "federation_session_revoked",
      resourceType: "federation_session",
      resourceId: sessionId,
      reason,
      now,
    });
    return sendJson(res, 200, {
      ok: true,
      data: {
        item: summarizeFederationSession(result.session, now),
      },
    });
  }

  if (req.method === "POST" && path === "/admin/api/federation/topology/rebalance") {
    const body = await readAdminBody(req);
    const reason = requireAdminReason(res, body, "Federation topology rebalance");
    if (!reason) return true;
    const relayId = String(body.relay_id || "").trim();
    const activeSessions = store
      .listFederationSessions(500)
      .filter((sessionItem) => !sessionItem.revoked_at && sessionItem.expires_at > now)
      .filter((sessionItem) => !relayId || sessionItem.relay_id === relayId);
    const revoked = activeSessions.map((sessionItem) => {
      const next = store.revokeFederationSession(sessionItem.session_id, now);
      store.recordAudit({
        actor: session.username,
        action: "federation_session_revoked",
        resourceType: "federation_session",
        resourceId: sessionItem.session_id,
        reason: `topology_rebalance: ${reason}`,
        now,
      });
      return summarizeFederationSession(next, now);
    });
    store.recordAudit({
      actor: session.username,
      action: "federation_topology_rebalanced",
      resourceType: "federation_topology",
      resourceId: relayId || "default",
      reason,
      now,
    });
    return sendJson(res, 200, {
      ok: true,
      data: {
        relay_id: relayId || null,
        revoked_count: revoked.length,
        revoked_sessions: revoked,
        topology: buildFederationTopology(now),
      },
    });
  }

  if (req.method === "GET" && path === "/admin/api/certificates") {
    return sendJson(res, 200, { ok: true, data: { items: store.listCertificates() } });
  }

  if (req.method === "POST" && path === "/admin/api/certificates") {
    const body = await readAdminBody(req);
    const reason = requireAdminReason(res, body, "Certificate issuance");
    if (!reason) return true;
    const relayId = String(body.relay_id || "").trim();
    if (!relayId) return sendAdminError(res, 400, "missing_relay_id", "Relay ID is required.");
    const lifetimeSeconds = Math.min(Math.max(Number(body.lifetime_seconds) || 365 * 24 * 60 * 60, 3600), 5 * 365 * 24 * 60 * 60);
    const certificate = store.issueCertificate({
      id: randomUUID(),
      relayId,
      now,
      expiresAt: now + lifetimeSeconds,
    });
    if (!certificate) return sendAdminError(res, 404, "relay_not_found", "Relay not found.");
    store.recordAudit({
      actor: session.username,
      action: "certificate_issued",
      resourceType: "relay",
      resourceId: relayId,
      reason,
      now,
    });
    return sendJson(res, 201, { ok: true, data: { item: certificate } });
  }

  const certificateRenewMatch = path.match(/^\/admin\/api\/certificates\/([^/]+)\/renew$/);
  if (req.method === "PATCH" && certificateRenewMatch) {
    const body = await readAdminBody(req);
    const reason = requireAdminReason(res, body, "Certificate renewal");
    if (!reason) return true;
    const certificateId = decodeURIComponent(certificateRenewMatch[1]);
    const lifetimeSeconds = Math.min(Math.max(Number(body.lifetime_seconds) || 365 * 24 * 60 * 60, 3600), 5 * 365 * 24 * 60 * 60);
    const certificate = store.renewCertificate(certificateId, now, now + lifetimeSeconds);
    if (!certificate) return sendAdminError(res, 404, "certificate_not_found", "Certificate not found.");
    store.recordAudit({
      actor: session.username,
      action: "certificate_renewed",
      resourceType: "relay",
      resourceId: certificate.relay_id,
      reason,
      now,
    });
    return sendJson(res, 200, { ok: true, data: { item: certificate } });
  }

  const certificateRevokeMatch = path.match(/^\/admin\/api\/certificates\/([^/]+)\/revoke$/);
  if (req.method === "PATCH" && certificateRevokeMatch) {
    const body = await readAdminBody(req);
    const reason = requireAdminReason(res, body, "Certificate revocation");
    if (!reason) return true;
    const certificateId = decodeURIComponent(certificateRevokeMatch[1]);
    const certificate = store.revokeCertificate(certificateId, now);
    if (!certificate) return sendAdminError(res, 404, "certificate_not_found", "Certificate not found.");
    store.recordAudit({
      actor: session.username,
      action: "certificate_revoked",
      resourceType: "relay",
      resourceId: certificate.relay_id,
      reason,
      now,
    });
    return sendJson(res, 200, { ok: true, data: { item: certificate } });
  }

  if (req.method === "GET" && path === "/admin/api/audit") {
    const limit = parseLimit(url.searchParams.get("limit"), 100, 500);
    const offset = parseOffset(url.searchParams.get("offset"), 0);
    const sort = normalizeSort(url.searchParams.get("sort"), ["created_at", "action", "actor", "resource_type", "resource_id", "result"], "created_at");
    const order = normalizeOrder(url.searchParams.get("order"), "desc");
    const actor = String(url.searchParams.get("actor") || "").trim();
    const action = String(url.searchParams.get("action") || "").trim();
    const resourceType = String(url.searchParams.get("resource_type") || "").trim();
    const resourceId = String(url.searchParams.get("resource_id") || "").trim();
    const query = String(url.searchParams.get("q") || "").trim();
    const audit = store.listAudit(500);
    const filtered = filterAuditItems(audit, { actor, action, resourceType, resourceId, query });
    const sorted = sortItems(filtered, sort, order, (item, field) => item[field]);
    const page = paginateItems(sorted, limit, offset);
    return sendJson(res, 200, {
      ok: true,
      data: {
        items: page.items,
        total: page.total,
        limit,
        offset,
        sort,
        order,
        actor: actor || null,
        action: action || null,
        resource_type: resourceType || null,
        resource_id: resourceId || null,
        q: query,
      },
    });
  }

  if (req.method === "GET" && path === "/admin/api/audit/export") {
    const sort = normalizeSort(url.searchParams.get("sort"), ["created_at", "action", "actor", "resource_type", "resource_id", "result"], "created_at");
    const order = normalizeOrder(url.searchParams.get("order"), "desc");
    const actor = String(url.searchParams.get("actor") || "").trim();
    const action = String(url.searchParams.get("action") || "").trim();
    const resourceType = String(url.searchParams.get("resource_type") || "").trim();
    const resourceId = String(url.searchParams.get("resource_id") || "").trim();
    const query = String(url.searchParams.get("q") || "").trim();
    const format = String(url.searchParams.get("format") || "csv").trim().toLowerCase();
    if (!["csv", "jsonl"].includes(format)) {
      return sendAdminError(res, 400, "invalid_export_format", "Export format must be csv or jsonl.");
    }
    const audit = store.listAudit(5000);
    const filtered = filterAuditItems(audit, { actor, action, resourceType, resourceId, query });
    const sorted = sortItems(filtered, sort, order, (item, field) => item[field]);
    const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
    if (format === "jsonl") {
      return sendText(
        res,
        200,
        encodeAuditJsonl(sorted),
        "application/x-ndjson; charset=utf-8",
        `registry-audit-${timestamp}.jsonl`
      );
    }
    return sendText(
      res,
      200,
      encodeAuditCsv(sorted),
      "text/csv; charset=utf-8",
      `registry-audit-${timestamp}.csv`
    );
  }

  return sendAdminError(res, 404, "not_found", "Registry admin endpoint not found.");
}

async function handleRequest(req, res) {
  const requestId = randomUUID();
  const startedAt = Date.now();
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  const path = normalizeAdminPath(url.pathname);

  res.setHeader("X-Request-Id", requestId);

  try {
    if (req.method === "GET" && (path === "/healthz" || path === "/health")) {
      return sendJson(res, 200, { status: "ok", role: "registry" });
    }

    if ((req.method === "GET" || req.method === "HEAD") && url.pathname.startsWith(LEGACY_ADMIN_ROOT) && !url.pathname.startsWith(`${LEGACY_ADMIN_ROOT}/api/`)) {
      const location = `${normalizeAdminPath(url.pathname)}${url.search}`;
      res.statusCode = 308;
      res.setHeader("Location", location);
      res.end();
      return;
    }

    const adminHandled = await handleRegistryAdmin(req, res, path, url);
    if (adminHandled || path.startsWith("/admin") || url.pathname.startsWith("/registry/admin")) return;

    if (req.method === "GET" && path === "/readyz") {
      try {
        db.prepare("SELECT 1 AS ok").get();
        return sendJson(res, 200, { status: "ok", db: "ok" });
      } catch (err) {
        return sendJson(res, 503, { status: "degraded", db: "error" });
      }
    }

    if (req.method === "POST" && path === "/v1/registry/relays:heartbeat") {
      const now = nowEpochSeconds();
      const { json, auth } = await readSignedRegistryBody(req, url, now);
      if (!auth.ok) {
        return sendJson(res, 401, { error: { code: auth.code, message: "Invalid registry signature." } });
      }

      const validation = validateRelayPayload(json);
      if (!validation.ok) {
        return sendJson(res, 400, { error: { code: validation.error, message: "Invalid relay heartbeat." } });
      }

      const relay = store.upsertRelay(validation.relay, now);
      return sendJson(res, 200, { ok: true, relay });
    }

    if (req.method === "POST" && path === "/v1/registry/federation/sessions") {
      const now = nowEpochSeconds();
      const { json, auth } = await readSignedRegistryBody(req, url, now);
      if (!auth.ok) {
        return sendJson(res, 401, { error: { code: auth.code, message: "Invalid registry signature." } });
      }

      const result = federationSessions.issue(json, now);
      store.recordAudit({
        actor: "relay",
        action: result.ok ? "federation_session_issued" : "federation_session_denied",
        resourceType: "relay",
        resourceId: result.session?.relay_id || result.relayId || json?.relay_id || null,
        result: result.ok ? "success" : "denied",
        reason: result.ok ? null : result.code,
        now,
      });
      if (!result.ok) {
        return sendJson(res, result.status, { ok: false, error: { code: result.code, message: "Federation session denied." } });
      }
      return sendJson(res, result.status, { ok: true, session: result.session });
    }

    if (req.method === "POST" && path === "/v1/registry/client/sessions") {
      const now = nowEpochSeconds();
      const { json, auth } = await readSignedRegistryBody(req, url, now);
      if (!auth.ok) {
        return sendJson(res, 401, { error: { code: auth.code, message: "Invalid registry signature." } });
      }

      const result = clientAccessGrants.issue(json, now);
      store.recordAudit({
        actor: "client",
        action: result.ok ? "client_access_grant_issued" : "client_access_grant_denied",
        resourceType: "client_instance",
        resourceId: result.grant?.client_instance_id || json?.client_instance_id || null,
        result: result.ok ? "success" : "denied",
        reason: result.ok ? null : result.code,
        now,
      });
      if (!result.ok) {
        return sendJson(res, result.status, {
          ok: false,
          error: { code: result.code, message: "Client relay access grant denied." },
        });
      }
      return sendJson(res, result.status, { ok: true, grant: result.grant });
    }

    const clientGrantRenewMatch = path.match(/^\/v1\/registry\/client\/sessions\/([^/]+):renew$/);
    if (req.method === "POST" && clientGrantRenewMatch) {
      const now = nowEpochSeconds();
      const { json, auth } = await readSignedRegistryBody(req, url, now);
      if (!auth.ok) {
        return sendJson(res, 401, { error: { code: auth.code, message: "Invalid registry signature." } });
      }

      const grantId = decodeURIComponent(clientGrantRenewMatch[1]);
      const result = clientAccessGrants.renew(grantId, json, now);
      store.recordAudit({
        actor: "client",
        action: result.ok ? "client_access_grant_renewed" : "client_access_grant_denied",
        resourceType: "client_access_grant",
        resourceId: grantId,
        result: result.ok ? "success" : "denied",
        reason: result.ok ? null : result.code,
        now,
      });
      if (!result.ok) {
        return sendJson(res, result.status, {
          ok: false,
          error: { code: result.code, message: "Client relay access grant renewal denied." },
        });
      }
      return sendJson(res, result.status, { ok: true, grant: result.grant });
    }

    if (req.method === "POST" && path === "/v1/registry/client/reports") {
      const now = nowEpochSeconds();
      const { json, auth } = await readSignedRegistryBody(req, url, now);
      if (!auth.ok) {
        return sendJson(res, 401, { error: { code: auth.code, message: "Invalid registry signature." } });
      }

      const result = clientAbuseReports.record(json, { sourceIp: requestSourceIp(req) }, now);
      store.recordAudit({
        actor: "client",
        action: result.ok ? "client_abuse_report_received" : "client_abuse_report_denied",
        resourceType: "relay",
        resourceId: result.report?.relay_id || json?.relay_id || null,
        result: result.ok ? "success" : "denied",
        reason: result.ok ? null : result.code,
        now,
      });
      if (!result.ok) {
        return sendJson(res, result.status, {
          ok: false,
          error: { code: result.code, message: "Client abuse report denied." },
        });
      }
      return sendJson(res, result.status, {
        ok: true,
        report: result.report,
        selection_effect: result.selection_effect,
        duplicate_collapsed: result.duplicate_collapsed === true,
      });
    }

    if (req.method === "POST" && path === "/v1/registry/client/feedback") {
      const now = nowEpochSeconds();
      const { json, auth } = await readSignedRegistryBody(req, url, now);
      if (!auth.ok) {
        return sendJson(res, 401, { error: { code: auth.code, message: "Invalid registry signature." } });
      }

      const result = clientFeedback.record(json, now);
      store.recordAudit({
        actor: "client",
        action: result.ok
          ? result.duplicate_collapsed === true
            ? "client_feedback_duplicate"
            : "client_feedback_received"
          : "client_feedback_denied",
        resourceType: "client_feedback",
        resourceId: result.feedback?.feedback_id || json?.feedback_id || null,
        result: result.ok ? "success" : "denied",
        reason: result.ok ? null : result.code,
        now,
      });
      if (!result.ok) {
        return sendJson(res, result.status, {
          ok: false,
          error: { code: result.code, message: "Client feedback denied." },
        });
      }
      return sendJson(res, result.status, {
        ok: true,
        feedback: {
          feedback_id: result.feedback.feedback_id,
          status: result.feedback.status,
        },
        duplicate_collapsed: result.duplicate_collapsed === true,
      });
    }

    if (req.method === "POST" && path === "/v1/registry/client/moderation-reports") {
      const now = nowEpochSeconds();
      const { json, auth } = await readSignedRegistryBody(req, url, now);
      if (!auth.ok) {
        return sendJson(res, 401, { error: { code: auth.code, message: "Invalid registry signature." } });
      }

      const result = clientModerationReports.record(json, { sourceIp: requestSourceIp(req) }, now);
      store.recordAudit({
        actor: "client",
        action: result.ok
          ? result.duplicate_collapsed === true
            ? "client_moderation_report_duplicate"
            : "client_moderation_report_received"
          : "client_moderation_report_denied",
        resourceType: "client_moderation_report",
        resourceId: result.report?.report_id || json?.report_id || null,
        result: result.ok ? "success" : "denied",
        reason: result.ok ? null : result.code,
        now,
      });
      if (!result.ok) {
        return sendJson(res, result.status, {
          ok: false,
          error: { code: result.code, message: "Client moderation report denied." },
        });
      }
      return sendJson(res, result.status, {
        ok: true,
        report: {
          report_id: result.report.report_id,
          status: result.report.status,
        },
        duplicate_collapsed: result.duplicate_collapsed === true,
      });
    }

    if (req.method === "GET" && path === "/v1/registry/federation/revocations") {
      const now = nowEpochSeconds();
      const limit = Math.min(Math.max(Number(url.searchParams.get("limit")) || 500, 1), 5000);
      const items = store.listRevokedFederationSessions(limit).map((session) => ({
        session_id: session.session_id,
        relay_id: session.relay_id,
        revoked_at: session.revoked_at,
        expires_at: session.expires_at,
        policy_version: session.policy_version,
      }));
      return sendJson(res, 200, {
        ok: true,
        generated_at: now,
        revoked_sessions: items.map((item) => item.session_id),
        items,
      });
    }

    const renewMatch = path.match(/^\/v1\/registry\/federation\/sessions\/([^/]+):renew$/);
    if (req.method === "POST" && renewMatch) {
      const now = nowEpochSeconds();
      const { json, auth } = await readSignedRegistryBody(req, url, now);
      if (!auth.ok) {
        return sendJson(res, 401, { error: { code: auth.code, message: "Invalid registry signature." } });
      }

      const sessionId = decodeURIComponent(renewMatch[1]);
      const result = federationSessions.renew(sessionId, json, now);
      store.recordAudit({
        actor: "relay",
        action: result.ok ? "federation_session_renewed" : "federation_session_denied",
        resourceType: "federation_session",
        resourceId: sessionId,
        result: result.ok ? "success" : "denied",
        reason: result.ok ? null : result.code,
        now,
      });
      if (!result.ok) {
        return sendJson(res, result.status, { ok: false, error: { code: result.code, message: "Federation session renewal denied." } });
      }
      return sendJson(res, result.status, { ok: true, session: result.session });
    }

    const revokeMatch = path.match(/^\/v1\/registry\/federation\/sessions\/([^/]+):revoke$/);
    if (req.method === "POST" && revokeMatch) {
      const now = nowEpochSeconds();
      const { json, auth } = await readSignedRegistryBody(req, url, now);
      if (!auth.ok) {
        return sendJson(res, 401, { error: { code: auth.code, message: "Invalid registry signature." } });
      }

      const sessionId = decodeURIComponent(revokeMatch[1]);
      const result = federationSessions.revoke(sessionId, json, now);
      store.recordAudit({
        actor: "relay",
        action: result.ok ? "federation_session_revoked" : "federation_session_denied",
        resourceType: "federation_session",
        resourceId: sessionId,
        result: result.ok ? "success" : "denied",
        reason: result.ok ? json?.reason || null : result.code,
        now,
      });
      if (!result.ok) {
        return sendJson(res, result.status, { ok: false, error: { code: result.code, message: "Federation session revocation denied." } });
      }
      return sendJson(res, result.status, { ok: true, session: result.session });
    }

    if (req.method === "GET" && path === "/v1/registry/relays") {
      const now = nowEpochSeconds();
      const policy = store.policy(config);
      const denyRelays = new Set(policy.deny_relays || []);
      const allowedStatuses = new Set(policy.allowed_statuses_for_discovery || ["active"]);
      const relays = store
        .listRelays()
        .filter((relay) => now - relay.last_seen <= config.activeTtlSeconds)
        .filter((relay) => allowedStatuses.has(relay.status))
        .filter((relay) => !denyRelays.has(relay.relay_id));
      return sendJson(res, 200, {
        protocol_version: 1,
        registry_version: "0.1.0",
        now,
        ttl_seconds: config.activeTtlSeconds,
        relays,
      });
    }

    if (req.method === "GET" && path.startsWith("/v1/registry/relays/")) {
      const relayId = decodeURIComponent(path.slice("/v1/registry/relays/".length));
      const relay = store.getRelay(relayId);
      if (!relay) return sendJson(res, 404, { error: { code: "relay_not_found", message: "Relay not found." } });
      const now = nowEpochSeconds();
      const active = now - relay.last_seen <= config.activeTtlSeconds;
      return sendJson(res, 200, { now, active, relay });
    }

    if (req.method === "GET" && path === "/v1/registry/revocations") {
      return sendJson(res, 200, store.revocations(nowEpochSeconds()));
    }

    if (req.method === "GET" && path === "/v1/registry/policy") {
      return sendJson(res, 200, store.policy(config));
    }

    if (req.method === "GET" && path === "/v1/registry/client/beta-program") {
      const betaProgram = clientBetaProgram.current();
      return sendJson(res, 200, {
        ok: true,
        beta_program: betaProgram,
      });
    }

    return sendJson(res, 404, { error: { code: "not_found", message: "Not found." } });
  } catch (err) {
    if (err && err.code === "PAYLOAD_TOO_LARGE") {
      return sendJson(res, 413, { error: { code: "payload_too_large", message: "Request body too large." } });
    }
    if (err && err.code === "INVALID_JSON") {
      return sendJson(res, 400, { error: { code: "invalid_json", message: "Invalid JSON body." } });
    }
    log("error", "request_failed", { request_id: requestId, error: err && err.stack ? err.stack : String(err) });
    return sendJson(res, 500, { error: { code: "internal_error", message: "Internal error." } });
  } finally {
    log("info", "request", {
      request_id: requestId,
      method: req.method,
      path,
      status: res.statusCode,
      duration_ms: Date.now() - startedAt,
    });
  }
}

const cleanupTimer = setInterval(() => {
  try {
    const now = nowEpochSeconds();
    store.cleanup(now, config.activeTtlSeconds * 4, config.nonceTtlSeconds);
  } catch (err) {
    log("warn", "cleanup_failed", { error: String(err) });
  }
}, 60_000);

const server = createServer(handleRequest);
server.listen(config.port, config.host, () => {
  log("info", "registry_listening", {
    host: config.host,
    port: config.port,
    db_path: config.dbPath,
    require_signature: config.requireSignature,
    federation_session_ttl_seconds: config.federationSessionTtlSeconds,
    federation_policy_version: config.federationPolicyVersion,
  });
});

async function shutdown(signal) {
  log("info", "registry_shutdown", { signal });
  clearInterval(cleanupTimer);
  await new Promise((resolve) => server.close(resolve));
  db.close();
  process.exit(0);
}

process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
