const fs = require("fs");

function readNumber(value, fallback, min = 0) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < min) return fallback;
  return parsed;
}

function readBool(value, fallback) {
  if (value === undefined || value === null || value === "") return fallback;
  return value === "1" || String(value).toLowerCase() === "true";
}

function readJsonFile(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch (err) {
    throw new Error(`failed to read registry admin credentials file: ${err.message}`);
  }
}

function parseBetaProgramJson(raw) {
  if (!raw || !String(raw).trim()) return {};
  try {
    return JSON.parse(String(raw));
  } catch (err) {
    throw new Error(`failed to parse REGISTRY_BETA_PROGRAM_JSON: ${err.message}`);
  }
}

function loadConfig(env = process.env) {
  const sharedSecret = env.REGISTRY_SHARED_SECRET || "";
  const credentialsFilePath = env.REGISTRY_ADMIN_CREDENTIALS_FILE || "";
  const allowPlaintextAdminPassword = readBool(env.REGISTRY_ADMIN_ALLOW_PLAINTEXT_PASSWORD, false);
  const privateRegistryMode = readBool(env.REGISTRY_PRIVATE_MODE, false);
  let adminUsername = env.REGISTRY_ADMIN_USERNAME || "admin";
  let adminPasswordHash = env.REGISTRY_ADMIN_PASSWORD_HASH || "";

  if (credentialsFilePath) {
    const credentials = readJsonFile(credentialsFilePath);
    adminUsername = String(credentials.username || adminUsername || "admin");
    adminPasswordHash = String(credentials.password_hash || adminPasswordHash || "");
  }

  if (!adminPasswordHash && !allowPlaintextAdminPassword) {
    throw new Error(
      "registry admin password hash is required; set REGISTRY_ADMIN_PASSWORD_HASH or REGISTRY_ADMIN_CREDENTIALS_FILE"
    );
  }

  return {
    host: env.HOST || "0.0.0.0",
    port: readNumber(env.PORT || "8080", 8080, 1),
    dbPath: env.REGISTRY_DB_PATH || "/app/data/registry.sqlite",
    sharedSecret,
    activeTtlSeconds: readNumber(env.REGISTRY_ACTIVE_TTL_SECONDS, 300, 1),
    maxBodyBytes: readNumber(env.REGISTRY_MAX_BODY_BYTES, 65536, 1024),
    signatureSkewSeconds: readNumber(env.REGISTRY_SIGNATURE_SKEW_SECONDS, 300, 1),
    nonceTtlSeconds: readNumber(env.REGISTRY_NONCE_TTL_SECONDS, 600, 1),
    requireSignature: readBool(env.REGISTRY_REQUIRE_SIGNATURE, true),
    adminUsername,
    adminPasswordHash,
    adminPasswordPlaintext: allowPlaintextAdminPassword ? env.REGISTRY_ADMIN_PASSWORD || "" : "",
    adminCredentialsFilePath: credentialsFilePath || null,
    adminAllowPlaintextPassword: allowPlaintextAdminPassword,
    adminSessionTtlSeconds: readNumber(env.REGISTRY_ADMIN_SESSION_TTL_SECONDS, 8 * 60 * 60, 60),
    privateRegistryMode,
    federationSessionTtlSeconds: readNumber(env.REGISTRY_FEDERATION_SESSION_TTL_SECONDS, 300, 60),
    federationMaxPeersPerSession: readNumber(env.REGISTRY_FEDERATION_MAX_PEERS_PER_SESSION, 4, 0),
    federationPolicyVersion: readNumber(env.REGISTRY_FEDERATION_POLICY_VERSION, 1, 1),
    federationMinTrustLevel: env.REGISTRY_FEDERATION_MIN_TRUST_LEVEL || (privateRegistryMode ? "community" : "trusted"),
    federationRequireCertificate: readBool(env.REGISTRY_FEDERATION_REQUIRE_CERTIFICATE, false),
    federationSessionSigningSecret: env.REGISTRY_FEDERATION_SESSION_SIGNING_SECRET || sharedSecret,
    federationDefaultMaxEventsPerMinute: readNumber(env.REGISTRY_FEDERATION_MAX_EVENTS_PER_MINUTE, 600, 1),
    federationDefaultMaxBytesPerMinute: readNumber(env.REGISTRY_FEDERATION_MAX_BYTES_PER_MINUTE, 10485760, 1),
    federationDefaultMaxInFlight: readNumber(env.REGISTRY_FEDERATION_MAX_IN_FLIGHT, 4, 1),
    clientAccessGrantTtlSeconds: readNumber(env.REGISTRY_CLIENT_ACCESS_GRANT_TTL_SECONDS, 300, 60),
    clientAccessMaxCandidates: readNumber(env.REGISTRY_CLIENT_ACCESS_MAX_CANDIDATES, 3, 1),
    clientAccessPolicyVersion: readNumber(env.REGISTRY_CLIENT_ACCESS_POLICY_VERSION, 1, 1),
    clientAccessMinTrustLevel: env.REGISTRY_CLIENT_ACCESS_MIN_TRUST_LEVEL || "",
    clientAccessGrantSigningSecret: env.REGISTRY_CLIENT_ACCESS_GRANT_SIGNING_SECRET || sharedSecret,
    clientAccessDefaultMaxEventsPerMinute: readNumber(env.REGISTRY_CLIENT_ACCESS_MAX_EVENTS_PER_MINUTE, 120, 1),
    clientAccessDefaultMaxBytesPerMinute: readNumber(env.REGISTRY_CLIENT_ACCESS_MAX_BYTES_PER_MINUTE, 1048576, 1),
    clientAccessPreferSameRegion: readBool(env.REGISTRY_CLIENT_ACCESS_PREFER_SAME_REGION, true),
    clientAbuseReportAcceptanceWindowSeconds: readNumber(
      env.REGISTRY_CLIENT_ABUSE_REPORT_ACCEPTANCE_WINDOW_SECONDS,
      15 * 60,
      60
    ),
    clientAbuseDeprioritizeWindowSeconds: readNumber(
      env.REGISTRY_CLIENT_ABUSE_DEPRIORITIZE_WINDOW_SECONDS,
      60 * 60,
      60
    ),
    clientAbusePairCooldownSeconds: readNumber(
      env.REGISTRY_CLIENT_ABUSE_PAIR_COOLDOWN_SECONDS,
      30 * 60,
      60
    ),
    clientAbuseSingleReportWeight: readNumber(
      env.REGISTRY_CLIENT_ABUSE_SINGLE_REPORT_WEIGHT,
      1,
      0
    ),
    clientAbuseDistinctClientBonus: readNumber(
      env.REGISTRY_CLIENT_ABUSE_DISTINCT_CLIENT_BONUS,
      2,
      0
    ),
    clientAbuseDuplicateBucketSeconds: readNumber(
      env.REGISTRY_CLIENT_ABUSE_DUPLICATE_BUCKET_SECONDS,
      300,
      60
    ),
    clientAbuseRateLimitWindowSeconds: readNumber(
      env.REGISTRY_CLIENT_ABUSE_RATE_LIMIT_WINDOW_SECONDS,
      900,
      60
    ),
    clientAbuseMaxReportsPerClientWindow: readNumber(
      env.REGISTRY_CLIENT_ABUSE_MAX_REPORTS_PER_CLIENT_WINDOW,
      8,
      1
    ),
    clientAbuseMaxReportsPerIpWindow: readNumber(
      env.REGISTRY_CLIENT_ABUSE_MAX_REPORTS_PER_IP_WINDOW,
      16,
      1
    ),
    clientAbuseMaxReportsPerGrantWindow: readNumber(
      env.REGISTRY_CLIENT_ABUSE_MAX_REPORTS_PER_GRANT_WINDOW,
      4,
      1
    ),
    clientAbuseCorroborationClientThreshold: readNumber(
      env.REGISTRY_CLIENT_ABUSE_CORROBORATION_CLIENT_THRESHOLD,
      2,
      1
    ),
    clientAbuseCorroborationScoreThreshold: readNumber(
      env.REGISTRY_CLIENT_ABUSE_CORROBORATION_SCORE_THRESHOLD,
      3,
      0
    ),
    betaProgram: parseBetaProgramJson(env.REGISTRY_BETA_PROGRAM_JSON),
  };
}

module.exports = { loadConfig };
