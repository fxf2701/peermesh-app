const { randomBytes, scryptSync, timingSafeEqual } = require("crypto");

const sessions = new Map();

function safeEqual(a, b) {
  const left = Buffer.isBuffer(a) ? a : Buffer.from(String(a || ""));
  const right = Buffer.isBuffer(b) ? b : Buffer.from(String(b || ""));
  return left.length === right.length && timingSafeEqual(left, right);
}

function parsePasswordHash(encoded) {
  const value = String(encoded || "");
  const parts = value.split("$");
  if (parts.length !== 6 || parts[0] !== "scrypt") {
    throw new Error("unsupported registry admin password hash format");
  }
  const [, n, r, p, saltHex, keyHex] = parts;
  return {
    algorithm: "scrypt",
    params: {
      N: Number(n),
      r: Number(r),
      p: Number(p),
    },
    salt: Buffer.from(saltHex, "hex"),
    key: Buffer.from(keyHex, "hex"),
  };
}

function verifyPassword(password, config, parsedPasswordHash) {
  if (parsedPasswordHash) {
    const derived = scryptSync(String(password || ""), parsedPasswordHash.salt, parsedPasswordHash.key.length, parsedPasswordHash.params);
    return safeEqual(derived, parsedPasswordHash.key);
  }
  if (config.adminAllowPlaintextPassword) {
    return safeEqual(password, config.adminPasswordPlaintext);
  }
  return false;
}

function parseCookies(req) {
  const header = req.headers.cookie;
  if (!header || typeof header !== "string") return {};
  const result = {};
  for (const entry of header.split(";")) {
    const [key, ...rest] = entry.trim().split("=");
    if (key) result[key] = rest.join("=");
  }
  return result;
}

function buildSessionCookie(sessionId, ttlSeconds) {
  return [
    `registry_admin_session=${sessionId}`,
    "Path=/",
    "HttpOnly",
    "SameSite=Lax",
    `Max-Age=${ttlSeconds}`,
  ].join("; ");
}

function buildClearCookie() {
  return [
    "registry_admin_session=",
    "Path=/",
    "HttpOnly",
    "SameSite=Lax",
    "Max-Age=0",
  ].join("; ");
}

function createRegistryAdminAuth(config) {
  const parsedPasswordHash = config.adminPasswordHash ? parsePasswordHash(config.adminPasswordHash) : null;

  function login(username, password) {
    if (!safeEqual(username, config.adminUsername)) return null;
    if (!verifyPassword(password, config, parsedPasswordHash)) return null;
    const sessionId = randomBytes(24).toString("hex");
    const csrfToken = randomBytes(24).toString("hex");
    sessions.set(sessionId, {
      username: config.adminUsername,
      csrfToken,
      expiresAt: Date.now() + config.adminSessionTtlSeconds * 1000,
    });
    return sessionId;
  }

  function sessionForRequest(req) {
    const sessionId = parseCookies(req).registry_admin_session;
    if (!sessionId) return null;
    const session = sessions.get(sessionId);
    if (!session) return null;
    if (session.expiresAt <= Date.now()) {
      sessions.delete(sessionId);
      return null;
    }
    return { ...session, sessionId };
  }

  function logout(req) {
    const sessionId = parseCookies(req).registry_admin_session;
    if (sessionId) sessions.delete(sessionId);
  }

  function verifyCsrf(req, session) {
    const headerToken = req.headers["x-csrf-token"];
    if (!session || !headerToken) return false;
    return safeEqual(headerToken, session.csrfToken);
  }

  return {
    login,
    logout,
    sessionForRequest,
    verifyCsrf,
    buildSessionCookie,
    buildClearCookie,
  };
}

module.exports = { createRegistryAdminAuth, parsePasswordHash };
