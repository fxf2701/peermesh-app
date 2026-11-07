// Structured logger with basic redaction.

const SENSITIVE_KEYS = new Set([
  "authorization",
  "token",
  "password",
  "secret",
  "sig",
  "body",
  "payload",
  "nonce",
  "cursor",
  "gid",
  "x-relay-peer-token",
]);

function isPlainObject(value) {
  if (!value || typeof value !== "object") return false;
  return Object.getPrototypeOf(value) === Object.prototype;
}

function redactValue(value, depth = 0) {
  if (depth > 4) return "[redacted]";
  if (value instanceof Error) {
    return { name: value.name, message: value.message };
  }
  if (Array.isArray(value)) {
    return value.map((item) => redactValue(item, depth + 1));
  }
  if (isPlainObject(value)) {
    const result = {};
    for (const [key, val] of Object.entries(value)) {
      const lowerKey = key.toLowerCase();
      if (SENSITIVE_KEYS.has(lowerKey)) {
        result[key] = "[redacted]";
      } else {
        result[key] = redactValue(val, depth + 1);
      }
    }
    return result;
  }
  return value;
}

function logEvent(level, message, meta = {}) {
  const entry = {
    level,
    msg: message,
    ts: new Date().toISOString(),
    ...redactValue(meta),
  };
  // eslint-disable-next-line no-console
  console.log(JSON.stringify(entry));
}

function logRequest({ reqId, method, path, statusCode, durationMs }) {
  logEvent("info", "http_request", {
    req_id: reqId,
    method,
    path,
    status: statusCode,
    duration_ms: durationMs,
  });
}

module.exports = { logEvent, logRequest };
