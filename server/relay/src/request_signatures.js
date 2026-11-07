// Request signature verification for private relay mode.

const crypto = require("crypto");

function sha256Hex(input) {
  return crypto.createHash("sha256").update(input || "").digest("hex");
}

function computeSignature(secret, parts) {
  const hmac = crypto.createHmac("sha256", secret);
  hmac.update(parts.join("\n"));
  return hmac.digest("hex");
}

function normalizeRawBody(rawBody) {
  if (rawBody == null) return Buffer.alloc(0);
  if (Buffer.isBuffer(rawBody)) return rawBody;
  if (typeof rawBody === "string") return Buffer.from(rawBody, "utf8");
  return Buffer.from(String(rawBody), "utf8");
}

function verifySignature({ secret, method, path, rawBody, timestamp, signature, skewSeconds }) {
  if (!secret) return { ok: true };
  if (!signature || !timestamp) {
    return { ok: false, error: "missing_signature" };
  }

  const ts = Number(timestamp);
  if (!Number.isInteger(ts)) {
    return { ok: false, error: "invalid_signature_ts" };
  }

  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - ts) > skewSeconds) {
    return { ok: false, error: "signature_skew" };
  }

  if (!/^[a-f0-9]{64}$/i.test(signature)) {
    return { ok: false, error: "invalid_signature" };
  }

  const bodyHash = sha256Hex(normalizeRawBody(rawBody));
  const expected = computeSignature(secret, [
    method.toUpperCase(),
    path,
    String(ts),
    bodyHash,
  ]);

  const ok = crypto.timingSafeEqual(
    Buffer.from(expected, "hex"),
    Buffer.from(signature, "hex")
  );
  return ok
    ? { ok: true, replayKey: `${ts}:${signature.toLowerCase()}` }
    : { ok: false, error: "invalid_signature" };
}

function createRequestReplayCache({ redis, maxEntries, ttlSeconds, prefix = "request_sig" }) {
  if (!Number.isFinite(maxEntries) || maxEntries <= 0) return null;
  if (!Number.isFinite(ttlSeconds) || ttlSeconds <= 0) return null;

  if (redis) {
    return {
      async has(key) {
        const result = await redis.exists(`${prefix}:${key}`);
        return Number(result) > 0;
      },
      async remember(key) {
        const result = await redis.set(`${prefix}:${key}`, "1", { EX: ttlSeconds, NX: true });
        return result === "OK";
      },
    };
  }

  const cache = new Map();

  function pruneExpired(now) {
    for (const [key, entry] of cache.entries()) {
      if (now - entry.ts > ttlSeconds) cache.delete(key);
    }
  }

  function ensureCapacity() {
    while (cache.size > maxEntries) {
      const oldest = cache.keys().next().value;
      if (!oldest) break;
      cache.delete(oldest);
    }
  }

  return {
    async has(key, now = Math.floor(Date.now() / 1000)) {
      pruneExpired(now);
      return cache.has(key);
    },
    async remember(key, now = Math.floor(Date.now() / 1000)) {
      pruneExpired(now);
      if (cache.has(key)) return false;
      cache.set(key, { ts: now });
      ensureCapacity();
      return true;
    },
  };
}

module.exports = { verifySignature, sha256Hex, computeSignature, createRequestReplayCache };
