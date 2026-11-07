// Config loader skeleton.

function readNumber(value, fallback) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) return fallback;
  return parsed;
}

function readBool(value, fallback = false) {
  if (value === undefined || value === null || value === "") return fallback;
  return value === "1" || String(value).toLowerCase() === "true";
}

function readOptionalBool(value) {
  if (value === undefined || value === null || value === "") return null;
  return readBool(value, false);
}

function loadConfig() {
  const port = Number(process.env.PORT || "8080");
  const fetchMaxLimit = readNumber(process.env.FETCH_MAX_LIMIT, 200);
  const serverMode = process.env.SERVER_MODE || process.env.RELAY_MODE || "";
  const managedOrProduction =
    process.env.NODE_ENV === "production" ||
    serverMode === "managed";
  const allowUnsignedDevRequests = readBool(process.env.ALLOW_UNSIGNED_DEV_REQUESTS, false);
  const explicitSignatureRequired = readOptionalBool(process.env.REQUEST_SIGNATURE_REQUIRED);
  const legacySignatureEnabled = readBool(process.env.REQUEST_SIGNATURE_ENABLED, false);
  const requestSignatureRequired = allowUnsignedDevRequests
    ? false
    : Boolean(explicitSignatureRequired || legacySignatureEnabled || managedOrProduction);
  const requestSignatureSecret = process.env.REQUEST_SIGNATURE_SECRET || "";

  if (requestSignatureRequired && !requestSignatureSecret) {
    throw new Error(
      "REQUEST_SIGNATURE_SECRET is required when relay request signatures are required"
    );
  }

  const relaySelfId =
    process.env.RELAY_SELF_ID ||
    process.env.FEDERATION_SELF_ID ||
    process.env.REGISTRY_ADVERTISED_ENDPOINT ||
    "";
  const registryClientAccessGrantsEnabled =
    process.env.REGISTRY_CLIENT_ACCESS_GRANTS_ENABLED === "1";
  const registryClientAccessGrantSigningSecret =
    process.env.REGISTRY_CLIENT_ACCESS_GRANT_SIGNING_SECRET || "";
  const clientRelaySessionsEnabled =
    process.env.CLIENT_RELAY_SESSIONS_ENABLED === "1";
  const clientRelaySessionBindingSecret =
    process.env.CLIENT_RELAY_SESSION_BINDING_SECRET || "";

  if (registryClientAccessGrantsEnabled && !relaySelfId) {
    throw new Error(
      "RELAY_SELF_ID or FEDERATION_SELF_ID is required when managed client access grants are enabled"
    );
  }
  if (
    registryClientAccessGrantsEnabled &&
    !registryClientAccessGrantSigningSecret
  ) {
    throw new Error(
      "REGISTRY_CLIENT_ACCESS_GRANT_SIGNING_SECRET is required when managed client access grants are enabled"
    );
  }
  if (clientRelaySessionsEnabled && !relaySelfId) {
    throw new Error(
      "RELAY_SELF_ID or FEDERATION_SELF_ID is required when client relay sessions are enabled"
    );
  }
  if (clientRelaySessionsEnabled && !clientRelaySessionBindingSecret) {
    throw new Error(
      "CLIENT_RELAY_SESSION_BINDING_SECRET is required when client relay sessions are enabled"
    );
  }

  return {
    port,
    serverMode,
    managedOrProduction,
    relaySelfId,
    dataDir: process.env.DATA_DIR || "./data",
    databaseUrl: process.env.DATABASE_URL || "postgres://postgres:postgres@localhost:5432/relay",
    dbPoolMax: readNumber(process.env.DB_POOL_MAX, 10),
    dbIdleTimeoutMs: readNumber(process.env.DB_IDLE_TIMEOUT_MS, 30000),
    dbConnTimeoutMs: readNumber(process.env.DB_CONN_TIMEOUT_MS, 2000),
    dbCircuitBreakerEnabled: process.env.DB_CIRCUIT_BREAKER_ENABLED !== "0",
    dbCircuitBreakerFailureThreshold: readNumber(
      process.env.DB_CIRCUIT_BREAKER_FAILURE_THRESHOLD,
      3
    ),
    dbCircuitBreakerBaseBackoffMs: readNumber(
      process.env.DB_CIRCUIT_BREAKER_BASE_BACKOFF_MS,
      500
    ),
    dbCircuitBreakerMaxBackoffMs: readNumber(
      process.env.DB_CIRCUIT_BREAKER_MAX_BACKOFF_MS,
      5000
    ),
    redisUrl: process.env.REDIS_URL || "",
    maxEventsPerRequest: readNumber(process.env.MAX_EVENTS_PER_REQUEST, 100),
    maxEventBytes: readNumber(process.env.MAX_EVENT_BYTES, 65536),
    maxRequestBytes: readNumber(process.env.MAX_REQUEST_BYTES, 1_000_000),
    eventMaxFutureSeconds: readNumber(process.env.EVENT_MAX_FUTURE_SECONDS, 600),
    eventExpGraceSeconds: readNumber(process.env.EVENT_EXP_GRACE_SECONDS, 300),
    cleanupIntervalSeconds: readNumber(process.env.CLEANUP_INTERVAL_SECONDS, 60),
    cleanupGraceSeconds: readNumber(process.env.CLEANUP_GRACE_SECONDS, 300),
    cleanupUseAdvisoryLock: process.env.CLEANUP_USE_ADVISORY_LOCK !== "0",
    cleanupWatchdogTimeoutSeconds: readNumber(process.env.CLEANUP_WATCHDOG_TIMEOUT_SECONDS, 120),
    retentionProfile: process.env.RETENTION_PROFILE || "",
    fetchMaxLimit,
    enableTestDbErrors: process.env.ENABLE_TEST_DB_ERRORS === "1",
    trustedProxyIps: (process.env.TRUSTED_PROXY_IPS || "")
      .split(",")
      .map((entry) => entry.trim())
      .filter(Boolean),
    ipAllowlist: (process.env.IP_ALLOWLIST || "")
      .split(",")
      .map((entry) => entry.trim())
      .filter(Boolean),
    ipDenylist: (process.env.IP_DENYLIST || "")
      .split(",")
      .map((entry) => entry.trim())
      .filter(Boolean),
    federationPeerAllowlist: (process.env.FEDERATION_PEER_ALLOWLIST || "")
      .split(",")
      .map((entry) => entry.trim())
      .filter(Boolean),
    federationPeerBanlist: (process.env.FEDERATION_PEER_BANLIST || "")
      .split(",")
      .map((entry) => entry.trim())
      .filter(Boolean),
    federationPeers: (process.env.FEDERATION_PEERS || "")
      .split(",")
      .map((entry) => entry.trim())
      .filter(Boolean),
    federationSyncGids: (process.env.FEDERATION_SYNC_GIDS || "")
      .split(",")
      .map((entry) => entry.trim())
      .filter(Boolean),
    federationSyncIntervalSeconds: readNumber(
      process.env.FEDERATION_SYNC_INTERVAL_SECONDS,
      60
    ),
    federationSyncMaxBackoffSeconds: readNumber(
      process.env.FEDERATION_SYNC_MAX_BACKOFF_SECONDS,
      300
    ),
    federationSyncJitterSeconds: readNumber(process.env.FEDERATION_SYNC_JITTER_SECONDS, 5),
    federationSyncMaxInFlightPerPeer: readNumber(
      process.env.FEDERATION_SYNC_MAX_IN_FLIGHT_PER_PEER,
      1
    ),
    federationSyncMaxQueuePerPeer: readNumber(
      process.env.FEDERATION_SYNC_MAX_QUEUE_PER_PEER,
      2
    ),
    federationRotatedTokenTtlSeconds: readNumber(
      process.env.FEDERATION_ROTATED_TOKEN_TTL_SECONDS,
      300
    ),
    federationJoinTokenTtlSeconds: readNumber(
      process.env.FEDERATION_JOIN_TOKEN_TTL_SECONDS,
      600
    ),
    federationJoinRateLimit: readNumber(process.env.FEDERATION_JOIN_RATE_LIMIT, 60),
    federationJoinRateLimitWindowSeconds: readNumber(
      process.env.FEDERATION_JOIN_RATE_LIMIT_WINDOW_SECONDS,
      60
    ),
    federationHandshakeIntervalSeconds: readNumber(
      process.env.FEDERATION_HANDSHAKE_INTERVAL_SECONDS,
      60
    ),
    federationHandshakeRateLimit: readNumber(
      process.env.FEDERATION_HANDSHAKE_RATE_LIMIT,
      30
    ),
    federationHandshakeRateLimitWindowSeconds: readNumber(
      process.env.FEDERATION_HANDSHAKE_RATE_LIMIT_WINDOW_SECONDS,
      60
    ),
    backupOkFile: process.env.BACKUP_OK_FILE || "",
    backupMaxAgeSeconds: readNumber(process.env.BACKUP_MAX_AGE_SECONDS, 0),
    backupRetentionDays: readNumber(process.env.BACKUP_RETENTION_DAYS, 0),
    restoreDrillMaxAgeSeconds: readNumber(process.env.RESTORE_DRILL_MAX_AGE_SECONDS, 0),
    restoreDrillOkFile: process.env.RESTORE_DRILL_OK_FILE || "",
    alertLogPath: process.env.ALERT_LOG_PATH || "",
    banlistImportHmacSecret: process.env.BANLIST_IMPORT_HMAC_SECRET || "",
    federationHeartbeatTtlSeconds: readNumber(
      process.env.FEDERATION_HEARTBEAT_TTL_SECONDS,
      120
    ),
    federationHeartbeatFailureThreshold: readNumber(
      process.env.FEDERATION_HEARTBEAT_FAILURE_THRESHOLD,
      5
    ),
    federationHeartbeatBackoffSeconds: readNumber(
      process.env.FEDERATION_HEARTBEAT_BACKOFF_SECONDS,
      30
    ),
    federationConnectionCapacity: readNumber(
      process.env.FEDERATION_CONNECTION_CAPACITY,
      32
    ),
    federationSyncLimit: Math.min(
      readNumber(process.env.FEDERATION_SYNC_LIMIT, fetchMaxLimit),
      fetchMaxLimit
    ),
    federationMinPeers: readNumber(process.env.FEDERATION_MIN_PEERS, 0),
    federationMaxPeers: readNumber(process.env.FEDERATION_MAX_PEERS, 0),
    federationSelfUrl: process.env.FEDERATION_SELF_URL || "",
    federationSelfId: process.env.FEDERATION_SELF_ID || process.env.FEDERATION_SELF_URL || "",
    federationMtlsEnabled: process.env.FEDERATION_MTLS_ENABLED === "1",
    federationMtlsCertPath: process.env.FEDERATION_MTLS_CERT_PATH || "",
    federationMtlsKeyPath: process.env.FEDERATION_MTLS_KEY_PATH || "",
    federationMtlsCaPath: process.env.FEDERATION_MTLS_CA_PATH || "",
    federationMtlsRejectUnauthorized: process.env.FEDERATION_MTLS_REJECT_UNAUTHORIZED !== "0",
    federationAllowCursorRegression: process.env.FEDERATION_CURSOR_ALLOW_REGRESSION === "1",
    federationRequireTls: process.env.FEDERATION_REQUIRE_TLS === "1",
    federationPushEnabled: readBool(process.env.FEDERATION_PUSH_ENABLED, true),
    federationPushIntervalMs: readNumber(process.env.FEDERATION_PUSH_INTERVAL_MS, 500),
    federationPushWorkers: readNumber(process.env.FEDERATION_PUSH_WORKERS, 1),
    federationPushMaxQueue: readNumber(process.env.FEDERATION_PUSH_MAX_QUEUE, 1000),
    federationPushMaxBatchEvents: readNumber(process.env.FEDERATION_PUSH_MAX_BATCH_EVENTS, 100),
    federationPushRetryBaseMs: readNumber(process.env.FEDERATION_PUSH_RETRY_BASE_MS, 500),
    federationPushRetryMaxMs: readNumber(process.env.FEDERATION_PUSH_RETRY_MAX_MS, 30000),
    rateLimitWindowSeconds: readNumber(process.env.RATE_LIMIT_WINDOW_SECONDS, 60),
    rateLimitBurstWindowSeconds: readNumber(
      process.env.RATE_LIMIT_BURST_WINDOW_SECONDS,
      10
    ),
    rateLimitSpikeWindowSeconds: readNumber(process.env.RATE_LIMIT_SPIKE_WINDOW_SECONDS, 60),
    rateLimitSpikeThreshold: readNumber(process.env.RATE_LIMIT_SPIKE_THRESHOLD, 50),
    rateLimitSpikeCooldownSeconds: readNumber(
      process.env.RATE_LIMIT_SPIKE_COOLDOWN_SECONDS,
      120
    ),
    ingestRateLimitPerIp: readNumber(process.env.INGEST_RATE_LIMIT_PER_IP, 120),
    ingestRateLimitPerGid: readNumber(process.env.INGEST_RATE_LIMIT_PER_GID, 300),
    ingestRetryEnabled: process.env.INGEST_RETRY_ENABLED === "1",
    ingestRetryMaxEntries: readNumber(process.env.INGEST_RETRY_MAX_ENTRIES, 500),
    ingestRetryMaxAttempts: readNumber(process.env.INGEST_RETRY_MAX_ATTEMPTS, 5),
    ingestRetryBaseDelayMs: readNumber(process.env.INGEST_RETRY_BASE_DELAY_MS, 500),
    ingestRetryMaxDelayMs: readNumber(process.env.INGEST_RETRY_MAX_DELAY_MS, 5000),
    ingestRetryIntervalMs: readNumber(process.env.INGEST_RETRY_INTERVAL_MS, 250),
    dbCooldownEnabled: process.env.DB_COOLDOWN_ENABLED === "1",
    dbCooldownWaitingThreshold: readNumber(process.env.DB_COOLDOWN_WAITING_THRESHOLD, 10),
    dbCooldownWaitMsThreshold: readNumber(process.env.DB_COOLDOWN_WAIT_MS_THRESHOLD, 50),
    dbCooldownMinSeconds: readNumber(process.env.DB_COOLDOWN_MIN_SECONDS, 15),
    dbCooldownStableWindows: readNumber(process.env.DB_COOLDOWN_STABLE_WINDOWS, 2),
    dbCooldownCheckIntervalMs: readNumber(process.env.DB_COOLDOWN_CHECK_INTERVAL_MS, 1000),
    auditLogEnabled: process.env.AUDIT_LOG_ENABLED === "1",
    auditLogPath: process.env.AUDIT_LOG_PATH || "",
    auditLogSecret: process.env.AUDIT_LOG_SECRET || "",
    requestSignatureEnabled: requestSignatureRequired,
    requestSignatureRequired,
    allowUnsignedDevRequests,
    requestSignatureSecret,
    requestSignatureSkewSeconds: readNumber(
      process.env.REQUEST_SIGNATURE_SKEW_SECONDS,
      300
    ),
    requestSignatureReplayTtlSeconds: readNumber(
      process.env.REQUEST_SIGNATURE_REPLAY_TTL_SECONDS,
      Math.max(600, readNumber(process.env.REQUEST_SIGNATURE_SKEW_SECONDS, 300) * 2)
    ),
    requestSignatureReplayMaxEntries: readNumber(
      process.env.REQUEST_SIGNATURE_REPLAY_MAX_ENTRIES,
      10000
    ),
    adminAuthFile: process.env.ADMIN_AUTH_FILE || "./config/admin_auth.json",
    adminSessionTtlSeconds: readNumber(process.env.ADMIN_SESSION_TTL_SECONDS, 8 * 60 * 60),
    fetchRateLimitPerIp: readNumber(process.env.FETCH_RATE_LIMIT_PER_IP, 120),
    fetchRateLimitPerGid: readNumber(process.env.FETCH_RATE_LIMIT_PER_GID, 300),
    ingestBurstPerIp: readNumber(process.env.INGEST_BURST_PER_IP, 30),
    ingestBurstPerGid: readNumber(process.env.INGEST_BURST_PER_GID, 60),
    fetchBurstPerIp: readNumber(process.env.FETCH_BURST_PER_IP, 30),
    fetchBurstPerGid: readNumber(process.env.FETCH_BURST_PER_GID, 60),
    nonceCacheMaxEntries: readNumber(process.env.NONCE_CACHE_MAX_ENTRIES, 10000),
    nonceCacheTtlSeconds: readNumber(process.env.NONCE_CACHE_TTL_SECONDS, 600),
    healthCheckDbTimeoutMs: readNumber(process.env.HEALTH_CHECK_DB_TIMEOUT_MS, 2000),
    observabilitySummaryIntervalSeconds: readNumber(
      process.env.OBSERVABILITY_SUMMARY_INTERVAL_SECONDS,
      60
    ),
    observabilitySloTarget: Math.min(
      1,
      Math.max(readNumber(process.env.OBSERVABILITY_SLO_TARGET, 0.99), 0)
    ),
    partitionCheckIntervalSeconds: readNumber(
      process.env.PARTITION_CHECK_INTERVAL_SECONDS,
      300
    ),
    startupSchemaValidation: process.env.STARTUP_SCHEMA_VALIDATION !== "0",
    startupSchemaValidationTimeoutMs: readNumber(
      process.env.STARTUP_SCHEMA_VALIDATION_TIMEOUT_MS,
      5000
    ),
    partitionAutoCreateEnabled: process.env.PARTITION_AUTO_CREATE_ENABLED === "1",
    partitionAutoCreateMonthsAhead: readNumber(
      process.env.PARTITION_AUTO_CREATE_MONTHS_AHEAD,
      1
    ),
    partitionAutoCreateBackfillMonths: readNumber(
      process.env.PARTITION_AUTO_CREATE_BACKFILL_MONTHS,
      0
    ),
    partitionAutoCreateIntervalSeconds: readNumber(
      process.env.PARTITION_AUTO_CREATE_INTERVAL_SECONDS,
      3600
    ),
    registryHeartbeatEnabled:
      process.env.REGISTRY_HEARTBEAT_ENABLED === "1",

    registryUrl:
      process.env.REGISTRY_URL || "",

    registrySharedSecret:
      process.env.REGISTRY_SHARED_SECRET || "",

    registryAdvertisedEndpoint:
      process.env.REGISTRY_ADVERTISED_ENDPOINT || "",

    registryRelayRegion:
      process.env.REGISTRY_RELAY_REGION || "",

    registryHeartbeatIntervalSeconds:
      readNumber(
        process.env.REGISTRY_HEARTBEAT_INTERVAL_SECONDS,
        60
      ),
    registryFederationSessionsEnabled:
      process.env.REGISTRY_FEDERATION_SESSIONS_ENABLED === "1",
    registryFederationSessionRefreshIntervalSeconds:
      readNumber(process.env.REGISTRY_FEDERATION_SESSION_REFRESH_INTERVAL_SECONDS, 30),
    registryFederationSessionRetrySeconds:
      readNumber(process.env.REGISTRY_FEDERATION_SESSION_RETRY_SECONDS, 30),
    registryFederationSessionRenewAheadSeconds:
      readNumber(process.env.REGISTRY_FEDERATION_SESSION_RENEW_AHEAD_SECONDS, 60),
    registryFederationRevocationsPollSeconds:
      readNumber(process.env.REGISTRY_FEDERATION_REVOCATIONS_POLL_SECONDS, 60),
    registryFederationSessionMaxPeers:
      readNumber(process.env.REGISTRY_FEDERATION_SESSION_MAX_PEERS, 4),
    registryFederationSessionMaxEventsPerMinute:
      readNumber(process.env.REGISTRY_FEDERATION_SESSION_MAX_EVENTS_PER_MINUTE, 600),
    registryFederationSessionMaxBytesPerMinute:
      readNumber(process.env.REGISTRY_FEDERATION_SESSION_MAX_BYTES_PER_MINUTE, 10485760),
    registryFederationSessionSigningSecret:
      process.env.REGISTRY_FEDERATION_SESSION_SIGNING_SECRET || "",
    registryClientAccessGrantsEnabled,
    registryClientAccessGrantSigningSecret,
    clientRelaySessionsEnabled,
    clientRelaySessionBindingSecret,
    clientRelaySessionTtlSeconds: readNumber(
      process.env.CLIENT_RELAY_SESSION_TTL_SECONDS,
      300
    ),
    clientRelaySessionCleanupIntervalSeconds: readNumber(
      process.env.CLIENT_RELAY_SESSION_CLEANUP_INTERVAL_SECONDS,
      30
    ),
    clientRelaySessionMaxPerClient: readNumber(
      process.env.CLIENT_RELAY_SESSION_MAX_PER_CLIENT,
      8
    ),
  };
}

module.exports = { loadConfig };
