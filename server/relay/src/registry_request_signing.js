const { createHash, createHmac } = require("crypto");

function bodyHashHex(body) {
  return createHash("sha256").update(body || "").digest("hex");
}

function signRegistryRequest({ secret, method, path, timestamp, rawBody }) {
  const canonical = [
    String(method || "").toUpperCase(),
    String(path || ""),
    String(timestamp || ""),
    bodyHashHex(rawBody),
  ].join("\n");

  return createHmac("sha256", secret).update(canonical).digest("hex");
}

module.exports = { bodyHashHex, signRegistryRequest };
