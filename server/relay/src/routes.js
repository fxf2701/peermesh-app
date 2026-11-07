// Routing table for relay endpoints.

const fs = require("fs");
const path = require("path");
const { parseJsonBodyWithRaw, sendJson } = require("./utils");
const { validateIngestRequest, validateFetchRequest } = require("./validation");
const { createStorage } = require("./storage");
const { createRateLimiter } = require("./rate_limit");
const { createRedisClient } = require("./redis");
const { createMetrics } = require("./metrics");
const { logEvent, logRequest } = require("./logger");
const { createIngestRetryQueue } = require("./ingest_retry_queue");
const { createAuditLogger } = require("./audit_log");
const { createHash, createHmac, timingSafeEqual, randomUUID } = require("crypto");
const { verifySignature, createRequestReplayCache } = require("./request_signatures");
const {
  signFederationRequest,
  validateFederationGrant,
  validateFederationPushGrant,
} = require("./federation_push");
const {
  decodeClientAccessGrantHeader,
  validateClientAccessGrant,
} = require("./client_access_grants");
const { validateHopChain } = require("./federation_hops");
const { isPeerBanned, matchesIpList } = require("./peer_policy");
const { isPeerMappingActive, isPeerMappingQuarantined } = require("./peer_mapping_state");
const { createFederationSessionLimitManager } = require("./federation_session_limits");
const { createAdminRouter } = require("./routes_admin");

function redactToken(token) {
  if (!token || typeof token !== "string") return null;
  if (token.length <= 8) return "***";
  return `${token.slice(0, 4)}...${token.slice(-4)}`;
}

function readPublicTokenFromFile(dataDir) {
  const tokenPath = path.join(dataDir || "./data", "public_token.txt");
  try {
    if (!fs.existsSync(tokenPath)) return { token: null, createdAt: null };
    const raw = fs.readFileSync(tokenPath, "utf8");
    const tokenLine = raw.split("\n").find((line) => line.startsWith("token="));
    const createdLine = raw
      .split("\n")
      .find((line) => line.startsWith("created_at_utc="));
    const tokenValue = tokenLine ? tokenLine.replace("token=", "").trim() : null;
    const createdAt = createdLine ? createdLine.replace("created_at_utc=", "").trim() : null;
    return { token: tokenValue || null, createdAt: createdAt || null };
  } catch (err) {
    return { token: null, createdAt: null };
  }
}

function getPublicToken(config) {
  const fileToken = readPublicTokenFromFile(config.dataDir).token;
  return fileToken || "";
}

function createRouter({
  config,
  storage: providedStorage,
  redis: providedRedis,
  metrics: providedMetrics,
  cooldown,
  federationState,
  federationSync,
  registrySessions,
  federationPush,
  clientRelaySessions,
}) {
  const redis = providedRedis || createRedisClient({ url: config.redisUrl });
  const storage = providedStorage || createStorage({ config, redis });
  const metrics = providedMetrics || createMetrics();
  const requestReplayCache = createRequestReplayCache({
    redis,
    maxEntries: config.requestSignatureReplayMaxEntries,
    ttlSeconds: config.requestSignatureReplayTtlSeconds,
  });
  const ownsStorage = !providedStorage;
  const ownsRedis = !providedRedis;
  const ingestIpLimiter = createRateLimiter({
    limit: config.ingestRateLimitPerIp,
    windowSeconds: config.rateLimitWindowSeconds,
    redis,
    keyPrefix: "rl:ingest:ip",
  });
  const ingestGidLimiter = createRateLimiter({
    limit: config.ingestRateLimitPerGid,
    windowSeconds: config.rateLimitWindowSeconds,
    redis,
    keyPrefix: "rl:ingest:gid",
  });
  const fetchIpLimiter = createRateLimiter({
    limit: config.fetchRateLimitPerIp,
    windowSeconds: config.rateLimitWindowSeconds,
    redis,
    keyPrefix: "rl:fetch:ip",
  });
  const fetchGidLimiter = createRateLimiter({
    limit: config.fetchRateLimitPerGid,
    windowSeconds: config.rateLimitWindowSeconds,
    redis,
    keyPrefix: "rl:fetch:gid",
  });
  const ingestIpBurstLimiter = createRateLimiter({
    limit: config.ingestBurstPerIp,
    windowSeconds: config.rateLimitBurstWindowSeconds,
    redis,
    keyPrefix: "rlb:ingest:ip",
  });
  const ingestGidBurstLimiter = createRateLimiter({
    limit: config.ingestBurstPerGid,
    windowSeconds: config.rateLimitBurstWindowSeconds,
    redis,
    keyPrefix: "rlb:ingest:gid",
  });
  const fetchIpBurstLimiter = createRateLimiter({
    limit: config.fetchBurstPerIp,
    windowSeconds: config.rateLimitBurstWindowSeconds,
    redis,
    keyPrefix: "rlb:fetch:ip",
  });
  const fetchGidBurstLimiter = createRateLimiter({
    limit: config.fetchBurstPerGid,
    windowSeconds: config.rateLimitBurstWindowSeconds,
    redis,
    keyPrefix: "rlb:fetch:gid",
  });
  const joinRateLimiter = createRateLimiter({
    limit: config.federationJoinRateLimit,
    windowSeconds: config.federationJoinRateLimitWindowSeconds,
    redis,
    keyPrefix: "rl:join",
  });
  const handshakeRateLimiter = createRateLimiter({
    limit: config.federationHandshakeRateLimit,
    windowSeconds: config.federationHandshakeRateLimitWindowSeconds,
    redis,
    keyPrefix: "rl:handshake",
  });
  const ingestRetryQueue = createIngestRetryQueue({ storage, metrics, config });
  const auditLogger = createAuditLogger({ config });
  const federationSessionLimits = createFederationSessionLimitManager({ windowSeconds: 60 });
  const adminRouter = createAdminRouter({
    config,
    storage,
    federationState,
    federationSync,
    registrySessions,
    federationPush,
    auditLogger,
    metrics,
  });

  function isTrustedProxy(ip) {
    return matchesIpList(ip, config.trustedProxyIps);
  }

  function clientIp(req) {
    const remote = req.socket.remoteAddress || "unknown";
    if (!isTrustedProxy(remote)) return remote;
    const forwarded = req.headers["x-forwarded-for"];
    if (typeof forwarded === "string" && forwarded.length > 0) {
      return forwarded.split(",")[0].trim();
    }
    return remote;
  }

  function hashGid(gid) {
    if (!gid) return "unknown";
    return createHash("sha256").update(String(gid)).digest("hex").slice(0, 8);
  }

  function isTlsRequest(req) {
    if (req.socket && req.socket.encrypted) return true;
    const forwarded = req.headers["x-forwarded-proto"];
    return typeof forwarded === "string" && forwarded.toLowerCase().includes("https");
  }

  function rejectRateLimited(res, scope, retryAfterSeconds, extra) {
    if (retryAfterSeconds) {
      res.setHeader("Retry-After", String(retryAfterSeconds));
    }
    return sendJson(res, 429, {
      error: {
        code: "rate_limited",
        message: "Rate limit exceeded.",
        details: {
          scope,
          retry_after_seconds: retryAfterSeconds,
          ...extra,
        },
      },
    });
  }

  function requiresAppRequestSignature(req, pathname) {
    if (!config.requestSignatureRequired) return false;
    if (req.headers["x-relay-peer"]) return false;
    if (req.headers["x-federation-session"]) return false;
    if (hasManagedClientTransportAuth(req, pathname)) return false;
    return (
      (req.method === "POST" && pathname === "/v1/client/session:open") ||
      (req.method === "POST" && /^\/v1\/client\/session\/[^/]+:(renew|close)$/.test(pathname)) ||
      (req.method === "GET" && pathname === "/v1/events:fetch") ||
      (req.method === "POST" && pathname === "/v1/events:ingest")
    );
  }

  function hasManagedClientTransportAuth(req, pathname) {
    const grantHeader = headerValue(req, "x-relay-access-grant");
    const sessionId = headerValue(req, "x-client-relay-session");
    const sessionToken = headerValue(req, "x-client-relay-session-token");
    if (req.method === "POST" && pathname === "/v1/client/session:open") {
      return Boolean(config.registryClientAccessGrantsEnabled && grantHeader);
    }
    if (req.method === "POST" && /^\/v1\/client\/session\/[^/]+:renew$/.test(pathname)) {
      return Boolean(
        config.registryClientAccessGrantsEnabled &&
          config.clientRelaySessionsEnabled &&
          grantHeader &&
          sessionId &&
          sessionToken
      );
    }
    if (req.method === "POST" && /^\/v1\/client\/session\/[^/]+:close$/.test(pathname)) {
      return Boolean(config.clientRelaySessionsEnabled && sessionId && sessionToken);
    }
    if (
      (req.method === "GET" && pathname === "/v1/events:fetch") ||
      (req.method === "POST" && pathname === "/v1/events:ingest")
    ) {
      return Boolean(
        config.registryClientAccessGrantsEnabled &&
          (grantHeader || sessionId || sessionToken)
      );
    }
    return false;
  }

  async function verifyAppRequestSignature(req, res, { requestTarget, rawBody, deferReplayProtection = false }) {
    const signature = req.headers["x-relay-signature"];
    const timestamp = req.headers["x-relay-timestamp"];
    const result = verifySignature({
      secret: config.requestSignatureSecret,
      method: req.method,
      path: requestTarget,
      rawBody,
      timestamp: typeof timestamp === "string" ? timestamp : "",
      signature: typeof signature === "string" ? signature : "",
      skewSeconds: config.requestSignatureSkewSeconds,
    });
    if (!result.ok) {
      sendJson(res, 401, {
        error: { code: "invalid_signature", message: "Invalid request signature." },
      });
      return false;
    }
    if (requestReplayCache && result.replayKey) {
      if (deferReplayProtection) {
        const replayed = requestReplayCache.has
          ? await requestReplayCache.has(result.replayKey)
          : false;
        if (replayed) {
          sendJson(res, 401, {
            error: { code: "signature_replay", message: "Request signature replay detected." },
          });
          return false;
        }
        return {
          replayKey: result.replayKey,
          async commit() {
            return requestReplayCache.remember(result.replayKey);
          },
        };
      }
      const accepted = await requestReplayCache.remember(result.replayKey);
      if (!accepted) {
        sendJson(res, 401, {
          error: { code: "signature_replay", message: "Request signature replay detected." },
        });
        return false;
      }
    }
    return { replayKey: result.replayKey || null, commit: null };
  }

  async function commitReplayProtection(replayState, context) {
    if (!replayState || typeof replayState.commit !== "function") return true;
    try {
      const accepted = await replayState.commit();
      if (!accepted) {
        logEvent("warn", "request_signature_replay_commit_race", {
          context,
          replay_key: replayState.replayKey || null,
        });
      }
      return accepted;
    } catch (err) {
      logEvent("warn", "request_signature_replay_commit_failed", {
        context,
        replay_key: replayState.replayKey || null,
        error: err && err.message ? err.message : String(err),
      });
      return false;
    }
  }

  function headerValue(req, name) {
    const value = req.headers[name.toLowerCase()];
    return typeof value === "string" ? value.trim() : "";
  }

  function requiresManagedClientAccessGrant(req, pathname) {
    if (!config.registryClientAccessGrantsEnabled) return false;
    if (req.headers["x-relay-peer"]) return false;
    if (req.headers["x-federation-session"]) return false;
    return (
      (req.method === "GET" && pathname === "/v1/events:fetch") ||
      (req.method === "POST" && pathname === "/v1/events:ingest")
    );
  }

  function requiresManagedClientRelaySession(req, pathname) {
    if (!config.clientRelaySessionsEnabled) return false;
    if (req.headers["x-relay-peer"]) return false;
    if (req.headers["x-federation-session"]) return false;
    return (
      (req.method === "GET" && pathname === "/v1/events:fetch") ||
      (req.method === "POST" && pathname === "/v1/events:ingest")
    );
  }

  function validateManagedClientAccessGrant(req, { operation, gids, nowSeconds }) {
    const grant = decodeClientAccessGrantHeader(headerValue(req, "x-relay-access-grant"));
    const accessToken = headerValue(req, "x-relay-access-token");
    if (!grant || !accessToken) {
      return { ok: false, status: 401, code: "client_access_grant_required" };
    }
    const validation = validateClientAccessGrant({
      config,
      grant,
      accessToken,
      operation,
      gids,
      nowSeconds,
    });
    if (!validation.ok) {
      return { ok: false, status: 403, code: validation.code };
    }
    return validation;
  }

  function validateManagedClientRelaySession(req, { grantId, clientInstanceId, operation, gids, nowSeconds }) {
    const sessionId = headerValue(req, "x-client-relay-session");
    const sessionToken = headerValue(req, "x-client-relay-session-token");
    if (!sessionId || !sessionToken) {
      return { ok: false, status: 401, code: "client_session_required" };
    }
    if (!clientRelaySessions || !clientRelaySessions.validateRequestBinding) {
      return { ok: false, status: 404, code: "client_relay_sessions_disabled" };
    }
    const validation = clientRelaySessions.validateRequestBinding({
      sessionId,
      sessionToken,
      grantId,
      clientInstanceId,
      operation,
      gids,
      now: nowSeconds,
    });
    if (!validation.ok) {
      return { ok: false, status: 403, code: validation.code };
    }
    return validation;
  }

  function validateManagedClientRelaySessionById(
    req,
    { sessionId, grantId, clientInstanceId, operation = null, gids = [], nowSeconds }
  ) {
    const sessionToken = headerValue(req, "x-client-relay-session-token");
    const sessionHeaderId = headerValue(req, "x-client-relay-session");
    if (!sessionId || !sessionToken) {
      return { ok: false, status: 401, code: "client_session_required" };
    }
    if (sessionHeaderId && sessionHeaderId !== sessionId) {
      return { ok: false, status: 403, code: "client_session_mismatch" };
    }
    if (!clientRelaySessions || !clientRelaySessions.validateRequestBinding) {
      return { ok: false, status: 404, code: "client_relay_sessions_disabled" };
    }
    const validation = clientRelaySessions.validateRequestBinding({
      sessionId,
      sessionToken,
      grantId,
      clientInstanceId,
      operation,
      gids,
      now: nowSeconds,
    });
    if (!validation.ok) {
      return { ok: false, status: 403, code: validation.code };
    }
    return validation;
  }

  function safeEqualFederationSignature(actual, expected) {
    try {
      const expectedBuffer = Buffer.from(expected, "hex");
      const actualBuffer = Buffer.from(actual, "hex");
      return actualBuffer.length === expectedBuffer.length && timingSafeEqual(actualBuffer, expectedBuffer);
    } catch (_) {
      return false;
    }
  }

  function validateFederationSignature({ req, token, timestamp, signature, requestTarget, rawBody }) {
    const timestampSeconds = Number(timestamp);
    const nowSeconds = Math.floor(Date.now() / 1000);
    const skewSeconds = Math.max(1, config.requestSignatureSkewSeconds || 300);
    if (!Number.isFinite(timestampSeconds) || Math.abs(nowSeconds - timestampSeconds) > skewSeconds) {
      return { ok: false, code: "invalid_federation_signature", nowSeconds };
    }
    const expectedSignature = signFederationRequest({
      token,
      method: req.method,
      path: requestTarget,
      timestamp,
      rawBody,
    });
    if (!safeEqualFederationSignature(signature, expectedSignature)) {
      return { ok: false, code: "invalid_federation_signature", nowSeconds };
    }
    return { ok: true, nowSeconds };
  }

  function decodeFederationGrantHeader(value) {
    if (!value) return null;
    try {
      const raw = Buffer.from(value, "base64url").toString("utf8");
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === "object" ? parsed : null;
    } catch (_) {
      return null;
    }
  }

  async function sessionIsRevoked(sessionId) {
    if (!registrySessions || !registrySessions.isSessionRevoked) return false;
    return Boolean(await registrySessions.isSessionRevoked(sessionId));
  }

  function rejectFederationSessionLimited(res, limitResult, labels = {}) {
    metrics.inc("federation_session_limit_rejections_total", 1, {
      code: limitResult.code,
      ...labels,
    });
    if (limitResult.retryAfterSeconds) {
      res.setHeader("Retry-After", String(limitResult.retryAfterSeconds));
    }
    return sendJson(res, 429, {
      error: {
        code: limitResult.code,
        message: "Federation session limit exceeded.",
        details: {
          retry_after_seconds: limitResult.retryAfterSeconds || 1,
        },
      },
    });
  }

  function recordFederationReconciliationFailure(code) {
    metrics.inc("federation_reconciliation_validation_failures_total", 1, {
      code: String(code || "unknown"),
    });
  }

  function mapDbError(err) {
    if (!err || !err.code) {
      return { status: 500, error: { code: "db_error", message: "Database error." } };
    }

    switch (err.code) {
      case "DB_CIRCUIT_OPEN":
        return { status: 503, error: { code: "db_unavailable", message: "Database unavailable." } };
      case "23505":
        return { status: 409, error: { code: "conflict", message: "Conflict." } };
      case "22P02":
      case "23502":
      case "22001":
        return { status: 400, error: { code: "invalid_request", message: "Invalid request." } };
      default:
        return { status: 500, error: { code: "db_error", message: "Database error." } };
    }
  }

  async function route(req, res) {
    const start = Date.now();
    const reqId = randomUUID();
    const pathname = req.url.split("?")[0];
    const requestTarget = req.url;
    const path = pathname;
    res.setHeader("X-Request-Id", reqId);
    const ip = clientIp(req);
    let auditMeta = null;

    res.on("finish", () => {
      const durationMs = Date.now() - start;
      metrics.inc("requests_total");
      metrics.inc("http_responses_total", 1, { status: String(res.statusCode) });
      metrics.observe("http_request_duration_ms", durationMs);
      if (res.statusCode >= 400) metrics.inc("errors_total");
      logRequest({
        reqId,
        method: req.method,
        path,
        statusCode: res.statusCode,
        durationMs,
      });
      if (auditLogger && auditMeta) {
        auditLogger.log({
          action: auditMeta.action,
          req_id: reqId,
          method: req.method,
          path,
          status: res.statusCode,
          ip,
          ...auditMeta,
        });
      }
    });

    if (config.ipDenylist.length > 0 && matchesIpList(ip, config.ipDenylist)) {
      return sendJson(res, 403, { error: { code: "forbidden", message: "Access denied." } });
    }
    if (config.ipAllowlist.length > 0 && !matchesIpList(ip, config.ipAllowlist)) {
      return sendJson(res, 403, { error: { code: "forbidden", message: "Access denied." } });
    }

    const adminHandled = await adminRouter.route(req, res);
    if (adminHandled) return;

    if (requiresAppRequestSignature(req, pathname) && req.method === "GET") {
      const valid = await verifyAppRequestSignature(req, res, {
        requestTarget,
        rawBody: "",
      });
      if (!valid) return;
    }

    if (req.headers["x-relay-peer"]) {
      const loopHeader = req.headers["x-relay-loop"];
      if (typeof loopHeader === "string") {
        const validation = validateHopChain(loopHeader, config.federationSelfId || "");
        if (!validation.ok) {
          return sendJson(res, 409, {
            error: { code: "loop_detected", message: "Federation loop detected." },
          });
        }
      }
    }

    if (req.method === "GET" && req.url === "/healthz") {
      metrics.inc("health_requests_total");
      return sendJson(res, 200, { status: "ok" });
    }

    if (req.method === "GET" && req.url === "/readyz") {
      metrics.inc("ready_requests_total");
      try {
        const startDb = Date.now();
        await storage.healthCheck({ timeoutMs: config.healthCheckDbTimeoutMs });
        metrics.observe("health_db_check_ms", Date.now() - startDb);
        return sendJson(res, 200, { status: "ok", db: "ok" });
      } catch (err) {
        if (err && err.code === "HEALTH_DB_TIMEOUT") {
          metrics.inc("health_db_check_timeouts_total");
        } else {
          metrics.inc("health_db_check_failures_total");
        }
        return sendJson(res, 503, { status: "degraded", db: "error" });
      }
    }

    if (req.method === "GET" && req.url === "/metrics") {
      const body = metrics.renderPrometheus();
      res.statusCode = 200;
      res.setHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
      res.setHeader("Content-Length", Buffer.byteLength(body));
      res.end(body);
      return;
    }

    if (req.method === "GET" && req.url === "/health") {
      metrics.inc("health_requests_total");
      try {
        const startDb = Date.now();
        await storage.healthCheck({ timeoutMs: config.healthCheckDbTimeoutMs });
        metrics.observe("health_db_check_ms", Date.now() - startDb);
        return sendJson(res, 200, { status: "ok", db: "ok" });
      } catch (err) {
        if (err && err.code === "HEALTH_DB_TIMEOUT") {
          metrics.inc("health_db_check_timeouts_total");
        } else {
          metrics.inc("health_db_check_failures_total");
        }
        return sendJson(res, 503, { status: "degraded", db: "error" });
      }
    }

    if (req.method === "POST" && req.url === "/v1/peers:join") {
      const payload = await parseJsonBodyWithRaw(req, res, { maxBytes: config.maxRequestBytes });
      if (!payload) return;
      const { raw, json } = payload;
      const peerUrl = json && typeof json.peer_url === "string" ? json.peer_url.trim() : "";
      const selfId = json && typeof json.self_id === "string" ? json.self_id.trim() : "";
      const peerJoinToken =
        json && typeof json.peer_join_token === "string" ? json.peer_join_token.trim() : "";
      if (!peerUrl || !selfId) {
        return sendJson(res, 400, {
          error: { code: "invalid_input", message: "peer_url and self_id are required." },
        });
      }

      const signature = req.headers["x-relay-join-signature"];
      const timestamp = req.headers["x-relay-join-timestamp"];
      const nonce = req.headers["x-relay-join-nonce"];
      if (
        typeof signature !== "string" ||
        typeof timestamp !== "string" ||
        typeof nonce !== "string"
      ) {
        return sendJson(res, 401, {
          error: { code: "invalid_signature", message: "Missing join signature headers." },
        });
      }

      const ts = Number(timestamp);
      if (!Number.isFinite(ts)) {
        return sendJson(res, 400, {
          error: { code: "invalid_input", message: "Invalid join timestamp." },
        });
      }
      const nowSec = Math.floor(Date.now() / 1000);
      const skew = Math.abs(nowSec - ts);
      if (skew > config.requestSignatureSkewSeconds) {
        return sendJson(res, 401, {
          error: { code: "invalid_signature", message: "Join timestamp out of window." },
        });
      }

      const replayed = await storage.isJoinNonceReplay(nonce, nowSec);
      if (replayed) {
        return sendJson(res, 409, {
          error: { code: "nonce_replay", message: "Join nonce already used." },
        });
      }

      if (config.federationRequireTls && !isTlsRequest(req)) {
        return sendJson(res, 426, {
          error: { code: "tls_required", message: "TLS required for federation join." },
        });
      }
      const joinLimit = await joinRateLimiter(ip);
      if (!joinLimit.ok) {
        metrics.inc("rate_limited_total");
        return rejectRateLimited(res, "join", joinLimit.retryAfterSeconds);
      }
      const existingMapping = storage.getPeerMappingByUrl
        ? await storage.getPeerMappingByUrl(peerUrl)
        : null;
      if (existingMapping && isPeerMappingQuarantined(existingMapping)) {
        return sendJson(res, 403, {
          error: { code: "peer_quarantined", message: "Peer is quarantined." },
        });
      }
      if (config.federationMaxPeers > 0 && storage.listPeerMappings) {
        const mappings = await storage.listPeerMappings();
        const enabledCount = mappings.filter((item) => isPeerMappingActive(item)).length;
        const alreadyEnabled = existingMapping ? isPeerMappingActive(existingMapping) : false;
        if (!alreadyEnabled && enabledCount >= config.federationMaxPeers) {
          return sendJson(res, 429, {
            error: { code: "capacity_full", message: "Maximum peers reached." },
          });
        }
      }
      const joinToken = existingMapping && existingMapping.join_token
        ? existingMapping.join_token
        : null;
      const joinTokenExpiresAt = existingMapping && existingMapping.join_token_expires_at
        ? Date.parse(existingMapping.join_token_expires_at)
        : null;
      const joinTokenValid = Boolean(
        joinToken && (!Number.isFinite(joinTokenExpiresAt) || joinTokenExpiresAt > Date.now())
      );

      const publicToken = getPublicToken(config);
      if (!publicToken && !joinTokenValid) {
        return sendJson(res, 503, {
          error: { code: "join_unavailable", message: "Join token not configured." },
        });
      }
      const canonical = `JOIN\n${timestamp}\n${nonce}\n${raw}`;
      const sigBuffer = Buffer.from(signature, "hex");
      const candidateTokens = [
        ...(publicToken ? [publicToken] : []),
        ...(joinTokenValid ? [joinToken] : []),
      ];
      const signatureValid = candidateTokens.some((token) => {
        const expected = createHmac("sha256", token).update(canonical).digest("hex");
        const expectedBuffer = Buffer.from(expected, "hex");
        return (
          sigBuffer.length === expectedBuffer.length &&
          timingSafeEqual(sigBuffer, expectedBuffer)
        );
      });
      if (!signatureValid) {
        return sendJson(res, 401, {
          error: { code: "invalid_signature", message: "Invalid join signature." },
        });
      }

      const dynamicBanlist = storage.listActivePeerBanlistValues
        ? await storage.listActivePeerBanlistValues()
        : [];
      const combinedBanlist = [...config.federationPeerBanlist, ...dynamicBanlist];
      if (isPeerBanned(ip, selfId, combinedBanlist)) {
        logEvent("warn", "federation_peer_banned", { ip, peer_id: selfId });
        return sendJson(res, 403, { error: { code: "forbidden", message: "Peer banned." } });
      }

      const heartbeatTtl = Math.max(1, config.federationHeartbeatTtlSeconds || 120);
      const capacityLimit = Number(config.federationConnectionCapacity || 0);
      if (capacityLimit > 0 && storage.countActivePeerConnections) {
        const activeCount = await storage.countActivePeerConnections(heartbeatTtl);
        if (activeCount >= capacityLimit) {
          return sendJson(res, 429, {
            error: { code: "capacity_full", message: "Peer capacity reached." },
          });
        }
      }
      const mapping = storage.upsertPeerJoinRequest
        ? await storage.upsertPeerJoinRequest({
          peerUrl,
          selfId,
          peerToken: peerJoinToken || null,
        })
        : null;
      if (!mapping) {
        return sendJson(res, 500, {
          error: { code: "join_failed", message: "Unable to store peer mapping." },
        });
      }
      return sendJson(res, 200, {
        ok: true,
        peer_id: selfId,
        peer_url: peerUrl,
        status: mapping.joined_at ? "joined" : "pending",
      });
    }

    if (req.method === "POST" && req.url === "/v1/peers:handshake") {
      const payload = await parseJsonBodyWithRaw(req, res, { maxBytes: config.maxRequestBytes });
      if (!payload) return;
      const { raw, json } = payload;
      const peerUrl = json && typeof json.peer_url === "string" ? json.peer_url.trim() : "";
      const selfId = json && typeof json.self_id === "string" ? json.self_id.trim() : "";
      const peerJoinToken =
        json && typeof json.peer_join_token === "string" ? json.peer_join_token.trim() : "";
      if (!peerUrl || !selfId) {
        return sendJson(res, 400, {
          error: { code: "invalid_input", message: "peer_url and self_id are required." },
        });
      }

      const signature = req.headers["x-relay-join-signature"];
      const timestamp = req.headers["x-relay-join-timestamp"];
      const nonce = req.headers["x-relay-join-nonce"];
      if (
        typeof signature !== "string" ||
        typeof timestamp !== "string" ||
        typeof nonce !== "string"
      ) {
        return sendJson(res, 401, {
          error: { code: "invalid_signature", message: "Missing handshake signature headers." },
        });
      }

      const ts = Number(timestamp);
      if (!Number.isFinite(ts)) {
        return sendJson(res, 400, {
          error: { code: "invalid_input", message: "Invalid handshake timestamp." },
        });
      }
      const nowSec = Math.floor(Date.now() / 1000);
      const skew = Math.abs(nowSec - ts);
      if (skew > config.requestSignatureSkewSeconds) {
        return sendJson(res, 401, {
          error: { code: "invalid_signature", message: "Handshake timestamp out of window." },
        });
      }

      const replayed = await storage.isJoinNonceReplay(nonce, nowSec);
      if (replayed) {
        return sendJson(res, 409, {
          error: { code: "nonce_replay", message: "Handshake nonce already used." },
        });
      }

      if (config.federationRequireTls && !isTlsRequest(req)) {
        return sendJson(res, 426, {
          error: { code: "tls_required", message: "TLS required for federation handshake." },
        });
      }
      const handshakeLimit = await handshakeRateLimiter(ip);
      if (!handshakeLimit.ok) {
        metrics.inc("rate_limited_total");
        return rejectRateLimited(res, "handshake", handshakeLimit.retryAfterSeconds);
      }
      const mapping = await storage.getPeerMappingByUrl(peerUrl);
      if (!mapping || !mapping.enabled) {
        return sendJson(res, 404, {
          error: { code: "peer_not_registered", message: "Peer not registered." },
        });
      }
      if (isPeerMappingQuarantined(mapping)) {
        return sendJson(res, 403, {
          error: { code: "peer_quarantined", message: "Peer is quarantined." },
        });
      }
      if (peerJoinToken && storage.updatePeerTokenIfEmpty) {
        await storage.updatePeerTokenIfEmpty({
          peerUrl,
          peerToken: peerJoinToken,
        });
      }
      const joinTokenExpiresAtMs = mapping.join_token_expires_at
        ? Date.parse(mapping.join_token_expires_at)
        : null;
      const joinTokenValid = Boolean(
        mapping.join_token &&
          (!Number.isFinite(joinTokenExpiresAtMs) || joinTokenExpiresAtMs > Date.now())
      );
      const publicToken = getPublicToken(config);
      const candidateTokens = [
        ...(joinTokenValid ? [mapping.join_token] : []),
        ...(publicToken ? [publicToken] : []),
      ];
      if (candidateTokens.length === 0) {
        return sendJson(res, 503, {
          error: { code: "handshake_unavailable", message: "Join token not configured." },
        });
      }
      const canonical = `HANDSHAKE\n${timestamp}\n${nonce}\n${raw}`;
      const sigBuffer = Buffer.from(signature, "hex");
      const signatureValid = candidateTokens.some((token) => {
        const expected = createHmac("sha256", token).update(canonical).digest("hex");
        const expectedBuffer = Buffer.from(expected, "hex");
        return (
          sigBuffer.length === expectedBuffer.length &&
          timingSafeEqual(sigBuffer, expectedBuffer)
        );
      });
      if (!signatureValid) {
        return sendJson(res, 401, {
          error: { code: "invalid_signature", message: "Invalid handshake signature." },
        });
      }
      if (mapping.self_id && mapping.self_id !== selfId) {
        logEvent("warn", "federation_handshake_self_id_mismatch", {
          peer_url: peerUrl,
          expected: mapping.self_id,
          got: selfId,
        });
        return sendJson(res, 403, {
          error: { code: "invalid_self_id", message: "Self ID mismatch." },
        });
      }

      const dynamicBanlist = storage.listActivePeerBanlistValues
        ? await storage.listActivePeerBanlistValues()
        : [];
      const combinedBanlist = [...config.federationPeerBanlist, ...dynamicBanlist];
      if (isPeerBanned(ip, selfId, combinedBanlist)) {
        logEvent("warn", "federation_peer_banned", { ip, peer_id: selfId });
        return sendJson(res, 403, { error: { code: "forbidden", message: "Peer banned." } });
      }

      if (!storage.issuePeerRotatedToken) {
        return sendJson(res, 503, {
          error: { code: "handshake_unavailable", message: "Handshake not supported." },
        });
      }

      const existingRotatedToken = mapping.rotated_token;
      const existingRotatedExpiresAt = mapping.rotated_expires_at
        ? Date.parse(mapping.rotated_expires_at)
        : null;
      let rotatedToken = existingRotatedToken;
      let rotatedExpiresAt = mapping.rotated_expires_at || null;
      let expiresIn = Math.max(60, config.federationRotatedTokenTtlSeconds || 300);
      const nowMs = Date.now();
      const rotatedValid = existingRotatedToken &&
        (!Number.isFinite(existingRotatedExpiresAt) || existingRotatedExpiresAt > nowMs);
      if (rotatedValid && Number.isFinite(existingRotatedExpiresAt)) {
        expiresIn = Math.max(1, Math.floor((existingRotatedExpiresAt - nowMs) / 1000));
      }
      if (rotatedValid && !Number.isFinite(existingRotatedExpiresAt)) {
        rotatedExpiresAt = new Date(nowMs + expiresIn * 1000).toISOString();
      }
      if (!rotatedValid) {
        rotatedToken = randomUUID();
        expiresIn = Math.max(60, config.federationRotatedTokenTtlSeconds || 300);
        rotatedExpiresAt = new Date(Date.now() + expiresIn * 1000).toISOString();
      }
      const joinToken = randomUUID();
      const joinExpiresIn = Math.max(60, config.federationJoinTokenTtlSeconds || 600);
      const joinTokenExpiresAt = new Date(Date.now() + joinExpiresIn * 1000).toISOString();
      await storage.markPeerSeen({ peerUrl, selfId });
      const updated = await storage.issuePeerRotatedToken({
        peerUrl,
        selfId,
        rotatedToken,
        rotatedExpiresAt,
        joinToken,
        joinTokenExpiresAt,
      });
      if (!updated) {
        return sendJson(res, 500, {
          error: { code: "handshake_failed", message: "Unable to issue rotated token." },
        });
      }
      logEvent("info", "federation_handshake_issued", {
        peer_url: peerUrl,
        self_id: selfId,
        rotated_token: redactToken(rotatedToken),
        rotated_expires_at: rotatedExpiresAt,
        join_token: redactToken(joinToken),
        join_token_expires_at: joinTokenExpiresAt,
      });

      return sendJson(res, 200, {
        ok: true,
        rotated_token: rotatedToken,
        expires_in: expiresIn,
        join_token: joinToken,
        join_token_expires_in: joinExpiresIn,
      });
    }

    if (req.method === "POST" && req.url === "/v1/peers:heartbeat") {
      const payload = await parseJsonBodyWithRaw(req, res, { maxBytes: config.maxRequestBytes });
      if (!payload) return;
      const { raw, json } = payload;
      const peerUrl = json && typeof json.peer_url === "string" ? json.peer_url.trim() : "";
      const selfId = json && typeof json.self_id === "string" ? json.self_id.trim() : "";
      if (!peerUrl || !selfId) {
        return sendJson(res, 400, {
          error: { code: "invalid_input", message: "peer_url and self_id are required." },
        });
      }

      const signature = req.headers["x-relay-join-signature"];
      const timestamp = req.headers["x-relay-join-timestamp"];
      const nonce = req.headers["x-relay-join-nonce"];
      if (
        typeof signature !== "string" ||
        typeof timestamp !== "string" ||
        typeof nonce !== "string"
      ) {
        return sendJson(res, 401, {
          error: { code: "invalid_signature", message: "Missing heartbeat signature headers." },
        });
      }

      const ts = Number(timestamp);
      if (!Number.isFinite(ts)) {
        return sendJson(res, 400, {
          error: { code: "invalid_input", message: "Invalid heartbeat timestamp." },
        });
      }
      const nowSec = Math.floor(Date.now() / 1000);
      const skew = Math.abs(nowSec - ts);
      if (skew > config.requestSignatureSkewSeconds) {
        return sendJson(res, 401, {
          error: { code: "invalid_signature", message: "Heartbeat timestamp out of window." },
        });
      }

      const replayed = await storage.isJoinNonceReplay(nonce, nowSec);
      if (replayed) {
        return sendJson(res, 409, {
          error: { code: "nonce_replay", message: "Heartbeat nonce already used." },
        });
      }

      const peerMapping = await storage.getPeerMappingByUrl(peerUrl);
      if (!peerMapping || !peerMapping.enabled) {
        return sendJson(res, 403, {
          error: { code: "peer_not_registered", message: "Peer not registered." },
        });
      }
      if (isPeerMappingQuarantined(peerMapping)) {
        return sendJson(res, 403, {
          error: { code: "peer_quarantined", message: "Peer is quarantined." },
        });
      }

      if (peerMapping.self_id && peerMapping.self_id !== selfId) {
        logEvent("warn", "federation_heartbeat_self_id_mismatch", {
          peer_url: peerUrl,
          expected: peerMapping.self_id,
          got: selfId,
        });
        return sendJson(res, 403, {
          error: { code: "invalid_self_id", message: "Self ID mismatch." },
        });
      }
      if (!peerMapping.rotated_token) {
        return sendJson(res, 403, {
          error: { code: "forbidden", message: "Rotated token required." },
        });
      }
      const expiresAt = peerMapping.rotated_expires_at
        ? Date.parse(peerMapping.rotated_expires_at)
        : null;
      if (Number.isFinite(expiresAt) && expiresAt <= Date.now()) {
        return sendJson(res, 403, {
          error: { code: "forbidden", message: "Rotated token expired." },
        });
      }

      const canonical = `HEARTBEAT\n${timestamp}\n${nonce}\n${raw}`;
      const expected = createHmac("sha256", peerMapping.rotated_token)
        .update(canonical)
        .digest("hex");
      const sigBuffer = Buffer.from(signature, "hex");
      const expectedBuffer = Buffer.from(expected, "hex");
      if (
        sigBuffer.length !== expectedBuffer.length ||
        !timingSafeEqual(sigBuffer, expectedBuffer)
      ) {
        return sendJson(res, 401, {
          error: { code: "invalid_signature", message: "Invalid heartbeat signature." },
        });
      }

      const dynamicBanlist = storage.listActivePeerBanlistValues
        ? await storage.listActivePeerBanlistValues()
        : [];
      const combinedBanlist = [...config.federationPeerBanlist, ...dynamicBanlist];
      if (isPeerBanned(ip, selfId, combinedBanlist)) {
        logEvent("warn", "federation_peer_banned", { ip, peer_id: selfId });
        return sendJson(res, 403, { error: { code: "forbidden", message: "Peer banned." } });
      }

      await storage.markPeerSeen({ peerUrl, selfId });
      return sendJson(res, 200, {
        ok: true,
        peer_id: selfId,
        peer_url: peerUrl,
        status: "seen",
      });
    }

    if (req.method === "GET" && req.url === "/v1/peers:stats") {
      if (!req.headers["x-relay-peer"]) {
        return sendJson(res, 403, {
          error: { code: "forbidden", message: "Peer authentication required." },
        });
      }
      const peerId =
        typeof req.headers["x-relay-from"] === "string"
          ? req.headers["x-relay-from"].trim()
          : "";
      const peerToken =
        typeof req.headers["x-relay-peer-token"] === "string"
          ? req.headers["x-relay-peer-token"].trim()
          : "";
      const mapping = peerId && storage.getPeerMappingBySelfId
        ? await storage.getPeerMappingBySelfId(peerId)
        : null;
      if (!mapping || !isPeerMappingActive(mapping) || !mapping.rotated_token) {
        return sendJson(res, 403, {
          error: {
            code: mapping && isPeerMappingQuarantined(mapping) ? "peer_quarantined" : "forbidden",
            message: mapping && isPeerMappingQuarantined(mapping) ? "Peer is quarantined." : "Rotated token required.",
          },
        });
      }
      const expiresAt = mapping.rotated_expires_at
        ? Date.parse(mapping.rotated_expires_at)
        : null;
      if (Number.isFinite(expiresAt) && expiresAt <= Date.now()) {
        return sendJson(res, 403, {
          error: { code: "forbidden", message: "Rotated token expired." },
        });
      }
      if (!peerToken || peerToken !== mapping.rotated_token) {
        return sendJson(res, 403, {
          error: { code: "forbidden", message: "Invalid peer token." },
        });
      }
      const dynamicBanlist = storage.listActivePeerBanlistValues
        ? await storage.listActivePeerBanlistValues()
        : [];
      const combinedBanlist = [...config.federationPeerBanlist, ...dynamicBanlist];
      if (isPeerBanned(ip, peerId, combinedBanlist)) {
        logEvent("warn", "federation_peer_banned", { ip, peer_id: peerId });
        return sendJson(res, 403, { error: { code: "forbidden", message: "Peer banned." } });
      }
      if (
        config.federationPeerAllowlist.length > 0 &&
        !matchesIpList(ip, config.federationPeerAllowlist)
      ) {
        return sendJson(res, 403, { error: { code: "forbidden", message: "Peer not allowed." } });
      }

      const heartbeatTtl = Math.max(1, config.federationHeartbeatTtlSeconds || 120);
      let connected = 0;
      if (storage.countActivePeerConnections) {
        connected = await storage.countActivePeerConnections(heartbeatTtl);
      }
      const capacityLimit = Number(config.federationConnectionCapacity || 0);
      return sendJson(res, 200, {
        connected,
        capacity_limit: capacityLimit,
        heartbeat_ttl_seconds: heartbeatTtl,
      });
    }

    if (req.method === "POST" && pathname === "/v1/client/session:open") {
      const payload = await parseJsonBodyWithRaw(req, res, { maxBytes: config.maxRequestBytes });
      if (!payload) return;
      const { raw, json } = payload;

      let signatureReplayState = null;
      if (requiresAppRequestSignature(req, pathname)) {
        const valid = await verifyAppRequestSignature(req, res, {
          requestTarget,
          rawBody: raw,
          deferReplayProtection: true,
        });
        if (!valid) return;
        signatureReplayState = valid;
      }

      if (!clientRelaySessions || !clientRelaySessions.enabled || !clientRelaySessions.enabled()) {
        return sendJson(res, 404, {
          error: { code: "client_relay_sessions_disabled", message: "Client relay sessions are disabled." },
        });
      }

      const grant = decodeClientAccessGrantHeader(headerValue(req, "x-relay-access-grant"));
      if (!grant) {
        return sendJson(res, 401, {
          error: { code: "client_access_grant_required", message: "Managed client access grant is required." },
        });
      }

      const result = clientRelaySessions.open(json, grant, Math.floor(Date.now() / 1000));
      if (!result.ok) {
        return sendJson(res, result.status, {
          error: { code: result.code, message: "Client relay session open failed." },
        });
      }
      return sendJson(res, result.status, { ok: true, session: result.session });
    }

    const clientSessionMutationMatch = pathname.match(/^\/v1\/client\/session\/([^/]+):(renew|close)$/);
    if (req.method === "POST" && clientSessionMutationMatch) {
      const payload = await parseJsonBodyWithRaw(req, res, { maxBytes: config.maxRequestBytes });
      if (!payload) return;
      const { raw, json } = payload;

      if (requiresAppRequestSignature(req, pathname)) {
        const valid = await verifyAppRequestSignature(req, res, {
          requestTarget,
          rawBody: raw,
        });
        if (!valid) return;
      }

      if (!clientRelaySessions || !clientRelaySessions.enabled || !clientRelaySessions.enabled()) {
        return sendJson(res, 404, {
          error: { code: "client_relay_sessions_disabled", message: "Client relay sessions are disabled." },
        });
      }

      const sessionId = decodeURIComponent(clientSessionMutationMatch[1]);
      const action = clientSessionMutationMatch[2];

      if (hasManagedClientTransportAuth(req, pathname)) {
        const sessionValidation = validateManagedClientRelaySessionById(req, {
          sessionId,
          grantId: action === "renew" ? decodeClientAccessGrantHeader(headerValue(req, "x-relay-access-grant"))?.grant_id : null,
          clientInstanceId: json && json.client_instance_id,
          nowSeconds: Math.floor(Date.now() / 1000),
        });
        if (!sessionValidation.ok) {
          return sendJson(res, sessionValidation.status, {
            error: {
              code: sessionValidation.code,
              message: "Managed client relay session is not authorized.",
            },
          });
        }
      }

      if (action === "close") {
        const result = clientRelaySessions.close(sessionId, json, Math.floor(Date.now() / 1000));
        if (!result.ok) {
          return sendJson(res, result.status, {
            error: { code: result.code, message: "Client relay session close failed." },
          });
        }
        return sendJson(res, result.status, { ok: true, session: result.session });
      }

      const grant = decodeClientAccessGrantHeader(headerValue(req, "x-relay-access-grant"));
      if (!grant) {
        return sendJson(res, 401, {
          error: { code: "client_access_grant_required", message: "Managed client access grant is required." },
        });
      }

      const result = clientRelaySessions.renew(
        sessionId,
        json,
        grant,
        Math.floor(Date.now() / 1000)
      );
      if (!result.ok) {
        return sendJson(res, result.status, {
          error: { code: result.code, message: "Client relay session renew failed." },
        });
      }
      return sendJson(res, result.status, { ok: true, session: result.session });
    }

    if (req.method === "POST" && req.url === "/v1/federation/events:push") {
      const sessionId = headerValue(req, "x-federation-session");
      const token = headerValue(req, "x-federation-token");
      const timestamp = headerValue(req, "x-federation-timestamp");
      const signature = headerValue(req, "x-federation-signature");
      if (!sessionId || !token || !timestamp || !signature) {
        return sendJson(res, 401, {
          error: { code: "federation_session_required", message: "Federation session headers are required." },
        });
      }

      const contentLength = Number(req.headers["content-length"] || 0);
      if (Number.isFinite(contentLength) && contentLength > config.maxRequestBytes) {
        return sendJson(res, 413, {
          error: { code: "payload_too_large", message: "Request body too large." },
        });
      }
      const payload = await parseJsonBodyWithRaw(req, res, { maxBytes: config.maxRequestBytes });
      if (!payload) return;
      const signatureValidation = validateFederationSignature({
        req,
        token,
        timestamp,
        signature,
        requestTarget: pathname,
        rawBody: payload.raw,
      });
      if (!signatureValidation.ok) {
        metrics.inc("federation_push_validation_failures_total");
        return sendJson(res, 401, {
          error: { code: "invalid_federation_signature", message: "Invalid federation signature." },
        });
      }
      const grantValidation = validateFederationPushGrant({
        config,
        grant: payload.json && payload.json.peer_grant,
        sessionId,
        token,
        events: payload.json && payload.json.events,
        sourceRelayId: payload.json && payload.json.source_relay_id,
        destinationRelayId: payload.json && payload.json.destination_relay_id,
        nowSeconds: signatureValidation.nowSeconds,
      });
      if (!grantValidation.ok) {
        metrics.inc("federation_push_validation_failures_total");
        return sendJson(res, 403, {
          error: {
            code: grantValidation.code,
            message: "Federation session grant is not authorized.",
          },
        });
      }
      if (await sessionIsRevoked(sessionId)) {
        metrics.inc("federation_push_validation_failures_total");
        return sendJson(res, 403, {
          error: {
            code: "session_revoked",
            message: "Federation session grant is not authorized.",
          },
        });
      }
      const validation = validateIngestRequest(payload.json, config);
      if (!validation.ok) {
        return sendJson(res, 400, { error: validation.error });
      }
      const sessionLeaseResult = federationSessionLimits.begin({
        sessionId,
        limits: payload.json && payload.json.peer_grant && payload.json.peer_grant.limits,
        nowSeconds: signatureValidation.nowSeconds,
      });
      if (!sessionLeaseResult.ok) {
        return rejectFederationSessionLimited(res, sessionLeaseResult);
      }
      const sessionLease = sessionLeaseResult.lease;
      try {
        const quotaResult = federationSessionLimits.consume(sessionLease, {
          events: validation.data.events.length,
          bytes: Buffer.byteLength(payload.raw || "", "utf8"),
        });
        if (!quotaResult.ok) {
          return rejectFederationSessionLimited(res, quotaResult);
        }
        const result = await storage.ingestEvents(validation.data.events);
        metrics.inc("federation_push_ingest_total");
        return sendJson(res, 202, result);
      } catch (err) {
        const mapped = mapDbError(err);
        return sendJson(res, mapped.status, { error: mapped.error });
      } finally {
        federationSessionLimits.finish(sessionLease);
      }
    }

    if (req.method === "POST" && req.url === "/v1/events:ingest") {
      if (req.headers["x-relay-peer"]) {
        const peerId =
          typeof req.headers["x-relay-from"] === "string"
            ? req.headers["x-relay-from"].trim()
            : "";
        const peerToken =
          typeof req.headers["x-relay-peer-token"] === "string"
            ? req.headers["x-relay-peer-token"].trim()
            : "";
        const mapping = peerId && storage.getPeerMappingBySelfId
          ? await storage.getPeerMappingBySelfId(peerId)
          : null;
        if (!mapping || !isPeerMappingActive(mapping) || !mapping.rotated_token) {
          return sendJson(res, 403, {
            error: {
              code: mapping && isPeerMappingQuarantined(mapping) ? "peer_quarantined" : "forbidden",
              message: mapping && isPeerMappingQuarantined(mapping) ? "Peer is quarantined." : "Rotated token required.",
            },
          });
        }
        const expiresAt = mapping.rotated_expires_at
          ? Date.parse(mapping.rotated_expires_at)
          : null;
        if (Number.isFinite(expiresAt) && expiresAt <= Date.now()) {
          return sendJson(res, 403, {
            error: { code: "forbidden", message: "Rotated token expired." },
          });
        }
        if (!peerToken || peerToken !== mapping.rotated_token) {
          return sendJson(res, 403, {
            error: { code: "forbidden", message: "Invalid peer token." },
          });
        }
        const dynamicBanlist = storage.listActivePeerBanlistValues
          ? await storage.listActivePeerBanlistValues()
          : [];
        const combinedBanlist = [...config.federationPeerBanlist, ...dynamicBanlist];
        if (isPeerBanned(ip, peerId, combinedBanlist)) {
          logEvent("warn", "federation_peer_banned", { ip, peer_id: peerId });
          return sendJson(res, 403, { error: { code: "forbidden", message: "Peer banned." } });
        }
        if (
          config.federationPeerAllowlist.length > 0 &&
          !matchesIpList(ip, config.federationPeerAllowlist)
        ) {
          return sendJson(res, 403, { error: { code: "forbidden", message: "Peer not allowed." } });
        }
      }
      const contentLength = Number(req.headers["content-length"] || 0);
      if (Number.isFinite(contentLength) && contentLength > config.maxRequestBytes) {
        return sendJson(res, 413, {
          error: { code: "payload_too_large", message: "Request body too large." },
        });
      }

      if (cooldown && cooldown.isActive && cooldown.isActive()) {
        const retryAfter = cooldown.retryAfterSeconds ? cooldown.retryAfterSeconds() : 1;
        res.setHeader("Retry-After", String(retryAfter));
        metrics.inc("rate_limited_total");
        return sendJson(res, 429, {
          error: {
            code: "db_cooldown",
            message: "Database under heavy load. Retry later.",
            details: { retry_after_seconds: retryAfter },
          },
        });
      }

      const payload = await parseJsonBodyWithRaw(req, res, { maxBytes: config.maxRequestBytes });
      if (!payload) return;
      const { raw, json: body } = payload;

      let signatureReplayState = null;
      if (requiresAppRequestSignature(req, pathname)) {
        const valid = await verifyAppRequestSignature(req, res, {
          requestTarget,
          rawBody: raw,
          deferReplayProtection: true,
        });
        if (!valid) return;
        signatureReplayState = valid;
      }

      const validation = validateIngestRequest(body, config);
      if (!validation.ok) {
        return sendJson(res, 400, { error: validation.error });
      }

      const gids = new Set(validation.data.events.map((event) => event.gid).filter(Boolean));
      if (requiresManagedClientAccessGrant(req, pathname)) {
        const grantValidation = validateManagedClientAccessGrant(req, {
          operation: "ingest",
          gids: [...gids],
          nowSeconds: Math.floor(Date.now() / 1000),
        });
        if (!grantValidation.ok) {
          return sendJson(res, grantValidation.status, {
            error: {
              code: grantValidation.code,
              message: "Managed client access grant is not authorized.",
            },
          });
        }
        if (requiresManagedClientRelaySession(req, pathname)) {
          const sessionValidation = validateManagedClientRelaySession(req, {
            grantId: grantValidation.grantId,
            clientInstanceId: grantValidation.clientInstanceId,
            operation: "ingest",
            gids: [...gids],
            nowSeconds: Math.floor(Date.now() / 1000),
          });
          if (!sessionValidation.ok) {
            return sendJson(res, sessionValidation.status, {
              error: {
                code: sessionValidation.code,
                message: "Managed client relay session is not authorized.",
              },
            });
          }
        }
      }
      auditMeta = {
        action: "ingest",
        event_count: validation.data.events.length,
        gid_count: gids.size,
        peer: Boolean(req.headers["x-relay-peer"]),
      };

      const ipLimit = await ingestIpLimiter(ip);
      if (!ipLimit.ok) {
        metrics.inc("rate_limited_total");
        return rejectRateLimited(res, "ip", ipLimit.retryAfterSeconds);
      }
      const ipBurst = await ingestIpBurstLimiter(ip);
      if (!ipBurst.ok) {
        metrics.inc("rate_limited_total");
        return rejectRateLimited(res, "ip_burst", ipBurst.retryAfterSeconds);
      }

      for (const gid of gids) {
        const gidLimit = await ingestGidLimiter(gid);
        if (!gidLimit.ok) {
          metrics.inc("rate_limited_total");
          return rejectRateLimited(res, "gid", gidLimit.retryAfterSeconds, { gid });
        }
        const gidBurst = await ingestGidBurstLimiter(gid);
        if (!gidBurst.ok) {
          metrics.inc("rate_limited_total");
          return rejectRateLimited(res, "gid_burst", gidBurst.retryAfterSeconds, { gid });
        }
      }

      try {
        if (config.enableTestDbErrors && req.headers["x-test-db-error"]) {
          throw { code: req.headers["x-test-db-error"] };
        }
        const result = await storage.ingestEvents(validation.data.events);
        if (federationPush && federationPush.enqueue && !req.headers["x-relay-peer"]) {
          const accepted = new Set(result.accepted || []);
          const acceptedEvents = validation.data.events.filter((event) => accepted.has(event.eid));
          if (acceptedEvents.length > 0) {
            federationPush.enqueue(acceptedEvents, { source: "app_ingest" });
          }
        }
        await commitReplayProtection(signatureReplayState, "ingest_success");
        metrics.inc("ingest_requests_total");
        return sendJson(res, 200, result);
      } catch (err) {
        const mapped = mapDbError(err);
        if (ingestRetryQueue && mapped.status >= 500) {
          const queued = ingestRetryQueue.enqueue(validation.data.events, mapped.error.code);
          if (queued) {
            await commitReplayProtection(signatureReplayState, "ingest_retry_queued");
            logEvent("warn", "ingest_queued_for_retry", {
              events: validation.data.events.length,
              error: mapped.error.code,
            });
            return sendJson(res, 202, {
              accepted: validation.data.events.map((event) => event.eid),
              rejected: [],
            });
          }
        }
        return sendJson(res, mapped.status, { error: mapped.error });
      }
    }

    if (req.method === "GET" && req.url.startsWith("/v1/events:fetch")) {
      if (req.headers["x-relay-peer"]) {
        const peerId =
          typeof req.headers["x-relay-from"] === "string"
            ? req.headers["x-relay-from"].trim()
            : "";
        const peerToken =
          typeof req.headers["x-relay-peer-token"] === "string"
            ? req.headers["x-relay-peer-token"].trim()
            : "";
        const mapping = peerId && storage.getPeerMappingBySelfId
          ? await storage.getPeerMappingBySelfId(peerId)
          : null;
        if (!mapping || !isPeerMappingActive(mapping) || !mapping.rotated_token) {
          return sendJson(res, 403, {
            error: {
              code: mapping && isPeerMappingQuarantined(mapping) ? "peer_quarantined" : "forbidden",
              message: mapping && isPeerMappingQuarantined(mapping) ? "Peer is quarantined." : "Rotated token required.",
            },
          });
        }
        const expiresAt = mapping.rotated_expires_at
          ? Date.parse(mapping.rotated_expires_at)
          : null;
        if (Number.isFinite(expiresAt) && expiresAt <= Date.now()) {
          return sendJson(res, 403, {
            error: { code: "forbidden", message: "Rotated token expired." },
          });
        }
        if (!peerToken || peerToken !== mapping.rotated_token) {
          return sendJson(res, 403, {
            error: { code: "forbidden", message: "Invalid peer token." },
          });
        }
        const dynamicBanlist = storage.listActivePeerBanlistValues
          ? await storage.listActivePeerBanlistValues()
          : [];
        const combinedBanlist = [...config.federationPeerBanlist, ...dynamicBanlist];
        if (isPeerBanned(ip, peerId, combinedBanlist)) {
          logEvent("warn", "federation_peer_banned", { ip, peer_id: peerId });
          return sendJson(res, 403, { error: { code: "forbidden", message: "Peer banned." } });
        }
        if (
          config.federationPeerAllowlist.length > 0 &&
          !matchesIpList(ip, config.federationPeerAllowlist)
        ) {
          return sendJson(res, 403, { error: { code: "forbidden", message: "Peer not allowed." } });
        }
      }
      const validation = validateFetchRequest(req.url, config);
      if (!validation.ok) {
        return sendJson(res, 400, { error: validation.error });
      }

      let federationFetchSessionLease = null;
      if (req.headers["x-federation-session"]) {
        const sessionId = headerValue(req, "x-federation-session");
        const token = headerValue(req, "x-federation-token");
        const timestamp = headerValue(req, "x-federation-timestamp");
        const signature = headerValue(req, "x-federation-signature");
        const operation = headerValue(req, "x-federation-operation");
        const grant = decodeFederationGrantHeader(headerValue(req, "x-federation-grant"));
        if (!sessionId || !token || !timestamp || !signature || !operation || !grant) {
          recordFederationReconciliationFailure("federation_session_required");
          return sendJson(res, 401, {
            error: { code: "federation_session_required", message: "Federation session headers are required." },
          });
        }
        if (operation !== "pull_reconcile") {
          recordFederationReconciliationFailure("operation_not_allowed");
          return sendJson(res, 403, {
            error: { code: "operation_not_allowed", message: "Federation session grant is not authorized." },
          });
        }
        const signatureValidation = validateFederationSignature({
          req,
          token,
          timestamp,
          signature,
          requestTarget,
          rawBody: "",
        });
        if (!signatureValidation.ok) {
          recordFederationReconciliationFailure("invalid_federation_signature");
          return sendJson(res, 401, {
            error: { code: "invalid_federation_signature", message: "Invalid federation signature." },
          });
        }
        const grantValidation = validateFederationGrant({
          config,
          grant,
          sessionId,
          token,
          operation: "pull_reconcile",
          gids: [validation.data.gid],
          sourceRelayId: headerValue(req, "x-federation-source") || undefined,
          destinationRelayId: headerValue(req, "x-federation-destination") || undefined,
          nowSeconds: signatureValidation.nowSeconds,
        });
        if (!grantValidation.ok) {
          recordFederationReconciliationFailure(grantValidation.code);
          return sendJson(res, 403, {
            error: {
              code: grantValidation.code,
              message: "Federation session grant is not authorized.",
            },
          });
        }
        if (await sessionIsRevoked(sessionId)) {
          recordFederationReconciliationFailure("session_revoked");
          return sendJson(res, 403, {
            error: {
              code: "session_revoked",
              message: "Federation session grant is not authorized.",
            },
          });
        }
        const sessionLeaseResult = federationSessionLimits.begin({
          sessionId,
          limits: grant.limits,
          nowSeconds: signatureValidation.nowSeconds,
        });
        if (!sessionLeaseResult.ok) {
          return rejectFederationSessionLimited(res, sessionLeaseResult, {
            operation: "pull_reconcile",
          });
        }
        federationFetchSessionLease = sessionLeaseResult.lease;
        metrics.inc("federation_reconciliation_total");
      }

      if (requiresManagedClientAccessGrant(req, pathname)) {
        const grantValidation = validateManagedClientAccessGrant(req, {
          operation: "fetch",
          gids: [validation.data.gid],
          nowSeconds: Math.floor(Date.now() / 1000),
        });
        if (!grantValidation.ok) {
          return sendJson(res, grantValidation.status, {
            error: {
              code: grantValidation.code,
              message: "Managed client access grant is not authorized.",
            },
          });
        }
        if (requiresManagedClientRelaySession(req, pathname)) {
          const sessionValidation = validateManagedClientRelaySession(req, {
            grantId: grantValidation.grantId,
            clientInstanceId: grantValidation.clientInstanceId,
            operation: "fetch",
            gids: [validation.data.gid],
            nowSeconds: Math.floor(Date.now() / 1000),
          });
          if (!sessionValidation.ok) {
            return sendJson(res, sessionValidation.status, {
              error: {
                code: sessionValidation.code,
                message: "Managed client relay session is not authorized.",
              },
            });
          }
        }
      }

      auditMeta = {
        action: "fetch",
        gid_hash: hashGid(validation.data.gid),
        limit: validation.data.limit,
        peer: Boolean(req.headers["x-relay-peer"] || req.headers["x-federation-session"]),
      };

      const ipLimit = await fetchIpLimiter(ip);
      if (!ipLimit.ok) {
        metrics.inc("rate_limited_total");
        return rejectRateLimited(res, "ip", ipLimit.retryAfterSeconds);
      }
      const ipBurst = await fetchIpBurstLimiter(ip);
      if (!ipBurst.ok) {
        metrics.inc("rate_limited_total");
        return rejectRateLimited(res, "ip_burst", ipBurst.retryAfterSeconds);
      }

      const gidLimit = await fetchGidLimiter(validation.data.gid);
      if (!gidLimit.ok) {
        metrics.inc("rate_limited_total");
        return rejectRateLimited(res, "gid", gidLimit.retryAfterSeconds, {
          gid: validation.data.gid,
        });
      }
      const gidBurst = await fetchGidBurstLimiter(validation.data.gid);
      if (!gidBurst.ok) {
        metrics.inc("rate_limited_total");
        return rejectRateLimited(res, "gid_burst", gidBurst.retryAfterSeconds, {
          gid: validation.data.gid,
        });
      }

      try {
        if (config.enableTestDbErrors && req.headers["x-test-db-error"]) {
          throw { code: req.headers["x-test-db-error"] };
        }
        const result = await storage.fetchEvents(validation.data);
        if (federationFetchSessionLease) {
          const responseBytes = Buffer.byteLength(JSON.stringify(result), "utf8");
          const quotaResult = federationSessionLimits.consume(federationFetchSessionLease, {
            events: Array.isArray(result.events) ? result.events.length : 0,
            bytes: responseBytes,
          });
          if (!quotaResult.ok) {
            return rejectFederationSessionLimited(res, quotaResult, {
              operation: "pull_reconcile",
            });
          }
        }
        if (req.headers["x-federation-session"]) {
          metrics.inc(
            "federation_reconciliation_events_total",
            Array.isArray(result.events) ? result.events.length : 0
          );
        }
        metrics.inc("fetch_requests_total");
        return sendJson(res, 200, result);
      } catch (err) {
        const mapped = mapDbError(err);
        return sendJson(res, mapped.status, { error: mapped.error });
      } finally {
        if (federationFetchSessionLease) {
          federationSessionLimits.finish(federationFetchSessionLease);
        }
      }
    }

    return sendJson(res, 404, { error: "not_found" });
  }

  return {
    route,
    shutdown: async () => {
      if (ingestRetryQueue) {
        ingestRetryQueue.shutdown();
      }
      if (ownsStorage) {
        await storage.close();
      }
      if (ownsRedis && redis) {
        await redis.quit();
      }
    },
  };
}

module.exports = { createRouter };
