const { createHash } = require("crypto");

const HISTORY_LIMIT = 50;
const FAILURE_WINDOW_MS = 10 * 60 * 1000;

function normalizeUrl(raw) {
  return raw.replace(/\/+$/, "");
}

function peerIdFromUrl(peerUrl) {
  try {
    const parsed = new URL(peerUrl);
    if (parsed.host) return parsed.host;
  } catch (err) {
    // Fall through to hash.
  }
  return createHash("sha1").update(peerUrl).digest("hex").slice(0, 12);
}

function nowIso() {
  return new Date().toISOString();
}

function createFederationAdminState({ peers = [] } = {}) {
  const entriesByUrl = new Map();
  const entriesById = new Map();

  function ensurePeer(peerUrl) {
    const normalized = normalizeUrl(peerUrl);
    const existing = entriesByUrl.get(normalized);
    if (existing) return existing;
    const id = peerIdFromUrl(normalized);
    const entry = {
      id,
      peer_url: normalized,
      last_sync_at: null,
      last_success_at: null,
      last_error: "",
      cursor_lag_seconds: null,
      backpressure_queue: 0,
      in_flight: 0,
      remote_connected: null,
      remote_capacity: null,
      remote_heartbeat_ttl_seconds: null,
      remote_stats_at: null,
      history: [],
    };
    entriesByUrl.set(normalized, entry);
    entriesById.set(id, entry);
    return entry;
  }

  function recordHistory(entry, result, lagSeconds, error) {
    const ts = nowIso();
    entry.last_sync_at = ts;
    if (result === "ok") {
      entry.last_success_at = ts;
      entry.last_error = "";
    }
    if (result === "fail") {
      entry.last_error = error || "";
    }
    if (Number.isFinite(lagSeconds)) {
      entry.cursor_lag_seconds = lagSeconds;
    }
    entry.history.unshift({
      ts,
      result,
      lag_seconds: Number.isFinite(lagSeconds) ? lagSeconds : null,
      error: error || "",
    });
    if (entry.history.length > HISTORY_LIMIT) {
      entry.history.length = HISTORY_LIMIT;
    }
  }

  function recordSuccess({ peerUrl, lagSeconds }) {
    const entry = ensurePeer(peerUrl);
    recordHistory(entry, "ok", lagSeconds, "");
  }

  function recordFailure({ peerUrl, error }) {
    const entry = ensurePeer(peerUrl);
    recordHistory(entry, "fail", entry.cursor_lag_seconds, error);
  }

  function updateBackpressure({ peerUrl, inFlight, queue }) {
    const entry = ensurePeer(peerUrl);
    entry.in_flight = Number.isFinite(inFlight) ? inFlight : entry.in_flight;
    entry.backpressure_queue = Number.isFinite(queue) ? queue : entry.backpressure_queue;
  }

  function updateRemoteStats({ peerUrl, stats }) {
    const entry = ensurePeer(peerUrl);
    if (!stats) {
      entry.remote_connected = null;
      entry.remote_capacity = null;
      entry.remote_heartbeat_ttl_seconds = null;
      entry.remote_stats_at = nowIso();
      return;
    }
    entry.remote_connected = Number.isFinite(stats.connected) ? stats.connected : null;
    entry.remote_capacity = Number.isFinite(stats.capacity_limit) ? stats.capacity_limit : null;
    entry.remote_heartbeat_ttl_seconds = Number.isFinite(stats.heartbeat_ttl_seconds)
      ? stats.heartbeat_ttl_seconds
      : null;
    entry.remote_stats_at = nowIso();
  }

  function failuresInWindow(entry, nowMs) {
    const cutoff = nowMs - FAILURE_WINDOW_MS;
    let count = 0;
    for (const item of entry.history) {
      if (item.result !== "fail") continue;
      const ts = Date.parse(item.ts);
      if (!Number.isFinite(ts)) continue;
      if (ts >= cutoff) count += 1;
    }
    return count;
  }

  function statusFor(entry, failures10m) {
    if (failures10m > 0) return "degraded";
    if (Number.isFinite(entry.cursor_lag_seconds) && entry.cursor_lag_seconds > 60) {
      return "degraded";
    }
    return "ok";
  }

  function listPeers() {
    const nowMs = Date.now();
    return Array.from(entriesByUrl.values()).map((entry) => {
      const failures10m = failuresInWindow(entry, nowMs);
      return {
        id: entry.id,
        peer_url: entry.peer_url,
        last_sync_at: entry.last_sync_at,
        cursor_lag_seconds: entry.cursor_lag_seconds,
        failures_10m: failures10m,
        backpressure_queue: entry.backpressure_queue,
        last_error: entry.last_error,
        status: statusFor(entry, failures10m),
        remote_connected: entry.remote_connected,
        remote_capacity: entry.remote_capacity,
        remote_heartbeat_ttl_seconds: entry.remote_heartbeat_ttl_seconds,
      };
    });
  }

  function getPeer(id) {
    const entry = entriesById.get(id);
    if (!entry) return null;
    const failures10m = failuresInWindow(entry, Date.now());
    return {
      id: entry.id,
      peer_url: entry.peer_url,
      status: statusFor(entry, failures10m),
      last_sync_at: entry.last_sync_at,
      last_success_at: entry.last_success_at,
      last_error: entry.last_error,
      cursor_lag_seconds: entry.cursor_lag_seconds,
      backpressure_queue: entry.backpressure_queue,
      failures_10m: failures10m,
      remote_connected: entry.remote_connected,
      remote_capacity: entry.remote_capacity,
      remote_heartbeat_ttl_seconds: entry.remote_heartbeat_ttl_seconds,
    };
  }

  function getHistory(id) {
    const entry = entriesById.get(id);
    if (!entry) return null;
    return entry.history.map((item) => ({ ...item }));
  }

  peers.forEach((peer) => {
    if (peer) ensurePeer(peer);
  });

  return {
    ensurePeer,
    recordSuccess,
    recordFailure,
    updateBackpressure,
    updateRemoteStats,
    listPeers,
    getPeer,
    getHistory,
  };
}

module.exports = { createFederationAdminState };
