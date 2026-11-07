const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const {
  parseJsonBody,
  parseJsonBodyWithRaw,
  parseRawBody,
  parseUrlQuery,
  sendJson,
} = require("./utils");
const {
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
} = require("./admin_auth");
const {
  ACTIVE_PEER_STATE,
  QUARANTINED_PEER_STATE,
  normalizePeerState,
  isPeerMappingActive,
} = require("./peer_mapping_state");

const MIN_PASSWORD_LENGTH = 8;

function createAdminRouter({
  config,
  storage,
  federationState,
  federationSync,
  registrySessions,
  federationPush,
  auditLogger,
  metrics,
}) {
  const sessionTtlSeconds = config.adminSessionTtlSeconds || 8 * 60 * 60;
  const publicRoot = path.resolve(process.cwd(), "src/public/admin");
  const repoRoot = path.resolve(__dirname, "..", "..");
  const publicTokenPath = path.join(config.dataDir || "./data", "public_token.txt");
  const adminSettingsPath = path.join(config.dataDir || "./data", "admin_settings.json");

  function readPublicTokenFile() {
    try {
      if (!fs.existsSync(publicTokenPath)) return { token: "", createdAt: null };
      const raw = fs.readFileSync(publicTokenPath, "utf8");
      const tokenLine = raw.split("\n").find((line) => line.startsWith("token="));
      const createdLine = raw
        .split("\n")
        .find((line) => line.startsWith("created_at_utc="));
      return {
        token: tokenLine ? tokenLine.replace("token=", "").trim() : "",
        createdAt: createdLine ? createdLine.replace("created_at_utc=", "").trim() : null,
      };
    } catch (err) {
      return { token: "", createdAt: null };
    }
  }

  function readAdminSettings() {
    try {
      if (!fs.existsSync(adminSettingsPath)) {
        return { auto_connect_discovered_peers: false };
      }
      const raw = fs.readFileSync(adminSettingsPath, "utf8");
      const parsed = JSON.parse(raw);
      return {
        auto_connect_discovered_peers: Boolean(parsed.auto_connect_discovered_peers),
      };
    } catch (err) {
      return { auto_connect_discovered_peers: false };
    }
  }

  function writeAdminSettings(next) {
    const payload = {
      auto_connect_discovered_peers: Boolean(next.auto_connect_discovered_peers),
      updated_at: new Date().toISOString(),
    };
    fs.mkdirSync(path.dirname(adminSettingsPath), { recursive: true });
    fs.writeFileSync(adminSettingsPath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
    return payload;
  }

  function parsePrometheus(text) {
    const values = new Map();
    const lines = text.split("\n");
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) continue;
      const match = trimmed.match(
        /^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/
      );
      if (!match) continue;
      const name = match[1];
      const value = Number(match[3]);
      if (!Number.isFinite(value)) continue;
      if (!values.has(name)) values.set(name, []);
      values.get(name).push(value);
    }
    return values;
  }

  function sumSeries(values, name) {
    const series = values.get(name);
    if (!series || series.length === 0) return 0;
    return series.reduce((sum, value) => sum + value, 0);
  }

  function maxSeries(values, name) {
    const series = values.get(name);
    if (!series || series.length === 0) return null;
    return series.reduce((max, value) => (value > max ? value : max), series[0]);
  }

  function firstSeries(values, name) {
    const series = values.get(name);
    if (!series || series.length === 0) return null;
    return series[0];
  }

  function computeRate(current, previous, deltaMs) {
    if (!Number.isFinite(current) || !Number.isFinite(previous) || deltaMs <= 0) return 0;
    const delta = current - previous;
    if (!Number.isFinite(delta) || delta <= 0) return 0;
    return delta / (deltaMs / 1000);
  }

  let lastMetricsSample = null;
  const lastMetricsDetailSamples = new Map();
  const backupOkFile = config.backupOkFile || "";
  const restoreDrillOkFile = config.restoreDrillOkFile || "";
  const backupMaxAgeSeconds = Number(config.backupMaxAgeSeconds || 0);
  const alertLogPath = config.alertLogPath || "";

  async function handleLogin(req, res) {
    const body = await parseJsonBody(req, res, { maxBytes: 10_000 });
    if (!body) return true;
    const { username, password } = body;
    if (typeof username !== "string" || typeof password !== "string") {
      sendJson(res, 400, { error: { code: "invalid_request", message: "Invalid credentials." } });
      return true;
    }

    const auth = loadAdminAuth({ config });
    if (!auth.ok) {
      sendJson(res, 503, { error: { code: "auth_unavailable", message: "Admin auth unavailable." } });
      return true;
    }

    if (auth.username !== username || !verifyPassword(password, auth.passwordHash)) {
      sendJson(res, 401, { error: { code: "unauthorized", message: "Invalid credentials." } });
      return true;
    }

    const sessionId = createSession({ username, ttlSeconds: sessionTtlSeconds });
    res.setHeader(
      "Set-Cookie",
      buildSessionCookie({
        sessionId,
        maxAgeSeconds: sessionTtlSeconds,
        secure: isSecureRequest(req),
      })
    );
    sendJson(res, 200, { ok: true, username });
    return true;
  }

  async function handleLogout(req, res) {
    const sessionId = getSessionId(req);
    if (sessionId) {
      clearSession(sessionId);
    }
    res.setHeader(
      "Set-Cookie",
      buildClearCookie({
        secure: isSecureRequest(req),
      })
    );
    sendJson(res, 200, { ok: true });
    return true;
  }

  async function handleSessionStatus(req, res) {
    const sessionId = getSessionId(req);
    const session = getSession(sessionId);
    if (!session) {
      sendJson(res, 401, { error: { code: "unauthorized", message: "Not authenticated." } });
      return true;
    }
    sendJson(res, 200, {
      ok: true,
      username: session.username,
      ip_allowlist: config.ipAllowlist || "",
    });
    return true;
  }

  async function handleChangePassword(req, res) {
    const sessionId = getSessionId(req);
    const session = getSession(sessionId);
    if (!session) {
      sendJson(res, 401, { error: { code: "unauthorized", message: "Not authenticated." } });
      return true;
    }

    const body = await parseJsonBody(req, res, { maxBytes: 10_000 });
    if (!body) return true;
    const { current_password: currentPassword, new_password: newPassword } = body;

    if (typeof currentPassword !== "string" || typeof newPassword !== "string") {
      sendJson(res, 400, { error: { code: "invalid_request", message: "Invalid payload." } });
      return true;
    }
    if (newPassword.length < MIN_PASSWORD_LENGTH) {
      sendJson(res, 400, {
        error: {
          code: "invalid_request",
          message: `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`,
        },
      });
      return true;
    }

    const auth = loadAdminAuth({ config });
    if (!auth.ok) {
      sendJson(res, 503, { error: { code: "auth_unavailable", message: "Admin auth unavailable." } });
      return true;
    }

    if (!verifyPassword(currentPassword, auth.passwordHash)) {
      sendJson(res, 401, { error: { code: "unauthorized", message: "Invalid credentials." } });
      return true;
    }

    const nextHash = hashPassword(newPassword);
    const saved = saveAdminAuth({ config, username: auth.username, passwordHash: nextHash });
    if (!saved.ok) {
      sendJson(res, 500, { error: { code: "auth_update_failed", message: "Update failed." } });
      return true;
    }

    sendJson(res, 200, { ok: true });
    return true;
  }

  function requireSession(req, res) {
    const sessionId = getSessionId(req);
    const session = getSession(sessionId);
    if (!session) {
      sendJson(res, 401, { error: { code: "unauthorized", message: "Not authenticated." } });
      return null;
    }
    return session;
  }

  function recordAudit(session, action, details) {
    if (!auditLogger || !session) return;
    auditLogger.log({
      action,
      actor: session.username,
      ...details,
    });
  }

  async function handleListReputation(req, res) {
    if (!requireSession(req, res)) return true;
    const items = await storage.listPeerReputation();
    sendJson(res, 200, { items });
    return true;
  }

  async function handleCreateReputation(req, res) {
    const session = requireSession(req, res);
    if (!session) return true;
    const body = await parseJsonBody(req, res, { maxBytes: 10_000 });
    if (!body) return true;
    const { peer_id: peerId, score, note } = body;
    if (typeof peerId !== "string" || peerId.length === 0) {
      sendJson(res, 400, { error: { code: "invalid_request", message: "peer_id is required." } });
      return true;
    }
    const parsedScore = Number.isFinite(Number(score)) ? Number(score) : 0;
    const created = await storage.createPeerReputation({
      peerId,
      score: parsedScore,
      note: typeof note === "string" ? note : null,
    });
    recordAudit(session, "reputation_create", {
      id: created.id,
      peer_id: created.peer_id,
      score: created.score,
      note: created.note,
    });
    sendJson(res, 201, { item: created });
    return true;
  }

  function parseReputationCsvRows(raw) {
    const lines = raw
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean);
    if (lines.length === 0) return [];
    const header = lines[0].toLowerCase();
    const hasHeader = header.includes("peer") && header.includes("score");
    const rows = hasHeader ? lines.slice(1) : lines;
    return rows.map((line) => line.split(",").map((cell) => cell.trim()));
  }

  async function handleImportReputation(req, res) {
    const session = requireSession(req, res);
    if (!session) return true;
    const contentType = String(req.headers["content-type"] || "");
    if (!contentType.includes("text/csv")) {
      sendJson(res, 400, { error: { code: "invalid_request", message: "CSV required." } });
      return true;
    }
    const raw = await parseRawBody(req, res, { maxBytes: 200_000 });
    if (raw === null) return true;
    const rows = parseReputationCsvRows(raw);
    let created = 0;
    for (const row of rows) {
      const peerId = row[0] || "";
      const score = Number.isFinite(Number(row[1])) ? Number(row[1]) : 0;
      const note = row[2] || null;
      if (!peerId) continue;
      await storage.createPeerReputation({
        peerId,
        score,
        note: note ? String(note) : null,
      });
      created += 1;
    }
    recordAudit(session, "reputation_import", { created, total: rows.length });
    sendJson(res, 200, { ok: true, created });
    return true;
  }

  async function handleExportReputation(req, res) {
    if (!requireSession(req, res)) return true;
    const items = await storage.listPeerReputation();
    const header = "peer_id,score,note";
    const rows = items.map((item) => {
      const note = item.note ? String(item.note).replace(/,/g, " ") : "";
      return `${item.peer_id},${item.score},${note}`;
    });
    const body = [header, ...rows].join("\n");
    res.statusCode = 200;
    res.setHeader("Content-Type", "text/csv; charset=utf-8");
    res.setHeader("Content-Length", Buffer.byteLength(body));
    res.end(body);
    return true;
  }

  async function handleUpdateReputation(req, res, id) {
    const session = requireSession(req, res);
    if (!session) return true;
    const body = await parseJsonBody(req, res, { maxBytes: 10_000 });
    if (!body) return true;
    const parsedScore = Number.isFinite(Number(body.score)) ? Number(body.score) : 0;
    const updated = await storage.updatePeerReputation({
      id,
      score: parsedScore,
      note: typeof body.note === "string" ? body.note : null,
    });
    if (!updated) {
      sendJson(res, 404, { error: { code: "not_found", message: "Not found." } });
      return true;
    }
    recordAudit(session, "reputation_update", {
      id: updated.id,
      peer_id: updated.peer_id,
      score: updated.score,
      note: updated.note,
    });
    sendJson(res, 200, { item: updated });
    return true;
  }

  async function handleDeleteReputation(req, res, id) {
    const session = requireSession(req, res);
    if (!session) return true;
    const deleted = await storage.deletePeerReputation(id);
    if (!deleted) {
      sendJson(res, 404, { error: { code: "not_found", message: "Not found." } });
      return true;
    }
    recordAudit(session, "reputation_delete", { id });
    sendJson(res, 200, { ok: true });
    return true;
  }

  async function handleListBanlist(req, res) {
    if (!requireSession(req, res)) return true;
    const items = await storage.listPeerBanlist();
    sendJson(res, 200, { items });
    return true;
  }

  async function handleCreateBanlist(req, res) {
    const session = requireSession(req, res);
    if (!session) return true;
    const body = await parseJsonBody(req, res, { maxBytes: 10_000 });
    if (!body) return true;
    const { type, value, reason } = body;
    if (type !== "peer_id" && type !== "cidr") {
      sendJson(res, 400, { error: { code: "invalid_request", message: "Invalid type." } });
      return true;
    }
    if (typeof value !== "string" || value.length === 0) {
      sendJson(res, 400, { error: { code: "invalid_request", message: "value is required." } });
      return true;
    }
    const created = await storage.createPeerBanlist({ type, value, reason });
    recordAudit(session, "banlist_create", {
      id: created.id,
      type: created.type,
      value: created.value,
      reason: created.reason,
      enabled: created.enabled,
    });
    sendJson(res, 201, { item: created });
    return true;
  }

  function parseCsvRows(raw) {
    const lines = raw
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean);
    if (lines.length === 0) return [];
    const header = lines[0].toLowerCase();
    const hasHeader = header.includes("type") && header.includes("value");
    const rows = hasHeader ? lines.slice(1) : lines;
    return rows.map((line) => line.split(",").map((cell) => cell.trim()));
  }

  async function handleImportBanlist(req, res) {
    const session = requireSession(req, res);
    if (!session) return true;
    const contentType = String(req.headers["content-type"] || "");
    if (!config.banlistImportHmacSecret) {
      sendJson(res, 503, {
        error: {
          code: "banlist_signature_unavailable",
          message: "Banlist signature secret not configured.",
        },
      });
      return true;
    }
    let items = [];
    if (contentType.includes("text/csv")) {
      const raw = await parseRawBody(req, res, { maxBytes: 200_000 });
      if (raw === null) return true;
      const signatureHeader = req.headers["x-banlist-signature"];
      const signature = typeof signatureHeader === "string" ? signatureHeader.trim() : "";
      if (!/^[a-f0-9]{64}$/i.test(signature)) {
        sendJson(res, 401, {
          error: { code: "invalid_signature", message: "Invalid banlist signature." },
        });
        return true;
      }
      const expected = crypto
        .createHmac("sha256", config.banlistImportHmacSecret)
        .update(raw)
        .digest("hex");
      const sigOk = crypto.timingSafeEqual(
        Buffer.from(signature, "hex"),
        Buffer.from(expected, "hex")
      );
      if (!sigOk) {
        sendJson(res, 401, {
          error: { code: "invalid_signature", message: "Invalid banlist signature." },
        });
        return true;
      }
      const rows = parseCsvRows(raw);
      items = rows.map((row) => ({
        type: row[0],
        value: row[1],
        reason: row[2] || null,
        enabled: row[3] ? row[3].toLowerCase() !== "false" : true,
      }));
    } else {
      sendJson(res, 400, { error: { code: "invalid_request", message: "CSV required." } });
      return true;
    }
    let created = 0;
    for (const item of items) {
      const type = typeof item.type === "string" ? item.type.trim() : "";
      const value = typeof item.value === "string" ? item.value.trim() : "";
      const reason = typeof item.reason === "string" ? item.reason.trim() : null;
      const enabled = typeof item.enabled === "boolean" ? item.enabled : true;
      if (!type || !value) continue;
      if (type !== "peer_id" && type !== "cidr") continue;
      try {
        const createdItem = await storage.createPeerBanlist({ type, value, reason });
        if (!enabled && createdItem && createdItem.id) {
          await storage.updatePeerBanlist({ id: createdItem.id, enabled });
        }
        created += 1;
      } catch (err) {
        if (err && err.code === "23505") {
          continue;
        }
        throw err;
      }
    }
    recordAudit(session, "banlist_import", { created, total: items.length });
    sendJson(res, 200, { ok: true, created });
    return true;
  }

  async function handleExportBanlist(req, res) {
    if (!requireSession(req, res)) return true;
    const items = await storage.listPeerBanlist();
    const header = "type,value,reason,enabled";
    const rows = items.map((item) => {
      const reason = item.reason ? String(item.reason).replace(/,/g, " ") : "";
      return `${item.type},${item.value},${reason},${item.enabled}`;
    });
    const body = [header, ...rows].join("\n");
    res.statusCode = 200;
    res.setHeader("Content-Type", "text/csv; charset=utf-8");
    res.setHeader("Content-Length", Buffer.byteLength(body));
    res.end(body);
    return true;
  }

  async function handleUpdateBanlist(req, res, id) {
    const session = requireSession(req, res);
    if (!session) return true;
    const body = await parseJsonBody(req, res, { maxBytes: 10_000 });
    if (!body) return true;
    const enabled =
      typeof body.enabled === "boolean" ? body.enabled : undefined;
    const reason = typeof body.reason === "string" ? body.reason : undefined;
    const updated = await storage.updatePeerBanlist({ id, enabled, reason });
    if (!updated) {
      sendJson(res, 404, { error: { code: "not_found", message: "Not found." } });
      return true;
    }
    recordAudit(session, "banlist_update", {
      id: updated.id,
      type: updated.type,
      value: updated.value,
      reason: updated.reason,
      enabled: updated.enabled,
    });
    sendJson(res, 200, { item: updated });
    return true;
  }

  async function handleDeleteBanlist(req, res, id) {
    const session = requireSession(req, res);
    if (!session) return true;
    const deleted = await storage.deletePeerBanlist(id);
    if (!deleted) {
      sendJson(res, 404, { error: { code: "not_found", message: "Not found." } });
      return true;
    }
    recordAudit(session, "banlist_delete", { id });
    sendJson(res, 200, { ok: true });
    return true;
  }

  async function handleAuditLog(req, res) {
    const session = requireSession(req, res);
    if (!session) return true;
    if (!auditLogger || !auditLogger.path) {
      sendJson(res, 503, { error: { code: "unsupported", message: "Audit log unavailable." } });
      return true;
    }
    const query = parseUrlQuery(req.url);
    const scope = typeof query.scope === "string" ? query.scope.trim() : "";
    const sinceRaw = typeof query.since === "string" ? query.since.trim() : "";
    const limitRaw = typeof query.limit === "string" ? query.limit.trim() : "";
    const limit = Math.min(1000, Math.max(1, Number(limitRaw) || 200));
    const since = sinceRaw ? new Date(sinceRaw) : null;
    const actionPrefixes = [];
    if (!scope || scope === "all") {
      actionPrefixes.push("banlist_", "reputation_");
    } else if (scope === "banlist") {
      actionPrefixes.push("banlist_");
    } else if (scope === "reputation") {
      actionPrefixes.push("reputation_");
    }

    let items = [];
    try {
      if (fs.existsSync(auditLogger.path)) {
        const content = fs.readFileSync(auditLogger.path, "utf8");
        const lines = content.trim().split("\n").filter(Boolean);
        for (const line of lines) {
          let entry;
          try {
            entry = JSON.parse(line);
          } catch (err) {
            continue;
          }
          if (actionPrefixes.length > 0) {
            const matches = actionPrefixes.some((prefix) =>
              typeof entry.action === "string" ? entry.action.startsWith(prefix) : false
            );
            if (!matches) continue;
          }
          if (since instanceof Date && Number.isFinite(since.valueOf())) {
            const ts = new Date(entry.ts || 0);
            if (!Number.isFinite(ts.valueOf()) || ts < since) continue;
          }
          items.push(entry);
        }
      }
    } catch (err) {
      sendJson(res, 500, { error: { code: "audit_read_failed", message: "Audit read failed." } });
      return true;
    }
    if (items.length > limit) {
      items = items.slice(items.length - limit);
    }
    sendJson(res, 200, { items });
    return true;
  }

  async function handleListPeerMappings(req, res) {
    if (!requireSession(req, res)) return true;
    const items = await storage.listPeerMappings();
    sendJson(res, 200, { items: items.map(sanitizePeerMapping) });
    return true;
  }

  async function handleCreatePeerMapping(req, res) {
    if (!requireSession(req, res)) return true;
    const body = await parseJsonBody(req, res, { maxBytes: 10_000 });
    if (!body) return true;
    const peerUrl = typeof body.peer_url === "string" ? body.peer_url.trim() : "";
    const peerToken = typeof body.peer_token === "string" ? body.peer_token.trim() : "";
    const syncGids = Array.isArray(body.sync_gids)
      ? body.sync_gids.filter((gid) => typeof gid === "string" && gid.trim().length > 0)
      : [];
    const autoSync = typeof body.auto_sync === "boolean" ? body.auto_sync : false;
    const peerState = normalizePeerState(body.peer_state) || ACTIVE_PEER_STATE;
    const quarantineReason =
      typeof body.quarantine_reason === "string" && body.quarantine_reason.trim()
        ? body.quarantine_reason.trim()
        : null;
    const quarantinedUntil =
      typeof body.quarantined_until === "string" && body.quarantined_until.trim()
        ? body.quarantined_until.trim()
        : null;
    if (!peerUrl || !peerToken) {
      sendJson(res, 400, {
        error: { code: "invalid_request", message: "peer_url and peer_token are required." },
      });
      return true;
    }
    if (body.peer_state != null && !normalizePeerState(body.peer_state)) {
      sendJson(res, 400, {
        error: { code: "invalid_request", message: "Invalid peer_state." },
      });
      return true;
    }
    if (peerState === QUARANTINED_PEER_STATE && !quarantineReason) {
      sendJson(res, 400, {
        error: { code: "invalid_request", message: "quarantine_reason is required for quarantined peers." },
      });
      return true;
    }
    if (config.federationMaxPeers > 0 && storage.listPeerMappings) {
      const mappings = await storage.listPeerMappings();
      const enabledCount = mappings.filter((item) => isPeerMappingActive(item)).length;
      if (peerState === ACTIVE_PEER_STATE && enabledCount >= config.federationMaxPeers) {
        sendJson(res, 409, {
          error: { code: "capacity_full", message: "Maximum peers reached." },
        });
        return true;
      }
    }
    const created = await storage.createPeerMapping({
      peerUrl,
      peerToken,
      syncGids,
      autoSync,
      peerState,
      quarantineReason,
      quarantinedUntil,
    });
    sendJson(res, 201, { item: sanitizePeerMapping(created) });
    return true;
  }

  async function handleUpdatePeerMapping(req, res, id) {
    if (!requireSession(req, res)) return true;
    const body = await parseJsonBody(req, res, { maxBytes: 10_000 });
    if (!body) return true;
    const peerToken = typeof body.peer_token === "string" ? body.peer_token.trim() : null;
    const syncGids = Array.isArray(body.sync_gids)
      ? body.sync_gids.filter((gid) => typeof gid === "string" && gid.trim().length > 0)
      : null;
    const enabled = typeof body.enabled === "boolean" ? body.enabled : null;
    const autoSync = typeof body.auto_sync === "boolean" ? body.auto_sync : null;
    const peerState = body.peer_state == null ? null : normalizePeerState(body.peer_state);
    const quarantineReason =
      typeof body.quarantine_reason === "string" && body.quarantine_reason.trim()
        ? body.quarantine_reason.trim()
        : body.quarantine_reason === null
          ? null
          : undefined;
    const quarantinedUntil =
      typeof body.quarantined_until === "string" && body.quarantined_until.trim()
        ? body.quarantined_until.trim()
        : body.quarantined_until === null
          ? null
          : undefined;
    if (body.peer_state != null && !peerState) {
      sendJson(res, 400, {
        error: { code: "invalid_request", message: "Invalid peer_state." },
      });
      return true;
    }
    if (peerState === QUARANTINED_PEER_STATE && !quarantineReason) {
      sendJson(res, 400, {
        error: { code: "invalid_request", message: "quarantine_reason is required for quarantined peers." },
      });
      return true;
    }
    if (enabled === true && config.federationMaxPeers > 0 && storage.listPeerMappings) {
      const mappings = await storage.listPeerMappings();
      const current = mappings.find((item) => String(item.id) === String(id));
      const wasEnabled = current ? isPeerMappingActive(current) : false;
      const nextState = peerState || (current && current.peer_state) || ACTIVE_PEER_STATE;
      const nextEnabled = enabled == null ? Boolean(current && current.enabled) : enabled;
      const enabledCount = mappings.filter((item) => isPeerMappingActive(item)).length;
      if (!wasEnabled && nextEnabled && nextState === ACTIVE_PEER_STATE && enabledCount >= config.federationMaxPeers) {
        sendJson(res, 409, {
          error: { code: "capacity_full", message: "Maximum peers reached." },
        });
        return true;
      }
    }
    const updated = await storage.updatePeerMapping({
      id,
      peerToken,
      syncGids,
      enabled,
      autoSync,
      peerState,
      quarantineReason,
      quarantinedUntil,
    });
    if (!updated) {
      sendJson(res, 404, { error: { code: "not_found", message: "Not found." } });
      return true;
    }
    sendJson(res, 200, { item: sanitizePeerMapping(updated) });
    return true;
  }

  async function handleDeletePeerMapping(req, res, id) {
    if (!requireSession(req, res)) return true;
    const deleted = await storage.deletePeerMapping(id);
    if (!deleted) {
      sendJson(res, 404, { error: { code: "not_found", message: "Not found." } });
      return true;
    }
    sendJson(res, 200, { ok: true });
    return true;
  }

  function sanitizePeerMapping(item) {
    if (!item) return item;
    const {
      peer_token: peerToken,
      rotated_token: rotatedToken,
      join_token: joinToken,
      join_token_expires_at: joinTokenExpiresAt,
      ...rest
    } = item;
    return {
      ...rest,
      token_present: Boolean(peerToken),
      rotated_present: Boolean(rotatedToken),
    };
  }

  async function handleMetricsSummary(req, res) {
    if (!requireSession(req, res)) return true;
    if (!metrics || !metrics.renderPrometheus) {
      sendJson(res, 503, {
        error: { code: "metrics_unavailable", message: "Unable to read /metrics." },
      });
      return true;
    }
    let text = "";
    try {
      text = metrics.renderPrometheus();
    } catch (err) {
      sendJson(res, 503, {
        error: { code: "metrics_unavailable", message: "Unable to read /metrics." },
      });
      return true;
    }
    const values = parsePrometheus(text);
    const nowMs = Date.now();
    const requestsTotal = sumSeries(values, "requests_total");
    const errorsTotal = sumSeries(values, "errors_total");
    const federationFailTotal = sumSeries(values, "federation_sync_fail_total");
    const deltaMs = lastMetricsSample ? nowMs - lastMetricsSample.ts : 0;
    const requestsRate = computeRate(
      requestsTotal,
      lastMetricsSample ? lastMetricsSample.requestsTotal : 0,
      deltaMs
    );
    const errorsRate = computeRate(
      errorsTotal,
      lastMetricsSample ? lastMetricsSample.errorsTotal : 0,
      deltaMs
    );
    const failureRate = computeRate(
      federationFailTotal,
      lastMetricsSample ? lastMetricsSample.federationFailTotal : 0,
      deltaMs
    );
    lastMetricsSample = {
      ts: nowMs,
      requestsTotal,
      errorsTotal,
      federationFailTotal,
    };
    const errorRate = requestsRate > 0 ? errorsRate / requestsRate : 0;
    const poolInUse = firstSeries(values, "db_pool_in_use");
    const poolTotal = firstSeries(values, "db_pool_total");
    const waitMsAvg = firstSeries(values, "db_pool_wait_ms_avg");
    const cursorLagSeconds = maxSeries(values, "federation_cursor_lag_seconds");
    const rateLimitSpike = firstSeries(values, "rate_limited_spike_active");

    sendJson(res, 200, {
      timestamp: new Date(nowMs).toISOString(),
      traffic: {
        requests_per_s: Number(requestsRate.toFixed(4)),
        error_rate: Number(errorRate.toFixed(4)),
      },
      db: {
        pool_in_use: Number.isFinite(poolInUse) ? poolInUse : 0,
        pool_total: Number.isFinite(poolTotal) ? poolTotal : 0,
        wait_ms_avg: Number.isFinite(waitMsAvg) ? waitMsAvg : 0,
      },
      federation: {
        cursor_lag_seconds: Number.isFinite(cursorLagSeconds) ? cursorLagSeconds : null,
        failures_10m: Number.isFinite(failureRate) ? Math.round(failureRate * 600) : 0,
      },
      rate_limit: { spike_active: Boolean(rateLimitSpike && rateLimitSpike > 0) },
    });
    return true;
  }

  async function handleMetricsDetail(req, res) {
    if (!requireSession(req, res)) return true;
    if (!metrics || !metrics.renderPrometheus) {
      sendJson(res, 503, {
        error: { code: "metrics_unavailable", message: "Unable to read /metrics." },
      });
      return true;
    }
    const query = parseUrlQuery(req.url);
    const domain = typeof query.domain === "string" ? query.domain.trim() : "";
    if (!domain) {
      sendJson(res, 400, {
        error: { code: "invalid_request", message: "domain is required." },
      });
      return true;
    }
    let text = "";
    try {
      text = metrics.renderPrometheus();
    } catch (err) {
      sendJson(res, 503, {
        error: { code: "metrics_unavailable", message: "Unable to read /metrics." },
      });
      return true;
    }
    const values = parsePrometheus(text);
    const nowMs = Date.now();
    const previous = lastMetricsDetailSamples.get(domain);
    const deltaMs = previous ? nowMs - previous.ts : 0;
    const response = { timestamp: new Date(nowMs).toISOString(), domain, metrics: {} };

    if (domain === "traffic") {
      const requestsTotal = sumSeries(values, "requests_total");
      const errorsTotal = sumSeries(values, "errors_total");
      const requestsRate = computeRate(
        requestsTotal,
        previous ? previous.requestsTotal : 0,
        deltaMs
      );
      const errorsRate = computeRate(
        errorsTotal,
        previous ? previous.errorsTotal : 0,
        deltaMs
      );
      const errorRate = requestsRate > 0 ? errorsRate / requestsRate : 0;
      response.metrics = {
        requests_total: requestsTotal,
        errors_total: errorsTotal,
        requests_per_s: Number(requestsRate.toFixed(4)),
        errors_per_s: Number(errorsRate.toFixed(4)),
        error_rate: Number(errorRate.toFixed(4)),
      };
      lastMetricsDetailSamples.set(domain, { ts: nowMs, requestsTotal, errorsTotal });
    } else if (domain === "db") {
      response.metrics = {
        pool_in_use: firstSeries(values, "db_pool_in_use") || 0,
        pool_total: firstSeries(values, "db_pool_total") || 0,
        pool_idle: firstSeries(values, "db_pool_idle") || 0,
        pool_waiting: firstSeries(values, "db_pool_waiting") || 0,
        wait_ms_avg: firstSeries(values, "db_pool_wait_ms_avg") || 0,
        wait_ms_last: firstSeries(values, "db_pool_wait_ms_last") || 0,
      };
    } else if (domain === "federation") {
      const successTotal = sumSeries(values, "federation_sync_success_total");
      const failTotal = sumSeries(values, "federation_sync_fail_total");
      const dropsTotal = sumSeries(values, "federation_backpressure_drops_total");
      const successRate = computeRate(
        successTotal,
        previous ? previous.successTotal : 0,
        deltaMs
      );
      const failRate = computeRate(
        failTotal,
        previous ? previous.failTotal : 0,
        deltaMs
      );
      const dropsRate = computeRate(
        dropsTotal,
        previous ? previous.dropsTotal : 0,
        deltaMs
      );
      response.metrics = {
        cursor_lag_seconds: maxSeries(values, "federation_cursor_lag_seconds"),
        sync_success_per_s: Number(successRate.toFixed(4)),
        sync_fail_per_s: Number(failRate.toFixed(4)),
        failures_10m: Number.isFinite(failRate) ? Math.round(failRate * 600) : 0,
        backpressure_queue_max: maxSeries(values, "federation_backpressure_queue_size"),
        backpressure_in_flight_max: maxSeries(values, "federation_backpressure_in_flight"),
        backpressure_drops_per_s: Number(dropsRate.toFixed(4)),
      };
      lastMetricsDetailSamples.set(domain, {
        ts: nowMs,
        successTotal,
        failTotal,
        dropsTotal,
      });
    } else if (domain === "rate_limit") {
      const limitedTotal = sumSeries(values, "rate_limited_total");
      const spikeTotal = sumSeries(values, "rate_limited_spike_total");
      const limitedRate = computeRate(
        limitedTotal,
        previous ? previous.limitedTotal : 0,
        deltaMs
      );
      response.metrics = {
        rate_limited_total: limitedTotal,
        rate_limited_per_s: Number(limitedRate.toFixed(4)),
        spike_active: Boolean(firstSeries(values, "rate_limited_spike_active")),
        spike_total: spikeTotal,
      };
      lastMetricsDetailSamples.set(domain, {
        ts: nowMs,
        limitedTotal,
        spikeTotal,
      });
    } else {
      sendJson(res, 400, {
        error: { code: "invalid_request", message: "Unknown domain." },
      });
      return true;
    }

    sendJson(res, 200, response);
    return true;
  }

  function readFileMtime(filePath) {
    try {
      const stat = fs.statSync(filePath);
      return stat.mtimeMs || null;
    } catch (err) {
      return null;
    }
  }

  async function handleBackupStatus(req, res) {
    if (!requireSession(req, res)) return true;
    if (!backupOkFile) {
      sendJson(res, 503, {
        error: { code: "backup_status_unavailable", message: "Backup status not available." },
      });
      return true;
    }
    const backupMtimeMs = readFileMtime(backupOkFile);
    if (!backupMtimeMs) {
      sendJson(res, 503, {
        error: { code: "backup_status_unavailable", message: "Backup status not available." },
      });
      return true;
    }
    const nowMs = Date.now();
    const backupAgeSeconds = Math.max(0, Math.floor((nowMs - backupMtimeMs) / 1000));
    let lastStatus = "ok";
    if (backupMaxAgeSeconds > 0 && backupAgeSeconds > backupMaxAgeSeconds) {
      lastStatus = "stale";
    }
    const restoreMtimeMs = restoreDrillOkFile ? readFileMtime(restoreDrillOkFile) : null;
    let restoreDrillStatus = "unknown";
    if (restoreMtimeMs) {
      const restoreAgeSeconds = Math.max(0, Math.floor((nowMs - restoreMtimeMs) / 1000));
      if (config.restoreDrillMaxAgeSeconds > 0 && restoreAgeSeconds > config.restoreDrillMaxAgeSeconds) {
        restoreDrillStatus = "stale";
      } else {
        restoreDrillStatus = "ok";
      }
    }
    sendJson(res, 200, {
      last_backup_at: new Date(backupMtimeMs).toISOString(),
      backup_age_seconds: backupAgeSeconds,
      last_status: lastStatus,
      last_restore_drill_at: restoreMtimeMs ? new Date(restoreMtimeMs).toISOString() : null,
      restore_drill_status: restoreDrillStatus,
    });
    return true;
  }

  function readAlertLog(limit = 50) {
    if (!alertLogPath) return [];
    try {
      if (!fs.existsSync(alertLogPath)) return [];
      const raw = fs.readFileSync(alertLogPath, "utf8");
      const lines = raw.split("\n").filter(Boolean);
      const tail = lines.slice(-limit);
      return tail.map((line, index) => ({
        id: `${lines.length - tail.length + index}-${line}`,
        message: line,
      }));
    } catch (err) {
      return [];
    }
  }

  function parseAlertEntry(entry) {
    const match = entry.message.match(/^(\d{4}-\d{2}-\d{2}T[^ ]+)\s+(.*)$/);
    if (!match) {
      return {
        type: "alert",
        data: {
          id: entry.id,
          name: entry.message,
          severity: "warning",
          starts_at: null,
        },
      };
    }
    const timestamp = match[1];
    const message = match[2];
    if (message.startsWith("ack ")) {
      const note = message.slice(4).trim();
      return {
        type: "incident",
        data: {
          id: entry.id,
          name: note || "Alert acknowledged.",
          acknowledged_at: timestamp,
        },
      };
    }
    const severity = message.includes("missing") || message.includes("stale")
      ? "warning"
      : "info";
    return {
      type: "alert",
      data: {
        id: entry.id,
        name: message,
        severity,
        starts_at: timestamp,
      },
    };
  }

  async function handleAlerts(req, res) {
    if (!requireSession(req, res)) return true;
    if (!alertLogPath) {
      sendJson(res, 503, {
        error: { code: "alerts_unavailable", message: "Alert source not configured." },
      });
      return true;
    }
    const entries = readAlertLog(50).map(parseAlertEntry);
    const alerts = [];
    const incidents = [];
    entries.forEach((entry) => {
      if (!entry) return;
      if (entry.type === "incident") {
        incidents.push(entry.data);
      } else if (entry.type === "alert") {
        alerts.push(entry.data);
      }
    });
    sendJson(res, 200, {
      items: alerts,
      incidents,
      page: 1,
      per_page: 50,
      total: alerts.length,
      incidents_total: incidents.length,
    });
    return true;
  }

  async function handleSettingsSummary(req, res) {
    if (!requireSession(req, res)) return true;
    const settings = readAdminSettings();
    sendJson(res, 200, {
      request_signature_enabled: config.requestSignatureEnabled,
      federation_peer_allowlist: (config.federationPeerAllowlist || []).join(","),
      rate_limit_window_seconds: Number(config.rateLimitWindowSeconds || 0),
      cleanup_interval_seconds: Number(config.cleanupIntervalSeconds || 0),
      backup_retention_days: Number(config.backupRetentionDays || 0),
      admin_session_ttl_seconds: Number(config.adminSessionTtlSeconds || 0),
      auto_connect_discovered_peers: Boolean(settings.auto_connect_discovered_peers),
      log_paths: {
        audit: config.auditLogPath || "",
      },
    });
    return true;
  }

  async function handleUpdateDiscoverySettings(req, res) {
    const session = requireSession(req, res);
    if (!session) return true;
    const body = await parseJsonBody(req, res, { maxBytes: 10_000 });
    if (!body) return true;
    if (typeof body.auto_connect_discovered_peers !== "boolean") {
      sendJson(res, 400, {
        error: { code: "invalid_request", message: "auto_connect_discovered_peers is required." },
      });
      return true;
    }
    const saved = writeAdminSettings({
      auto_connect_discovered_peers: body.auto_connect_discovered_peers,
    });
    recordAudit(session, "settings_update", {
      auto_connect_discovered_peers: saved.auto_connect_discovered_peers,
    });
    sendJson(res, 200, {
      ok: true,
      auto_connect_discovered_peers: saved.auto_connect_discovered_peers,
    });
    return true;
  }

  async function handleAlertAck(req, res, alertId) {
    if (!requireSession(req, res)) return true;
    if (!alertLogPath) {
      sendJson(res, 503, {
        error: { code: "alerts_unavailable", message: "Alert source not configured." },
      });
      return true;
    }
    const entries = readAlertLog(200);
    const match = entries.find((entry) => entry.id === alertId);
    if (!match) {
      sendJson(res, 404, { error: { code: "not_found", message: "Alert not found." } });
      return true;
    }
    const body = await parseJsonBody(req, res, { maxBytes: 10_000 });
    if (!body) return true;
    const note = typeof body.note === "string" ? body.note.trim() : "";
    const stamp = new Date().toISOString();
    const ackLine = `${stamp} ack ${alertId} ${note}`.trim();
    try {
      fs.appendFileSync(alertLogPath, `${ackLine}\n`, "utf8");
    } catch (err) {
      sendJson(res, 503, {
        error: { code: "alerts_unavailable", message: "Alert source not configured." },
      });
      return true;
    }
    sendJson(res, 200, { ok: true });
    return true;
  }

  async function handleRetentionStatus(req, res) {
    if (!requireSession(req, res)) return true;
    if (!storage || !storage.getCleanupStats) {
      sendJson(res, 503, {
        error: { code: "retention_status_unavailable", message: "Retention status not available." },
      });
      return true;
    }
    const stats = storage.getCleanupStats() || {};
    const lastStartedAtMs = Number.isFinite(stats.lastStartedAtMs)
      ? stats.lastStartedAtMs
      : null;
    sendJson(res, 200, {
      profile: config.retentionProfile || "default",
      cleanup_interval_seconds: Number(config.cleanupIntervalSeconds || 0),
      last_cleanup_duration_ms: Number(stats.lastDurationMs || 0),
      last_cleanup_at: lastStartedAtMs ? new Date(lastStartedAtMs).toISOString() : null,
    });
    return true;
  }

  async function handleListPeers(req, res) {
    if (!requireSession(req, res)) return true;
    if (!federationState || !federationState.listPeers) {
      sendJson(res, 503, { error: { code: "unsupported", message: "Federation status unavailable." } });
      return true;
    }
    const items = federationState.listPeers();
    const heartbeatTtl = Math.max(1, config.federationHeartbeatTtlSeconds || 120);
    let mappingsByUrl = new Map();
    if (storage.listPeerMappings) {
      const mappings = await storage.listPeerMappings();
      mappingsByUrl = new Map(
        mappings.map((mapping) => [mapping.peer_url, mapping])
      );
    }
    const nowMs = Date.now();
    const enrichedItems = items.map((item) => {
      const mapping = mappingsByUrl.get(item.peer_url);
      const lastSeenAt = mapping && mapping.last_seen_at ? mapping.last_seen_at : null;
      let onlineStatus = "unknown";
      if (lastSeenAt) {
        const lastSeenMs = Date.parse(lastSeenAt);
        onlineStatus = Number.isFinite(lastSeenMs) && nowMs - lastSeenMs <= heartbeatTtl * 1000
          ? "online"
          : "offline";
      }
      return {
        ...item,
        last_seen_at: lastSeenAt,
        online_status: onlineStatus,
      };
    });
    let connected = 0;
    if (storage.countActivePeerConnections) {
      connected = await storage.countActivePeerConnections(heartbeatTtl);
    }
    const capacityLimit = Number(config.federationConnectionCapacity || 0);
    const minPeers = Number(config.federationMinPeers || 0);
    const maxPeers = Number(config.federationMaxPeers || 0);
    const autoSyncEnabled = storage.countAutoSyncMappings
      ? (await storage.countAutoSyncMappings()) > 0
      : false;
    const capacityWarning = capacityLimit > 0 ? connected >= capacityLimit : false;
    sendJson(res, 200, {
      items: enrichedItems,
      meta: {
        stats: {
          connected,
          capacity_limit: capacityLimit,
          heartbeat_ttl_seconds: heartbeatTtl,
          capacity_warning: capacityWarning,
          auto_sync_enabled: autoSyncEnabled,
          min_peers: minPeers,
          max_peers: maxPeers,
        },
      },
    });
    return true;
  }

  async function handleGetFederationSelf(req, res) {
    if (!requireSession(req, res)) return true;
    const { token: tokenValue, createdAt } = readPublicTokenFile();
    sendJson(res, 200, {
      self_id: config.federationSelfId || null,
      self_url: config.federationSelfUrl || null,
      public_token_present: Boolean(tokenValue),
      public_token: tokenValue || null,
      public_token_created_at: createdAt,
    });
    return true;
  }

  async function handleGetFederationSession(req, res) {
    if (!requireSession(req, res)) return true;
    const status = registrySessions && registrySessions.status
      ? registrySessions.status()
      : { enabled: false, status: "unavailable", session: null, active: false };
    sendJson(res, 200, status);
    return true;
  }

  async function handleRefreshFederationSession(req, res) {
    const session = requireSession(req, res);
    if (!session) return true;
    if (!registrySessions || !registrySessions.refresh) {
      sendJson(res, 503, {
        error: { code: "registry_sessions_unavailable", message: "Registry session client unavailable." },
      });
      return true;
    }
    await registrySessions.refresh({ force: true });
    recordAudit(session, "registry_federation_session_refresh", {});
    sendJson(res, 200, registrySessions.status());
    return true;
  }

  async function handleGetFederationPush(req, res) {
    if (!requireSession(req, res)) return true;
    if (!federationPush || !federationPush.status) {
      sendJson(res, 503, {
        error: { code: "federation_push_unavailable", message: "Federation push queue unavailable." },
      });
      return true;
    }
    sendJson(res, 200, federationPush.status());
    return true;
  }

  async function handleUpdateFederationPublicToken(req, res) {
    if (!requireSession(req, res)) return true;
    const body = await parseJsonBody(req, res, { maxBytes: 10_000 });
    if (!body) return true;
    const mode = typeof body.mode === "string" ? body.mode : "create";
    const providedToken =
      typeof body.public_token === "string" ? body.public_token.trim() : "";
    if (providedToken && !/^[A-Za-z0-9-]{12,}$/.test(providedToken)) {
      sendJson(res, 400, {
        error: {
          code: "invalid_request",
          message: "Token must be at least 12 characters (letters, numbers, hyphen).",
        },
      });
      return true;
    }
    if (mode !== "create" && mode !== "replace") {
      sendJson(res, 400, { error: { code: "invalid_request", message: "Invalid mode." } });
      return true;
    }
    const existing = readPublicTokenFile();
    if (mode === "create" && existing.token) {
      sendJson(res, 409, {
        error: { code: "conflict", message: "Public token already exists." },
      });
      return true;
    }
    const token = providedToken || crypto.randomUUID();
    const createdAt = new Date().toISOString();
    const payload = `token=${token}\ncreated_at_utc=${createdAt}\n`;
    fs.mkdirSync(path.dirname(publicTokenPath), { recursive: true });
    fs.writeFileSync(publicTokenPath, payload, "utf8");
    sendJson(res, 200, { ok: true, public_token: token, public_token_created_at: createdAt });
    return true;
  }

  async function handleRevokeFederationPublicToken(req, res) {
    if (!requireSession(req, res)) return true;
    try {
      if (fs.existsSync(publicTokenPath)) {
        fs.unlinkSync(publicTokenPath);
      }
    } catch (err) {
      // ignore delete errors
    }
    sendJson(res, 200, { ok: true });
    return true;
  }

  async function handleListInboundPeers(req, res) {
    if (!requireSession(req, res)) return true;
    if (!storage.listInboundPeers) {
      sendJson(res, 503, { error: { code: "unsupported", message: "Inbound peers unavailable." } });
      return true;
    }
    const heartbeatTtl = Math.max(1, config.federationHeartbeatTtlSeconds || 120);
    const items = await storage.listInboundPeers(heartbeatTtl);
    sendJson(res, 200, { items });
    return true;
  }

  async function handleGetPeer(req, res, id) {
    if (!requireSession(req, res)) return true;
    if (!federationState || !federationState.getPeer) {
      sendJson(res, 503, { error: { code: "unsupported", message: "Federation status unavailable." } });
      return true;
    }
    const item = federationState.getPeer(id);
    if (!item) {
      sendJson(res, 404, { error: { code: "not_found", message: "Peer not found." } });
      return true;
    }
    let lastSeenAt = null;
    let onlineStatus = "unknown";
    let mappingAutoSync = null;
    let mappingSyncGids = null;
    if (storage.getPeerMappingByUrl) {
      const mapping = await storage.getPeerMappingByUrl(item.peer_url);
      if (mapping && mapping.last_seen_at) {
        lastSeenAt = mapping.last_seen_at;
        const heartbeatTtl = Math.max(1, config.federationHeartbeatTtlSeconds || 120);
        const lastSeenMs = Date.parse(lastSeenAt);
        if (Number.isFinite(lastSeenMs)) {
          onlineStatus =
            Date.now() - lastSeenMs <= heartbeatTtl * 1000 ? "online" : "offline";
        }
      }
      if (mapping) {
        mappingAutoSync = typeof mapping.auto_sync === "boolean" ? mapping.auto_sync : null;
        mappingSyncGids = Array.isArray(mapping.sync_gids) ? mapping.sync_gids : null;
      }
    }
    sendJson(res, 200, {
      item: {
        ...item,
        last_seen_at: lastSeenAt,
        online_status: onlineStatus,
        auto_sync: mappingAutoSync,
        sync_gids: mappingSyncGids,
      },
    });
    return true;
  }

  async function handleGetPeerHistory(req, res, id) {
    if (!requireSession(req, res)) return true;
    if (!federationState || !federationState.getHistory) {
      sendJson(res, 503, { error: { code: "unsupported", message: "Federation status unavailable." } });
      return true;
    }
    const items = federationState.getHistory(id);
    if (!items) {
      sendJson(res, 404, { error: { code: "not_found", message: "Peer not found." } });
      return true;
    }
    sendJson(res, 200, { items });
    return true;
  }

  async function handlePeerAction(req, res) {
    if (!requireSession(req, res)) return true;
    if (!federationState || !federationSync) {
      sendJson(res, 503, { error: { code: "unsupported", message: "Federation control unavailable." } });
      return true;
    }
    const pathOnly = req.url.split("?")[0];
    const parts = pathOnly.split("/");
    if (parts.length !== 6) {
      sendJson(res, 400, { error: { code: "invalid_request", message: "Invalid peer action." } });
      return true;
    }
    const id = decodeURIComponent(parts[4] || "");
    const action = parts[5];
    const peer = federationState.getPeer ? federationState.getPeer(id) : null;
    if (!peer || !peer.peer_url) {
      sendJson(res, 404, { error: { code: "not_found", message: "Peer not found." } });
      return true;
    }
    if (action === "resync") {
      const ok = await federationSync.triggerResync(peer.peer_url);
      if (!ok) {
        sendJson(res, 409, {
          error: { code: "conflict", message: "Unable to trigger resync." },
        });
        return true;
      }
      sendJson(res, 200, { ok: true });
      return true;
    }
    if (action === "clear-backoff") {
      const ok = federationSync.clearHeartbeatBackoff(peer.peer_url);
      if (!ok) {
        sendJson(res, 404, { error: { code: "not_found", message: "Peer not found." } });
        return true;
      }
      sendJson(res, 200, { ok: true });
      return true;
    }
    sendJson(res, 400, { error: { code: "invalid_request", message: "Invalid peer action." } });
    return true;
  }

  function contentTypeFor(filePath) {
    const ext = path.extname(filePath).toLowerCase();
    if (ext === ".html") return "text/html; charset=utf-8";
    if (ext === ".css") return "text/css; charset=utf-8";
    if (ext === ".js") return "application/javascript; charset=utf-8";
    if (ext === ".md") return "text/markdown; charset=utf-8";
    if (ext === ".pdf") return "application/pdf";
    if (ext === ".svg") return "image/svg+xml";
    return "application/octet-stream";
  }

  function serveFile(res, filePath) {
    try {
      const stat = fs.statSync(filePath);
      if (!stat.isFile()) return false;
      const body = fs.readFileSync(filePath);
      res.statusCode = 200;
      res.setHeader("Content-Type", contentTypeFor(filePath));
      res.setHeader("Content-Length", Buffer.byteLength(body));
      res.end(body);
      return true;
    } catch (err) {
      return false;
    }
  }

  function serveAdminStatic(req, res) {
    if (req.method !== "GET") return false;
    const pathOnly = req.url.split("?")[0];
    if (pathOnly === "/admin" || pathOnly === "/admin/") {
      return serveFile(res, path.join(publicRoot, "dashboard.html"));
    }
    if (pathOnly === "/admin/login") {
      return serveFile(res, path.join(publicRoot, "login.html"));
    }
    if (pathOnly === "/admin/dashboard") {
      return serveFile(res, path.join(publicRoot, "dashboard.html"));
    }
    if (pathOnly === "/admin/banlist") {
      return serveFile(res, path.join(publicRoot, "banlist.html"));
    }
    if (pathOnly === "/admin/settings") {
      return serveFile(res, path.join(publicRoot, "settings.html"));
    }
    if (pathOnly === "/admin/reputation") {
      return serveFile(res, path.join(publicRoot, "reputation.html"));
    }
    if (pathOnly === "/admin/audit") {
      return serveFile(res, path.join(publicRoot, "audit.html"));
    }
    if (pathOnly === "/admin/peers") {
      return serveFile(res, path.join(publicRoot, "peers.html"));
    }
    if (pathOnly === "/admin/peer") {
      return serveFile(res, path.join(publicRoot, "peer_detail.html"));
    }
    if (pathOnly.startsWith("/admin/assets/")) {
      const rel = pathOnly.replace("/admin/assets/", "");
      const safeRel = path.normalize(rel).replace(/^(\.\.(\/|\\|$))+/, "");
      const filePath = path.join(publicRoot, "assets", safeRel);
      return serveFile(res, filePath);
    }
    if (pathOnly.startsWith("/admin/runbooks/")) {
      const slug = pathOnly.replace("/admin/runbooks/", "").replace(/\/$/, "");
      const runbooks = {
        "incident-response": path.join(repoRoot, "server/relay/docs/relay_incident_response_runbook.md"),
        "backup-restore": path.join(repoRoot, "server/relay/docs/relay_backup_restore_runbook.md"),
        "deploy-rollback": path.join(repoRoot, "server/relay/docs/relay_deploy_rollback_runbook.md"),
      };
      const pdfSuffix = ".pdf";
      const requestedPdf = slug.endsWith(pdfSuffix);
      const key = requestedPdf ? slug.slice(0, -pdfSuffix.length) : slug;
      const runbookPath = runbooks[key];
      if (!runbookPath) return false;
      if (requestedPdf) {
        return serveRunbookPdf(res, runbookPath, key);
      }
      return serveFile(res, runbookPath);
    }
    return false;
  }

  function wrapTextLine(line, maxLen) {
    if (line.length <= maxLen) return [line];
    const parts = [];
    let cursor = 0;
    while (cursor < line.length) {
      parts.push(line.slice(cursor, cursor + maxLen));
      cursor += maxLen;
    }
    return parts;
  }

  function escapePdfText(value) {
    return value.replace(/\\/g, "\\\\").replace(/\(/g, "\\(").replace(/\)/g, "\\)");
  }

  function buildPdfBuffer(title, rawText) {
    const maxLineLength = 100;
    const lineHeight = 14;
    const pageHeight = 792;
    const topMargin = 72;
    const bottomMargin = 72;
    const usableHeight = pageHeight - topMargin - bottomMargin;
    const linesPerPage = Math.floor(usableHeight / lineHeight);
    const lines = [];
    if (title) {
      lines.push(title);
      lines.push("");
    }
    rawText.split(/\r?\n/).forEach((line) => {
      const trimmed = line.replace(/\t/g, "  ");
      wrapTextLine(trimmed, maxLineLength).forEach((wrapped) => lines.push(wrapped));
    });
    const pages = [];
    for (let i = 0; i < lines.length; i += linesPerPage) {
      pages.push(lines.slice(i, i + linesPerPage));
    }
    const objects = [];
    const addObject = (body) => {
      objects.push(body);
      return objects.length;
    };
    const pageKids = [];
    const fontObj = addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
    const contentRefs = [];
    pages.forEach((pageLines) => {
      const textLines = pageLines
        .map((line, index) => {
          const escaped = escapePdfText(line);
          if (index === 0) return `(${escaped}) Tj`;
          return `0 -${lineHeight} Td (${escaped}) Tj`;
        })
        .join("\n");
      const contentStream = `BT\n/F1 12 Tf\n72 ${pageHeight - topMargin} Td\n${textLines}\nET`;
      const contentObj = addObject(
        `<< /Length ${Buffer.byteLength(contentStream)} >>\nstream\n${contentStream}\nendstream`
      );
      contentRefs.push(contentObj);
    });
    contentRefs.forEach((contentObj) => {
      const pageObj = addObject(
        `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 ${pageHeight}] /Contents ${contentObj} 0 R /Resources << /Font << /F1 ${fontObj} 0 R >> >> >>`
      );
      pageKids.push(pageObj);
    });
    const pagesObj = addObject(
      `<< /Type /Pages /Kids [${pageKids.map((id) => `${id} 0 R`).join(" ")}] /Count ${pageKids.length} >>`
    );
    const catalogObj = addObject(`<< /Type /Catalog /Pages ${pagesObj} 0 R >>`);
    const header = "%PDF-1.4\n";
    let body = header;
    const offsets = [0];
    objects.forEach((obj, index) => {
      offsets.push(Buffer.byteLength(body));
      body += `${index + 1} 0 obj\n${obj}\nendobj\n`;
    });
    const xrefOffset = Buffer.byteLength(body);
    body += "xref\n";
    body += `0 ${objects.length + 1}\n`;
    body += "0000000000 65535 f \n";
    offsets.slice(1).forEach((offset) => {
      body += `${String(offset).padStart(10, "0")} 00000 n \n`;
    });
    body += `trailer\n<< /Size ${objects.length + 1} /Root ${catalogObj} 0 R >>\nstartxref\n${xrefOffset}\n%%EOF`;
    return Buffer.from(body, "utf8");
  }

  function serveRunbookPdf(res, runbookPath, key) {
    try {
      const raw = fs.readFileSync(runbookPath, "utf8");
      const title = `Runbook: ${key.replace(/-/g, " ")}`;
      const pdf = buildPdfBuffer(title, raw);
      res.statusCode = 200;
      res.setHeader("Content-Type", "application/pdf");
      res.setHeader(
        "Content-Disposition",
        `attachment; filename="${key}-runbook.pdf"`
      );
      res.setHeader("Content-Length", pdf.length);
      res.end(pdf);
      return true;
    } catch (err) {
      return false;
    }
  }

  async function route(req, res) {
    const pathOnly = req.url.split("?")[0];
    if (serveAdminStatic(req, res)) return true;
    if (req.method === "POST" && req.url === "/admin/api/auth/login") {
      return handleLogin(req, res);
    }
    if (req.method === "GET" && req.url === "/admin/api/auth/me") {
      return handleSessionStatus(req, res);
    }
    if (req.method === "POST" && req.url === "/admin/api/auth/logout") {
      return handleLogout(req, res);
    }
    if (req.method === "POST" && req.url === "/admin/api/auth/change-password") {
      return handleChangePassword(req, res);
    }
    if (req.method === "GET" && req.url === "/admin/api/reputation") {
      return handleListReputation(req, res);
    }
    if (req.method === "GET" && pathOnly === "/admin/api/audit") {
      return handleAuditLog(req, res);
    }
    if (req.method === "GET" && req.url.startsWith("/admin/api/reputation/export")) {
      return handleExportReputation(req, res);
    }
    if (req.method === "GET" && pathOnly === "/admin/api/peer-mappings") {
      return handleListPeerMappings(req, res);
    }
    if (req.method === "GET" && pathOnly === "/admin/api/peers") {
      return handleListPeers(req, res);
    }
    if (req.method === "GET" && pathOnly === "/admin/api/metrics/summary") {
      return handleMetricsSummary(req, res);
    }
    if (req.method === "GET" && pathOnly === "/admin/api/metrics/detail") {
      return handleMetricsDetail(req, res);
    }
    if (req.method === "GET" && pathOnly === "/admin/api/backup/status") {
      return handleBackupStatus(req, res);
    }
    if (req.method === "GET" && pathOnly === "/admin/api/retention/status") {
      return handleRetentionStatus(req, res);
    }
    if (req.method === "GET" && pathOnly === "/admin/api/alerts") {
      return handleAlerts(req, res);
    }
    if (req.method === "GET" && pathOnly === "/admin/api/settings/summary") {
      return handleSettingsSummary(req, res);
    }
    if (req.method === "PATCH" && pathOnly === "/admin/api/settings/discovery") {
      return handleUpdateDiscoverySettings(req, res);
    }
    if (req.method === "POST" && pathOnly.startsWith("/admin/api/alerts/")) {
      const parts = pathOnly.split("/");
      if (parts.length === 6 && parts[5] === "ack") {
        const alertId = decodeURIComponent(parts[4] || "");
        if (!alertId) {
          sendJson(res, 400, {
            error: { code: "invalid_request", message: "Alert id is required." },
          });
          return true;
        }
        return handleAlertAck(req, res, alertId);
      }
    }
    if (req.method === "GET" && pathOnly === "/admin/api/federation/self") {
      return handleGetFederationSelf(req, res);
    }
    if (req.method === "GET" && pathOnly === "/admin/api/federation/session") {
      return handleGetFederationSession(req, res);
    }
    if (req.method === "GET" && pathOnly === "/admin/api/federation/push") {
      return handleGetFederationPush(req, res);
    }
    if (req.method === "GET" && pathOnly === "/admin/api/peers/inbound") {
      return handleListInboundPeers(req, res);
    }
    if (req.method === "GET" && pathOnly.startsWith("/admin/api/peers/")) {
      const parts = pathOnly.split("/");
      if (parts.length === 6 && parts[5] === "history") {
        const id = decodeURIComponent(parts[4] || "");
        return handleGetPeerHistory(req, res, id);
      }
      if (parts.length === 5) {
        const id = decodeURIComponent(parts[4] || "");
        return handleGetPeer(req, res, id);
      }
    }
    if (req.method === "GET" && req.url === "/admin/api/banlist") {
      return handleListBanlist(req, res);
    }
    if (req.method === "GET" && req.url.startsWith("/admin/api/banlist/export")) {
      return handleExportBanlist(req, res);
    }
    if (req.method === "POST" && req.url === "/admin/api/reputation") {
      return handleCreateReputation(req, res);
    }
    if (req.method === "POST" && req.url === "/admin/api/reputation/import") {
      return handleImportReputation(req, res);
    }
    if (req.method === "POST" && req.url === "/admin/api/banlist") {
      return handleCreateBanlist(req, res);
    }
    if (req.method === "POST" && req.url === "/admin/api/banlist/import") {
      return handleImportBanlist(req, res);
    }
    if (req.method === "POST" && pathOnly === "/admin/api/peer-mappings") {
      return handleCreatePeerMapping(req, res);
    }
    if (req.method === "POST" && pathOnly === "/admin/api/federation/self/public-token") {
      return handleUpdateFederationPublicToken(req, res);
    }
    if (req.method === "POST" && pathOnly === "/admin/api/federation/session/refresh") {
      return handleRefreshFederationSession(req, res);
    }
    if (req.method === "POST" && pathOnly.startsWith("/admin/api/peers/")) {
      const parts = pathOnly.split("/");
      if (parts.length === 6 && (parts[5] === "resync" || parts[5] === "clear-backoff")) {
        return handlePeerAction(req, res);
      }
    }
    if (req.method === "DELETE" && pathOnly === "/admin/api/federation/self/public-token") {
      return handleRevokeFederationPublicToken(req, res);
    }
    if (req.method === "PATCH" && pathOnly.startsWith("/admin/api/peer-mappings/")) {
      const id = Number(pathOnly.split("/").pop());
      if (!Number.isFinite(id)) {
        sendJson(res, 400, { error: { code: "invalid_request", message: "Invalid id." } });
        return true;
      }
      return handleUpdatePeerMapping(req, res, id);
    }
    if (req.method === "DELETE" && pathOnly.startsWith("/admin/api/peer-mappings/")) {
      const id = Number(pathOnly.split("/").pop());
      if (!Number.isFinite(id)) {
        sendJson(res, 400, { error: { code: "invalid_request", message: "Invalid id." } });
        return true;
      }
      return handleDeletePeerMapping(req, res, id);
    }
    if (req.method === "PATCH" && req.url.startsWith("/admin/api/reputation/")) {
      const id = Number(req.url.split("/").pop());
      if (!Number.isFinite(id)) {
        sendJson(res, 400, { error: { code: "invalid_request", message: "Invalid id." } });
        return true;
      }
      return handleUpdateReputation(req, res, id);
    }
    if (req.method === "DELETE" && req.url.startsWith("/admin/api/reputation/")) {
      const id = Number(req.url.split("/").pop());
      if (!Number.isFinite(id)) {
        sendJson(res, 400, { error: { code: "invalid_request", message: "Invalid id." } });
        return true;
      }
      return handleDeleteReputation(req, res, id);
    }
    if (req.method === "PATCH" && req.url.startsWith("/admin/api/banlist/")) {
      const id = Number(req.url.split("/").pop());
      if (!Number.isFinite(id)) {
        sendJson(res, 400, { error: { code: "invalid_request", message: "Invalid id." } });
        return true;
      }
      return handleUpdateBanlist(req, res, id);
    }
    if (req.method === "DELETE" && req.url.startsWith("/admin/api/banlist/")) {
      const id = Number(req.url.split("/").pop());
      if (!Number.isFinite(id)) {
        sendJson(res, 400, { error: { code: "invalid_request", message: "Invalid id." } });
        return true;
      }
      return handleDeleteBanlist(req, res, id);
    }
    return false;
  }

  return { route };
}

module.exports = { createAdminRouter };
