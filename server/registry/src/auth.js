const { createHash, createHmac, timingSafeEqual } = require("crypto");

function safeEqualHex(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const aBuffer = Buffer.from(a, "hex");
  const bBuffer = Buffer.from(b, "hex");
  return aBuffer.length === bBuffer.length && timingSafeEqual(aBuffer, bBuffer);
}

function bodyHash(rawBody) {
  return createHash("sha256").update(rawBody || "").digest("hex");
}

function signRegistryRequest({ secret, method, path, timestamp, rawBody }) {
  const canonical = [
    String(method || "").toUpperCase(),
    String(path || ""),
    String(timestamp || ""),
    bodyHash(rawBody),
  ].join("\n");
  return createHmac("sha256", secret).update(canonical).digest("hex");
}

function signLegacyRegistryRequest({ secret, method, path, timestamp, nonce, rawBody }) {
  const canonical = [
    String(method || "").toUpperCase(),
    String(path || ""),
    String(timestamp || ""),
    String(nonce || ""),
    rawBody || "",
  ].join("\n");
  return createHmac("sha256", secret).update(canonical).digest("hex");
}

function verifyRegistrySignature({ config, store, method, path, rawBody, headers, now }) {
  if (!config.requireSignature) return { ok: true };
  if (!config.sharedSecret) return { ok: false, code: "registry_secret_not_configured" };

  const signature = headers["x-registry-signature"];
  const timestamp = headers["x-registry-timestamp"];
  const nonce = headers["x-registry-nonce"];

  if (typeof signature !== "string" || typeof timestamp !== "string") {
    return { ok: false, code: "missing_signature_headers" };
  }

  const ts = Number(timestamp);
  if (!Number.isFinite(ts)) return { ok: false, code: "invalid_timestamp" };
  if (Math.abs(now - ts) > config.signatureSkewSeconds) {
    return { ok: false, code: "timestamp_out_of_window" };
  }

  const expected = signRegistryRequest({
    secret: config.sharedSecret,
    method,
    path,
    timestamp,
    rawBody,
  });

  if (safeEqualHex(signature, expected)) return { ok: true };

  if (typeof nonce === "string" && nonce) {
    const legacyExpected = signLegacyRegistryRequest({
      secret: config.sharedSecret,
      method,
      path,
      timestamp,
      nonce,
      rawBody,
    });
    if (safeEqualHex(signature, legacyExpected)) {
      const nonceAccepted = store.rememberNonce(nonce, now, config.nonceTtlSeconds);
      if (!nonceAccepted) return { ok: false, code: "nonce_replay" };
      return { ok: true, legacy: true };
    }
  }

  return { ok: false, code: "invalid_signature" };
}

module.exports = { signRegistryRequest, signLegacyRegistryRequest, verifyRegistrySignature };
