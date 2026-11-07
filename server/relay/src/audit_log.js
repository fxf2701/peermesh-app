// Append-only audit log with hash chaining.

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { logEvent } = require("./logger");

function computeHash({ secret, prevHash, payload }) {
  const hash = crypto.createHash("sha256");
  if (secret) hash.update(secret);
  hash.update(prevHash || "");
  hash.update(payload);
  return hash.digest("hex");
}

function readLastHash(filePath) {
  if (!fs.existsSync(filePath)) return "0";
  const content = fs.readFileSync(filePath, "utf8");
  const lines = content.trim().split("\n").filter(Boolean);
  if (lines.length === 0) return "0";
  try {
    const last = JSON.parse(lines[lines.length - 1]);
    return last.hash || "0";
  } catch (err) {
    return "0";
  }
}

function createAuditLogger({ config, onLog = logEvent }) {
  if (!config.auditLogEnabled) return null;

  const filePath = config.auditLogPath || path.join(config.dataDir || ".", "audit.log");
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  let prevHash = readLastHash(filePath);
  const secret = config.auditLogSecret || "";

  function log(entry) {
    const payload = {
      ts: new Date().toISOString(),
      ...entry,
      prev_hash: prevHash,
    };
    const payloadText = JSON.stringify(payload);
    const hash = computeHash({ secret, prevHash, payload: payloadText });
    const line = `${payloadText.slice(0, -1)},"hash":"${hash}"}\n`;
    fs.appendFileSync(filePath, line);
    prevHash = hash;
  }

  function verify() {
    if (!fs.existsSync(filePath)) return { ok: true, entries: 0 };
    const content = fs.readFileSync(filePath, "utf8");
    const lines = content.trim().split("\n").filter(Boolean);
    let lastHash = "0";
    for (const line of lines) {
      let entry;
      try {
        entry = JSON.parse(line);
      } catch (err) {
        return { ok: false, error: "invalid_json" };
      }
      const { hash, prev_hash: prevHashField, ...rest } = entry;
      const payloadText = JSON.stringify({ ...rest, prev_hash: prevHashField });
      const expected = computeHash({ secret, prevHash: lastHash, payload: payloadText });
      if (prevHashField !== lastHash || hash !== expected) {
        return { ok: false, error: "hash_mismatch" };
      }
      lastHash = hash;
    }
    return { ok: true, entries: lines.length };
  }

  onLog("info", "audit_log_enabled", { path: filePath });

  return { log, verify, path: filePath };
}

module.exports = { createAuditLogger };
