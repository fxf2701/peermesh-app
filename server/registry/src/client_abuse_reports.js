const { randomUUID } = require("crypto");

const ALLOWED_REASONS = new Set([
  "spam",
  "protocol_violation",
  "invalid_session_behavior",
  "suspicious_payload_pattern",
  "connection_flood",
]);

function normalizeCounters(input) {
  const counters = input && typeof input === "object" ? input : {};
  const normalized = {};
  for (const [key, value] of Object.entries(counters)) {
    if (typeof key !== "string" || !key.trim()) continue;
    const number = Number(value);
    if (!Number.isFinite(number) || number < 0) continue;
    normalized[key.trim()] = Math.floor(number);
  }
  return normalized;
}

function effectiveClientAbusePolicy(config, store) {
  const policy = store.policy(config);
  const acceptanceWindow =
    Number.isFinite(Number(policy.client_abuse_report_acceptance_window_seconds))
      ? Math.max(60, Math.floor(Number(policy.client_abuse_report_acceptance_window_seconds)))
      : config.clientAbuseReportAcceptanceWindowSeconds;
  const deprioritizeWindow =
    Number.isFinite(Number(policy.client_abuse_deprioritize_window_seconds))
      ? Math.max(60, Math.floor(Number(policy.client_abuse_deprioritize_window_seconds)))
      : config.clientAbuseDeprioritizeWindowSeconds;
  const pairCooldown =
    Number.isFinite(Number(policy.client_abuse_pair_cooldown_seconds))
      ? Math.max(60, Math.floor(Number(policy.client_abuse_pair_cooldown_seconds)))
      : config.clientAbusePairCooldownSeconds;
  const singleReportWeight =
    Number.isFinite(Number(policy.client_abuse_single_report_weight))
      ? Math.max(0, Number(policy.client_abuse_single_report_weight))
      : config.clientAbuseSingleReportWeight;
  const distinctClientBonus =
    Number.isFinite(Number(policy.client_abuse_distinct_client_bonus))
      ? Math.max(0, Number(policy.client_abuse_distinct_client_bonus))
      : config.clientAbuseDistinctClientBonus;
  const duplicateBucketSeconds =
    Number.isFinite(Number(policy.client_abuse_duplicate_bucket_seconds))
      ? Math.max(60, Math.floor(Number(policy.client_abuse_duplicate_bucket_seconds)))
      : config.clientAbuseDuplicateBucketSeconds;
  const rateLimitWindowSeconds =
    Number.isFinite(Number(policy.client_abuse_rate_limit_window_seconds))
      ? Math.max(60, Math.floor(Number(policy.client_abuse_rate_limit_window_seconds)))
      : config.clientAbuseRateLimitWindowSeconds;
  const maxReportsPerClientWindow =
    Number.isFinite(Number(policy.client_abuse_max_reports_per_client_window))
      ? Math.max(1, Math.floor(Number(policy.client_abuse_max_reports_per_client_window)))
      : config.clientAbuseMaxReportsPerClientWindow;
  const maxReportsPerIpWindow =
    Number.isFinite(Number(policy.client_abuse_max_reports_per_ip_window))
      ? Math.max(1, Math.floor(Number(policy.client_abuse_max_reports_per_ip_window)))
      : config.clientAbuseMaxReportsPerIpWindow;
  const maxReportsPerGrantWindow =
    Number.isFinite(Number(policy.client_abuse_max_reports_per_grant_window))
      ? Math.max(1, Math.floor(Number(policy.client_abuse_max_reports_per_grant_window)))
      : config.clientAbuseMaxReportsPerGrantWindow;
  const corroborationClientThreshold =
    Number.isFinite(Number(policy.client_abuse_corroboration_client_threshold))
      ? Math.max(1, Math.floor(Number(policy.client_abuse_corroboration_client_threshold)))
      : config.clientAbuseCorroborationClientThreshold;
  const corroborationScoreThreshold =
    Number.isFinite(Number(policy.client_abuse_corroboration_score_threshold))
      ? Math.max(0, Number(policy.client_abuse_corroboration_score_threshold))
      : config.clientAbuseCorroborationScoreThreshold;

  return {
    acceptanceWindow,
    deprioritizeWindow,
    pairCooldown,
    singleReportWeight,
    distinctClientBonus,
    duplicateBucketSeconds,
    rateLimitWindowSeconds,
    maxReportsPerClientWindow,
    maxReportsPerIpWindow,
    maxReportsPerGrantWindow,
    corroborationClientThreshold,
    corroborationScoreThreshold,
  };
}

function decayedWeight(reportCreatedAt, now, windowSeconds) {
  const age = Math.max(0, now - reportCreatedAt);
  if (age >= windowSeconds) return 0;
  return 1 - age / windowSeconds;
}

function summarizeRelayAbuse(store, relayId, now, policy) {
  const reports = store.listRelayAbuseReports(relayId, now - policy.deprioritizeWindow);
  const clients = new Set();
  let decayedScore = 0;
  let lastReportAt = null;

  for (const report of reports) {
    clients.add(report.client_instance_id);
    decayedScore += policy.singleReportWeight * decayedWeight(report.created_at, now, policy.deprioritizeWindow);
    if (!lastReportAt || report.created_at > lastReportAt) lastReportAt = report.created_at;
  }

  if (clients.size > 1) {
    decayedScore += (clients.size - 1) * policy.distinctClientBonus;
  }

  const corroborated =
    clients.size >= policy.corroborationClientThreshold ||
    decayedScore >= policy.corroborationScoreThreshold;

  return {
    report_count: reports.length,
    distinct_clients: clients.size,
    last_report_at: lastReportAt,
    decayed_score: Number(decayedScore.toFixed(4)),
    corroborated,
  };
}

function selectionStateForRelay(store, relayId, clientInstanceId, now, policy) {
  const summary = summarizeRelayAbuse(store, relayId, now, policy);
  const latestClientReport = store.getClientRelayLatestAbuseReport(
    clientInstanceId,
    relayId,
    now - policy.pairCooldown
  );
  const pairCooldownUntil = latestClientReport
    ? latestClientReport.created_at + policy.pairCooldown
    : null;
  const blockedForClient = Boolean(pairCooldownUntil && pairCooldownUntil > now);
  return {
    relay_id: relayId,
    score: summary.decayed_score,
    report_count: summary.report_count,
    distinct_clients: summary.distinct_clients,
    last_report_at: summary.last_report_at,
    corroborated: summary.corroborated,
    blocked_for_client: blockedForClient,
    pair_cooldown_until: blockedForClient ? pairCooldownUntil : null,
    deny_renewal_for_pair: blockedForClient,
    suppress_global_introduction: summary.corroborated,
  };
}

function validateReportBody(body) {
  if (!body || typeof body !== "object") return { ok: false, code: "body_required" };

  const grantId = typeof body.grant_id === "string" ? body.grant_id.trim() : "";
  if (!grantId) return { ok: false, code: "missing_grant_id" };

  const relayId = typeof body.relay_id === "string" ? body.relay_id.trim() : "";
  if (!relayId) return { ok: false, code: "missing_relay_id" };

  const clientInstanceId =
    typeof body.client_instance_id === "string" ? body.client_instance_id.trim() : "";
  if (!clientInstanceId) return { ok: false, code: "missing_client_instance_id" };

  const reason = typeof body.reason === "string" ? body.reason.trim() : "";
  if (!reason) return { ok: false, code: "missing_reason" };
  if (!ALLOWED_REASONS.has(reason)) return { ok: false, code: "invalid_reason" };

  const timestamp = Number(body.timestamp);
  if (!Number.isFinite(timestamp) || timestamp <= 0) return { ok: false, code: "invalid_timestamp" };

  return {
    ok: true,
    report: {
      grant_id: grantId,
      relay_id: relayId,
      client_instance_id: clientInstanceId,
      reason,
      timestamp: Math.floor(timestamp),
      counters: normalizeCounters(body.counters),
    },
  };
}

function createClientAbuseReportManager({ config, store }) {
  function record(body, meta, now) {
    const validation = validateReportBody(body);
    if (!validation.ok) return { ok: false, status: 400, code: validation.code };

    const policy = effectiveClientAbusePolicy(config, store);
    const report = validation.report;
    const grant = store.getClientAccessGrant(report.grant_id);
    if (!grant) return { ok: false, status: 404, code: "grant_not_found" };
    if (grant.revoked_at) return { ok: false, status: 409, code: "grant_revoked" };
    if (report.client_instance_id !== grant.client_instance_id) {
      return { ok: false, status: 403, code: "client_instance_mismatch" };
    }
    if (!Array.isArray(grant.candidate_relays) || !grant.candidate_relays.some((item) => item.relay_id === report.relay_id)) {
      return { ok: false, status: 403, code: "relay_not_authorized_by_grant" };
    }
    if (report.timestamp > now + 60) {
      return { ok: false, status: 400, code: "timestamp_in_future" };
    }
    if (report.timestamp > grant.expires_at + policy.acceptanceWindow) {
      return { ok: false, status: 409, code: "report_outside_acceptance_window" };
    }
    const duplicateBucketStart =
      Math.floor(report.timestamp / policy.duplicateBucketSeconds) * policy.duplicateBucketSeconds;
    const duplicate = store.findDuplicateClientAbuseReport({
      grantId: grant.grant_id,
      relayId: report.relay_id,
      reason: report.reason,
      bucketStart: duplicateBucketStart,
      bucketEnd: duplicateBucketStart + policy.duplicateBucketSeconds,
    });
    if (duplicate) {
      return {
        ok: true,
        status: 200,
        report: duplicate,
        duplicate_collapsed: true,
        selection_effect: selectionStateForRelay(
          store,
          duplicate.relay_id,
          duplicate.client_instance_id,
          now,
          policy
        ),
      };
    }

    const rateLimitSince = now - policy.rateLimitWindowSeconds;
    if (
      store.countClientAbuseReportsForClient(report.client_instance_id, rateLimitSince) >=
      policy.maxReportsPerClientWindow
    ) {
      return { ok: false, status: 429, code: "client_report_rate_limited" };
    }
    if (
      store.countClientAbuseReportsForGrant(grant.grant_id, rateLimitSince) >=
      policy.maxReportsPerGrantWindow
    ) {
      return { ok: false, status: 429, code: "grant_report_rate_limited" };
    }
    if (
      meta?.sourceIp &&
      store.countClientAbuseReportsForSourceIp(meta.sourceIp, rateLimitSince) >=
        policy.maxReportsPerIpWindow
    ) {
      return { ok: false, status: 429, code: "source_ip_report_rate_limited" };
    }

    const stored = store.insertClientAbuseReport({
      report_id: `car_${randomUUID()}`,
      grant_id: grant.grant_id,
      relay_id: report.relay_id,
      client_instance_id: report.client_instance_id,
      reason: report.reason,
      reported_at: report.timestamp,
      counters: report.counters,
      source_ip: meta?.sourceIp || null,
      created_at: now,
    });
    const selectionEffect = selectionStateForRelay(
      store,
      stored.relay_id,
      stored.client_instance_id,
      now,
      policy
    );
    return {
      ok: true,
      status: 201,
      report: stored,
      selection_effect: selectionEffect,
    };
  }

  return {
    record,
    selectionStateForRelay: (relayId, clientInstanceId, now) =>
      selectionStateForRelay(store, relayId, clientInstanceId, now, effectiveClientAbusePolicy(config, store)),
  };
}

module.exports = {
  createClientAbuseReportManager,
  effectiveClientAbusePolicy,
  summarizeRelayAbuse,
};
