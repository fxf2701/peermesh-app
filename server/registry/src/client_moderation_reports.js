const ALLOWED_REASONS = new Set([
  "harassment",
  "threatening_behavior",
  "spam",
  "inappropriate_content",
  "impersonation",
  "other",
]);

const TARGET_TYPE_PEER = "peer";
const TARGET_TYPE_MESSAGE = "message";
const SUPPORTED_PROTOCOL_VERSION = 1;
const MAX_USER_NOTE_LENGTH = 2000;
const MAX_PLAINTEXT_EXCERPT_LENGTH = 280;
const MAX_OPERATOR_NOTE_LENGTH = 4000;
const SOURCE_IP_RATE_LIMIT_WINDOW_SECONDS = 60 * 60;
const SOURCE_IP_RATE_LIMIT_MAX_REPORTS = 30;
const ALLOWED_STATUSES = new Set([
  "received",
  "in_review",
  "resolved_actioned",
  "resolved_no_action",
  "rejected_invalid",
]);

function trimOptionalString(value, maxLength) {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (!trimmed) return null;
  return trimmed.slice(0, maxLength);
}

function validateReportBody(body) {
  if (!body || typeof body !== "object") return { ok: false, code: "body_required" };

  const reportId = typeof body.report_id === "string" ? body.report_id.trim() : "";
  if (!reportId) return { ok: false, code: "missing_report_id" };

  const groupId = typeof body.group_id === "string" ? body.group_id.trim() : "";
  if (!groupId) return { ok: false, code: "missing_group_id" };

  const reporterPeerId =
    typeof body.reporter_peer_id === "string" ? body.reporter_peer_id.trim() : "";
  if (!reporterPeerId) return { ok: false, code: "missing_reporter_peer_id" };

  const reportedPeerId =
    typeof body.reported_peer_id === "string" ? body.reported_peer_id.trim() : "";
  if (!reportedPeerId) return { ok: false, code: "missing_reported_peer_id" };
  if (reportedPeerId === reporterPeerId) return { ok: false, code: "self_report_not_allowed" };

  const targetType = typeof body.target_type === "string" ? body.target_type.trim() : "";
  if (![TARGET_TYPE_PEER, TARGET_TYPE_MESSAGE].includes(targetType)) {
    return { ok: false, code: "invalid_target_type" };
  }

  const reasonCode = typeof body.reason_code === "string" ? body.reason_code.trim() : "";
  if (!reasonCode) return { ok: false, code: "missing_reason_code" };
  if (!ALLOWED_REASONS.has(reasonCode)) return { ok: false, code: "invalid_reason_code" };

  const createdAtMs = Number(body.created_at_ms);
  if (!Number.isFinite(createdAtMs) || createdAtMs <= 0) {
    return { ok: false, code: "invalid_created_at_ms" };
  }

  const protocolVersion = Number(body.protocol_version);
  if (!Number.isFinite(protocolVersion) || protocolVersion !== SUPPORTED_PROTOCOL_VERSION) {
    return { ok: false, code: "unsupported_protocol_version" };
  }

  const includePlaintext = body.include_plaintext === true;
  const userNote = trimOptionalString(body.user_note, MAX_USER_NOTE_LENGTH);
  const plaintextExcerpt = trimOptionalString(body.plaintext_excerpt, MAX_PLAINTEXT_EXCERPT_LENGTH);
  const messageId = typeof body.message_id === "string" ? body.message_id.trim() : "";
  const eventId = typeof body.event_id === "string" ? body.event_id.trim() : "";
  const ciphertextRef = typeof body.ciphertext_ref === "string" ? body.ciphertext_ref.trim() : "";
  const appVersion = typeof body.app_version === "string" ? body.app_version.trim() : "";

  if (targetType === TARGET_TYPE_PEER) {
    if (messageId) return { ok: false, code: "peer_report_message_id_not_allowed" };
    if (includePlaintext) return { ok: false, code: "peer_report_plaintext_not_allowed" };
    if (plaintextExcerpt) return { ok: false, code: "peer_report_plaintext_not_allowed" };
  }

  if (targetType === TARGET_TYPE_MESSAGE) {
    if (!messageId) return { ok: false, code: "missing_message_id" };
    if (plaintextExcerpt && !includePlaintext) {
      return { ok: false, code: "plaintext_excerpt_requires_opt_in" };
    }
  }

  return {
    ok: true,
    report: {
      report_id: reportId,
      group_id: groupId,
      reporter_peer_id: reporterPeerId,
      reported_peer_id: reportedPeerId,
      target_type: targetType,
      message_id: messageId || null,
      event_id: eventId || null,
      reason_code: reasonCode,
      user_note: userNote,
      include_plaintext: includePlaintext,
      plaintext_excerpt: plaintextExcerpt,
      ciphertext_ref: ciphertextRef || null,
      client_created_at_ms: Math.floor(createdAtMs),
      app_version: appVersion || null,
      protocol_version: protocolVersion,
    },
  };
}

function materiallyMatches(existing, incoming) {
  return (
    existing.report_id === incoming.report_id &&
    existing.group_id === incoming.group_id &&
    existing.reporter_peer_id === incoming.reporter_peer_id &&
    existing.reported_peer_id === incoming.reported_peer_id &&
    existing.target_type === incoming.target_type &&
    (existing.message_id || null) === (incoming.message_id || null) &&
    (existing.event_id || null) === (incoming.event_id || null) &&
    existing.reason_code === incoming.reason_code &&
    (existing.user_note || null) === (incoming.user_note || null) &&
    Boolean(existing.include_plaintext) === Boolean(incoming.include_plaintext) &&
    (existing.plaintext_excerpt || null) === (incoming.plaintext_excerpt || null) &&
    (existing.ciphertext_ref || null) === (incoming.ciphertext_ref || null) &&
    Number(existing.client_created_at_ms) === Number(incoming.client_created_at_ms) &&
    (existing.app_version || null) === (incoming.app_version || null) &&
    Number(existing.protocol_version) === Number(incoming.protocol_version)
  );
}

function createClientModerationReportManager({ store }) {
  function record(body, meta, now) {
    const validation = validateReportBody(body);
    if (!validation.ok) return { ok: false, status: 400, code: validation.code };

    const report = validation.report;
    const existing = store.getClientModerationReport(report.report_id);
    if (existing) {
      if (!materiallyMatches(existing, report)) {
        return { ok: false, status: 409, code: "report_id_conflict" };
      }
      return {
        ok: true,
        status: 200,
        report: existing,
        duplicate_collapsed: true,
      };
    }

    const sourceIp = typeof meta?.sourceIp === "string" ? meta.sourceIp.trim() : "";
    if (sourceIp) {
      const rateLimitSince = now - SOURCE_IP_RATE_LIMIT_WINDOW_SECONDS;
      store.pruneClientModerationReportIpEvents(rateLimitSince);
      if (
        store.countClientModerationReportIpEvents(sourceIp, rateLimitSince) >=
        SOURCE_IP_RATE_LIMIT_MAX_REPORTS
      ) {
        return { ok: false, status: 429, code: "source_ip_report_rate_limited" };
      }
      store.insertClientModerationReportIpEvent(sourceIp, now);
    }

    const stored = store.insertClientModerationReport({
      ...report,
      status: "received",
      duplicate_of_report_id: null,
      assigned_operator: null,
      received_at: now,
      updated_at: now,
      resolved_at: null,
    });

    return {
      ok: true,
      status: 201,
      report: stored,
      duplicate_collapsed: false,
    };
  }

  function updateStatus(reportId, body, operator, now) {
    const existing = store.getClientModerationReport(reportId);
    if (!existing) return { ok: false, status: 404, code: "report_not_found" };

    const nextStatus = typeof body?.status === "string" ? body.status.trim() : "";
    if (!ALLOWED_STATUSES.has(nextStatus)) {
      return { ok: false, status: 400, code: "invalid_status" };
    }

    const resolvedAt = nextStatus.startsWith("resolved_") || nextStatus === "rejected_invalid" ? now : null;
    const updated = store.updateClientModerationReportStatus(reportId, {
      status: nextStatus,
      assignedOperator: operator,
      updatedAt: now,
      resolvedAt,
    });
    return { ok: true, status: 200, report: updated };
  }

  function addNote(reportId, body, operator, now) {
    const existing = store.getClientModerationReport(reportId);
    if (!existing) return { ok: false, status: 404, code: "report_not_found" };

    const noteText = trimOptionalString(body?.note, MAX_OPERATOR_NOTE_LENGTH);
    if (!noteText) return { ok: false, status: 400, code: "missing_note" };

    const note = store.insertClientModerationReportNote({
      report_id: reportId,
      operator,
      note: noteText,
      created_at: now,
    });
    return { ok: true, status: 201, note };
  }

  return {
    record,
    updateStatus,
    addNote,
  };
}

module.exports = {
  ALLOWED_REASONS,
  ALLOWED_STATUSES,
  createClientModerationReportManager,
};
