const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const DEFAULT_ITERATIONS = 150000;
const SESSION_BYTES = 24;

const sessions = new Map();

function resolveAuthFile(adminAuthFile) {
  if (!adminAuthFile) return null;
  return path.resolve(process.cwd(), adminAuthFile);
}

function hashPassword(password, salt = crypto.randomBytes(16).toString("base64")) {
  const hash = crypto
    .pbkdf2Sync(password, salt, DEFAULT_ITERATIONS, 32, "sha256")
    .toString("base64");
  return `pbkdf2_sha256$${DEFAULT_ITERATIONS}$${salt}$${hash}`;
}

function verifyPassword(password, stored) {
  if (!stored || typeof stored !== "string") return false;
  const parts = stored.split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2_sha256") return false;
  const iterations = Number(parts[1]);
  const salt = parts[2];
  const expected = parts[3];
  if (!Number.isFinite(iterations) || iterations <= 0 || !salt || !expected) return false;
  const actual = crypto
    .pbkdf2Sync(password, salt, iterations, 32, "sha256")
    .toString("base64");
  return crypto.timingSafeEqual(Buffer.from(actual), Buffer.from(expected));
}

function loadAdminAuth({ config }) {
  const authFile = resolveAuthFile(config.adminAuthFile);
  if (!authFile || !fs.existsSync(authFile)) {
    return { ok: false, error: "auth_file_missing", authFile };
  }
  try {
    const raw = fs.readFileSync(authFile, "utf8");
    const parsed = JSON.parse(raw);
    if (!parsed.username || !parsed.password_hash) {
      return { ok: false, error: "invalid_auth_file", authFile };
    }
    return { ok: true, authFile, username: parsed.username, passwordHash: parsed.password_hash };
  } catch (err) {
    return { ok: false, error: "auth_file_unreadable", authFile, details: err };
  }
}

function saveAdminAuth({ config, username, passwordHash }) {
  const authFile = resolveAuthFile(config.adminAuthFile);
  if (!authFile) {
    return { ok: false, error: "auth_file_missing" };
  }
  const payload = {
    username,
    password_hash: passwordHash,
    updated_at: new Date().toISOString(),
  };
  fs.mkdirSync(path.dirname(authFile), { recursive: true });
  fs.writeFileSync(authFile, `${JSON.stringify(payload, null, 2)}\n`, { mode: 0o600 });
  try {
    fs.chmodSync(authFile, 0o600);
  } catch (err) {
    // Ignore chmod errors on non-POSIX filesystems.
  }
  return { ok: true, authFile };
}

function parseCookies(req) {
  const header = req.headers.cookie;
  if (!header || typeof header !== "string") return {};
  const entries = header.split(";").map((item) => item.trim());
  const result = {};
  for (const entry of entries) {
    const [key, ...rest] = entry.split("=");
    if (!key) continue;
    result[key] = rest.join("=");
  }
  return result;
}

function isSecureRequest(req) {
  if (req.socket && req.socket.encrypted) return true;
  const forwarded = req.headers["x-forwarded-proto"];
  if (typeof forwarded === "string") {
    return forwarded.split(",")[0].trim() === "https";
  }
  return false;
}

function buildSessionCookie({ sessionId, maxAgeSeconds, secure }) {
  const parts = [
    `admin_session=${sessionId}`,
    "Path=/",
    "HttpOnly",
    "SameSite=Lax",
    `Max-Age=${maxAgeSeconds}`,
  ];
  if (secure) parts.push("Secure");
  return parts.join("; ");
}

function buildClearCookie({ secure }) {
  const parts = [
    "admin_session=",
    "Path=/",
    "HttpOnly",
    "SameSite=Lax",
    "Max-Age=0",
  ];
  if (secure) parts.push("Secure");
  return parts.join("; ");
}

function createSession({ username, ttlSeconds }) {
  const sessionId = crypto.randomBytes(SESSION_BYTES).toString("hex");
  const expiresAt = Date.now() + ttlSeconds * 1000;
  sessions.set(sessionId, { username, expiresAt });
  return sessionId;
}

function getSession(sessionId) {
  if (!sessionId) return null;
  const entry = sessions.get(sessionId);
  if (!entry) return null;
  if (entry.expiresAt <= Date.now()) {
    sessions.delete(sessionId);
    return null;
  }
  return entry;
}

function clearSession(sessionId) {
  if (!sessionId) return;
  sessions.delete(sessionId);
}

function getSessionId(req) {
  const cookies = parseCookies(req);
  return cookies.admin_session || null;
}

module.exports = {
  hashPassword,
  verifyPassword,
  loadAdminAuth,
  saveAdminAuth,
  createSession,
  getSession,
  clearSession,
  getSessionId,
  buildSessionCookie,
  buildClearCookie,
  isSecureRequest,
};
