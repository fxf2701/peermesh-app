const fs = require("fs");
const path = require("path");
const Database = require("better-sqlite3");
const { normalizeBetaProgramConfig } = require("./client_beta_program");

const CLIENT_MODERATION_REPORT_IP_RETENTION_SECONDS = 60 * 60;

function openDatabase(dbPath) {
  fs.mkdirSync(path.dirname(dbPath), { recursive: true });
  const db = new Database(dbPath);
  db.pragma("journal_mode = WAL");
  db.pragma("foreign_keys = ON");
  db.pragma("busy_timeout = 5000");

  db.exec(`
    CREATE TABLE IF NOT EXISTS relays (
      relay_id TEXT PRIMARY KEY,
      public_key TEXT NOT NULL,
      endpoint TEXT NOT NULL,
      region TEXT,
      capabilities_json TEXT NOT NULL DEFAULT '[]',
      version TEXT,
      trust_level TEXT NOT NULL DEFAULT 'community',
      status TEXT NOT NULL DEFAULT 'active',
      federation_enabled INTEGER NOT NULL DEFAULT 0,
      last_seen INTEGER NOT NULL,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS used_nonces (
      nonce TEXT PRIMARY KEY,
      seen_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL
    );

  `);

  const columns = new Set(
    db.prepare("PRAGMA table_info(relays)").all().map((column) => column.name)
  );
  const addColumn = (name, sql) => {
    if (!columns.has(name)) db.exec(`ALTER TABLE relays ADD COLUMN ${sql}`);
  };
  addColumn("trust_level", "trust_level TEXT NOT NULL DEFAULT 'community'");
  addColumn("status", "status TEXT NOT NULL DEFAULT 'active'");
  addColumn("federation_enabled", "federation_enabled INTEGER NOT NULL DEFAULT 0");

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_relays_last_seen ON relays(last_seen);
    CREATE INDEX IF NOT EXISTS idx_relays_trust_level ON relays(trust_level);
    CREATE INDEX IF NOT EXISTS idx_relays_status ON relays(status);
    CREATE INDEX IF NOT EXISTS idx_used_nonces_expires_at ON used_nonces(expires_at);

    CREATE TABLE IF NOT EXISTS registry_audit (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      actor TEXT NOT NULL,
      action TEXT NOT NULL,
      resource_type TEXT NOT NULL,
      resource_id TEXT,
      result TEXT NOT NULL DEFAULT 'success',
      reason TEXT,
      created_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS relay_certificates (
      id TEXT PRIMARY KEY,
      relay_id TEXT NOT NULL,
      public_key TEXT NOT NULL,
      status TEXT NOT NULL,
      issued_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL,
      revoked_at INTEGER,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL,
      FOREIGN KEY (relay_id) REFERENCES relays(relay_id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS federation_sessions (
      session_id TEXT PRIMARY KEY,
      relay_id TEXT NOT NULL,
      peers_json TEXT NOT NULL,
      requested_gids_json TEXT NOT NULL,
      permissions_json TEXT NOT NULL,
      limits_json TEXT NOT NULL,
      issued_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL,
      policy_version INTEGER NOT NULL,
      registry_signature TEXT NOT NULL,
      revoked_at INTEGER,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL,
      FOREIGN KEY (relay_id) REFERENCES relays(relay_id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS registry_policy (
      id TEXT PRIMARY KEY,
      policy_json TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS registry_beta_program (
      id TEXT PRIMARY KEY,
      config_json TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS client_access_grants (
      grant_id TEXT PRIMARY KEY,
      client_instance_id TEXT NOT NULL,
      candidate_relays_json TEXT NOT NULL,
      allowed_gids_json TEXT NOT NULL,
      permissions_json TEXT NOT NULL,
      limits_json TEXT NOT NULL,
      network_hints_json TEXT NOT NULL DEFAULT '{}',
      issued_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL,
      policy_version INTEGER NOT NULL,
      access_token TEXT NOT NULL,
      registry_signature TEXT NOT NULL,
      connection_mode TEXT NOT NULL,
      revoked_at INTEGER,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS client_abuse_reports (
      report_id TEXT PRIMARY KEY,
      grant_id TEXT NOT NULL,
      relay_id TEXT NOT NULL,
      client_instance_id TEXT NOT NULL,
      reason TEXT NOT NULL,
      reported_at INTEGER NOT NULL,
      counters_json TEXT NOT NULL DEFAULT '{}',
      source_ip TEXT,
      created_at INTEGER NOT NULL,
      FOREIGN KEY (grant_id) REFERENCES client_access_grants(grant_id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS client_feedback (
      feedback_id TEXT PRIMARY KEY,
      category TEXT NOT NULL,
      feedback_type TEXT NOT NULL DEFAULT 'user_feedback',
      message TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      app_version TEXT,
      build_number TEXT,
      android_version TEXT,
      device_model TEXT,
      local_device_id TEXT,
      source TEXT NOT NULL,
      status TEXT NOT NULL,
      received_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS client_moderation_reports (
      report_id TEXT PRIMARY KEY,
      group_id TEXT NOT NULL,
      reporter_peer_id TEXT NOT NULL,
      reported_peer_id TEXT NOT NULL,
      target_type TEXT NOT NULL,
      message_id TEXT,
      event_id TEXT,
      reason_code TEXT NOT NULL,
      user_note TEXT,
      include_plaintext INTEGER NOT NULL,
      plaintext_excerpt TEXT,
      ciphertext_ref TEXT,
      client_created_at_ms INTEGER NOT NULL,
      app_version TEXT,
      protocol_version INTEGER NOT NULL,
      status TEXT NOT NULL,
      duplicate_of_report_id TEXT,
      source_ip TEXT,
      assigned_operator TEXT,
      received_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL,
      resolved_at INTEGER
    );

    CREATE TABLE IF NOT EXISTS client_moderation_report_ip_events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      source_ip TEXT NOT NULL,
      observed_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS client_moderation_report_notes (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      report_id TEXT NOT NULL,
      operator TEXT NOT NULL,
      note TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      FOREIGN KEY (report_id) REFERENCES client_moderation_reports(report_id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS registry_revocation_feed (
      id TEXT PRIMARY KEY,
      version INTEGER NOT NULL,
      feed_json TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL,
      published_at INTEGER NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_registry_audit_created_at ON registry_audit(created_at);
    CREATE INDEX IF NOT EXISTS idx_relay_certificates_relay_id ON relay_certificates(relay_id);
    CREATE INDEX IF NOT EXISTS idx_relay_certificates_status ON relay_certificates(status);
    CREATE INDEX IF NOT EXISTS idx_federation_sessions_relay_id ON federation_sessions(relay_id);
    CREATE INDEX IF NOT EXISTS idx_federation_sessions_expires_at ON federation_sessions(expires_at);
    CREATE INDEX IF NOT EXISTS idx_client_access_grants_client_instance_id ON client_access_grants(client_instance_id);
    CREATE INDEX IF NOT EXISTS idx_client_access_grants_expires_at ON client_access_grants(expires_at);
    CREATE INDEX IF NOT EXISTS idx_client_abuse_reports_relay_id_created_at
      ON client_abuse_reports(relay_id, created_at DESC);
    CREATE INDEX IF NOT EXISTS idx_client_abuse_reports_client_relay_created_at
      ON client_abuse_reports(client_instance_id, relay_id, created_at DESC);
    CREATE INDEX IF NOT EXISTS idx_client_abuse_reports_grant_id ON client_abuse_reports(grant_id);
    CREATE INDEX IF NOT EXISTS idx_client_feedback_received_at
      ON client_feedback(received_at DESC);
    CREATE INDEX IF NOT EXISTS idx_client_feedback_category_received_at
      ON client_feedback(category, received_at DESC);
    CREATE INDEX IF NOT EXISTS idx_client_moderation_reports_status_received_at
      ON client_moderation_reports(status, received_at DESC);
    CREATE INDEX IF NOT EXISTS idx_client_moderation_reports_group_id_received_at
      ON client_moderation_reports(group_id, received_at DESC);
    CREATE INDEX IF NOT EXISTS idx_client_moderation_reports_reported_peer_id_received_at
      ON client_moderation_reports(reported_peer_id, received_at DESC);
    CREATE INDEX IF NOT EXISTS idx_client_moderation_reports_target_type_received_at
      ON client_moderation_reports(target_type, received_at DESC);
    CREATE INDEX IF NOT EXISTS idx_client_moderation_reports_message_id
      ON client_moderation_reports(message_id);
    CREATE INDEX IF NOT EXISTS idx_client_moderation_report_ip_events_source_observed
      ON client_moderation_report_ip_events(source_ip, observed_at);
    CREATE INDEX IF NOT EXISTS idx_client_moderation_report_ip_events_observed
      ON client_moderation_report_ip_events(observed_at);
    CREATE INDEX IF NOT EXISTS idx_client_moderation_report_notes_report_id_created_at
      ON client_moderation_report_notes(report_id, created_at DESC);
  `);

  // Legacy databases may have raw reporter IPs on moderation reports. Keep the
  // column for SQLite compatibility, but clear it and stop exposing it.
  db.exec("UPDATE client_moderation_reports SET source_ip = NULL WHERE source_ip IS NOT NULL");
  db.prepare("DELETE FROM client_moderation_report_ip_events WHERE observed_at < ?").run(
    Math.floor(Date.now() / 1000) - CLIENT_MODERATION_REPORT_IP_RETENTION_SECONDS
  );

  const auditColumns = new Set(
    db.prepare("PRAGMA table_info(registry_audit)").all().map((column) => column.name)
  );
  if (!auditColumns.has("result")) {
    db.exec("ALTER TABLE registry_audit ADD COLUMN result TEXT NOT NULL DEFAULT 'success'");
  }

  const clientFeedbackColumns = new Set(
    db.prepare("PRAGMA table_info(client_feedback)").all().map((column) => column.name)
  );
  if (!clientFeedbackColumns.has("feedback_type")) {
    db.exec("ALTER TABLE client_feedback ADD COLUMN feedback_type TEXT NOT NULL DEFAULT 'user_feedback'");
  }

  return db;
}

function createRegistryStore(db) {
  const upsertRelayStmt = db.prepare(`
    INSERT INTO relays (
      relay_id,
      public_key,
      endpoint,
      region,
      capabilities_json,
      version,
      last_seen,
      created_at,
      updated_at
    ) VALUES (
      @relay_id,
      @public_key,
      @endpoint,
      @region,
      @capabilities_json,
      @version,
      @now,
      @now,
      @now
    )
    ON CONFLICT(relay_id) DO UPDATE SET
      public_key = excluded.public_key,
      endpoint = excluded.endpoint,
      region = excluded.region,
      capabilities_json = excluded.capabilities_json,
      version = excluded.version,
      last_seen = excluded.last_seen,
      updated_at = excluded.updated_at
  `);

  const getRelayStmt = db.prepare(`SELECT * FROM relays WHERE relay_id = ?`);
  const listRelaysStmt = db.prepare(`
    SELECT * FROM relays
    ORDER BY last_seen DESC, relay_id ASC
  `);
  const listActiveRelaysStmt = db.prepare(`
    SELECT * FROM relays
    WHERE last_seen >= ? AND status = 'active'
    ORDER BY last_seen DESC, relay_id ASC
  `);
  const cleanupRelaysStmt = db.prepare(`DELETE FROM relays WHERE last_seen < ?`);
  const insertNonceStmt = db.prepare(`
    INSERT INTO used_nonces (nonce, seen_at, expires_at)
    VALUES (?, ?, ?)
  `);
  const cleanupNoncesStmt = db.prepare(`DELETE FROM used_nonces WHERE expires_at < ?`);
  const updateRelayTrustStmt = db.prepare(`
    UPDATE relays
    SET trust_level = ?, updated_at = ?
    WHERE relay_id = ?
  `);
  const updateRelayStatusStmt = db.prepare(`
    UPDATE relays
    SET status = ?, updated_at = ?
    WHERE relay_id = ?
  `);
  const updateRelayFederationStmt = db.prepare(`
    UPDATE relays
    SET federation_enabled = ?, updated_at = ?
    WHERE relay_id = ?
  `);
  const insertAuditStmt = db.prepare(`
    INSERT INTO registry_audit (
      actor,
      action,
      resource_type,
      resource_id,
      result,
      reason,
      created_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?)
  `);
  const listAuditStmt = db.prepare(`
    SELECT *
    FROM registry_audit
    ORDER BY created_at DESC, id DESC
    LIMIT ?
  `);
  const getCertificateStmt = db.prepare(`SELECT * FROM relay_certificates WHERE id = ?`);
  const latestCertificateForRelayStmt = db.prepare(`
    SELECT *
    FROM relay_certificates
    WHERE relay_id = ?
    ORDER BY issued_at DESC, id DESC
    LIMIT 1
  `);
  const listCertificatesStmt = db.prepare(`
    SELECT *
    FROM relay_certificates
    ORDER BY issued_at DESC, id DESC
  `);
  const insertCertificateStmt = db.prepare(`
    INSERT INTO relay_certificates (
      id,
      relay_id,
      public_key,
      status,
      issued_at,
      expires_at,
      created_at,
      updated_at
    ) VALUES (?, ?, ?, 'active', ?, ?, ?, ?)
  `);
  const updateCertificateRenewStmt = db.prepare(`
    UPDATE relay_certificates
    SET status = 'active', expires_at = ?, revoked_at = NULL, updated_at = ?
    WHERE id = ?
  `);
  const updateCertificateRevokeStmt = db.prepare(`
    UPDATE relay_certificates
    SET status = 'revoked', revoked_at = ?, updated_at = ?
    WHERE id = ?
  `);
  const activeCertificateForRelayStmt = db.prepare(`
    SELECT *
    FROM relay_certificates
    WHERE relay_id = ? AND status = 'active' AND expires_at > ?
    ORDER BY issued_at DESC, id DESC
    LIMIT 1
  `);
  const insertFederationSessionStmt = db.prepare(`
    INSERT INTO federation_sessions (
      session_id,
      relay_id,
      peers_json,
      requested_gids_json,
      permissions_json,
      limits_json,
      issued_at,
      expires_at,
      policy_version,
      registry_signature,
      revoked_at,
      created_at,
      updated_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
  `);
  const getFederationSessionStmt = db.prepare(`
    SELECT * FROM federation_sessions WHERE session_id = ?
  `);
  const listFederationSessionsStmt = db.prepare(`
    SELECT *
    FROM federation_sessions
    ORDER BY issued_at DESC, session_id DESC
    LIMIT ?
  `);
  const listRevokedFederationSessionsStmt = db.prepare(`
    SELECT *
    FROM federation_sessions
    WHERE revoked_at IS NOT NULL
    ORDER BY revoked_at DESC, session_id DESC
    LIMIT ?
  `);
  const updateFederationSessionStmt = db.prepare(`
    UPDATE federation_sessions
    SET peers_json = ?,
        requested_gids_json = ?,
        permissions_json = ?,
        limits_json = ?,
        expires_at = ?,
        policy_version = ?,
        registry_signature = ?,
        updated_at = ?
    WHERE session_id = ? AND revoked_at IS NULL
  `);
  const revokeFederationSessionStmt = db.prepare(`
    UPDATE federation_sessions
    SET revoked_at = ?, updated_at = ?
    WHERE session_id = ? AND revoked_at IS NULL
  `);
  const cleanupClientAccessGrantsStmt = db.prepare(`
    DELETE FROM client_access_grants
    WHERE expires_at < ?
  `);
  const insertClientAccessGrantStmt = db.prepare(`
    INSERT INTO client_access_grants (
      grant_id,
      client_instance_id,
      candidate_relays_json,
      allowed_gids_json,
      permissions_json,
      limits_json,
      network_hints_json,
      issued_at,
      expires_at,
      policy_version,
      access_token,
      registry_signature,
      connection_mode,
      revoked_at,
      created_at,
      updated_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
  `);
  const getClientAccessGrantStmt = db.prepare(`
    SELECT * FROM client_access_grants WHERE grant_id = ?
  `);
  const updateClientAccessGrantStmt = db.prepare(`
    UPDATE client_access_grants
    SET client_instance_id = ?,
        candidate_relays_json = ?,
        allowed_gids_json = ?,
        permissions_json = ?,
        limits_json = ?,
        network_hints_json = ?,
        issued_at = ?,
        expires_at = ?,
        policy_version = ?,
        access_token = ?,
        registry_signature = ?,
        connection_mode = ?,
        updated_at = ?
    WHERE grant_id = ? AND revoked_at IS NULL
  `);
  const insertClientAbuseReportStmt = db.prepare(`
    INSERT INTO client_abuse_reports (
      report_id,
      grant_id,
      relay_id,
      client_instance_id,
      reason,
      reported_at,
      counters_json,
      source_ip,
      created_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);
  const getClientAbuseReportStmt = db.prepare(`
    SELECT * FROM client_abuse_reports WHERE report_id = ?
  `);
  const getRelayAbuseSummaryStmt = db.prepare(`
    SELECT
      COUNT(*) AS report_count,
      COUNT(DISTINCT client_instance_id) AS distinct_clients,
      MAX(created_at) AS last_report_at
    FROM client_abuse_reports
    WHERE relay_id = ? AND created_at >= ?
  `);
  const listRelayAbuseReportsStmt = db.prepare(`
    SELECT *
    FROM client_abuse_reports
    WHERE relay_id = ? AND created_at >= ?
    ORDER BY created_at DESC, report_id DESC
  `);
  const getClientRelayLatestAbuseReportStmt = db.prepare(`
    SELECT *
    FROM client_abuse_reports
    WHERE client_instance_id = ? AND relay_id = ? AND created_at >= ?
    ORDER BY created_at DESC, report_id DESC
    LIMIT 1
  `);
  const findDuplicateClientAbuseReportStmt = db.prepare(`
    SELECT *
    FROM client_abuse_reports
    WHERE grant_id = ?
      AND relay_id = ?
      AND reason = ?
      AND reported_at >= ?
      AND reported_at < ?
    ORDER BY created_at DESC, report_id DESC
    LIMIT 1
  `);
  const countClientAbuseReportsForClientStmt = db.prepare(`
    SELECT COUNT(*) AS count
    FROM client_abuse_reports
    WHERE client_instance_id = ? AND created_at >= ?
  `);
  const countClientAbuseReportsForGrantStmt = db.prepare(`
    SELECT COUNT(*) AS count
    FROM client_abuse_reports
    WHERE grant_id = ? AND created_at >= ?
  `);
  const countClientAbuseReportsForSourceIpStmt = db.prepare(`
    SELECT COUNT(*) AS count
    FROM client_abuse_reports
    WHERE source_ip = ? AND created_at >= ?
  `);
  const insertClientModerationReportStmt = db.prepare(`
    INSERT INTO client_moderation_reports (
      report_id,
      group_id,
      reporter_peer_id,
      reported_peer_id,
      target_type,
      message_id,
      event_id,
      reason_code,
      user_note,
      include_plaintext,
      plaintext_excerpt,
      ciphertext_ref,
      client_created_at_ms,
      app_version,
      protocol_version,
      status,
      duplicate_of_report_id,
      source_ip,
      assigned_operator,
      received_at,
      updated_at,
      resolved_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);
  const getClientModerationReportStmt = db.prepare(`
    SELECT * FROM client_moderation_reports WHERE report_id = ?
  `);
  const insertClientFeedbackStmt = db.prepare(`
    INSERT INTO client_feedback (
      feedback_id,
      category,
      feedback_type,
      message,
      created_at,
      app_version,
      build_number,
      android_version,
      device_model,
      local_device_id,
      source,
      status,
      received_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);
  const getClientFeedbackStmt = db.prepare(`
    SELECT * FROM client_feedback WHERE feedback_id = ?
  `);
  const listClientFeedbackStmt = db.prepare(`
    SELECT *
    FROM client_feedback
    ORDER BY received_at DESC, feedback_id DESC
    LIMIT ?
  `);
  const listClientModerationReportsStmt = db.prepare(`
    SELECT *
    FROM client_moderation_reports
    ORDER BY received_at DESC, report_id DESC
    LIMIT ?
  `);
  const updateClientModerationReportStatusStmt = db.prepare(`
    UPDATE client_moderation_reports
    SET status = ?,
        assigned_operator = ?,
        updated_at = ?,
        resolved_at = ?
    WHERE report_id = ?
  `);
  const insertClientModerationReportNoteStmt = db.prepare(`
    INSERT INTO client_moderation_report_notes (
      report_id,
      operator,
      note,
      created_at
    ) VALUES (?, ?, ?, ?)
  `);
  const getClientModerationReportNoteStmt = db.prepare(`
    SELECT * FROM client_moderation_report_notes WHERE id = ?
  `);
  const listClientModerationReportNotesStmt = db.prepare(`
    SELECT *
    FROM client_moderation_report_notes
    WHERE report_id = ?
    ORDER BY created_at DESC, id DESC
  `);
  const insertClientModerationReportIpEventStmt = db.prepare(`
    INSERT INTO client_moderation_report_ip_events (
      source_ip,
      observed_at
    ) VALUES (?, ?)
  `);
  const countClientModerationReportIpEventsStmt = db.prepare(`
    SELECT COUNT(*) AS count
    FROM client_moderation_report_ip_events
    WHERE source_ip = ? AND observed_at >= ?
  `);
  const pruneClientModerationReportIpEventsStmt = db.prepare(`
    DELETE FROM client_moderation_report_ip_events
    WHERE observed_at < ?
  `);
  const getPolicyStmt = db.prepare(`
    SELECT *
    FROM registry_policy
    WHERE id = 'default'
  `);
  const insertPolicyStmt = db.prepare(`
    INSERT INTO registry_policy (
      id,
      policy_json,
      created_at,
      updated_at
    ) VALUES ('default', ?, ?, ?)
  `);
  const updatePolicyStmt = db.prepare(`
    UPDATE registry_policy
    SET policy_json = ?, updated_at = ?
    WHERE id = 'default'
  `);
  const getBetaProgramStmt = db.prepare(`
    SELECT *
    FROM registry_beta_program
    WHERE id = 'default'
  `);
  const insertBetaProgramStmt = db.prepare(`
    INSERT INTO registry_beta_program (
      id,
      config_json,
      created_at,
      updated_at
    ) VALUES ('default', ?, ?, ?)
  `);
  const updateBetaProgramStmt = db.prepare(`
    UPDATE registry_beta_program
    SET config_json = ?, updated_at = ?
    WHERE id = 'default'
  `);
  const getRevocationFeedStmt = db.prepare(`
    SELECT *
    FROM registry_revocation_feed
    WHERE id = 'default'
  `);
  const insertRevocationFeedStmt = db.prepare(`
    INSERT INTO registry_revocation_feed (
      id,
      version,
      feed_json,
      created_at,
      updated_at,
      published_at
    ) VALUES ('default', ?, ?, ?, ?, ?)
  `);
  const updateRevocationFeedStmt = db.prepare(`
    UPDATE registry_revocation_feed
    SET version = ?, feed_json = ?, updated_at = ?, published_at = ?
    WHERE id = 'default'
  `);

  function rowToRelay(row) {
    if (!row) return null;
    let capabilities = [];
    try {
      capabilities = JSON.parse(row.capabilities_json || "[]");
    } catch (_) {
      capabilities = [];
    }
    return {
      relay_id: row.relay_id,
      public_key: row.public_key,
      endpoint: row.endpoint,
      region: row.region || null,
      capabilities,
      version: row.version || null,
      trust_level: row.trust_level || "community",
      status: row.status || "active",
      federation_enabled: Boolean(row.federation_enabled),
      last_seen: row.last_seen,
      created_at: row.created_at,
      updated_at: row.updated_at,
    };
  }

  function rowToCertificate(row) {
    if (!row) return null;
    return {
      id: row.id,
      relay_id: row.relay_id,
      public_key: row.public_key,
      status: row.status,
      issued_at: row.issued_at,
      expires_at: row.expires_at,
      revoked_at: row.revoked_at || null,
      created_at: row.created_at,
      updated_at: row.updated_at,
    };
  }

  function parseJsonArray(value) {
    try {
      const parsed = JSON.parse(value || "[]");
      return Array.isArray(parsed) ? parsed : [];
    } catch (_) {
      return [];
    }
  }

  function parseJsonObject(value) {
    try {
      const parsed = JSON.parse(value || "{}");
      return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
    } catch (_) {
      return {};
    }
  }

  function defaultRegistryDeploymentMode(config = {}) {
    return config.privateRegistryMode ? "private_self_hosted" : "official_managed";
  }

  function defaultPolicy(config = {}) {
    const registryDeploymentMode = defaultRegistryDeploymentMode(config);
    return {
      registry_deployment_mode: registryDeploymentMode,
      deny_relays: [],
      suspended_relays: [],
      minimum_trust_level_for_auto_sync: registryDeploymentMode === "private_self_hosted" ? "community" : "verified",
      allowed_statuses_for_discovery: ["active"],
      minimum_trust_level_for_client_access:
        config.clientAccessMinTrustLevel || "verified",
      allowed_statuses_for_client_access: ["active"],
      client_access_grant_ttl_seconds: config.clientAccessGrantTtlSeconds || 300,
      max_candidates_per_client_access_grant: config.clientAccessMaxCandidates || 3,
      allowed_client_access_permissions: ["ingest", "fetch"],
      client_access_policy_version: config.clientAccessPolicyVersion || 1,
      prefer_same_region_for_client_access:
        typeof config.clientAccessPreferSameRegion === "boolean"
          ? config.clientAccessPreferSameRegion
          : true,
      client_abuse_report_acceptance_window_seconds:
        config.clientAbuseReportAcceptanceWindowSeconds || 900,
      client_abuse_deprioritize_window_seconds:
        config.clientAbuseDeprioritizeWindowSeconds || 3600,
      client_abuse_pair_cooldown_seconds:
        config.clientAbusePairCooldownSeconds || 1800,
      client_abuse_single_report_weight:
        Number.isFinite(Number(config.clientAbuseSingleReportWeight))
          ? Number(config.clientAbuseSingleReportWeight)
          : 1,
      client_abuse_distinct_client_bonus:
        Number.isFinite(Number(config.clientAbuseDistinctClientBonus))
          ? Number(config.clientAbuseDistinctClientBonus)
          : 2,
      client_abuse_duplicate_bucket_seconds:
        config.clientAbuseDuplicateBucketSeconds || 300,
      client_abuse_rate_limit_window_seconds:
        config.clientAbuseRateLimitWindowSeconds || 900,
      client_abuse_max_reports_per_client_window:
        config.clientAbuseMaxReportsPerClientWindow || 8,
      client_abuse_max_reports_per_ip_window:
        config.clientAbuseMaxReportsPerIpWindow || 16,
      client_abuse_max_reports_per_grant_window:
        config.clientAbuseMaxReportsPerGrantWindow || 4,
      client_abuse_corroboration_client_threshold:
        config.clientAbuseCorroborationClientThreshold || 2,
      client_abuse_corroboration_score_threshold:
        Number.isFinite(Number(config.clientAbuseCorroborationScoreThreshold))
          ? Number(config.clientAbuseCorroborationScoreThreshold)
          : 3,
      minimum_trust_level_for_federation: config.federationMinTrustLevel
        || (registryDeploymentMode === "private_self_hosted" ? "community" : "trusted"),
      federation_session_ttl_seconds: config.federationSessionTtlSeconds || 300,
      max_peers_per_session: config.federationMaxPeersPerSession || 4,
      allowed_statuses_for_federation: ["active"],
      allowed_federation_permissions: ["push", "pull_reconcile"],
      federation_policy_version: config.federationPolicyVersion || 1,
    };
  }

  function rowToPolicy(row, config = {}) {
    const fallback = defaultPolicy(config);
    if (!row) return fallback;
    const persisted = parseJsonObject(row.policy_json);
    return {
      registry_deployment_mode:
        persisted.registry_deployment_mode === "private_self_hosted"
          ? "private_self_hosted"
          : fallback.registry_deployment_mode,
      deny_relays: Array.isArray(persisted.deny_relays) ? persisted.deny_relays : fallback.deny_relays,
      suspended_relays: Array.isArray(persisted.suspended_relays) ? persisted.suspended_relays : fallback.suspended_relays,
      minimum_trust_level_for_auto_sync:
        typeof persisted.minimum_trust_level_for_auto_sync === "string"
          ? persisted.minimum_trust_level_for_auto_sync
          : fallback.minimum_trust_level_for_auto_sync,
      allowed_statuses_for_discovery:
        Array.isArray(persisted.allowed_statuses_for_discovery)
          ? persisted.allowed_statuses_for_discovery
          : fallback.allowed_statuses_for_discovery,
      minimum_trust_level_for_client_access:
        typeof persisted.minimum_trust_level_for_client_access === "string"
          ? persisted.minimum_trust_level_for_client_access
          : fallback.minimum_trust_level_for_client_access,
      allowed_statuses_for_client_access:
        Array.isArray(persisted.allowed_statuses_for_client_access)
          ? persisted.allowed_statuses_for_client_access
          : fallback.allowed_statuses_for_client_access,
      client_access_grant_ttl_seconds:
        Number.isFinite(Number(persisted.client_access_grant_ttl_seconds))
          ? Number(persisted.client_access_grant_ttl_seconds)
          : fallback.client_access_grant_ttl_seconds,
      max_candidates_per_client_access_grant:
        Number.isFinite(Number(persisted.max_candidates_per_client_access_grant))
          ? Number(persisted.max_candidates_per_client_access_grant)
          : fallback.max_candidates_per_client_access_grant,
      allowed_client_access_permissions:
        Array.isArray(persisted.allowed_client_access_permissions)
          ? persisted.allowed_client_access_permissions
          : fallback.allowed_client_access_permissions,
      client_access_policy_version:
        Number.isFinite(Number(persisted.client_access_policy_version))
          ? Number(persisted.client_access_policy_version)
          : fallback.client_access_policy_version,
      prefer_same_region_for_client_access:
        typeof persisted.prefer_same_region_for_client_access === "boolean"
          ? persisted.prefer_same_region_for_client_access
          : fallback.prefer_same_region_for_client_access,
      client_abuse_report_acceptance_window_seconds:
        Number.isFinite(Number(persisted.client_abuse_report_acceptance_window_seconds))
          ? Number(persisted.client_abuse_report_acceptance_window_seconds)
          : fallback.client_abuse_report_acceptance_window_seconds,
      client_abuse_deprioritize_window_seconds:
        Number.isFinite(Number(persisted.client_abuse_deprioritize_window_seconds))
          ? Number(persisted.client_abuse_deprioritize_window_seconds)
          : fallback.client_abuse_deprioritize_window_seconds,
      client_abuse_pair_cooldown_seconds:
        Number.isFinite(Number(persisted.client_abuse_pair_cooldown_seconds))
          ? Number(persisted.client_abuse_pair_cooldown_seconds)
          : fallback.client_abuse_pair_cooldown_seconds,
      client_abuse_single_report_weight:
        Number.isFinite(Number(persisted.client_abuse_single_report_weight))
          ? Number(persisted.client_abuse_single_report_weight)
          : fallback.client_abuse_single_report_weight,
      client_abuse_distinct_client_bonus:
        Number.isFinite(Number(persisted.client_abuse_distinct_client_bonus))
          ? Number(persisted.client_abuse_distinct_client_bonus)
          : fallback.client_abuse_distinct_client_bonus,
      client_abuse_duplicate_bucket_seconds:
        Number.isFinite(Number(persisted.client_abuse_duplicate_bucket_seconds))
          ? Number(persisted.client_abuse_duplicate_bucket_seconds)
          : fallback.client_abuse_duplicate_bucket_seconds,
      client_abuse_rate_limit_window_seconds:
        Number.isFinite(Number(persisted.client_abuse_rate_limit_window_seconds))
          ? Number(persisted.client_abuse_rate_limit_window_seconds)
          : fallback.client_abuse_rate_limit_window_seconds,
      client_abuse_max_reports_per_client_window:
        Number.isFinite(Number(persisted.client_abuse_max_reports_per_client_window))
          ? Number(persisted.client_abuse_max_reports_per_client_window)
          : fallback.client_abuse_max_reports_per_client_window,
      client_abuse_max_reports_per_ip_window:
        Number.isFinite(Number(persisted.client_abuse_max_reports_per_ip_window))
          ? Number(persisted.client_abuse_max_reports_per_ip_window)
          : fallback.client_abuse_max_reports_per_ip_window,
      client_abuse_max_reports_per_grant_window:
        Number.isFinite(Number(persisted.client_abuse_max_reports_per_grant_window))
          ? Number(persisted.client_abuse_max_reports_per_grant_window)
          : fallback.client_abuse_max_reports_per_grant_window,
      client_abuse_corroboration_client_threshold:
        Number.isFinite(Number(persisted.client_abuse_corroboration_client_threshold))
          ? Number(persisted.client_abuse_corroboration_client_threshold)
          : fallback.client_abuse_corroboration_client_threshold,
      client_abuse_corroboration_score_threshold:
        Number.isFinite(Number(persisted.client_abuse_corroboration_score_threshold))
          ? Number(persisted.client_abuse_corroboration_score_threshold)
          : fallback.client_abuse_corroboration_score_threshold,
      minimum_trust_level_for_federation:
        typeof persisted.minimum_trust_level_for_federation === "string"
          ? persisted.minimum_trust_level_for_federation
          : fallback.minimum_trust_level_for_federation,
      federation_session_ttl_seconds:
        Number.isFinite(Number(persisted.federation_session_ttl_seconds))
          ? Number(persisted.federation_session_ttl_seconds)
          : fallback.federation_session_ttl_seconds,
      max_peers_per_session:
        Number.isFinite(Number(persisted.max_peers_per_session))
          ? Number(persisted.max_peers_per_session)
          : fallback.max_peers_per_session,
      allowed_statuses_for_federation:
        Array.isArray(persisted.allowed_statuses_for_federation)
          ? persisted.allowed_statuses_for_federation
          : fallback.allowed_statuses_for_federation,
      allowed_federation_permissions:
        Array.isArray(persisted.allowed_federation_permissions)
          ? persisted.allowed_federation_permissions
          : fallback.allowed_federation_permissions,
      federation_policy_version:
        Number.isFinite(Number(persisted.federation_policy_version))
          ? Number(persisted.federation_policy_version)
          : fallback.federation_policy_version,
    };
  }

  function ensurePolicy(config = {}) {
    const existing = getPolicyStmt.get();
    if (existing) return rowToPolicy(existing, config);
    const now = Math.floor(Date.now() / 1000);
    const policy = defaultPolicy(config);
    insertPolicyStmt.run(JSON.stringify(policy), now, now);
    return policy;
  }

  function defaultBetaProgram(config = {}) {
    return normalizeBetaProgramConfig(config.betaProgram || {});
  }

  function rowToBetaProgram(row, config = {}) {
    if (!row) return defaultBetaProgram(config);
    return normalizeBetaProgramConfig(parseJsonObject(row.config_json));
  }

  function ensureBetaProgram(config = {}) {
    const existing = getBetaProgramStmt.get();
    if (existing) return rowToBetaProgram(existing, config);
    const now = Math.floor(Date.now() / 1000);
    const betaProgram = defaultBetaProgram(config);
    insertBetaProgramStmt.run(JSON.stringify(betaProgram), now, now);
    return betaProgram;
  }

  function liveRevocationSnapshot(now) {
    const relays = listRelaysStmt
      .all()
      .map(rowToRelay)
      .filter((relay) => relay.status === "revoked" || relay.status === "banned");
    return {
      generated_at: now,
      revoked_relays: relays.map((relay) => relay.relay_id),
      items: relays,
    };
  }

  function rowToRevocationFeed(row, now) {
    const fallback = liveRevocationSnapshot(now);
    if (!row) {
      return {
        version: 1,
        ...fallback,
      };
    }
    const persisted = parseJsonObject(row.feed_json);
    return {
      version: row.version,
      generated_at: Number.isFinite(Number(persisted.generated_at)) ? Number(persisted.generated_at) : row.published_at,
      revoked_relays: Array.isArray(persisted.revoked_relays) ? persisted.revoked_relays : [],
      items: Array.isArray(persisted.items) ? persisted.items : [],
    };
  }

  function ensureRevocationFeed(now) {
    const existing = getRevocationFeedStmt.get();
    if (existing) return rowToRevocationFeed(existing, now);
    const feed = liveRevocationSnapshot(now);
    insertRevocationFeedStmt.run(1, JSON.stringify(feed), now, now, now);
    return {
      version: 1,
      ...feed,
    };
  }

  function rowToFederationSession(row) {
    if (!row) return null;
    return {
      session_id: row.session_id,
      relay_id: row.relay_id,
      peers: parseJsonArray(row.peers_json),
      requested_gids: parseJsonArray(row.requested_gids_json),
      permissions: parseJsonArray(row.permissions_json),
      limits: parseJsonObject(row.limits_json),
      issued_at: row.issued_at,
      expires_at: row.expires_at,
      policy_version: row.policy_version,
      registry_signature: row.registry_signature,
      revoked_at: row.revoked_at || null,
      created_at: row.created_at,
      updated_at: row.updated_at,
    };
  }

  function rowToClientAccessGrant(row) {
    if (!row) return null;
    return {
      grant_id: row.grant_id,
      client_instance_id: row.client_instance_id,
      candidate_relays: parseJsonArray(row.candidate_relays_json),
      allowed_gids: parseJsonArray(row.allowed_gids_json),
      permissions: parseJsonArray(row.permissions_json),
      limits: parseJsonObject(row.limits_json),
      network_hints: parseJsonObject(row.network_hints_json),
      issued_at: row.issued_at,
      expires_at: row.expires_at,
      policy_version: row.policy_version,
      access_token: row.access_token,
      registry_signature: row.registry_signature,
      connection_mode: row.connection_mode,
      revoked_at: row.revoked_at || null,
      created_at: row.created_at,
      updated_at: row.updated_at,
    };
  }

  function rowToClientAbuseReport(row) {
    if (!row) return null;
    return {
      report_id: row.report_id,
      grant_id: row.grant_id,
      relay_id: row.relay_id,
      client_instance_id: row.client_instance_id,
      reason: row.reason,
      reported_at: row.reported_at,
      counters: parseJsonObject(row.counters_json),
      source_ip: row.source_ip || null,
      created_at: row.created_at,
    };
  }

  function rowToClientModerationReport(row) {
    if (!row) return null;
    return {
      report_id: row.report_id,
      group_id: row.group_id,
      reporter_peer_id: row.reporter_peer_id,
      reported_peer_id: row.reported_peer_id,
      target_type: row.target_type,
      message_id: row.message_id || null,
      event_id: row.event_id || null,
      reason_code: row.reason_code,
      user_note: row.user_note || null,
      include_plaintext: Boolean(row.include_plaintext),
      plaintext_excerpt: row.plaintext_excerpt || null,
      ciphertext_ref: row.ciphertext_ref || null,
      client_created_at_ms: row.client_created_at_ms,
      app_version: row.app_version || null,
      protocol_version: row.protocol_version,
      status: row.status,
      duplicate_of_report_id: row.duplicate_of_report_id || null,
      assigned_operator: row.assigned_operator || null,
      received_at: row.received_at,
      updated_at: row.updated_at,
      resolved_at: row.resolved_at || null,
    };
  }

  function rowToClientModerationReportNote(row) {
    if (!row) return null;
    return {
      id: row.id,
      report_id: row.report_id,
      operator: row.operator,
      note: row.note,
      created_at: row.created_at,
    };
  }

  function rowToClientFeedback(row) {
    if (!row) return null;
    return {
      feedback_id: row.feedback_id,
      category: row.category,
      feedback_type: row.feedback_type || "user_feedback",
      message: row.message,
      created_at: row.created_at,
      app_version: row.app_version || null,
      build_number: row.build_number || null,
      android_version: row.android_version || null,
      device_model: row.device_model || null,
      local_device_id: row.local_device_id || null,
      source: row.source,
      status: row.status,
      received_at: row.received_at,
    };
  }

  return {
    upsertRelay(relay, now) {
      const capabilities = Array.isArray(relay.capabilities) ? relay.capabilities : [];
      upsertRelayStmt.run({
        relay_id: relay.relay_id,
        public_key: relay.public_key,
        endpoint: relay.endpoint,
        region: relay.region || null,
        capabilities_json: JSON.stringify(capabilities),
        version: relay.version || null,
        now,
      });
      return rowToRelay(getRelayStmt.get(relay.relay_id));
    },

    listActiveRelays(now, ttlSeconds) {
      return listActiveRelaysStmt.all(now - ttlSeconds).map(rowToRelay);
    },

    listRelays() {
      return listRelaysStmt.all().map(rowToRelay);
    },

    getRelay(relayId) {
      return rowToRelay(getRelayStmt.get(relayId));
    },

    relayDetails(relayId) {
      const relay = rowToRelay(getRelayStmt.get(relayId));
      if (!relay) return null;
      return {
        relay,
        certificate: rowToCertificate(latestCertificateForRelayStmt.get(relayId)),
        audit: listAuditStmt
          .all(250)
          .filter((entry) => entry.resource_type === "relay" && entry.resource_id === relayId),
      };
    },

    updateRelayTrust(relayId, trustLevel, now) {
      updateRelayTrustStmt.run(trustLevel, now, relayId);
      return rowToRelay(getRelayStmt.get(relayId));
    },

    updateRelayStatus(relayId, status, now) {
      updateRelayStatusStmt.run(status, now, relayId);
      return rowToRelay(getRelayStmt.get(relayId));
    },

    updateRelayFederation(relayId, enabled, now) {
      updateRelayFederationStmt.run(enabled ? 1 : 0, now, relayId);
      return rowToRelay(getRelayStmt.get(relayId));
    },

    dashboard(now, ttlSeconds) {
      const relays = listRelaysStmt.all().map(rowToRelay);
      const activeCutoff = now - ttlSeconds;
      const recentCutoff = now - 60;
      const certificates = listCertificatesStmt.all().map(rowToCertificate);
      const auditEvents = listAuditStmt.all(500);
      const federationSessions = listFederationSessionsStmt.all(500).map(rowToFederationSession);
      const revocationFeed = getRevocationFeedStmt.get();
      const counts = {
        total_relays: relays.length,
        active_relays: relays.filter((relay) => relay.status === "active" && relay.last_seen >= activeCutoff).length,
        community_relays: relays.filter((relay) => relay.trust_level === "community").length,
        verified_relays: relays.filter((relay) => relay.trust_level === "verified").length,
        trusted_relays: relays.filter((relay) => relay.trust_level === "trusted").length,
        suspended_relays: relays.filter((relay) => relay.status === "suspended").length,
        revoked_relays: relays.filter((relay) => relay.status === "revoked").length,
        banned_relays: relays.filter((relay) => relay.status === "banned").length,
        federation_enabled_relays: relays.filter((relay) => relay.federation_enabled).length,
        heartbeat_failures: 0,
        heartbeats_per_minute: relays.filter((relay) => relay.last_seen >= recentCutoff).length,
        discovery_requests_per_minute: 0,
        certificate_issues: auditEvents.filter((entry) => entry.action === "certificate_issued").length,
        certificate_revocations: auditEvents.filter((entry) => entry.action === "certificate_revoked").length,
        certificates_active: certificates.filter((certificate) => certificate.status === "active").length,
        federation_session_issues: auditEvents.filter((entry) => entry.action === "federation_session_issued").length,
        federation_session_denials: auditEvents.filter((entry) => entry.action === "federation_session_denied").length,
        federation_sessions_active: federationSessions.filter((session) => !session.revoked_at && session.expires_at > now).length,
        federation_sessions_expired: federationSessions.filter((session) => !session.revoked_at && session.expires_at <= now).length,
        federation_sessions_revoked: federationSessions.filter((session) => !!session.revoked_at).length,
        policy_updates: auditEvents.filter((entry) => entry.action === "policy_updated").length,
        audit_events: auditEvents.length,
      };
      return {
        counts,
        relays,
        health: {
          status: "ok",
          last_policy_publish: auditEvents.find((entry) => entry.action === "policy_updated")?.created_at || null,
          last_revocation_feed_publish: revocationFeed ? revocationFeed.published_at : null,
        },
      };
    },

    recordAudit({ actor, action, resourceType, resourceId, result, reason, now }) {
      insertAuditStmt.run(
        actor || "admin",
        action,
        resourceType,
        resourceId || null,
        result || "success",
        reason || null,
        now
      );
    },

    listAudit(limit = 100) {
      return listAuditStmt.all(Math.min(Math.max(Number(limit) || 100, 1), 5000));
    },

    revocations(now) {
      return ensureRevocationFeed(now);
    },

    rebuildRevocations(now) {
      const existing = getRevocationFeedStmt.get();
      const nextVersion = existing ? Number(existing.version || 0) + 1 : 1;
      const feed = liveRevocationSnapshot(now);
      if (existing) updateRevocationFeedStmt.run(nextVersion, JSON.stringify(feed), now, now);
      else insertRevocationFeedStmt.run(nextVersion, JSON.stringify(feed), now, now, now);
      return {
        version: nextVersion,
        ...feed,
      };
    },

    policy(config = {}) {
      return ensurePolicy(config);
    },

    updatePolicy(nextPolicy, now, config = {}) {
      const merged = rowToPolicy(
        {
          policy_json: JSON.stringify(nextPolicy),
        },
        config
      );
      const existing = getPolicyStmt.get();
      if (existing) updatePolicyStmt.run(JSON.stringify(merged), now);
      else insertPolicyStmt.run(JSON.stringify(merged), now, now);
      return merged;
    },

    betaProgram(config = {}) {
      return ensureBetaProgram(config);
    },

    updateBetaProgram(nextConfig, now, config = {}) {
      const merged = normalizeBetaProgramConfig(nextConfig);
      const existing = getBetaProgramStmt.get();
      if (existing) updateBetaProgramStmt.run(JSON.stringify(merged), now);
      else insertBetaProgramStmt.run(JSON.stringify(merged), now, now);
      return merged;
    },

    listCertificates() {
      return listCertificatesStmt.all().map(rowToCertificate);
    },

    issueCertificate({ id, relayId, now, expiresAt }) {
      const relay = rowToRelay(getRelayStmt.get(relayId));
      if (!relay) return null;
      insertCertificateStmt.run(id, relayId, relay.public_key, now, expiresAt, now, now);
      return rowToCertificate(getCertificateStmt.get(id));
    },

    renewCertificate(id, now, expiresAt) {
      updateCertificateRenewStmt.run(expiresAt, now, id);
      return rowToCertificate(getCertificateStmt.get(id));
    },

    revokeCertificate(id, now) {
      updateCertificateRevokeStmt.run(now, now, id);
      return rowToCertificate(getCertificateStmt.get(id));
    },

    activeCertificateForRelay(relayId, now) {
      return rowToCertificate(activeCertificateForRelayStmt.get(relayId, now));
    },

    insertFederationSession(session) {
      insertFederationSessionStmt.run(
        session.session_id,
        session.relay_id,
        JSON.stringify(session.peers || []),
        JSON.stringify(session.requested_gids || []),
        JSON.stringify(session.permissions || []),
        JSON.stringify(session.limits || {}),
        session.issued_at,
        session.expires_at,
        session.policy_version,
        session.registry_signature,
        session.created_at || session.issued_at,
        session.updated_at || session.issued_at
      );
      return rowToFederationSession(getFederationSessionStmt.get(session.session_id));
    },

    getFederationSession(sessionId) {
      return rowToFederationSession(getFederationSessionStmt.get(sessionId));
    },

    listFederationSessions(limit = 100) {
      return listFederationSessionsStmt
        .all(Math.min(Math.max(Number(limit) || 100, 1), 500))
        .map(rowToFederationSession);
    },

    listRevokedFederationSessions(limit = 500) {
      return listRevokedFederationSessionsStmt
        .all(Math.min(Math.max(Number(limit) || 500, 1), 5000))
        .map(rowToFederationSession);
    },

    updateFederationSession(session) {
      updateFederationSessionStmt.run(
        JSON.stringify(session.peers || []),
        JSON.stringify(session.requested_gids || []),
        JSON.stringify(session.permissions || []),
        JSON.stringify(session.limits || {}),
        session.expires_at,
        session.policy_version,
        session.registry_signature,
        session.updated_at,
        session.session_id
      );
      return rowToFederationSession(getFederationSessionStmt.get(session.session_id));
    },

    revokeFederationSession(sessionId, now) {
      revokeFederationSessionStmt.run(now, now, sessionId);
      return rowToFederationSession(getFederationSessionStmt.get(sessionId));
    },

    insertClientAccessGrant(grant) {
      insertClientAccessGrantStmt.run(
        grant.grant_id,
        grant.client_instance_id,
        JSON.stringify(grant.candidate_relays || []),
        JSON.stringify(grant.allowed_gids || []),
        JSON.stringify(grant.permissions || []),
        JSON.stringify(grant.limits || {}),
        JSON.stringify(grant.network_hints || {}),
        grant.issued_at,
        grant.expires_at,
        grant.policy_version,
        grant.access_token,
        grant.registry_signature,
        grant.connection_mode,
        grant.created_at || grant.issued_at,
        grant.updated_at || grant.issued_at
      );
      return rowToClientAccessGrant(getClientAccessGrantStmt.get(grant.grant_id));
    },

    getClientAccessGrant(grantId) {
      return rowToClientAccessGrant(getClientAccessGrantStmt.get(grantId));
    },

    updateClientAccessGrant(grant) {
      updateClientAccessGrantStmt.run(
        grant.client_instance_id,
        JSON.stringify(grant.candidate_relays || []),
        JSON.stringify(grant.allowed_gids || []),
        JSON.stringify(grant.permissions || []),
        JSON.stringify(grant.limits || {}),
        JSON.stringify(grant.network_hints || {}),
        grant.issued_at,
        grant.expires_at,
        grant.policy_version,
        grant.access_token,
        grant.registry_signature,
        grant.connection_mode,
        grant.updated_at || grant.issued_at,
        grant.grant_id
      );
      return rowToClientAccessGrant(getClientAccessGrantStmt.get(grant.grant_id));
    },

    insertClientAbuseReport(report) {
      insertClientAbuseReportStmt.run(
        report.report_id,
        report.grant_id,
        report.relay_id,
        report.client_instance_id,
        report.reason,
        report.reported_at,
        JSON.stringify(report.counters || {}),
        report.source_ip || null,
        report.created_at
      );
      return rowToClientAbuseReport(getClientAbuseReportStmt.get(report.report_id));
    },

    getRelayAbuseSummary(relayId, since) {
      const row = getRelayAbuseSummaryStmt.get(relayId, since);
      return {
        relay_id: relayId,
        report_count: Number(row?.report_count || 0),
        distinct_clients: Number(row?.distinct_clients || 0),
        last_report_at: row?.last_report_at ? Number(row.last_report_at) : null,
      };
    },

    listRelayAbuseReports(relayId, since) {
      return listRelayAbuseReportsStmt.all(relayId, since).map(rowToClientAbuseReport);
    },

    getClientRelayLatestAbuseReport(clientInstanceId, relayId, since) {
      return rowToClientAbuseReport(
        getClientRelayLatestAbuseReportStmt.get(clientInstanceId, relayId, since)
      );
    },

    findDuplicateClientAbuseReport({ grantId, relayId, reason, bucketStart, bucketEnd }) {
      return rowToClientAbuseReport(
        findDuplicateClientAbuseReportStmt.get(grantId, relayId, reason, bucketStart, bucketEnd)
      );
    },

    countClientAbuseReportsForClient(clientInstanceId, since) {
      return Number(countClientAbuseReportsForClientStmt.get(clientInstanceId, since)?.count || 0);
    },

    countClientAbuseReportsForGrant(grantId, since) {
      return Number(countClientAbuseReportsForGrantStmt.get(grantId, since)?.count || 0);
    },

    countClientAbuseReportsForSourceIp(sourceIp, since) {
      if (!sourceIp) return 0;
      return Number(countClientAbuseReportsForSourceIpStmt.get(sourceIp, since)?.count || 0);
    },

    insertClientFeedback(feedback) {
      insertClientFeedbackStmt.run(
        feedback.feedback_id,
        feedback.category,
        feedback.feedback_type,
        feedback.message,
        feedback.created_at,
        feedback.app_version,
        feedback.build_number,
        feedback.android_version,
        feedback.device_model,
        feedback.local_device_id,
        feedback.source,
        feedback.status,
        feedback.received_at
      );
      return rowToClientFeedback(getClientFeedbackStmt.get(feedback.feedback_id));
    },

    getClientFeedback(feedbackId) {
      return rowToClientFeedback(getClientFeedbackStmt.get(feedbackId));
    },

    listClientFeedback(limit = 100) {
      return listClientFeedbackStmt.all(limit).map(rowToClientFeedback);
    },

    insertClientModerationReport(report) {
      insertClientModerationReportStmt.run(
        report.report_id,
        report.group_id,
        report.reporter_peer_id,
        report.reported_peer_id,
        report.target_type,
        report.message_id,
        report.event_id,
        report.reason_code,
        report.user_note,
        report.include_plaintext ? 1 : 0,
        report.plaintext_excerpt,
        report.ciphertext_ref,
        report.client_created_at_ms,
        report.app_version,
        report.protocol_version,
        report.status,
        report.duplicate_of_report_id,
        null,
        report.assigned_operator,
        report.received_at,
        report.updated_at,
        report.resolved_at
      );
      return rowToClientModerationReport(getClientModerationReportStmt.get(report.report_id));
    },

    insertClientModerationReportIpEvent(sourceIp, observedAt) {
      if (!sourceIp) return;
      insertClientModerationReportIpEventStmt.run(sourceIp, observedAt);
    },

    countClientModerationReportIpEvents(sourceIp, since) {
      if (!sourceIp) return 0;
      return Number(countClientModerationReportIpEventsStmt.get(sourceIp, since)?.count || 0);
    },

    pruneClientModerationReportIpEvents(before) {
      pruneClientModerationReportIpEventsStmt.run(before);
    },

    getClientModerationReport(reportId) {
      return rowToClientModerationReport(getClientModerationReportStmt.get(reportId));
    },

    listClientModerationReports(limit = 100) {
      return listClientModerationReportsStmt
        .all(Math.min(Math.max(Number(limit) || 100, 1), 500))
        .map(rowToClientModerationReport);
    },

    updateClientModerationReportStatus(reportId, { status, assignedOperator, updatedAt, resolvedAt }) {
      updateClientModerationReportStatusStmt.run(
        status,
        assignedOperator || null,
        updatedAt,
        resolvedAt || null,
        reportId
      );
      return rowToClientModerationReport(getClientModerationReportStmt.get(reportId));
    },

    insertClientModerationReportNote(note) {
      const result = insertClientModerationReportNoteStmt.run(
        note.report_id,
        note.operator,
        note.note,
        note.created_at
      );
      return rowToClientModerationReportNote(getClientModerationReportNoteStmt.get(result.lastInsertRowid));
    },

    listClientModerationReportNotes(reportId) {
      return listClientModerationReportNotesStmt.all(reportId).map(rowToClientModerationReportNote);
    },

    rememberNonce(nonce, now, ttlSeconds) {
      try {
        insertNonceStmt.run(nonce, now, now + ttlSeconds);
        return true;
      } catch (err) {
        if (err && err.code === "SQLITE_CONSTRAINT_PRIMARYKEY") return false;
        throw err;
      }
    },

    cleanup(now, relayMaxAgeSeconds, nonceTtlSeconds) {
      cleanupRelaysStmt.run(now - relayMaxAgeSeconds);
      cleanupNoncesStmt.run(now - nonceTtlSeconds);
      cleanupClientAccessGrantsStmt.run(now);
      pruneClientModerationReportIpEventsStmt.run(
        now - CLIENT_MODERATION_REPORT_IP_RETENTION_SECONDS
      );
    },
  };
}

module.exports = { openDatabase, createRegistryStore };
