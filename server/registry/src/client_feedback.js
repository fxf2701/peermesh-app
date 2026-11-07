const ALLOWED_CATEGORIES = new Set([
  "general",
  "bug",
  "feature_request",
  "nearby_rooms",
  "groups",
  "maps_awareness",
]);

const ALLOWED_SOURCE = "android_beta";
const ALLOWED_FEEDBACK_TYPES = new Set(["user_feedback", "crash_report"]);
const MAX_MESSAGE_LENGTH = 4000;
const MAX_METADATA_LENGTH = 200;

function trimOptionalString(value, maxLength) {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (!trimmed) return null;
  return trimmed.slice(0, maxLength);
}

function validateFeedbackBody(body) {
  if (!body || typeof body !== "object") return { ok: false, code: "body_required" };

  const feedbackId = typeof body.feedback_id === "string" ? body.feedback_id.trim() : "";
  if (!feedbackId) return { ok: false, code: "missing_feedback_id" };

  const category = typeof body.category === "string" ? body.category.trim() : "";
  if (!ALLOWED_CATEGORIES.has(category)) return { ok: false, code: "invalid_category" };

  const message = typeof body.message === "string" ? body.message.trim() : "";
  if (!message) return { ok: false, code: "missing_message" };
  if (message.length > MAX_MESSAGE_LENGTH) return { ok: false, code: "message_too_long" };

  const createdAt = Number(body.created_at);
  if (!Number.isFinite(createdAt) || createdAt <= 0) {
    return { ok: false, code: "invalid_created_at" };
  }

  const feedbackTypeRaw = typeof body.feedback_type === "string" ? body.feedback_type.trim() : "";
  const feedbackType = feedbackTypeRaw || "user_feedback";
  if (!ALLOWED_FEEDBACK_TYPES.has(feedbackType)) return { ok: false, code: "invalid_feedback_type" };

  const source = typeof body.source === "string" ? body.source.trim() : "";
  if (source !== ALLOWED_SOURCE) return { ok: false, code: "invalid_source" };

  return {
    ok: true,
    feedback: {
      feedback_id: feedbackId,
      category,
      feedback_type: feedbackType,
      message,
      created_at: Math.floor(createdAt),
      app_version: trimOptionalString(body.app_version, MAX_METADATA_LENGTH),
      build_number: trimOptionalString(body.build_number, MAX_METADATA_LENGTH),
      android_version: trimOptionalString(body.android_version, MAX_METADATA_LENGTH),
      device_model: trimOptionalString(body.device_model, MAX_METADATA_LENGTH),
      local_device_id: trimOptionalString(body.local_device_id, MAX_METADATA_LENGTH),
      source,
    },
  };
}

function materiallyMatches(existing, incoming) {
  return (
    existing.feedback_id === incoming.feedback_id &&
    existing.category === incoming.category &&
    existing.feedback_type === incoming.feedback_type &&
    existing.message === incoming.message &&
    Number(existing.created_at) === Number(incoming.created_at) &&
    (existing.app_version || null) === (incoming.app_version || null) &&
    (existing.build_number || null) === (incoming.build_number || null) &&
    (existing.android_version || null) === (incoming.android_version || null) &&
    (existing.device_model || null) === (incoming.device_model || null) &&
    (existing.local_device_id || null) === (incoming.local_device_id || null) &&
    existing.source === incoming.source
  );
}

function createClientFeedbackManager({ store }) {
  function record(body, now) {
    const validation = validateFeedbackBody(body);
    if (!validation.ok) return { ok: false, status: 400, code: validation.code };

    const feedback = validation.feedback;
    const existing = store.getClientFeedback(feedback.feedback_id);
    if (existing) {
      if (!materiallyMatches(existing, feedback)) {
        return { ok: false, status: 409, code: "feedback_id_conflict" };
      }
      return {
        ok: true,
        status: 200,
        feedback: existing,
        duplicate_collapsed: true,
      };
    }

    const stored = store.insertClientFeedback({
      ...feedback,
      status: "received",
      received_at: now,
    });
    return {
      ok: true,
      status: 201,
      feedback: stored,
      duplicate_collapsed: false,
    };
  }

  return {
    record,
  };
}

module.exports = {
  ALLOWED_CATEGORIES,
  ALLOWED_FEEDBACK_TYPES,
  createClientFeedbackManager,
};
