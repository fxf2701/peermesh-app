// Shared helpers for HTTP handlers.

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json");
  res.setHeader("Content-Length", Buffer.byteLength(body));
  res.end(body);
}

function parseUrlQuery(url) {
  const queryIndex = url.indexOf("?");
  if (queryIndex === -1) return {};

  const queryString = url.slice(queryIndex + 1);
  const params = new URLSearchParams(queryString);
  const result = {};

  for (const [key, value] of params.entries()) {
    result[key] = value;
  }

  return result;
}

function encodeCursor({ ts, eid, order }) {
  if (!ts || !eid) return null;
  const payload = JSON.stringify({
    ts,
    eid,
    ...(Number.isInteger(order) && order > 0 ? { order } : {}),
  });
  return Buffer.from(payload).toString("base64url");
}

function decodeCursor(cursor) {
  if (!cursor) return null;
  try {
    const payload = Buffer.from(cursor, "base64url").toString("utf8");
    const parsed = JSON.parse(payload);
    if (!parsed.ts || !parsed.eid) return null;
    const decoded = { ts: parsed.ts, eid: parsed.eid };
    if (Number.isInteger(parsed.order) && parsed.order > 0) {
      decoded.order = parsed.order;
    }
    return decoded;
  } catch (err) {
    return null;
  }
}

function parseJsonBody(req, res, { maxBytes = 1_000_000 } = {}) {
  return new Promise((resolve) => {
    let data = "";
    req.on("data", (chunk) => {
      data += chunk;
      if (data.length > maxBytes) {
        sendJson(res, 413, {
          error: { code: "payload_too_large", message: "Request body too large." },
        });
        req.destroy();
        resolve(null);
      }
    });

    req.on("end", () => {
      if (!data) return resolve({});
      try {
        resolve(JSON.parse(data));
      } catch (err) {
        sendJson(res, 400, {
          error: { code: "invalid_json", message: "Request body is not valid JSON." },
        });
        resolve(null);
      }
    });
  });
}

function parseJsonBodyWithRaw(req, res, { maxBytes = 1_000_000 } = {}) {
  return new Promise((resolve) => {
    let data = "";
    req.on("data", (chunk) => {
      data += chunk;
      if (data.length > maxBytes) {
        sendJson(res, 413, {
          error: { code: "payload_too_large", message: "Request body too large." },
        });
        req.destroy();
        resolve(null);
      }
    });

    req.on("end", () => {
      if (!data) return resolve({ raw: "", json: {} });
      try {
        resolve({ raw: data, json: JSON.parse(data) });
      } catch (err) {
        sendJson(res, 400, {
          error: { code: "invalid_json", message: "Request body is not valid JSON." },
        });
        resolve(null);
      }
    });
  });
}

function parseRawBody(req, res, { maxBytes = 1_000_000 } = {}) {
  return new Promise((resolve) => {
    let data = "";
    req.on("data", (chunk) => {
      data += chunk;
      if (data.length > maxBytes) {
        sendJson(res, 413, {
          error: { code: "payload_too_large", message: "Request body too large." },
        });
        req.destroy();
        resolve(null);
      }
    });

    req.on("end", () => resolve(data));
  });
}

module.exports = {
  sendJson,
  parseUrlQuery,
  parseJsonBody,
  parseJsonBodyWithRaw,
  parseRawBody,
  encodeCursor,
  decodeCursor,
};
