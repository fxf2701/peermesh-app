const loginForm = document.getElementById("login-form");
const loginStatus = document.getElementById("login-status");
const logoutBtn = document.getElementById("logout-btn");
const sessionStatus = document.getElementById("session-status");
const ipAllowlistBanner = document.getElementById("ip-allowlist-banner");
const accountUsername = document.getElementById("account-username");
const accountChangePassword = document.getElementById("account-change-password");
const accountLogout = document.getElementById("account-logout");
const accountStatus = document.getElementById("account-status");
const sessionCard = document.getElementById("session-card");
const menuToggle = document.getElementById("menu-toggle");
const menu = document.getElementById("menu");
const footerSession = document.getElementById("footer-session");
const authGuestItems = document.querySelectorAll(".auth-guest");
const authUserItems = document.querySelectorAll(".auth-user");
const banlistForm = document.getElementById("banlist-form");
const banlistTable = document.getElementById("banlist-table");
const banlistStatus = document.getElementById("banlist-status");
const banlistSearch = document.getElementById("banlist-search");
const banlistFilter = document.getElementById("banlist-filter");
const banlistExportCsv = document.getElementById("banlist-export-csv");
const banlistImportFile = document.getElementById("banlist-import-file");
const banlistImportBtn = document.getElementById("banlist-import-btn");
const peersTable = document.getElementById("peers-table");
const peersStatus = document.getElementById("peers-status");
const peersSearch = document.getElementById("peers-search");
const peersTotal = document.getElementById("peers-total");
const peersAvgLag = document.getElementById("peers-avg-lag");
const peersFailures = document.getElementById("peers-failures");
const peersConnected = document.getElementById("peers-connected");
const peersCapacity = document.getElementById("peers-capacity");
const peersHeartbeat = document.getElementById("peers-heartbeat");
const peersBoundsStatus = document.getElementById("peers-bounds-status");
const metricsDetailTable = document.getElementById("metrics-detail-table");
const metricsDetailStatus = document.getElementById("metrics-detail-status");
const metricsDetailUpdated = document.getElementById("metrics-detail-updated");
const metricsDetailRefresh = document.getElementById("metrics-detail-refresh");
const metricsDetailButtons = document.querySelectorAll("[data-metrics-domain]");
const dashboardMetricsCard = document.getElementById("dashboard-metrics-card");
const dashboardMetricsStatus = document.getElementById("dashboard-metrics-status");
const dashboardMetricsUpdated = document.getElementById("dashboard-metrics-updated");
const metricsHealth = document.getElementById("metrics-health");
const metricsReady = document.getElementById("metrics-ready");
const metricsRequestsRate = document.getElementById("metrics-requests-rate");
const metricsErrorRate = document.getElementById("metrics-error-rate");
const metricsDbPool = document.getElementById("metrics-db-pool");
const metricsFederationLag = document.getElementById("metrics-federation-lag");
const metricsRateSpike = document.getElementById("metrics-rate-spike");
const backupStatusCard = document.getElementById("backup-status-card");
const backupLastTime = document.getElementById("backup-last-time");
const backupAge = document.getElementById("backup-age");
const backupLastStatus = document.getElementById("backup-last-status");
const backupRestoreDrill = document.getElementById("backup-restore-drill");
const backupStatusMessage = document.getElementById("backup-status-message");
const retentionStatusCard = document.getElementById("retention-status-card");
const retentionProfile = document.getElementById("retention-profile");
const retentionInterval = document.getElementById("retention-interval");
const retentionLastCleanup = document.getElementById("retention-last-cleanup");
const retentionLastDuration = document.getElementById("retention-last-duration");
const retentionNextRun = document.getElementById("retention-next-run");
const retentionStatusMessage = document.getElementById("retention-status-message");
const alertsStatusCard = document.getElementById("alerts-status-card");
const alertsTable = document.getElementById("alerts-table");
const incidentsTable = document.getElementById("incidents-table");
const alertsStatusMessage = document.getElementById("alerts-status-message");
const settingsTable = document.getElementById("settings-table");
const settingsStatus = document.getElementById("settings-status");
const settingsRefresh = document.getElementById("settings-refresh");
const settingsLogPaths = document.getElementById("settings-log-paths");
const settingsAutoConnect = document.getElementById("settings-auto-connect");
const settingsDiscoveryStatus = document.getElementById("settings-discovery-status");
const peerJoinForm = document.getElementById("peer-join-form");
const peerJoinStatus = document.getElementById("peer-join-status");
const peerJoinSubmit = peerJoinForm
  ? peerJoinForm.querySelector("button[type=\"submit\"]")
  : null;
const peerMappingsTable = document.getElementById("peer-mappings-table");
const peerPreviewUrl = document.getElementById("peer-preview-url");
const peerPreviewGids = document.getElementById("peer-preview-gids");
const peerPreviewToken = document.getElementById("peer-preview-token");
const peerPreviewNote = document.getElementById("peer-preview-note");
const selfPeerId = document.getElementById("self-peer-id");
const selfPeerUrl = document.getElementById("self-peer-url");
const selfPublicToken = document.getElementById("self-public-token");
const selfPublicTokenCreated = document.getElementById("self-public-token-created");
const selfTokenCreate = document.getElementById("self-token-create");
const selfTokenCopy = document.getElementById("self-token-copy");
const selfTokenReplace = document.getElementById("self-token-replace");
const selfTokenRevoke = document.getElementById("self-token-revoke");
const selfTokenStatus = document.getElementById("self-token-status");
const selfTokenEdit = document.getElementById("self-token-edit");
const selfTokenInput = document.getElementById("self-token-input");
const selfTokenGenerate = document.getElementById("self-token-generate");
const selfTokenSave = document.getElementById("self-token-save");
const selfTokenCancel = document.getElementById("self-token-cancel");
let selfTokenMode = null;
let selfTokenValue = "";
let selfTokenCreatedAt = "";
const reputationForm = document.getElementById("reputation-form");
const reputationTable = document.getElementById("reputation-table");
const reputationStatus = document.getElementById("reputation-status");
const reputationSearch = document.getElementById("reputation-search");
const reputationExportCsv = document.getElementById("reputation-export-csv");
const reputationImportFile = document.getElementById("reputation-import-file");
const reputationImportBtn = document.getElementById("reputation-import-btn");
const auditTable = document.getElementById("audit-table");
const auditStatus = document.getElementById("audit-status");
const auditScope = document.getElementById("audit-scope");
const auditSince = document.getElementById("audit-since");
const auditLimit = document.getElementById("audit-limit");
const auditSearch = document.getElementById("audit-search");
const auditRefresh = document.getElementById("audit-refresh");
const peerTitle = document.getElementById("peer-title");
const peerSubtitle = document.getElementById("peer-subtitle");
const peerStatus = document.getElementById("peer-status");
const peerLag = document.getElementById("peer-lag");
const peerBackpressure = document.getElementById("peer-backpressure");
const peerUrl = document.getElementById("peer-url");
const peerRemoteConnected = document.getElementById("peer-remote-connected");
const peerRemoteCapacity = document.getElementById("peer-remote-capacity");
const peerRemoteTtl = document.getElementById("peer-remote-ttl");
const peerLastSync = document.getElementById("peer-last-sync");
const peerLastSuccess = document.getElementById("peer-last-success");
const peerLastError = document.getElementById("peer-last-error");
const peerAutoSync = document.getElementById("peer-auto-sync");
const peerSyncGids = document.getElementById("peer-sync-gids");
const peerLastSeen = document.getElementById("peer-last-seen");
const peerOnlineStatus = document.getElementById("peer-online-status");
const peerHistoryTable = document.getElementById("peer-history-table");
const peerDetailStatus = document.getElementById("peer-detail-status");
const peerResync = document.getElementById("peer-resync");
const peerClearBackoff = document.getElementById("peer-clear-backoff");
const peerRefresh = document.getElementById("peer-refresh");
const peerControlStatus = document.getElementById("peer-control-status");
const peerHistoryChart = document.getElementById("peer-history-chart");
const peerHistoryEmpty = document.getElementById("peer-history-empty");
const peerHistoryLagMissing = document.getElementById("peer-history-lag-missing");
const inboundPeersTable = document.getElementById("inbound-peers-table");
const inboundPeersStatus = document.getElementById("inbound-peers-status");
let banlistItems = [];
let peersItems = [];
let livePeersItems = [];
let peersStats = {
  connected: 0,
  capacity_limit: 0,
  heartbeat_ttl_seconds: 0,
  min_peers: 0,
  max_peers: 0,
};
let inboundPeers = [];
let peerMappingsItems = [];
let reputationItems = [];
let auditItems = [];
const PEERS_POLL_FAST_MS = 10000;
const PEERS_POLL_SLOW_MS = 30000;
const METRICS_DETAIL_POLL_MS = 30000;
const DASHBOARD_METRICS_POLL_MS = 30000;
const BACKUP_STATUS_POLL_MS = 60000;
const RETENTION_STATUS_POLL_MS = 60000;
const ALERTS_STATUS_POLL_MS = 60000;
let peersFastTimer = null;
let peersSlowTimer = null;
let peersFastInFlight = false;
let peersSlowInFlight = false;
let metricsDetailTimer = null;
let metricsDetailInFlight = false;
let metricsDetailDomain = "traffic";
let dashboardMetricsTimer = null;
let dashboardMetricsInFlight = false;
let backupStatusTimer = null;
let backupStatusInFlight = false;
let retentionStatusTimer = null;
let retentionStatusInFlight = false;
let alertsStatusTimer = null;
let alertsStatusInFlight = false;

function findMappingByUrl(peerUrl) {
  if (!peerUrl) return null;
  return peerMappingsItems.find((item) => item.peer_url === peerUrl) || null;
}

function findInboundByPeer(item) {
  if (!item) return null;
  return (
    inboundPeers.find((entry) => entry.peer_url && entry.peer_url === item.peer_url) ||
    inboundPeers.find((entry) => entry.self_id && entry.self_id === item.id) ||
    null
  );
}

function computeOnlineStatus(lastSeenAt) {
  if (!lastSeenAt) return "unknown";
  const ttlSeconds = Number(peersStats.heartbeat_ttl_seconds || 0);
  if (!ttlSeconds) return "unknown";
  const lastSeenMs = Date.parse(lastSeenAt);
  if (!Number.isFinite(lastSeenMs)) return "unknown";
  return Date.now() - lastSeenMs <= ttlSeconds * 1000 ? "online" : "offline";
}

function buildLivePeersList() {
  const byUrl = new Map();
  peersItems.forEach((item) => {
    if (!item || !item.peer_url) return;
    const mapping = findMappingByUrl(item.peer_url);
    if (!mapping) {
      const onlineStatus = computeOnlineStatus(item.last_seen_at);
      if (onlineStatus !== "online") return;
    }
    byUrl.set(item.peer_url, item);
  });
  peerMappingsItems.forEach((mapping) => {
    if (!mapping || !mapping.peer_url || !mapping.token_present) return;
    if (byUrl.has(mapping.peer_url)) return;
    byUrl.set(mapping.peer_url, {
      id: mapping.self_id || mapping.peer_url,
      peer_url: mapping.peer_url,
      last_sync_at: null,
      cursor_lag_seconds: null,
      failures_10m: 0,
      backpressure_queue: 0,
      last_error: "",
      remote_connected: null,
      remote_capacity: null,
      remote_heartbeat_ttl_seconds: null,
      last_seen_at: mapping.last_seen_at || null,
      online_status: computeOnlineStatus(mapping.last_seen_at || null),
      status: "unknown",
    });
  });
  return Array.from(byUrl.values());
}

function setStatus(el, text, isError) {
  el.textContent = text;
  el.style.color = isError ? "#a12b2b" : "#4b443b";
}

async function changePassword() {
  const currentPassword = window.prompt("Current password:", "");
  if (currentPassword === null) return;
  const newPassword = window.prompt("New password:", "");
  if (newPassword === null) return;
  if (newPassword.length < 8) {
    showToast("Password must be at least 8 characters.", true);
    if (accountStatus) setStatus(accountStatus, "Password too short.", true);
    return;
  }
  const res = await fetch("/admin/api/auth/change-password", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      current_password: currentPassword,
      new_password: newPassword,
    }),
  });
  if (!res.ok) {
    showToast("Failed to change password.", true);
    if (accountStatus) setStatus(accountStatus, "Change password failed.", true);
    return;
  }
  showToast("Password updated.", false);
  if (accountStatus) setStatus(accountStatus, "Password updated.", false);
}

async function login(ev) {
  ev.preventDefault();
  const formData = new FormData(loginForm);
  const payload = {
    username: formData.get("username"),
    password: formData.get("password"),
  };

  const res = await fetch("/admin/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });

  if (!res.ok) {
    setStatus(loginStatus, "Login failed.", true);
    sessionCard.classList.add("hidden");
    return;
  }

  setStatus(loginStatus, "Login ok.", false);
  if (sessionCard) sessionCard.classList.remove("hidden");
  window.location.href = "/admin/dashboard";
}

async function logout() {
  const res = await fetch("/admin/api/auth/logout", { method: "POST" });
  if (!res.ok) {
    setStatus(sessionStatus, "Logout failed.", true);
    return;
  }
  setStatus(sessionStatus, "Logged out.", false);
  if (sessionCard) sessionCard.classList.add("hidden");
  window.location.href = "/admin/login";
}

async function checkSession() {
  const res = await fetch("/admin/api/auth/me");
  if (!res.ok) {
    if (sessionCard) sessionCard.classList.add("hidden");
    if (loginForm) return;
    authGuestItems.forEach((item) => item.classList.remove("hidden"));
    authUserItems.forEach((item) => item.classList.add("hidden"));
    window.location.href = "/admin/login";
    return;
  }
  const payload = await res.json();
  if (sessionCard) sessionCard.classList.remove("hidden");
  if (footerSession && payload && payload.username) {
    footerSession.textContent = `Logged in as ${payload.username}`;
  }
  if (ipAllowlistBanner) {
    const allowlist = payload && payload.ip_allowlist ? String(payload.ip_allowlist).trim() : "";
    if (allowlist) {
      ipAllowlistBanner.textContent = `IP allowlist active: ${allowlist}`;
      ipAllowlistBanner.classList.remove("hidden");
    } else {
      ipAllowlistBanner.classList.add("hidden");
    }
  }
  if (accountUsername && payload && payload.username) {
    accountUsername.textContent = payload.username;
  }
  authGuestItems.forEach((item) => item.classList.add("hidden"));
  authUserItems.forEach((item) => item.classList.remove("hidden"));
  if (loginForm) {
    window.location.href = "/admin/dashboard";
  }
}

function setBanlistStatus(text, isError) {
  if (!banlistStatus) return;
  banlistStatus.textContent = text;
  banlistStatus.style.color = isError ? "#a12b2b" : "#4b443b";
}

function applyBanlistFilters(items) {
  let filtered = [...items];
  const query = banlistSearch ? banlistSearch.value.trim().toLowerCase() : "";
  if (query) {
    filtered = filtered.filter((item) =>
      String(item.value).toLowerCase().includes(query)
    );
  }
  const status = banlistFilter ? banlistFilter.value : "all";
  if (status === "active") {
    filtered = filtered.filter((item) => item.enabled);
  }
  if (status === "paused") {
    filtered = filtered.filter((item) => !item.enabled);
  }
  return filtered;
}

function renderBanlist(items) {
  if (!banlistTable) return;
  const tbody = banlistTable.querySelector("tbody");
  tbody.textContent = "";
  items.forEach((item) => {
    const row = document.createElement("tr");
    const status = item.enabled ? "active" : "paused";
    row.innerHTML = `
      <td>${item.type}</td>
      <td>${item.value}</td>
      <td>${item.reason || ""}</td>
      <td><span class="badge ${item.enabled ? "" : "off"}">${status}</span></td>
      <td>
        <button data-action="toggle" data-id="${item.id}" data-enabled="${item.enabled}">Toggle</button>
        <button data-action="delete" data-id="${item.id}">Delete</button>
      </td>
    `;
    tbody.appendChild(row);
  });
}

function downloadFile(filename, content, type) {
  const blob = new Blob([content], { type });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

async function exportBanlist(format) {
  const url = "/admin/api/banlist/export?format=csv";
  const res = await fetch(url);
  if (!res.ok) {
    setBanlistStatus("Failed to export banlist.", true);
    showToast("Failed to export banlist.", true);
    return;
  }
  const contentType = res.headers.get("content-type") || "";
  const text = await res.text();
  downloadFile("banlist.csv", text, contentType || "text/plain");
  showToast("Banlist exported.", false);
}

async function importBanlist() {
  if (!banlistImportFile || !banlistImportFile.files || banlistImportFile.files.length === 0) {
    setBanlistStatus("Choose a file to import.", true);
    showToast("Choose a file to import.", true);
    return;
  }
  const file = banlistImportFile.files[0];
  const confirmed = window.confirm(`Import banlist entries from ${file.name}?`);
  if (!confirmed) return;
  const signature = window.prompt("Banlist signature (hex HMAC-SHA256):", "");
  if (!signature || !signature.trim()) {
    setBanlistStatus("Signature is required for import.", true);
    showToast("Signature is required for import.", true);
    return;
  }
  const content = await file.text();
  const isCsv = file.name.toLowerCase().endsWith(".csv");
  if (!isCsv) {
    setBanlistStatus("Only CSV files are supported.", true);
    showToast("Only CSV files are supported.", true);
    return;
  }
  const res = await fetch("/admin/api/banlist/import", {
    method: "POST",
    headers: {
      "Content-Type": "text/csv",
      "X-Banlist-Signature": signature.trim(),
    },
    body: content,
  });
  if (!res.ok) {
    setBanlistStatus("Failed to import banlist.", true);
    showToast("Failed to import banlist.", true);
    return;
  }
  const payload = await res.json();
  setBanlistStatus(`Imported ${payload.created || 0} entries.`, false);
  showToast("Banlist imported.", false);
  banlistImportFile.value = "";
  await loadBanlist();
}

async function loadBanlist() {
  if (!banlistTable) return;
  const res = await fetch("/admin/api/banlist");
  if (!res.ok) {
    setBanlistStatus("Failed to load banlist.", true);
    return;
  }
  const payload = await res.json();
  banlistItems = payload.items || [];
  renderBanlist(applyBanlistFilters(banlistItems));
}

async function handleBanlistAction(ev) {
  const target = ev.target;
  if (!(target instanceof HTMLElement)) return;
  const action = target.getAttribute("data-action");
  const id = target.getAttribute("data-id");
  if (!action || !id) return;

  if (action === "toggle") {
    const enabled = target.getAttribute("data-enabled") !== "true";
    await fetch(`/admin/api/banlist/${id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled }),
    });
    await loadBanlist();
  }

  if (action === "delete") {
    const confirmed = window.confirm("Delete this banlist entry?");
    if (!confirmed) return;
    const res = await fetch(`/admin/api/banlist/${id}`, { method: "DELETE" });
    if (!res.ok) {
      setBanlistStatus("Failed to delete banlist entry.", true);
      showToast("Failed to delete banlist entry.", true);
      return;
    }
    await loadBanlist();
  }
}

async function handleBanlistSubmit(ev) {
  ev.preventDefault();
  const formData = new FormData(banlistForm);
  const payload = {
    type: formData.get("type"),
    value: formData.get("value"),
    reason: formData.get("reason"),
  };
  const valueLabel = String(payload.value || "").trim();
  const confirmed = window.confirm(`Add ban for ${valueLabel || "this entry"}?`);
  if (!confirmed) return;
  const res = await fetch("/admin/api/banlist", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    setBanlistStatus("Failed to add ban.", true);
    return;
  }
  banlistForm.reset();
  setBanlistStatus("Ban added.", false);
  await loadBanlist();
}

function applyReputationFilters(items) {
  let filtered = [...items];
  const query = reputationSearch ? reputationSearch.value.trim().toLowerCase() : "";
  if (query) {
    filtered = filtered.filter((item) => {
      const haystack = `${item.peer_id || ""} ${item.note || ""}`.toLowerCase();
      return haystack.includes(query);
    });
  }
  return filtered;
}

function renderReputation(items) {
  if (!reputationTable) return;
  const tbody = reputationTable.querySelector("tbody");
  tbody.textContent = "";
  if (!items.length) {
    const row = document.createElement("tr");
    row.innerHTML = `<td colspan="4" class="muted">No reputation entries yet.</td>`;
    tbody.appendChild(row);
    return;
  }
  items.forEach((item) => {
    const row = document.createElement("tr");
    row.innerHTML = `
      <td>${item.peer_id}</td>
      <td>${item.score}</td>
      <td>${item.note || "—"}</td>
      <td>
        <button type="button" data-action="edit" data-id="${item.id}">Edit</button>
        <button type="button" data-action="delete" data-id="${item.id}">Delete</button>
      </td>
    `;
    tbody.appendChild(row);
  });
}

async function loadReputation() {
  if (!reputationTable) return;
  const res = await fetch("/admin/api/reputation");
  if (!res.ok) {
    setReputationStatus("Failed to load reputation.", true);
    return;
  }
  const payload = await res.json();
  reputationItems = payload.items || [];
  renderReputation(applyReputationFilters(reputationItems));
}

async function handleReputationSubmit(ev) {
  ev.preventDefault();
  const formData = new FormData(reputationForm);
  const peerId = String(formData.get("peer_id") || "").trim();
  const score = Number(formData.get("score") || 0);
  const note = String(formData.get("note") || "").trim();
  if (!peerId) {
    setReputationStatus("Peer ID is required.", true);
    showToast("Peer ID is required.", true);
    return;
  }
  const res = await fetch("/admin/api/reputation", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ peer_id: peerId, score, note }),
  });
  if (!res.ok) {
    setReputationStatus("Failed to add reputation.", true);
    showToast("Failed to add reputation.", true);
    return;
  }
  reputationForm.reset();
  setReputationStatus("Reputation saved.", false);
  showToast("Reputation saved.", false);
  await loadReputation();
}

async function handleReputationAction(ev) {
  const target = ev.target;
  if (!(target instanceof HTMLElement)) return;
  const action = target.getAttribute("data-action");
  const id = Number(target.getAttribute("data-id"));
  if (!action || !Number.isFinite(id)) return;

  if (action === "delete") {
    const confirmed = window.confirm("Delete this reputation entry?");
    if (!confirmed) return;
    const res = await fetch(`/admin/api/reputation/${id}`, { method: "DELETE" });
    if (!res.ok) {
      setReputationStatus("Failed to delete reputation.", true);
      showToast("Failed to delete reputation.", true);
      return;
    }
    setReputationStatus("Reputation deleted.", false);
    showToast("Reputation deleted.", false);
    await loadReputation();
    return;
  }

  if (action === "edit") {
    const item = reputationItems.find((entry) => entry.id === id);
    if (!item) return;
    const nextScore = window.prompt("Score:", String(item.score));
    if (nextScore === null) return;
    const nextNote = window.prompt("Note:", item.note || "");
    if (nextNote === null) return;
    const res = await fetch(`/admin/api/reputation/${id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        score: Number(nextScore),
        note: String(nextNote),
      }),
    });
    if (!res.ok) {
      setReputationStatus("Failed to update reputation.", true);
      showToast("Failed to update reputation.", true);
      return;
    }
    setReputationStatus("Reputation updated.", false);
    showToast("Reputation updated.", false);
    await loadReputation();
  }
}

async function exportReputationCsv() {
  const res = await fetch("/admin/api/reputation/export");
  if (!res.ok) {
    setReputationStatus("Failed to export reputation.", true);
    showToast("Failed to export reputation.", true);
    return;
  }
  const contentType = res.headers.get("content-type") || "";
  const text = await res.text();
  downloadFile("reputation.csv", text, contentType || "text/plain");
  showToast("Reputation exported.", false);
}

async function importReputation() {
  if (!reputationImportFile || !reputationImportFile.files || reputationImportFile.files.length === 0) {
    setReputationStatus("Choose a CSV file to import.", true);
    showToast("Choose a CSV file to import.", true);
    return;
  }
  const file = reputationImportFile.files[0];
  const confirmed = window.confirm(`Import reputation entries from ${file.name}?`);
  if (!confirmed) return;
  if (!file.name.toLowerCase().endsWith(".csv")) {
    setReputationStatus("Only CSV files are supported.", true);
    showToast("Only CSV files are supported.", true);
    return;
  }
  const content = await file.text();
  const res = await fetch("/admin/api/reputation/import", {
    method: "POST",
    headers: { "Content-Type": "text/csv" },
    body: content,
  });
  if (!res.ok) {
    setReputationStatus("Failed to import reputation.", true);
    showToast("Failed to import reputation.", true);
    return;
  }
  const payload = await res.json();
  setReputationStatus(`Imported ${payload.created || 0} entries.`, false);
  showToast("Reputation imported.", false);
  reputationImportFile.value = "";
  await loadReputation();
}

function setAuditStatus(text, isError) {
  if (!auditStatus) return;
  auditStatus.textContent = text;
  auditStatus.style.color = isError ? "#a12b2b" : "#4b443b";
}

function formatAuditTarget(entry) {
  if (entry.peer_id) return `peer:${entry.peer_id}`;
  if (entry.type && entry.value) return `${entry.type}:${entry.value}`;
  if (entry.id) return `id:${entry.id}`;
  return "—";
}

function formatAuditDetails(entry) {
  const parts = [];
  if (typeof entry.score === "number") parts.push(`score=${entry.score}`);
  if (typeof entry.note === "string" && entry.note) parts.push(`note="${entry.note}"`);
  if (typeof entry.reason === "string" && entry.reason) parts.push(`reason="${entry.reason}"`);
  if (typeof entry.enabled === "boolean") parts.push(`enabled=${entry.enabled}`);
  if (typeof entry.created === "number") parts.push(`created=${entry.created}`);
  if (typeof entry.total === "number") parts.push(`total=${entry.total}`);
  return parts.length > 0 ? parts.join(" ") : "—";
}

function applyAuditFilters(items) {
  let filtered = [...items];
  const query = auditSearch ? auditSearch.value.trim().toLowerCase() : "";
  if (query) {
    filtered = filtered.filter((entry) => {
      const target = formatAuditTarget(entry);
      const details = formatAuditDetails(entry);
      const haystack = `${entry.action || ""} ${entry.actor || ""} ${target} ${details}`.toLowerCase();
      return haystack.includes(query);
    });
  }
  return filtered;
}

function renderAudit(items) {
  if (!auditTable) return;
  const tbody = auditTable.querySelector("tbody");
  tbody.textContent = "";
  if (items.length === 0) {
    const row = document.createElement("tr");
    row.innerHTML = `<td colspan="5" class="muted">No audit entries yet.</td>`;
    tbody.appendChild(row);
    return;
  }
  items.forEach((entry) => {
    const row = document.createElement("tr");
    row.innerHTML = `
      <td>${formatTimestamp(entry.ts)}</td>
      <td>${entry.action || "—"}</td>
      <td>${entry.actor || "—"}</td>
      <td>${formatAuditTarget(entry)}</td>
      <td class="muted">${formatAuditDetails(entry)}</td>
    `;
    tbody.appendChild(row);
  });
}

async function loadAudit() {
  if (!auditTable) return;
  const scope = auditScope ? auditScope.value : "all";
  const limit = auditLimit ? auditLimit.value : "200";
  const sinceRaw = auditSince ? auditSince.value : "";
  const params = new URLSearchParams();
  if (scope) params.set("scope", scope);
  if (limit) params.set("limit", limit);
  if (sinceRaw) params.set("since", new Date(sinceRaw).toISOString());
  try {
    const res = await fetch(`/admin/api/audit?${params.toString()}`);
    if (!res.ok) {
      setAuditStatus("Failed to load audit trail.", true);
      auditItems = [];
      renderAudit([]);
      return;
    }
    const payload = await res.json();
    auditItems = payload.items || [];
    setAuditStatus(`Loaded ${auditItems.length} entries.`, false);
    renderAudit(applyAuditFilters(auditItems));
  } catch (err) {
    setAuditStatus("Failed to load audit trail.", true);
    auditItems = [];
    renderAudit([]);
  }
}

function toggleMenu() {
  if (!menu) return;
  menu.classList.toggle("open");
}

function renderBreadcrumbs() {
  const nodes = document.querySelectorAll(".breadcrumbs[data-breadcrumbs]");
  if (!nodes.length) return;
  nodes.forEach((node) => {
    const raw = node.getAttribute("data-breadcrumbs") || "[]";
    let items = [];
    try {
      items = JSON.parse(raw);
    } catch (err) {
      items = [];
    }
    node.textContent = "";
    if (!Array.isArray(items) || items.length === 0) return;
    items.forEach((item) => {
      if (!item || !item.href || !item.label) return;
      const link = document.createElement("a");
      link.className = "muted";
      link.href = item.href;
      link.textContent = `← ${item.label}`;
      node.appendChild(link);
    });
  });
}

function setPeersStatus(text, isError) {
  if (!peersStatus) return;
  peersStatus.textContent = text;
  peersStatus.style.color = isError ? "#a12b2b" : "#4b443b";
}

function setInboundPeersStatus(text, isError) {
  if (!inboundPeersStatus) return;
  inboundPeersStatus.textContent = text;
  inboundPeersStatus.style.color = isError ? "#a12b2b" : "#4b443b";
}

function renderInboundPeers(items) {
  if (!inboundPeersTable) return;
  const tbody = inboundPeersTable.querySelector("tbody");
  tbody.textContent = "";
  if (!items || items.length === 0) {
    const row = document.createElement("tr");
    row.innerHTML = `<td colspan="4" class="muted">No inbound peers yet.</td>`;
    tbody.appendChild(row);
    return;
  }
  items.forEach((item) => {
    const status = item.status || "offline";
    const row = document.createElement("tr");
    row.innerHTML = `
      <td>${item.self_id || "—"}</td>
      <td>${item.peer_url || "—"}</td>
      <td>${formatTimestamp(item.last_seen_at)}</td>
      <td><span class="pill ${status}">${status}</span></td>
    `;
    tbody.appendChild(row);
  });
}

function setPeerJoinStatus(text, isError) {
  if (!peerJoinStatus) return;
  peerJoinStatus.textContent = text;
  peerJoinStatus.style.color = isError ? "#a12b2b" : "#4b443b";
}

async function handlePeerMappingError(res, fallbackMessage) {
  let message = fallbackMessage;
  try {
    const payload = await res.json();
    if (payload && payload.error && payload.error.code === "capacity_full") {
      message = "Maximum peers reached. Remove a mapping or increase limit.";
    }
  } catch (err) {
    // ignore parse errors and keep fallback
  }
  setPeerJoinStatus(message, true);
  showToast(message, true);
}

function setReputationStatus(text, isError) {
  if (!reputationStatus) return;
  reputationStatus.textContent = text;
  reputationStatus.style.color = isError ? "#a12b2b" : "#4b443b";
}

let toastTimer = null;
function showToast(message, isError) {
  if (!message) return;
  let toast = document.getElementById("admin-toast");
  if (!toast) {
    toast = document.createElement("div");
    toast.id = "admin-toast";
    toast.className = "toast";
    document.body.appendChild(toast);
  }
  toast.textContent = message;
  toast.classList.toggle("error", Boolean(isError));
  toast.classList.add("show");
  if (toastTimer) window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => {
    toast.classList.remove("show");
  }, 2200);
}

function maskToken(token) {
  if (!token) return "missing";
  if (token.length <= 8) return token;
  return `${token.slice(0, 4)}...${token.slice(-4)}`;
}

function setSelfTokenStatus(text, isError) {
  if (!selfTokenStatus) return;
  selfTokenStatus.textContent = text;
  selfTokenStatus.style.color = isError ? "#a12b2b" : "#4b443b";
}

function setTokenEditMode(mode) {
  selfTokenMode = mode;
  if (!selfTokenEdit || !selfTokenInput) return;
  const editing = mode === "create" || mode === "replace";
  selfTokenEdit.classList.toggle("hidden", !editing);
  if (editing) {
    selfTokenInput.value = "";
    selfTokenInput.focus();
  }
  if (selfTokenCreate) selfTokenCreate.classList.toggle("hidden", editing || Boolean(selfTokenValue));
  if (selfTokenCopy) selfTokenCopy.classList.toggle("hidden", editing || !selfTokenValue);
  if (selfTokenReplace) selfTokenReplace.classList.toggle("hidden", editing || !selfTokenValue);
  if (selfTokenRevoke) selfTokenRevoke.classList.toggle("hidden", editing || !selfTokenValue);
}

function generatePublicToken(length = 24) {
  const chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-";
  const bytes = new Uint8Array(length);
  if (window.crypto && window.crypto.getRandomValues) {
    window.crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < length; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  let token = "";
  for (let i = 0; i < length; i += 1) {
    token += chars[bytes[i] % chars.length];
  }
  return token;
}

async function copyPublicToken(token) {
  if (!token) {
    showToast("No public token to copy.", true);
    return;
  }
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(token);
    } else {
      window.prompt("Copy public token:", token);
    }
    showToast("Public token copied.", false);
  } catch (err) {
    showToast("Failed to copy token.", true);
  }
}

async function loadFederationSelf() {
  if (!selfPeerId && !selfPeerUrl && !selfPublicToken) return;
  try {
    const res = await fetch("/admin/api/federation/self");
    if (!res.ok) {
      setSelfTokenStatus("Failed to load relay info.", true);
      return;
    }
    const payload = await res.json();
    if (selfPeerId) selfPeerId.textContent = payload.self_id || "—";
    if (selfPeerUrl) selfPeerUrl.textContent = payload.self_url || "—";
    const tokenValue = payload.public_token || "";
    const tokenPresent = Boolean(payload.public_token_present);
    selfTokenValue = tokenPresent ? tokenValue : "";
    selfTokenCreatedAt = payload.public_token_created_at || "";
    if (selfPublicToken) {
      selfPublicToken.textContent = tokenPresent ? maskToken(tokenValue) : "missing";
      selfPublicToken.classList.toggle("muted", !tokenPresent);
    }
    if (selfPublicTokenCreated) {
      selfPublicTokenCreated.textContent = payload.public_token_created_at
        ? `Created ${formatTimestamp(payload.public_token_created_at)}`
        : "";
    }
    setTokenEditMode(null);
    if (selfTokenCopy) selfTokenCopy.dataset.token = tokenValue;
  } catch (err) {
    setSelfTokenStatus("Failed to load relay info.", true);
  }
}

async function createOrReplacePublicToken(mode, tokenValue) {
  const res = await fetch("/admin/api/federation/self/public-token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ mode, public_token: tokenValue }),
  });
  if (!res.ok) {
    setSelfTokenStatus("Failed to update public token.", true);
    showToast("Failed to update public token.", true);
    return;
  }
  const payload = await res.json();
  if (selfPublicToken) {
    selfPublicToken.textContent = maskToken(payload.public_token || "");
    selfPublicToken.classList.remove("muted");
  }
  if (selfPublicTokenCreated) {
    selfPublicTokenCreated.textContent = payload.public_token_created_at
      ? `Created ${formatTimestamp(payload.public_token_created_at)}`
      : "";
  }
  setSelfTokenStatus("Public token updated.", false);
  showToast("Public token updated.", false);
  await loadFederationSelf();
}

async function revokePublicToken() {
  const res = await fetch("/admin/api/federation/self/public-token", { method: "DELETE" });
  if (!res.ok) {
    setSelfTokenStatus("Failed to revoke public token.", true);
    showToast("Failed to revoke public token.", true);
    return;
  }
  setSelfTokenStatus("Public token revoked.", false);
  showToast("Public token revoked.", false);
  await loadFederationSelf();
}

function renderPeerMappings(items) {
  if (!peerMappingsTable) return;
  const tbody = peerMappingsTable.querySelector("tbody");
  tbody.textContent = "";
  if (!items.length) {
    const row = document.createElement("tr");
    row.innerHTML = `<td colspan="6" class="muted">No known peers yet.</td>`;
    tbody.appendChild(row);
    return;
  }
  items.forEach((item) => {
    const row = document.createElement("tr");
    const gids = Array.isArray(item.sync_gids) && item.sync_gids.length > 0
      ? item.sync_gids.join(", ")
      : "All";
    const tokenState = item.token_present ? "set" : "missing";
    row.innerHTML = `
      <td>${item.peer_url}</td>
      <td>${gids}</td>
      <td><span class="badge ${item.token_present ? "" : "off"}">${tokenState}</span></td>
      <td><span class="badge ${item.enabled ? "" : "off"}">${item.enabled ? "enabled" : "disabled"}</span></td>
      <td>${formatTimestamp(item.joined_at)}</td>
      <td>
        <button type="button" data-action="edit" data-id="${item.id}">Edit</button>
        <button type="button" data-action="toggle" data-id="${item.id}" data-enabled="${item.enabled}">
          ${item.enabled ? "Disable" : "Enable"}
        </button>
        <button type="button" data-action="delete" data-id="${item.id}">Delete</button>
      </td>
    `;
    tbody.appendChild(row);
  });
}

async function loadPeerMappings() {
  if (!peerMappingsTable) return;
  const res = await fetch("/admin/api/peer-mappings");
  if (!res.ok) {
    setPeerJoinStatus("Failed to load peer mappings.", true);
    return;
  }
  const payload = await res.json();
  peerMappingsItems = payload.items || [];
  renderPeerMappings(peerMappingsItems);
  livePeersItems = buildLivePeersList();
  if (livePeersItems.length) {
    const filtered = applyPeersFilters(livePeersItems);
    renderPeers(filtered);
  }
}

function wirePeerJoinForm() {
  if (!peerJoinForm) return;
  const updatePreview = () => {
    if (!peerJoinForm) return;
    const formData = new FormData(peerJoinForm);
    const peerUrl = String(formData.get("peer_url") || "").trim();
    const peerToken = String(formData.get("peer_token") || "").trim();
    const syncGidsRaw = String(formData.get("sync_gids") || "").trim();
    const syncGids = syncGidsRaw
      ? syncGidsRaw.split(",").map((gid) => gid.trim()).filter(Boolean)
      : [];
    if (peerPreviewUrl) peerPreviewUrl.textContent = peerUrl || "—";
    if (peerPreviewGids) {
      peerPreviewGids.textContent = syncGids.length ? syncGids.join(", ") : "All";
    }
    if (peerPreviewToken) {
      peerPreviewToken.textContent = peerToken ? "set" : "missing";
      peerPreviewToken.classList.toggle("muted", !peerToken);
    }
    if (peerPreviewNote) {
      if (!peerUrl || !peerToken) {
        peerPreviewNote.textContent = "Enter Peer URL + Token to enable Add Peer.";
      } else {
        peerPreviewNote.textContent = "Ready to save mapping.";
      }
    }
  };
  peerJoinForm.addEventListener("input", updatePreview);
  peerJoinForm.addEventListener("change", updatePreview);
  updatePreview();
  peerJoinForm.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const formData = new FormData(peerJoinForm);
    const peerUrl = String(formData.get("peer_url") || "").trim();
    const peerToken = String(formData.get("peer_token") || "").trim();
    const syncGidsRaw = String(formData.get("sync_gids") || "").trim();
    if (!peerUrl || !peerToken) {
      setPeerJoinStatus("Peer URL and token are required.", true);
      showToast("Peer URL and token are required.", true);
      return;
    }
    const syncGids = syncGidsRaw
      ? syncGidsRaw.split(",").map((gid) => gid.trim()).filter(Boolean)
      : [];
    const res = await fetch("/admin/api/peer-mappings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        peer_url: peerUrl,
        peer_token: peerToken,
        sync_gids: syncGids,
      }),
    });
    if (!res.ok) {
      await handlePeerMappingError(res, "Failed to add peer mapping.");
      return;
    }
    setPeerJoinStatus("Peer mapping saved.", false);
    showToast("Peer mapping saved.", false);
    peerJoinForm.reset();
    await loadPeerMappings();
  });
}

async function handlePeerMappingsAction(ev) {
  const target = ev.target;
  if (!(target instanceof HTMLElement)) return;
  const action = target.getAttribute("data-action");
  const id = Number(target.getAttribute("data-id"));
  if (!action || !Number.isFinite(id)) return;

  if (action === "toggle") {
    const enabled = target.getAttribute("data-enabled") !== "true";
    const res = await fetch(`/admin/api/peer-mappings/${id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled }),
    });
    if (!res.ok) {
      await handlePeerMappingError(res, "Failed to update mapping.");
      return;
    }
    setPeerJoinStatus("Mapping updated.", false);
    showToast("Mapping updated.", false);
    await loadPeerMappings();
    return;
  }

  if (action === "delete") {
    const confirmed = window.confirm("Delete this peer mapping?");
    if (!confirmed) return;
    const res = await fetch(`/admin/api/peer-mappings/${id}`, { method: "DELETE" });
    if (!res.ok) {
      setPeerJoinStatus("Failed to delete mapping.", true);
      showToast("Failed to delete mapping.", true);
      return;
    }
    setPeerJoinStatus("Mapping deleted.", false);
    showToast("Mapping deleted.", false);
    await loadPeerMappings();
    return;
  }

  if (action === "edit") {
    const item = peerMappingsItems.find((entry) => entry.id === id);
    if (!item) return;
    const nextGids = window.prompt(
      "Sync GIDs (comma-separated, leave blank for all):",
      Array.isArray(item.sync_gids) ? item.sync_gids.join(", ") : ""
    );
    if (nextGids === null) return;
    const nextToken = window.prompt("New peer token (leave blank to keep):", "");
    if (nextToken === null) return;
    const payload = {
      sync_gids: nextGids
        .split(",")
        .map((gid) => gid.trim())
        .filter(Boolean),
    };
    if (nextToken.trim().length > 0) {
      payload.peer_token = nextToken.trim();
    }
    const res = await fetch(`/admin/api/peer-mappings/${id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    if (!res.ok) {
      await handlePeerMappingError(res, "Failed to update mapping.");
      return;
    }
    setPeerJoinStatus("Mapping updated.", false);
    showToast("Mapping updated.", false);
    await loadPeerMappings();
  }
}

async function handleLivePeersAction(ev) {
  const target = ev.target;
  if (!(target instanceof HTMLElement)) return;
  const action = target.getAttribute("data-action");
  if (action !== "connect" && action !== "disconnect") return;
  const peerUrl = target.getAttribute("data-peer-url");
  if (!peerUrl) return;
  target.setAttribute("disabled", "disabled");

  const mapping = findMappingByUrl(peerUrl);
  if (action === "disconnect") {
    if (!mapping) {
      showToast("No mapping found to disconnect.", true);
      target.removeAttribute("disabled");
      return;
    }
    const res = await fetch(`/admin/api/peer-mappings/${mapping.id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled: false }),
    });
    if (!res.ok) {
      await handlePeerMappingError(res, "Failed to disconnect mapping.");
      target.removeAttribute("disabled");
      return;
    }
    showToast("Mapping disabled.", false);
    await loadPeerMappings();
    await loadPeers();
    await loadInboundPeers();
    return;
  }

  let peerToken = "";
  if (!mapping || !mapping.token_present) {
    peerToken = window.prompt("Public token for this peer:", "") || "";
    if (!peerToken.trim()) {
      showToast("Public token is required to connect.", true);
      target.removeAttribute("disabled");
      return;
    }
  }

  if (!mapping) {
    const res = await fetch("/admin/api/peer-mappings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        peer_url: peerUrl,
        peer_token: peerToken.trim(),
        sync_gids: [],
      }),
    });
    if (!res.ok) {
      await handlePeerMappingError(res, "Failed to connect peer.");
      target.removeAttribute("disabled");
      return;
    }
    showToast("Peer mapping added.", false);
  } else {
    const payload = { enabled: true };
    if (peerToken.trim()) payload.peer_token = peerToken.trim();
    const res = await fetch(`/admin/api/peer-mappings/${mapping.id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    if (!res.ok) {
      await handlePeerMappingError(res, "Failed to connect peer.");
      target.removeAttribute("disabled");
      return;
    }
    showToast("Mapping enabled.", false);
  }
  await loadPeerMappings();
  await loadPeers();
  await loadInboundPeers();
}

function mockPeers() {
  const now = Date.now();
  return [
    {
      id: "peer-a1",
      peer_url: "https://relay-north.example.com",
      last_sync_at: new Date(now - 2 * 60 * 1000).toISOString(),
      cursor_lag_seconds: 12,
      failures_10m: 0,
      backpressure_queue: 0,
      last_error: "",
      remote_connected: 3,
      remote_capacity: 32,
      remote_heartbeat_ttl_seconds: 120,
    },
    {
      id: "peer-b2",
      peer_url: "https://relay-south.example.com",
      last_sync_at: new Date(now - 11 * 60 * 1000).toISOString(),
      cursor_lag_seconds: 64,
      failures_10m: 2,
      backpressure_queue: 6,
      last_error: "Timeout on batch fetch",
      remote_connected: 0,
      remote_capacity: 16,
      remote_heartbeat_ttl_seconds: 60,
    },
    {
      id: "peer-c3",
      peer_url: "https://relay-east.example.com",
      last_sync_at: new Date(now - 36 * 60 * 1000).toISOString(),
      cursor_lag_seconds: 130,
      failures_10m: 4,
      backpressure_queue: 12,
      last_error: "Retry backoff in progress",
      remote_connected: null,
      remote_capacity: null,
      remote_heartbeat_ttl_seconds: null,
    },
  ];
}

function formatTimestamp(value) {
  if (!value) return "—";
  const dt = new Date(value);
  if (Number.isNaN(dt.getTime())) return "—";
  return dt.toLocaleString();
}

function peerHealth(item) {
  if (item.status) return item.status;
  if (item.failures_10m > 0 || item.cursor_lag_seconds > 60) return "degraded";
  return "ok";
}

function applyPeersFilters(items) {
  let filtered = [...items];
  const query = peersSearch ? peersSearch.value.trim().toLowerCase() : "";
  if (query) {
    filtered = filtered.filter((item) => {
      const haystack = `${item.peer_url || ""} ${item.id || ""}`.toLowerCase();
      return haystack.includes(query);
    });
  }
  filtered.sort((a, b) => {
    const tsA = a.last_seen_at ? Date.parse(a.last_seen_at) : 0;
    const tsB = b.last_seen_at ? Date.parse(b.last_seen_at) : 0;
    const safeA = Number.isFinite(tsA) ? tsA : 0;
    const safeB = Number.isFinite(tsB) ? tsB : 0;
    return safeB - safeA;
  });
  return filtered;
}

function renderPeers(items) {
  if (!peersTable) return;
  const tbody = peersTable.querySelector("tbody");
  tbody.textContent = "";
  if (items.length === 0) {
    setPeersStatus(
      "No live peers yet. Configured mappings appear in Known Peers.",
      false
    );
  } else if (peersStatus && peersStatus.textContent.includes("No live peers")) {
    setPeersStatus("", false);
  }
  const maxPeers = Number(peersStats.max_peers || 0);
  const connectedCount = Number(peersStats.connected || 0);
  const maxReached = maxPeers > 0 && connectedCount >= maxPeers;
  items.forEach((item) => {
    const status = peerHealth(item);
    const mapping = findMappingByUrl(item.peer_url);
    const inboundEntry = findInboundByPeer(item);
    const inbound = Boolean(inboundEntry && inboundEntry.status === "online");
    const outbound = Boolean(mapping && mapping.enabled && mapping.rotated_present);
    const bidirectional = inbound && outbound;
    const link =
      inbound && outbound ? "<span class=\"peer-link connected\">⇄</span>" : inbound ? "<span class=\"peer-link inbounded\">←</span>" : outbound ? "<span class=\"peer-link outbounded\">→</span>" : "—";
    const connectLabel = bidirectional ? "Disconnect" : "Connect";
    const connectAction = bidirectional ? "disconnect" : "connect";
    const peerUrl = item.peer_url || "";
    const connectDisabled = !peerUrl || (maxReached && connectAction === "connect");
    const remoteConnected =
      item.remote_connected === null || item.remote_connected === undefined
        ? "--"
        : String(item.remote_connected);
    const remoteCapacity =
      item.remote_capacity === null || item.remote_capacity === undefined
        ? "--"
        : String(item.remote_capacity);
    const lastSeen = formatTimestamp(item.last_seen_at);
    const onlineStatus =
      item.online_status || computeOnlineStatus(item.last_seen_at);
    const onlineLabel = onlineStatus === "unknown" ? "—" : onlineStatus;
    const onlineClass = onlineStatus === "online" ? "ok" : "offline";
    const row = document.createElement("tr");
    if (status === "degraded") row.classList.add("row-degraded");
    row.innerHTML = `
      <td>${item.peer_url || item.id}</td>
      <td>${remoteConnected}/${remoteCapacity}</td>
      <td class="muted">N/A</td>
      <td>${lastSeen}</td>
      <td>${
        onlineStatus === "unknown"
          ? "—"
          : `<span class="pill ${onlineClass}">${onlineLabel}</span>`
      }</td>
      <td>${link}</td>
      <td>
        <button type="button" data-action="${connectAction}" data-peer-url="${peerUrl}" ${
      connectDisabled ? "disabled" : ""
    }>
          ${connectLabel}
        </button>
        <a class="chip link-chip" href="/admin/peer?id=${encodeURIComponent(
          item.id || ""
        )}">Detail</a>
      </td>
    `;
    tbody.appendChild(row);
  });
}

function updatePeerKpis(items) {
  if (!peersTotal || !peersAvgLag || !peersFailures) return;
  const total = items.length;
  const avgLag =
    total === 0
      ? 0
      : Math.round(
          items.reduce((sum, item) => sum + (item.cursor_lag_seconds || 0), 0) / total
        );
  const failures = items.reduce((sum, item) => sum + (item.failures_10m || 0), 0);
  peersTotal.textContent = total.toString();
  peersAvgLag.textContent = avgLag.toString();
  peersFailures.textContent = failures.toString();
}

function updatePeersStats(stats) {
  if (!peersConnected || !peersCapacity || !peersHeartbeat) return;
  peersConnected.textContent = String(stats.connected ?? 0);
  peersCapacity.textContent = stats.capacity_limit > 0 ? String(stats.capacity_limit) : "--";
  peersHeartbeat.textContent = stats.heartbeat_ttl_seconds > 0 ? String(stats.heartbeat_ttl_seconds) : "--";
  const connectedValue = Number(stats.connected);
  const minValue = Number(stats.min_peers);
  const maxValue = Number(stats.max_peers);
  const connected = Number.isFinite(connectedValue) ? connectedValue : 0;
  const minPeers = Number.isFinite(minValue) ? minValue : 0;
  const maxPeers = Number.isFinite(maxValue) ? maxValue : 0;
  if (peersBoundsStatus) {
    const boundsText = `Bounds: min ${minPeers || "—"} / max ${maxPeers || "—"} · connected ${connected}`;
    let isWarning = false;
    if (minPeers > 0 && connected < minPeers) {
      isWarning = true;
      peersBoundsStatus.textContent = `${boundsText} (below minimum)`;
    } else if (maxPeers > 0 && connected >= maxPeers) {
      isWarning = true;
      peersBoundsStatus.textContent = `${boundsText} (at max)`;
    } else {
      peersBoundsStatus.textContent = boundsText;
    }
    peersBoundsStatus.style.color = isWarning ? "#a12b2b" : "#4b443b";
  }
  if (peerJoinSubmit) {
    const overMax = maxPeers > 0 && connected >= maxPeers;
    peerJoinSubmit.disabled = overMax;
  }
}

async function loadPeers() {
  if (!peersTable) return;
  try {
    const res = await fetch("/admin/api/peers");
    if (res.ok) {
      const payload = await res.json();
      peersItems = payload.items || [];
      peersStats = (payload.meta && payload.meta.stats) || peersStats;
      setPeersStatus("", false);
      updatePeersStats(peersStats);
    } else {
      peersItems = mockPeers();
      setPeersStatus("Showing sample data (API not ready).", true);
      updatePeersStats({
        connected: "-",
        capacity_limit: "--",
        heartbeat_ttl_seconds: "--",
      });
    }
  } catch (err) {
    peersItems = mockPeers();
    setPeersStatus("Showing sample data (API not reachable).", true);
    updatePeersStats({
      connected: "-",
      capacity_limit: "--",
      heartbeat_ttl_seconds: "--",
    });
  }
  livePeersItems = buildLivePeersList();
  const filtered = applyPeersFilters(livePeersItems);
  renderPeers(filtered);
  updatePeerKpis(peersItems);
}

async function loadInboundPeers() {
  if (!inboundPeersTable) return;
  try {
    const res = await fetch("/admin/api/peers/inbound");
    if (res.ok) {
      const payload = await res.json();
      inboundPeers = payload.items || [];
      setInboundPeersStatus("", false);
    } else {
      inboundPeers = [];
      setInboundPeersStatus("Inbound peers unavailable.", true);
    }
  } catch (err) {
    inboundPeers = [];
    setInboundPeersStatus("Inbound peers unavailable.", true);
  }
  renderInboundPeers(inboundPeers);
  if (livePeersItems.length) {
    const filtered = applyPeersFilters(livePeersItems);
    renderPeers(filtered);
  }
}

async function pollPeersFast() {
  if (!peersTable || document.hidden || peersFastInFlight) return;
  peersFastInFlight = true;
  try {
    await loadInboundPeers();
  } finally {
    peersFastInFlight = false;
  }
}

async function pollPeersSlow() {
  if (!peersTable || document.hidden || peersSlowInFlight) return;
  peersSlowInFlight = true;
  try {
    await loadPeers();
  } finally {
    peersSlowInFlight = false;
  }
}

function startPeersPolling() {
  if (!peersTable || peersFastTimer || peersSlowTimer) return;
  pollPeersFast();
  pollPeersSlow();
  peersFastTimer = window.setInterval(pollPeersFast, PEERS_POLL_FAST_MS);
  peersSlowTimer = window.setInterval(pollPeersSlow, PEERS_POLL_SLOW_MS);
}

function stopPeersPolling() {
  if (peersFastTimer) {
    window.clearInterval(peersFastTimer);
    peersFastTimer = null;
  }
  if (peersSlowTimer) {
    window.clearInterval(peersSlowTimer);
    peersSlowTimer = null;
  }
}

function setMetricsDetailStatus(text, isError) {
  if (!metricsDetailStatus) return;
  metricsDetailStatus.textContent = text;
  metricsDetailStatus.style.color = isError ? "#a12b2b" : "#4b443b";
}

function setDashboardMetricsStatus(text, isError) {
  if (!dashboardMetricsStatus) return;
  dashboardMetricsStatus.textContent = text;
  dashboardMetricsStatus.style.color = isError ? "#a12b2b" : "#4b443b";
}

function setBackupStatusMessage(text, isError) {
  if (!backupStatusMessage) return;
  backupStatusMessage.textContent = text;
  backupStatusMessage.style.color = isError ? "#a12b2b" : "#4b443b";
}

function setRetentionStatusMessage(text, isError) {
  if (!retentionStatusMessage) return;
  retentionStatusMessage.textContent = text;
  retentionStatusMessage.style.color = isError ? "#a12b2b" : "#4b443b";
}

function setAlertsStatusMessage(text, isError) {
  if (!alertsStatusMessage) return;
  alertsStatusMessage.textContent = text;
  alertsStatusMessage.style.color = isError ? "#a12b2b" : "#4b443b";
}

function formatRate(value) {
  if (!Number.isFinite(value)) return "--";
  return value < 1 ? value.toFixed(3) : value.toFixed(2);
}

function formatRatio(value) {
  if (!Number.isFinite(value)) return "--";
  if (value < 0.01) return value.toFixed(4);
  if (value < 0.1) return value.toFixed(3);
  return value.toFixed(2);
}

function setText(el, text, className) {
  if (!el) return;
  el.textContent = text;
  if (className) {
    el.className = `kpi-value ${className}`;
  } else {
    el.className = "kpi-value";
  }
}

function formatAge(seconds) {
  if (!Number.isFinite(seconds)) return "—";
  if (seconds < 60) return `${seconds}s`;
  const mins = Math.floor(seconds / 60);
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 48) return `${hours}h`;
  const days = Math.floor(hours / 24);
  return `${days}d`;
}

function formatInterval(seconds) {
  if (!Number.isFinite(seconds)) return "—";
  if (seconds < 60) return `${seconds}s`;
  const mins = Math.floor(seconds / 60);
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  return `${hours}h`;
}

function formatMetricValue(value) {
  if (value === null || value === undefined) return "—";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (Number.isFinite(value)) {
    return Number.isInteger(value) ? String(value) : value.toFixed(4);
  }
  return String(value);
}

function renderMetricsDetail(payload) {
  if (!metricsDetailTable) return;
  const tbody = metricsDetailTable.querySelector("tbody");
  tbody.textContent = "";
  const metrics = payload && payload.metrics ? payload.metrics : {};
  const entries = Object.entries(metrics);
  if (entries.length === 0) {
    const row = document.createElement("tr");
    row.innerHTML = `<td colspan="2" class="muted">No metrics available.</td>`;
    tbody.appendChild(row);
  } else {
    entries.forEach(([key, value]) => {
      const row = document.createElement("tr");
      row.innerHTML = `<td>${key}</td><td>${formatMetricValue(value)}</td>`;
      tbody.appendChild(row);
    });
  }
  if (metricsDetailUpdated) {
    metricsDetailUpdated.textContent = payload && payload.timestamp
      ? `Updated ${formatTimestamp(payload.timestamp)}`
      : "";
  }
}

function setActiveMetricsDomain(domain) {
  metricsDetailDomain = domain;
  metricsDetailButtons.forEach((button) => {
    const isActive = button.getAttribute("data-metrics-domain") === domain;
    button.classList.toggle("active", isActive);
  });
}

async function loadMetricsDetail(domain = metricsDetailDomain) {
  if (!metricsDetailTable) return;
  if (metricsDetailInFlight) return;
  metricsDetailInFlight = true;
  try {
    const res = await fetch(
      `/admin/api/metrics/detail?domain=${encodeURIComponent(domain)}`
    );
    if (!res.ok) {
      setMetricsDetailStatus("Failed to load metrics.", true);
      renderMetricsDetail(null);
      return;
    }
    const payload = await res.json();
    setMetricsDetailStatus("", false);
    renderMetricsDetail(payload);
  } catch (err) {
    setMetricsDetailStatus("Failed to load metrics.", true);
    renderMetricsDetail(null);
  } finally {
    metricsDetailInFlight = false;
  }
}

async function loadDashboardMetrics() {
  if (!dashboardMetricsCard || dashboardMetricsInFlight) return;
  dashboardMetricsInFlight = true;
  try {
    const [metricsRes, healthRes, readyRes] = await Promise.all([
      fetch("/admin/api/metrics/summary"),
      fetch("/healthz"),
      fetch("/readyz"),
    ]);
    if (!metricsRes.ok) {
      setDashboardMetricsStatus("Failed to load metrics.", true);
      return;
    }
    const payload = await metricsRes.json();
    setDashboardMetricsStatus("", false);
    const traffic = payload.traffic || {};
    const db = payload.db || {};
    const federation = payload.federation || {};
    const rateLimit = payload.rate_limit || {};
    setText(
      metricsRequestsRate,
      traffic.requests_per_s !== undefined
        ? `${formatRate(traffic.requests_per_s)} req/s`
        : "--"
    );
    if (metricsErrorRate) {
      if (Number.isFinite(traffic.error_rate)) {
        metricsErrorRate.textContent = `${(traffic.error_rate * 100).toFixed(2)}%`;
      } else {
        metricsErrorRate.textContent = "--";
      }
    }
    setText(
      metricsDbPool,
      `${db.pool_in_use ?? "--"} / ${db.pool_total ?? "--"}`
    );
    setText(
      metricsFederationLag,
      Number.isFinite(federation.cursor_lag_seconds)
        ? String(federation.cursor_lag_seconds)
        : "--"
    );
    setText(metricsRateSpike, rateLimit.spike_active ? "true" : "false");
    setText(metricsHealth, healthRes.ok ? "ok" : "down", healthRes.ok ? "ok" : "offline");
    setText(metricsReady, readyRes.ok ? "ok" : "down", readyRes.ok ? "ok" : "offline");
    if (dashboardMetricsUpdated) {
      dashboardMetricsUpdated.textContent = payload.timestamp
        ? `Updated ${formatTimestamp(payload.timestamp)}`
        : "";
    }
  } catch (err) {
    setDashboardMetricsStatus("Failed to load metrics.", true);
  } finally {
    dashboardMetricsInFlight = false;
  }
}

async function loadBackupStatus() {
  if (!backupStatusCard || backupStatusInFlight) return;
  backupStatusInFlight = true;
  try {
    const res = await fetch("/admin/api/backup/status");
    if (!res.ok) {
      setBackupStatusMessage("Backup status unavailable.", true);
      if (backupLastTime) backupLastTime.textContent = "—";
      if (backupAge) backupAge.textContent = "";
      if (backupLastStatus) {
        backupLastStatus.textContent = "--";
        backupLastStatus.className = "pill offline";
      }
      if (backupRestoreDrill) backupRestoreDrill.textContent = "—";
      return;
    }
    const payload = await res.json();
    setBackupStatusMessage("", false);
    if (backupLastTime) {
      backupLastTime.textContent = payload.last_backup_at
        ? formatTimestamp(payload.last_backup_at)
        : "—";
    }
    if (backupAge) {
      backupAge.textContent = Number.isFinite(payload.backup_age_seconds)
        ? `Age ${formatAge(payload.backup_age_seconds)}`
        : "";
    }
    if (backupLastStatus) {
      const status = payload.last_status || "--";
      backupLastStatus.textContent = status;
      backupLastStatus.className = status === "ok" ? "pill ok" : "pill offline";
    }
    if (backupRestoreDrill) {
      backupRestoreDrill.textContent = payload.last_restore_drill_at
        ? formatTimestamp(payload.last_restore_drill_at)
        : "—";
    }
    if (payload.restore_drill_status === "stale") {
      setBackupStatusMessage("Restore drill is stale.", true);
    }
  } catch (err) {
    setBackupStatusMessage("Backup status unavailable.", true);
  } finally {
    backupStatusInFlight = false;
  }
}

async function loadRetentionStatus() {
  if (!retentionStatusCard || retentionStatusInFlight) return;
  retentionStatusInFlight = true;
  try {
    const res = await fetch("/admin/api/retention/status");
    if (!res.ok) {
      setRetentionStatusMessage("Retention status unavailable.", true);
      if (retentionProfile) retentionProfile.textContent = "—";
      if (retentionInterval) retentionInterval.textContent = "—";
      if (retentionLastCleanup) retentionLastCleanup.textContent = "—";
      if (retentionLastDuration) retentionLastDuration.textContent = "";
      if (retentionNextRun) retentionNextRun.textContent = "—";
      return;
    }
    const payload = await res.json();
    setRetentionStatusMessage("", false);
    if (retentionProfile) retentionProfile.textContent = payload.profile || "—";
    const intervalSeconds = Number.isFinite(payload.cleanup_interval_seconds)
      ? payload.cleanup_interval_seconds
      : null;
    if (retentionInterval) {
      retentionInterval.textContent = Number.isFinite(intervalSeconds)
        ? formatInterval(intervalSeconds)
        : "—";
    }
    if (retentionLastCleanup) {
      retentionLastCleanup.textContent = payload.last_cleanup_at
        ? formatTimestamp(payload.last_cleanup_at)
        : "—";
    }
    if (retentionLastDuration) {
      retentionLastDuration.textContent = Number.isFinite(payload.last_cleanup_duration_ms)
        ? `Duration ${payload.last_cleanup_duration_ms}ms`
        : "";
    }
    if (retentionNextRun) {
      let nextRunAt = null;
      if (Number.isFinite(intervalSeconds)) {
        const lastMs = payload.last_cleanup_at ? Date.parse(payload.last_cleanup_at) : NaN;
        const baseMs = Number.isFinite(lastMs) ? lastMs : Date.now();
        nextRunAt = baseMs + intervalSeconds * 1000;
      }
      retentionNextRun.textContent = nextRunAt ? formatTimestamp(new Date(nextRunAt).toISOString()) : "—";
    }
  } catch (err) {
    setRetentionStatusMessage("Retention status unavailable.", true);
    if (retentionNextRun) retentionNextRun.textContent = "—";
  } finally {
    retentionStatusInFlight = false;
  }
}

function renderAlerts(items) {
  if (!alertsTable) return;
  const tbody = alertsTable.querySelector("tbody");
  tbody.textContent = "";
  if (!items || items.length === 0) {
    const row = document.createElement("tr");
    row.innerHTML = `<td colspan="4" class="muted">No active alerts.</td>`;
    tbody.appendChild(row);
    return;
  }
  items.forEach((item) => {
    const row = document.createElement("tr");
    const severity = item.severity || "unknown";
    row.innerHTML = `
      <td>${item.name || item.id || "Alert"}</td>
      <td><span class="badge ${severity === "warning" ? "" : "off"}">${severity}</span></td>
      <td>${formatTimestamp(item.starts_at)}</td>
      <td><button type="button" class="pill-button" data-alert-id="${item.id}">Ack</button></td>
    `;
    tbody.appendChild(row);
  });
  tbody.querySelectorAll("button[data-alert-id]").forEach((button) => {
    button.addEventListener("click", async () => {
      const alertId = button.getAttribute("data-alert-id");
      if (!alertId) return;
      const note = window.prompt("Ack note (optional):", "");
      if (note === null) return;
      button.disabled = true;
      await acknowledgeAlert(alertId, note);
      button.disabled = false;
    });
  });
}

function renderIncidents(items) {
  if (!incidentsTable) return;
  const tbody = incidentsTable.querySelector("tbody");
  tbody.textContent = "";
  if (!items || items.length === 0) {
    const row = document.createElement("tr");
    row.innerHTML = `<td colspan="2" class="muted">No recent incidents.</td>`;
    tbody.appendChild(row);
    return;
  }
  items.forEach((item) => {
    const row = document.createElement("tr");
    row.innerHTML = `
      <td>${item.name || item.id || "Incident"}</td>
      <td>${formatTimestamp(item.acknowledged_at)}</td>
    `;
    tbody.appendChild(row);
  });
}

async function acknowledgeAlert(alertId, note) {
  try {
    const res = await fetch(`/admin/api/alerts/${encodeURIComponent(alertId)}/ack`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ note }),
    });
    if (!res.ok) {
      setAlertsStatusMessage("Unable to acknowledge alert.", true);
      return;
    }
    setAlertsStatusMessage("Alert acknowledged.", false);
    await loadAlertsStatus();
  } catch (err) {
    setAlertsStatusMessage("Unable to acknowledge alert.", true);
  }
}

async function loadAlertsStatus() {
  if (!alertsStatusCard || alertsStatusInFlight) return;
  alertsStatusInFlight = true;
  try {
    const res = await fetch("/admin/api/alerts");
    if (!res.ok) {
      setAlertsStatusMessage("Alerts unavailable.", true);
      renderAlerts([]);
      renderIncidents([]);
      return;
    }
    const payload = await res.json();
    setAlertsStatusMessage("", false);
    renderAlerts(payload.items || []);
    renderIncidents(payload.incidents || []);
  } catch (err) {
    setAlertsStatusMessage("Alerts unavailable.", true);
    renderAlerts([]);
    renderIncidents([]);
  } finally {
    alertsStatusInFlight = false;
  }
}

function setSettingsStatus(text, isError) {
  if (!settingsStatus) return;
  settingsStatus.textContent = text;
  settingsStatus.style.color = isError ? "#a12b2b" : "#4b443b";
}

function setDiscoveryStatus(text, isError) {
  if (!settingsDiscoveryStatus) return;
  settingsDiscoveryStatus.textContent = text;
  settingsDiscoveryStatus.style.color = isError ? "#a12b2b" : "#4b443b";
}

function renderSettingsSummary(payload) {
  if (!settingsTable) return;
  const rows = [
    {
      label: "Request signatures",
      value: payload.request_signature_enabled ? "enabled" : "disabled",
    },
    {
      label: "Federation allowlist",
      value: payload.federation_peer_allowlist ? payload.federation_peer_allowlist : "—",
    },
    {
      label: "Rate limit window",
      value: Number.isFinite(payload.rate_limit_window_seconds)
        ? formatInterval(payload.rate_limit_window_seconds)
        : "—",
    },
    {
      label: "Cleanup interval",
      value: Number.isFinite(payload.cleanup_interval_seconds)
        ? formatInterval(payload.cleanup_interval_seconds)
        : "—",
    },
    {
      label: "Backup retention",
      value: Number.isFinite(payload.backup_retention_days)
        ? `${payload.backup_retention_days}d`
        : "—",
    },
    {
      label: "Admin session TTL",
      value: Number.isFinite(payload.admin_session_ttl_seconds)
        ? formatInterval(payload.admin_session_ttl_seconds)
        : "—",
    },
  ];
  const tbody = settingsTable.querySelector("tbody");
  tbody.textContent = "";
  rows.forEach((row) => {
    const tr = document.createElement("tr");
    tr.innerHTML = `<td>${row.label}</td><td>${row.value}</td>`;
    tbody.appendChild(tr);
  });
  if (settingsLogPaths) {
    const auditPath = payload.log_paths && payload.log_paths.audit ? payload.log_paths.audit : "—";
    settingsLogPaths.textContent = `audit: ${auditPath}`;
  }
  if (settingsAutoConnect) {
    settingsAutoConnect.checked = Boolean(payload.auto_connect_discovered_peers);
  }
}

async function loadSettingsSummary() {
  if (!settingsTable) return;
  try {
    const res = await fetch("/admin/api/settings/summary");
    if (!res.ok) {
      setSettingsStatus("Settings summary unavailable.", true);
      return;
    }
    const payload = await res.json();
    setSettingsStatus("", false);
    renderSettingsSummary(payload || {});
  } catch (err) {
    setSettingsStatus("Settings summary unavailable.", true);
  }
}

async function saveDiscoverySettings(enabled) {
  try {
    const res = await fetch("/admin/api/settings/discovery", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ auto_connect_discovered_peers: enabled }),
    });
    if (!res.ok) {
      setDiscoveryStatus("Failed to update discovery setting.", true);
      return;
    }
    setDiscoveryStatus("Discovery setting updated.", false);
  } catch (err) {
    setDiscoveryStatus("Failed to update discovery setting.", true);
  }
}

async function pollAlertsStatus() {
  if (!alertsStatusCard || document.hidden || alertsStatusInFlight) return;
  await loadAlertsStatus();
}

function startAlertsStatusPolling() {
  if (!alertsStatusCard || alertsStatusTimer) return;
  loadAlertsStatus();
  alertsStatusTimer = window.setInterval(pollAlertsStatus, ALERTS_STATUS_POLL_MS);
}

function stopAlertsStatusPolling() {
  if (alertsStatusTimer) {
    window.clearInterval(alertsStatusTimer);
    alertsStatusTimer = null;
  }
}

async function pollRetentionStatus() {
  if (!retentionStatusCard || document.hidden || retentionStatusInFlight) return;
  await loadRetentionStatus();
}

function startRetentionStatusPolling() {
  if (!retentionStatusCard || retentionStatusTimer) return;
  loadRetentionStatus();
  retentionStatusTimer = window.setInterval(pollRetentionStatus, RETENTION_STATUS_POLL_MS);
}

function stopRetentionStatusPolling() {
  if (retentionStatusTimer) {
    window.clearInterval(retentionStatusTimer);
    retentionStatusTimer = null;
  }
}

async function pollBackupStatus() {
  if (!backupStatusCard || document.hidden || backupStatusInFlight) return;
  await loadBackupStatus();
}

function startBackupStatusPolling() {
  if (!backupStatusCard || backupStatusTimer) return;
  loadBackupStatus();
  backupStatusTimer = window.setInterval(pollBackupStatus, BACKUP_STATUS_POLL_MS);
}

function stopBackupStatusPolling() {
  if (backupStatusTimer) {
    window.clearInterval(backupStatusTimer);
    backupStatusTimer = null;
  }
}

async function pollDashboardMetrics() {
  if (!dashboardMetricsCard || document.hidden || dashboardMetricsInFlight) return;
  await loadDashboardMetrics();
}

function startDashboardMetricsPolling() {
  if (!dashboardMetricsCard || dashboardMetricsTimer) return;
  loadDashboardMetrics();
  dashboardMetricsTimer = window.setInterval(pollDashboardMetrics, DASHBOARD_METRICS_POLL_MS);
}

function stopDashboardMetricsPolling() {
  if (dashboardMetricsTimer) {
    window.clearInterval(dashboardMetricsTimer);
    dashboardMetricsTimer = null;
  }
}

async function pollMetricsDetail() {
  if (!metricsDetailTable || document.hidden || metricsDetailInFlight) return;
  await loadMetricsDetail(metricsDetailDomain);
}

function startMetricsDetailPolling() {
  if (!metricsDetailTable || metricsDetailTimer) return;
  loadMetricsDetail(metricsDetailDomain);
  metricsDetailTimer = window.setInterval(pollMetricsDetail, METRICS_DETAIL_POLL_MS);
}

function stopMetricsDetailPolling() {
  if (metricsDetailTimer) {
    window.clearInterval(metricsDetailTimer);
    metricsDetailTimer = null;
  }
}

function getPeerIdFromUrl() {
  if (!peerTitle) return null;
  const params = new URLSearchParams(window.location.search);
  return params.get("id");
}

function renderPeerDetail(item, history) {
  if (!item) return;
  const status = peerHealth(item);
  if (peerTitle) peerTitle.textContent = item.peer_url || item.id || "Peer Detail";
  if (peerSubtitle) peerSubtitle.textContent = item.id ? `Peer ID: ${item.id}` : "";
  if (peerStatus) peerStatus.textContent = status;
  if (peerLag) peerLag.textContent = item.cursor_lag_seconds ?? "—";
  if (peerBackpressure) peerBackpressure.textContent = item.backpressure_queue ?? "—";
  if (peerUrl) peerUrl.textContent = item.peer_url || "—";
  if (peerRemoteConnected) {
    peerRemoteConnected.textContent =
      item.remote_connected === null || item.remote_connected === undefined
        ? "--"
        : String(item.remote_connected);
  }
  if (peerRemoteCapacity) {
    peerRemoteCapacity.textContent =
      item.remote_capacity === null || item.remote_capacity === undefined
        ? "--"
        : String(item.remote_capacity);
  }
  if (peerRemoteTtl) {
    const ttl =
      item.remote_heartbeat_ttl_seconds === null ||
      item.remote_heartbeat_ttl_seconds === undefined
        ? "--"
        : String(item.remote_heartbeat_ttl_seconds);
    peerRemoteTtl.textContent = ttl === "--" ? "--" : `${ttl}s`;
  }
  if (peerLastSync) peerLastSync.textContent = formatTimestamp(item.last_sync_at);
  if (peerLastSuccess) peerLastSuccess.textContent = formatTimestamp(item.last_success_at);
  if (peerLastError) peerLastError.textContent = item.last_error || "—";
  if (peerAutoSync) {
    peerAutoSync.textContent =
      typeof item.auto_sync === "boolean" ? (item.auto_sync ? "enabled" : "disabled") : "—";
  }
  if (peerSyncGids) {
    peerSyncGids.textContent =
      Array.isArray(item.sync_gids) && item.sync_gids.length > 0
        ? item.sync_gids.join(", ")
        : "All";
  }
  if (peerLastSeen) peerLastSeen.textContent = formatTimestamp(item.last_seen_at);
  if (peerOnlineStatus) {
    const onlineStatus = item.online_status || "unknown";
    if (onlineStatus === "online" || onlineStatus === "offline") {
      peerOnlineStatus.innerHTML = `<span class="pill ${onlineStatus}">${onlineStatus}</span>`;
    } else {
      peerOnlineStatus.textContent = "—";
    }
  }

  if (!peerHistoryTable) return;
  const tbody = peerHistoryTable.querySelector("tbody");
  tbody.textContent = "";
  if (history.length === 0) {
    const row = document.createElement("tr");
    row.innerHTML = `<td colspan="4" class="muted">No sync history yet.</td>`;
    tbody.appendChild(row);
  } else {
    history.forEach((entry) => {
      const row = document.createElement("tr");
      row.innerHTML = `
        <td>${formatTimestamp(entry.ts)}</td>
        <td>${entry.result}</td>
        <td>${entry.lag_seconds ?? "—"}</td>
        <td>${entry.error || "—"}</td>
      `;
      tbody.appendChild(row);
    });
  }

  renderPeerHistoryChart(history);
}

function renderPeerHistoryChart(history) {
  if (!peerHistoryChart) return;
  if (peerHistoryEmpty) {
    peerHistoryEmpty.classList.toggle("hidden", history.length > 0);
  }
  peerHistoryChart.textContent = "";
  if (history.length === 0) return;
  const slice = history.slice(0, 12);
  const hasLag = slice.some((entry) => Number.isFinite(entry.lag_seconds));
  if (peerHistoryLagMissing) {
    peerHistoryLagMissing.classList.toggle("hidden", hasLag);
  }
  if (!hasLag) return;
  const maxLag = slice.reduce((max, entry) => {
    const value = entry.lag_seconds || 0;
    return value > max ? value : max;
  }, 1);
  slice.forEach((entry) => {
    const value = entry.lag_seconds || 0;
    const height = Math.max(6, Math.round((value / maxLag) * 100));
    const bar = document.createElement("div");
    bar.className = `chart-bar${entry.result === "fail" ? " fail" : ""}`;
    bar.style.height = `${height}%`;
    bar.title = `${entry.result.toUpperCase()} · ${value}s`;
    peerHistoryChart.appendChild(bar);
  });
}

function mockPeerDetail(peerId) {
  const now = Date.now();
  return {
    item: {
      id: peerId || "peer-a1",
      peer_url: "https://relay-north.example.com",
      status: "ok",
      last_sync_at: new Date(now - 2 * 60 * 1000).toISOString(),
      last_success_at: new Date(now - 5 * 60 * 1000).toISOString(),
      cursor_lag_seconds: 12,
      backpressure_queue: 0,
      failures_10m: 0,
      last_error: "",
      remote_connected: 3,
      remote_capacity: 32,
      remote_heartbeat_ttl_seconds: 120,
    },
    history: [
      {
        ts: new Date(now - 6 * 60 * 1000).toISOString(),
        result: "ok",
        lag_seconds: 14,
        error: "",
      },
      {
        ts: new Date(now - 16 * 60 * 1000).toISOString(),
        result: "ok",
        lag_seconds: 21,
        error: "",
      },
      {
        ts: new Date(now - 36 * 60 * 1000).toISOString(),
        result: "fail",
        lag_seconds: 84,
        error: "Timeout on batch fetch",
      },
    ],
  };
}

async function loadPeerDetail() {
  const peerId = getPeerIdFromUrl();
  if (!peerId) return;
  try {
    const res = await fetch(`/admin/api/peers/${encodeURIComponent(peerId)}`);
    const historyRes = await fetch(`/admin/api/peers/${encodeURIComponent(peerId)}/history`);
    if (res.ok && historyRes.ok) {
      const payload = await res.json();
      const historyPayload = await historyRes.json();
      renderPeerDetail(payload.item, historyPayload.items || []);
      if (peerDetailStatus) peerDetailStatus.textContent = "";
      return;
    }
    const fallback = mockPeerDetail(peerId);
    renderPeerDetail(fallback.item, fallback.history);
    if (peerDetailStatus) {
      peerDetailStatus.textContent = "Showing sample data (API not ready).";
      peerDetailStatus.style.color = "#a12b2b";
    }
  } catch (err) {
    const fallback = mockPeerDetail(peerId);
    renderPeerDetail(fallback.item, fallback.history);
    if (peerDetailStatus) {
      peerDetailStatus.textContent = "Showing sample data (API not reachable).";
      peerDetailStatus.style.color = "#a12b2b";
    }
  }
}

function wirePeerControls() {
  if (!peerResync || !peerClearBackoff) return;
  const peerId = getPeerIdFromUrl();
  if (!peerId) return;
  const showControlStatus = (text, isError) => {
    if (!peerControlStatus) return;
    peerControlStatus.textContent = text;
    peerControlStatus.style.color = isError ? "#a12b2b" : "#4b443b";
  };
  peerResync.addEventListener("click", async () => {
    try {
      const res = await fetch(`/admin/api/peers/${encodeURIComponent(peerId)}/resync`, {
        method: "POST",
      });
      if (!res.ok) {
        showControlStatus("Resync not available yet (stub).", true);
        return;
      }
      showControlStatus("Resync triggered.", false);
    } catch (err) {
      showControlStatus("Resync not available yet (stub).", true);
    }
  });
  peerClearBackoff.addEventListener("click", async () => {
    try {
      const res = await fetch(
        `/admin/api/peers/${encodeURIComponent(peerId)}/clear-backoff`,
        { method: "POST" }
      );
      if (!res.ok) {
        showControlStatus("Clear backoff not available yet (stub).", true);
        return;
      }
      showControlStatus("Backoff cleared.", false);
    } catch (err) {
      showControlStatus("Clear backoff not available yet (stub).", true);
    }
  });
}

function wirePeerRefresh() {
  const peerId = getPeerIdFromUrl();
  if (!peerId) return;
  let refreshTimer = null;
  const startAutoRefresh = () => {
    if (refreshTimer) return;
    refreshTimer = window.setInterval(loadPeerDetail, 30000);
  };
  const stopAutoRefresh = () => {
    if (!refreshTimer) return;
    window.clearInterval(refreshTimer);
    refreshTimer = null;
  };
  startAutoRefresh();
  if (peerRefresh) {
    peerRefresh.addEventListener("click", () => {
      loadPeerDetail();
      if (peerControlStatus) {
        peerControlStatus.textContent = "Refreshed.";
        peerControlStatus.style.color = "#4b443b";
      }
    });
  }
  window.addEventListener("beforeunload", stopAutoRefresh);
}

if (menuToggle) {
  menuToggle.addEventListener("click", toggleMenu);
}
renderBreadcrumbs();

document.addEventListener("visibilitychange", () => {
  if (document.hidden) {
    stopPeersPolling();
    stopMetricsDetailPolling();
    stopDashboardMetricsPolling();
    stopBackupStatusPolling();
    stopRetentionStatusPolling();
    stopAlertsStatusPolling();
  } else {
    startPeersPolling();
    startMetricsDetailPolling();
    startDashboardMetricsPolling();
    startBackupStatusPolling();
    startRetentionStatusPolling();
    startAlertsStatusPolling();
  }
});

if (loginForm) {
  loginForm.addEventListener("submit", login);
}
if (logoutBtn) {
  logoutBtn.addEventListener("click", logout);
}
if (accountLogout) {
  accountLogout.addEventListener("click", logout);
}
if (accountChangePassword) {
  accountChangePassword.addEventListener("click", changePassword);
}

checkSession();
loadBanlist();
loadPeers();
loadInboundPeers();
loadPeerDetail();
wirePeerControls();
wirePeerRefresh();
wirePeerJoinForm();
loadPeerMappings();
loadFederationSelf();
loadReputation();
loadAudit();
loadSettingsSummary();
startPeersPolling();
startMetricsDetailPolling();
startDashboardMetricsPolling();
startBackupStatusPolling();
startRetentionStatusPolling();
startAlertsStatusPolling();
if (peerMappingsTable) {
  peerMappingsTable.addEventListener("click", handlePeerMappingsAction);
}
if (reputationForm) {
  reputationForm.addEventListener("submit", handleReputationSubmit);
}
if (reputationTable) {
  reputationTable.addEventListener("click", handleReputationAction);
}
if (reputationSearch) {
  reputationSearch.addEventListener("input", () => {
    renderReputation(applyReputationFilters(reputationItems));
  });
}
if (reputationExportCsv) {
  reputationExportCsv.addEventListener("click", exportReputationCsv);
}
if (reputationImportBtn) {
  reputationImportBtn.addEventListener("click", importReputation);
}
if (auditRefresh) {
  auditRefresh.addEventListener("click", loadAudit);
}
if (auditScope) {
  auditScope.addEventListener("change", loadAudit);
}
if (auditLimit) {
  auditLimit.addEventListener("change", loadAudit);
}
if (auditSince) {
  auditSince.addEventListener("change", loadAudit);
}
if (auditSearch) {
  auditSearch.addEventListener("input", () => {
    renderAudit(applyAuditFilters(auditItems));
  });
}
if (banlistForm) {
  banlistForm.addEventListener("submit", handleBanlistSubmit);
}
if (banlistTable) {
  banlistTable.addEventListener("click", handleBanlistAction);
}
if (banlistSearch) {
  banlistSearch.addEventListener("input", () => {
    renderBanlist(applyBanlistFilters(banlistItems));
  });
}
if (banlistFilter) {
  banlistFilter.addEventListener("change", () => {
    renderBanlist(applyBanlistFilters(banlistItems));
  });
}
if (banlistExportCsv) {
  banlistExportCsv.addEventListener("click", () => exportBanlist("csv"));
}
if (banlistImportBtn) {
  banlistImportBtn.addEventListener("click", importBanlist);
}
if (settingsRefresh) {
  settingsRefresh.addEventListener("click", loadSettingsSummary);
}
if (settingsAutoConnect) {
  settingsAutoConnect.addEventListener("change", () => {
    setDiscoveryStatus("", false);
    saveDiscoverySettings(settingsAutoConnect.checked);
  });
}
if (peersSearch) {
  peersSearch.addEventListener("input", () => {
    const filtered = applyPeersFilters(livePeersItems);
    renderPeers(filtered);
    updatePeerKpis(peersItems);
  });
}
if (peersTable) {
  peersTable.addEventListener("click", handleLivePeersAction);
}
if (metricsDetailButtons && metricsDetailButtons.length > 0) {
  metricsDetailButtons.forEach((button) => {
    button.addEventListener("click", () => {
      const domain = button.getAttribute("data-metrics-domain") || "traffic";
      setActiveMetricsDomain(domain);
      loadMetricsDetail(domain);
    });
  });
}
if (metricsDetailRefresh) {
  metricsDetailRefresh.addEventListener("click", () => {
    loadMetricsDetail(metricsDetailDomain);
  });
}
if (selfTokenCreate) {
  selfTokenCreate.addEventListener("click", () => setTokenEditMode("create"));
}
if (selfTokenCopy) {
  selfTokenCopy.addEventListener("click", () => {
    const token = selfTokenCopy.dataset.token || "";
    copyPublicToken(token);
  });
}
if (selfTokenReplace) {
  selfTokenReplace.addEventListener("click", () => {
    setTokenEditMode("replace");
  });
}
if (selfTokenRevoke) {
  selfTokenRevoke.addEventListener("click", () => {
    const confirmed = window.confirm("Revoke the public token?");
    if (!confirmed) return;
    revokePublicToken();
  });
}
if (selfTokenSave) {
  selfTokenSave.addEventListener("click", () => {
    if (!selfTokenMode) return;
    const value = String(selfTokenInput ? selfTokenInput.value : "").trim();
    if (!/^[A-Za-z0-9-]{12,}$/.test(value)) {
      showToast("Token must be at least 12 characters (letters, numbers, hyphen).", true);
      return;
    }
    if (!value) {
      showToast("Public token is required.", true);
      return;
    }
    createOrReplacePublicToken(selfTokenMode, value);
  });
}
if (selfTokenGenerate) {
  selfTokenGenerate.addEventListener("click", () => {
    if (!selfTokenInput) return;
    selfTokenInput.value = generatePublicToken(24);
    selfTokenInput.focus();
  });
}
if (selfTokenCancel) {
  selfTokenCancel.addEventListener("click", () => {
    setTokenEditMode(null);
    if (selfPublicToken) {
      selfPublicToken.textContent = selfTokenValue ? maskToken(selfTokenValue) : "missing";
      selfPublicToken.classList.toggle("muted", !selfTokenValue);
    }
    if (selfPublicTokenCreated) {
      selfPublicTokenCreated.textContent = selfTokenCreatedAt
        ? `Created ${formatTimestamp(selfTokenCreatedAt)}`
        : "";
    }
  });
}
