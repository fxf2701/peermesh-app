// Request and event validation helpers.

const { parseUrlQuery, decodeCursor } = require("./utils");

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const BASE64URL_REGEX = /^[A-Za-z0-9_-]+$/;
const BASE32_REGEX = /^[A-Z2-7]+$/i;

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isValidUuid(value) {
  return typeof value === "string" && UUID_REGEX.test(value);
}

function isValidGid(value) {
  if (typeof value !== "string" || value.length === 0) return false;
  return BASE64URL_REGEX.test(value) || BASE32_REGEX.test(value);
}

function validationError(code, message, details) {
  return { ok: false, error: { code, message, details } };
}

function validateIngestRequest(body, config) {
  if (!body || !Array.isArray(body.events)) {
    return validationError("invalid_body", "Body must include events array.");
  }

  if (body.events.length > config.maxEventsPerRequest) {
    return validationError("too_many_events", "Too many events in request.", {
      max_events: config.maxEventsPerRequest,
    });
  }

  return { ok: true, data: { events: body.events } };
}

function validateFetchRequest(url, config) {
  const query = parseUrlQuery(url);
  if (!query.gid) {
    return validationError("missing_gid", "gid is required.");
  }

  if (!isValidGid(query.gid)) {
    return validationError("invalid_gid", "gid must be base32/base64url.");
  }

  const limit = query.limit ? Number(query.limit) : 100;
  if (!Number.isFinite(limit) || limit <= 0) {
    return validationError("invalid_limit", "limit must be a positive number.");
  }
  const cappedLimit = Math.min(limit, config.fetchMaxLimit);

  let decodedCursor = null;
  if (query.cursor) {
    decodedCursor = decodeCursor(query.cursor);
    if (!decodedCursor) {
      return validationError("invalid_cursor", "cursor is invalid.");
    }
  }

  const eventIds = query.event_ids
    ? query.event_ids.split(",").map((value) => value.trim()).filter(Boolean)
    : [];
  if (eventIds.length > 32 || eventIds.some((value) => !BASE64URL_REGEX.test(value))) {
    return validationError(
      "invalid_event_ids",
      "event_ids must contain at most 32 base64url event ids.",
    );
  }

  return {
    ok: true,
    data: {
      gid: query.gid,
      cursor: query.cursor || null,
      cursorDecoded: decodedCursor,
      limit: cappedLimit,
      eventIds,
    },
  };
}

function validateEventEnvelope(event, config, now) {
  if (!isObject(event)) {
    return { ok: false, reason: "invalid_event", details: { field: "event" } };
  }

  if (!isValidUuid(event.eid)) {
    return { ok: false, reason: "invalid_eid", details: { field: "eid" } };
  }

  if (!isValidGid(event.gid)) {
    return { ok: false, reason: "invalid_gid", details: { field: "gid" } };
  }

  if (!Number.isInteger(event.ts)) {
    return { ok: false, reason: "invalid_ts", details: { field: "ts" } };
  }

  if (!Number.isInteger(event.exp)) {
    return { ok: false, reason: "invalid_exp", details: { field: "exp" } };
  }

  if (!isObject(event.body)) {
    return { ok: false, reason: "invalid_body", details: { field: "body" } };
  }

  if (typeof event.sig !== "string" || event.sig.length === 0) {
    return { ok: false, reason: "invalid_sig", details: { field: "sig" } };
  }

  if (event.exp <= now - config.eventExpGraceSeconds) {
    return { ok: false, reason: "expired", details: { field: "exp" } };
  }

  if (event.ts > now + config.eventMaxFutureSeconds) {
    return { ok: false, reason: "future_ts", details: { field: "ts" } };
  }

  let eventBytes = 0;
  try {
    eventBytes = Buffer.byteLength(JSON.stringify(event));
  } catch (err) {
    return { ok: false, reason: "invalid_event", details: { field: "event" } };
  }

  if (eventBytes > config.maxEventBytes) {
    return {
      ok: false,
      reason: "event_too_large",
      details: { max_bytes: config.maxEventBytes },
    };
  }

  return { ok: true };
}

module.exports = {
  validateIngestRequest,
  validateFetchRequest,
  validateEventEnvelope,
};
