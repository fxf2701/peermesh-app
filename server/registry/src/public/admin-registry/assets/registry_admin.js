const loginPanel = document.getElementById("login-panel");
const appPanel = document.getElementById("app-panel");
const loginForm = document.getElementById("login-form");
const loginStatus = document.getElementById("login-status");
const logoutButton = document.getElementById("logout-button");
const refreshButton = document.getElementById("refresh-button");

const dashboardGrid = document.getElementById("dashboard-grid");
const healthOutput = document.getElementById("health-output");
const metricsGrid = document.getElementById("metrics-grid");
const metricsMeta = document.getElementById("metrics-meta");
const metricsRatesGrid = document.getElementById("metrics-rates-grid");
const metricsFederationGrid = document.getElementById("metrics-federation-grid");
const metricsNotes = document.getElementById("metrics-notes");
const relaysBody = document.getElementById("relays-body");
const relaySearch = document.getElementById("relay-search");
const relaySort = document.getElementById("relay-sort");
const relayOrder = document.getElementById("relay-order");
const relayPagination = document.getElementById("relay-pagination");
const relayTitle = document.getElementById("relay-title");
const relayDetailGrid = document.getElementById("relay-detail-grid");
const relayActions = document.getElementById("relay-actions");
const capabilitiesList = document.getElementById("capabilities-list");
const certificateDetail = document.getElementById("certificate-detail");
const certificatesBody = document.getElementById("certificates-body");
const certificateForm = document.getElementById("certificate-form");
const policyOutput = document.getElementById("policy-output");
const revocationsOutput = document.getElementById("revocations-output");
const onboardingForm = document.getElementById("onboarding-form");
const onboardingRelayId = document.getElementById("onboarding-relay-id");
const onboardingIncludeHeartbeat = document.getElementById("onboarding-include-heartbeat");
const onboardingIncludeClientAccess = document.getElementById("onboarding-include-client-access");
const onboardingIncludeFederationSessions = document.getElementById("onboarding-include-federation-sessions");
const onboardingReason = document.getElementById("onboarding-reason");
const onboardingStatus = document.getElementById("onboarding-status");
const onboardingOutput = document.getElementById("onboarding-output");
const onboardingCopyButton = document.getElementById("onboarding-copy-button");
const onboardingDownloadButton = document.getElementById("onboarding-download-button");
const auditBody = document.getElementById("audit-body");
const auditSort = document.getElementById("audit-sort");
const auditOrder = document.getElementById("audit-order");
const auditPagination = document.getElementById("audit-pagination");
const auditExportCsv = document.getElementById("audit-export-csv");
const auditExportJsonl = document.getElementById("audit-export-jsonl");

const federationSessionsBody = document.getElementById("federation-sessions-body");
const federationSummary = document.getElementById("federation-summary");
const federationSort = document.getElementById("federation-sort");
const federationOrder = document.getElementById("federation-order");
const federationPagination = document.getElementById("federation-pagination");
const topologySummary = document.getElementById("topology-summary");
const topologyNodes = document.getElementById("topology-nodes");
const federationDenialsBody = document.getElementById("federation-denials-body");
const topologyRebalanceButton = document.getElementById("topology-rebalance-button");

const federationSessionTitle = document.getElementById("federation-session-title");
const federationSessionDetailGrid = document.getElementById("federation-session-detail-grid");
const federationSessionActions = document.getElementById("federation-session-actions");
const federationSessionPeersBody = document.getElementById("federation-session-peers-body");
const federationSessionRelayGrid = document.getElementById("federation-session-relay-grid");
const moderationReportsBody = document.getElementById("moderation-reports-body");
const moderationReportsPagination = document.getElementById("moderation-reports-pagination");
const moderationReportStatus = document.getElementById("moderation-report-status");
const moderationReportTargetType = document.getElementById("moderation-report-target-type");
const moderationReportGroupId = document.getElementById("moderation-report-group-id");
const moderationReportReportedPeerId = document.getElementById("moderation-report-reported-peer-id");
const moderationReportTitle = document.getElementById("moderation-report-title");
const moderationReportDetailGrid = document.getElementById("moderation-report-detail-grid");
const moderationReportActions = document.getElementById("moderation-report-actions");
const moderationReportEvidence = document.getElementById("moderation-report-evidence");
const moderationReportNotes = document.getElementById("moderation-report-notes");
const feedbackBody = document.getElementById("feedback-body");
const feedbackPagination = document.getElementById("feedback-pagination");
const feedbackCategory = document.getElementById("feedback-category");
const feedbackSearch = document.getElementById("feedback-search");
const feedbackTitle = document.getElementById("feedback-title");
const feedbackDetailGrid = document.getElementById("feedback-detail-grid");
const feedbackMessage = document.getElementById("feedback-message");
const betaProgramForm = document.getElementById("beta-program-form");
const betaProgramEnabled = document.getElementById("beta-program-enabled");
const betaProgramSeason = document.getElementById("beta-program-season");
const betaProgramEndsAt = document.getElementById("beta-program-ends-at");
const betaProgramLatestVersion = document.getElementById("beta-program-latest-version");
const betaProgramMinimumSupportedVersion = document.getElementById("beta-program-minimum-supported-version");
const betaProgramChangelog = document.getElementById("beta-program-changelog");
const betaProgramReason = document.getElementById("beta-program-reason");
const betaProgramStatus = document.getElementById("beta-program-status");
const betaProgramOutput = document.getElementById("beta-program-output");
const betaProgramReset = document.getElementById("beta-program-reset");

let relays = [];
let currentRelay = null;
let csrfToken = "";
let currentOnboardingBundle = null;
const RELAY_PAGE_SIZE = 25;
const AUDIT_PAGE_SIZE = 25;
const FEDERATION_PAGE_SIZE = 25;
const MODERATION_REPORT_PAGE_SIZE = 25;
const FEEDBACK_PAGE_SIZE = 25;
const relayTableState = { limit: RELAY_PAGE_SIZE, offset: 0, sort: "relay_id", order: "asc", q: "" };
const auditTableState = { limit: AUDIT_PAGE_SIZE, offset: 0, sort: "created_at", order: "desc" };
const federationTableState = { limit: FEDERATION_PAGE_SIZE, offset: 0, sort: "issued_at", order: "desc" };
const moderationReportTableState = {
  limit: MODERATION_REPORT_PAGE_SIZE,
  offset: 0,
  sort: "received_at",
  order: "desc",
  status: "",
  target_type: "",
  group_id: "",
  reported_peer_id: "",
};
const feedbackTableState = {
  limit: FEEDBACK_PAGE_SIZE,
  offset: 0,
  sort: "received_at",
  order: "desc",
  category: "",
  q: "",
};
const DEFAULT_ERROR_MESSAGE = "Failed to load this panel. Refresh to retry.";

function currentRelayId() {
  const match = window.location.pathname.match(/^\/(?:registry\/)?admin\/relays\/(.+)$/);
  return match ? decodeURIComponent(match[1]) : null;
}

function currentFederationSessionId() {
  const match = window.location.pathname.match(/^\/(?:registry\/)?admin\/federation\/sessions\/(.+)$/);
  return match ? decodeURIComponent(match[1]) : null;
}

function currentModerationReportId() {
  const match = window.location.pathname.match(/^\/(?:registry\/)?admin\/moderation-reports\/(.+)$/);
  return match ? decodeURIComponent(match[1]) : null;
}

function currentFeedbackId() {
  const match = window.location.pathname.match(/^\/(?:registry\/)?admin\/feedback\/(.+)$/);
  return match ? decodeURIComponent(match[1]) : null;
}

function setLoggedIn(loggedIn) {
  loginPanel?.classList.toggle("hidden", loggedIn);
  appPanel?.classList.toggle("hidden", !loggedIn);
  logoutButton?.classList.toggle("hidden", !loggedIn);
}

async function api(path, options = {}) {
  const method = String(options.method || "GET").toUpperCase();
  const res = await fetch(path, {
    headers: {
      "Content-Type": "application/json",
      ...(method !== "GET" && method !== "HEAD" && csrfToken ? { "X-CSRF-Token": csrfToken } : {}),
      ...(options.headers || {}),
    },
    ...options,
  });
  const payload = await res.json().catch(() => ({}));
  if (!res.ok || payload.ok === false) {
    const message = payload.error && payload.error.message
      ? payload.error.message
      : `Request failed: ${res.status}`;
    throw new Error(message);
  }
  return payload.data || {};
}

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function formatEpoch(value) {
  if (!Number.isFinite(value)) return "-";
  const epochMs = value > 1_000_000_000_000 ? value : value * 1000;
  return new Date(epochMs).toLocaleString();
}

function badge(value) {
  const css = String(value || "").replace(/[^a-z0-9_-]/gi, "");
  return `<span class="badge ${css}">${escapeHtml(value || "-")}</span>`;
}

function detailItem(label, value) {
  return `
    <div class="detail-item">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(value || "-")}</strong>
    </div>
  `;
}

function joinList(items) {
  return Array.isArray(items) && items.length > 0 ? items.join(", ") : "-";
}

function encodeQuery(params) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === null || value === undefined || value === "") return;
    search.set(key, String(value));
  });
  const encoded = search.toString();
  return encoded ? `?${encoded}` : "";
}

function renderPagination(container, state, total, label, onPageChange) {
  if (!container) return;
  const safeTotal = Number.isFinite(total) ? total : 0;
  const safeLimit = Math.max(1, Number(state.limit) || 1);
  const safeOffset = Math.max(0, Number(state.offset) || 0);
  const start = safeTotal === 0 ? 0 : safeOffset + 1;
  const end = Math.min(safeOffset + safeLimit, safeTotal);
  const prevDisabled = safeOffset <= 0 ? "disabled" : "";
  const nextDisabled = end >= safeTotal ? "disabled" : "";
  container.innerHTML = `
    <div>${escapeHtml(label)} ${safeTotal === 0 ? "0" : `${start}-${end}`} of ${safeTotal}</div>
    <div class="pagination-actions">
      <button type="button" data-page-action="prev" ${prevDisabled}>Prev</button>
      <button type="button" data-page-action="next" ${nextDisabled}>Next</button>
    </div>
  `;
  container.querySelector('[data-page-action="prev"]')?.addEventListener("click", () => {
    if (safeOffset <= 0) return;
    onPageChange(Math.max(0, safeOffset - safeLimit));
  });
  container.querySelector('[data-page-action="next"]')?.addEventListener("click", () => {
    if (end >= safeTotal) return;
    onPageChange(safeOffset + safeLimit);
  });
}

function currentAuditFilters() {
  const relayId = currentRelayId();
  if (relayId) {
    return {
      resource_type: "relay",
      resource_id: relayId,
    };
  }
  const sessionId = currentFederationSessionId();
  if (sessionId) {
    return {
      resource_type: "federation_session",
      resource_id: sessionId,
    };
  }
  const reportId = currentModerationReportId();
  if (reportId) {
    return {
      resource_type: "client_moderation_report",
      resource_id: reportId,
    };
  }
  const feedbackId = currentFeedbackId();
  if (feedbackId) {
    return {
      resource_type: "client_feedback",
      resource_id: feedbackId,
    };
  }
  return {};
}

function refreshAuditExportLinks() {
  const filters = currentAuditFilters();
  const query = encodeQuery({
    format: "csv",
    sort: auditTableState.sort,
    order: auditTableState.order,
    ...filters,
  });
  const jsonlQuery = encodeQuery({
    format: "jsonl",
    sort: auditTableState.sort,
    order: auditTableState.order,
    ...filters,
  });
  if (auditExportCsv) auditExportCsv.href = `/admin/api/audit/export${query}`;
  if (auditExportJsonl) auditExportJsonl.href = `/admin/api/audit/export${jsonlQuery}`;
}

function panelStateMarkup(kind, message) {
  return `<div class="panel-state ${escapeHtml(kind)}">${escapeHtml(message)}</div>`;
}

function setTableState(body, colSpan, kind, message) {
  if (!body) return;
  body.innerHTML = `<tr class="panel-state-row"><td colspan="${colSpan}">${panelStateMarkup(kind, message)}</td></tr>`;
}

function setBlockState(container, kind, message) {
  if (!container) return;
  container.innerHTML = panelStateMarkup(kind, message);
}

function setTextState(container, kind, message) {
  if (!container) return;
  container.innerHTML = `<span class="panel-state-inline ${escapeHtml(kind)}">${escapeHtml(message)}</span>`;
}

function setLoadingStates() {
  if (dashboardGrid) setBlockState(dashboardGrid, "loading", "Loading governance snapshot...");
  if (healthOutput) setTextState(healthOutput, "loading", "Loading registry health...");
  if (metricsGrid) setBlockState(metricsGrid, "loading", "Loading metrics...");
  if (metricsMeta) setTextState(metricsMeta, "loading", "Loading metrics metadata...");
  if (metricsRatesGrid) setBlockState(metricsRatesGrid, "loading", "Loading rate metrics...");
  if (metricsFederationGrid) setBlockState(metricsFederationGrid, "loading", "Loading federation metrics...");
  if (metricsNotes) setBlockState(metricsNotes, "loading", "Loading metric notes...");
  if (relaysBody) setTableState(relaysBody, 8, "loading", "Loading relays...");
  if (relayPagination) relayPagination.innerHTML = "";
  if (relayDetailGrid) setBlockState(relayDetailGrid, "loading", "Loading relay details...");
  if (relayActions) relayActions.innerHTML = "";
  if (capabilitiesList) setBlockState(capabilitiesList, "loading", "Loading capabilities...");
  if (certificateDetail) setBlockState(certificateDetail, "loading", "Loading certificate details...");
  if (certificatesBody) setTableState(certificatesBody, 5, "loading", "Loading certificates...");
  if (policyOutput) policyOutput.textContent = "Loading policy feed...";
  if (revocationsOutput) revocationsOutput.textContent = "Loading revocation feed...";
  if (onboardingOutput) onboardingOutput.textContent = "# Generate a bundle to export registry-managed onboarding settings.";
  if (auditBody) setTableState(auditBody, 6, "loading", "Loading audit log...");
  if (auditPagination) auditPagination.innerHTML = "";
  if (federationSessionsBody) setTableState(federationSessionsBody, 9, "loading", "Loading federation sessions...");
  if (federationPagination) federationPagination.innerHTML = "";
  if (federationSummary) setTextState(federationSummary, "loading", "Loading federation summary...");
  if (topologySummary) setTextState(topologySummary, "loading", "Loading topology...");
  if (topologyNodes) setBlockState(topologyNodes, "loading", "Loading topology nodes...");
  if (federationDenialsBody) setTableState(federationDenialsBody, 3, "loading", "Loading session denials...");
  if (federationSessionDetailGrid) setBlockState(federationSessionDetailGrid, "loading", "Loading session details...");
  if (federationSessionActions) federationSessionActions.innerHTML = "";
  if (federationSessionPeersBody) setTableState(federationSessionPeersBody, 5, "loading", "Loading assigned peers...");
  if (federationSessionRelayGrid) setBlockState(federationSessionRelayGrid, "loading", "Loading source relay...");
  if (moderationReportsBody) setTableState(moderationReportsBody, 8, "loading", "Loading moderation reports...");
  if (moderationReportsPagination) moderationReportsPagination.innerHTML = "";
  if (moderationReportDetailGrid) setBlockState(moderationReportDetailGrid, "loading", "Loading moderation report details...");
  if (moderationReportActions) moderationReportActions.innerHTML = "";
  if (moderationReportEvidence) moderationReportEvidence.textContent = "Loading moderation evidence...";
  if (moderationReportNotes) setBlockState(moderationReportNotes, "loading", "Loading operator notes...");
  if (feedbackBody) setTableState(feedbackBody, 6, "loading", "Loading feedback...");
  if (feedbackPagination) feedbackPagination.innerHTML = "";
  if (feedbackDetailGrid) setBlockState(feedbackDetailGrid, "loading", "Loading feedback details...");
  if (feedbackMessage) feedbackMessage.textContent = "Loading feedback message...";
  if (betaProgramOutput) betaProgramOutput.textContent = "Loading Beta Program config...";
  if (betaProgramStatus) betaProgramStatus.textContent = "";
}

async function loadPanel(loader, onError) {
  try {
    return await loader();
  } catch (err) {
    onError(err);
    return null;
  }
}

function setOnboardingStatus(message, kind = "") {
  if (!onboardingStatus) return;
  onboardingStatus.textContent = message || "";
  onboardingStatus.dataset.kind = kind || "";
}

function setBetaProgramStatus(message, kind = "") {
  if (!betaProgramStatus) return;
  betaProgramStatus.textContent = message || "";
  betaProgramStatus.dataset.kind = kind || "";
}

function changelogTextFromItems(items) {
  return Array.isArray(items) ? items.join("\n") : "";
}

function changelogItemsFromText(value) {
  return String(value || "")
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function renderBetaProgram(data) {
  if (betaProgramEnabled) betaProgramEnabled.checked = data?.enabled === true;
  if (betaProgramSeason) betaProgramSeason.value = data?.season || "";
  if (betaProgramEndsAt) betaProgramEndsAt.value = data?.endsAt || "";
  if (betaProgramLatestVersion) betaProgramLatestVersion.value = data?.latestVersion || "";
  if (betaProgramMinimumSupportedVersion) {
    betaProgramMinimumSupportedVersion.value = data?.minimumSupportedVersion || "";
  }
  if (betaProgramChangelog) betaProgramChangelog.value = changelogTextFromItems(data?.changelog);
  if (betaProgramOutput) betaProgramOutput.textContent = JSON.stringify(data || {}, null, 2);
}

async function loadBetaProgramPage() {
  if (!betaProgramOutput) return null;
  const data = await api("/admin/api/beta-program");
  renderBetaProgram(data);
  return data;
}

async function saveBetaProgram() {
  if (!betaProgramForm) return null;
  setBetaProgramStatus("Saving Beta Program config...", "loading");
  const data = await api("/admin/api/beta-program", {
    method: "PATCH",
    body: JSON.stringify({
      enabled: Boolean(betaProgramEnabled?.checked),
      season: betaProgramSeason?.value?.trim() || null,
      endsAt: betaProgramEndsAt?.value?.trim() || null,
      latestVersion: betaProgramLatestVersion?.value?.trim() || null,
      minimumSupportedVersion: betaProgramMinimumSupportedVersion?.value?.trim() || null,
      changelog: changelogItemsFromText(betaProgramChangelog?.value || ""),
      reason: betaProgramReason?.value?.trim() || "",
    }),
  });
  renderBetaProgram(data);
  if (betaProgramReason) betaProgramReason.value = "";
  setBetaProgramStatus("Beta Program config saved.", "success");
  return data;
}

function renderOnboardingBundle(bundle) {
  currentOnboardingBundle = bundle || null;
  if (onboardingOutput) {
    onboardingOutput.textContent = bundle?.content || "# Generate a bundle to export registry-managed onboarding settings.";
  }
}

async function generateOnboardingBundle() {
  if (!onboardingForm) return null;
  setOnboardingStatus("Generating onboarding bundle...", "loading");
  const bundle = await api("/admin/api/onboarding/relay-bundle", {
    method: "POST",
    body: JSON.stringify({
      relay_id: onboardingRelayId?.value?.trim() || "",
      include_heartbeat: onboardingIncludeHeartbeat?.checked !== false,
      include_client_access: Boolean(onboardingIncludeClientAccess?.checked),
      include_federation_sessions: Boolean(onboardingIncludeFederationSessions?.checked),
      reason: onboardingReason?.value?.trim() || "",
    }),
  });
  renderOnboardingBundle(bundle);
  const sections = Array.isArray(bundle.sections) && bundle.sections.length > 0 ? bundle.sections.join(", ") : "none";
  setOnboardingStatus(`Bundle generated. Sections: ${sections}.`, "success");
  return bundle;
}

async function copyOnboardingBundle() {
  if (!currentOnboardingBundle?.content) {
    setOnboardingStatus("Generate a bundle before copying.", "error");
    return;
  }
  try {
    await navigator.clipboard.writeText(currentOnboardingBundle.content);
    setOnboardingStatus("Bundle copied to clipboard.", "success");
  } catch (_) {
    setOnboardingStatus("Clipboard copy failed. Use Download instead.", "error");
  }
}

function downloadOnboardingBundle() {
  if (!currentOnboardingBundle?.content) {
    setOnboardingStatus("Generate a bundle before downloading.", "error");
    return;
  }
  const blob = new Blob([currentOnboardingBundle.content], { type: "text/plain;charset=utf-8" });
  const href = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = href;
  link.download = currentOnboardingBundle.filename || "relay-onboarding.env";
  link.click();
  URL.revokeObjectURL(href);
  setOnboardingStatus("Bundle download started.", "success");
}

function promptReason(label) {
  const reason = window.prompt(`${label} reason`, "") || "";
  const trimmed = reason.trim();
  if (!trimmed) {
    throw new Error("Reason is required.");
  }
  return trimmed;
}

function confirmDangerousAction({ title, target = "", reasonLabel = title }) {
  const message = target
    ? `${title}\n\nTarget: ${target}\n\nThis action will be recorded in the audit log. Continue?`
    : `${title}\n\nThis action will be recorded in the audit log. Continue?`;
  const confirmed = window.confirm(message);
  if (!confirmed) return null;
  return promptReason(reasonLabel);
}

function trustActionFor(relay, direction) {
  const levels = ["community", "verified", "trusted"];
  const index = levels.indexOf(relay.trust_level);
  const nextIndex = direction === "promote" ? index + 1 : index - 1;
  if (nextIndex < 0 || nextIndex >= levels.length) return "";
  return `
    <button data-action="trust" data-id="${escapeHtml(relay.relay_id)}" data-value="${levels[nextIndex]}">
      ${direction === "promote" ? "Promote Trust" : "Demote Trust"}
    </button>
  `;
}

function statusActions(relay) {
  if (relay.status === "active") {
    return `
      <button data-action="status" data-id="${escapeHtml(relay.relay_id)}" data-value="suspended">Suspend</button>
      <button data-action="status" data-id="${escapeHtml(relay.relay_id)}" data-value="revoked">Revoke</button>
      <button data-action="status" data-id="${escapeHtml(relay.relay_id)}" data-value="banned">Ban</button>
    `;
  }
  return `<button data-action="status" data-id="${escapeHtml(relay.relay_id)}" data-value="active">Reinstate</button>`;
}

function relayActionButtons(relay) {
  return `
    <a class="button-link" href="/admin/relays/${encodeURIComponent(relay.relay_id)}">View Details</a>
    ${trustActionFor(relay, "promote")}
    ${trustActionFor(relay, "demote")}
    ${statusActions(relay)}
  `;
}

function sessionActionButtons(session) {
  const revokeDisabled = session.status === "revoked" || session.status === "expired";
  return `
    <a class="button-link" href="/admin/federation/sessions/${encodeURIComponent(session.session_id)}">View Session</a>
    <button data-action="session-revoke" data-id="${escapeHtml(session.session_id)}" ${revokeDisabled ? "disabled" : ""}>
      Revoke Session
    </button>
  `;
}

function moderationReportActionButtons(report) {
  return `
    <a class="button-link" href="/admin/moderation-reports/${encodeURIComponent(report.report_id)}">View Report</a>
  `;
}

function renderDashboard(data) {
  if (!dashboardGrid) return;
  const cards = [
    ["Active Relays", data.active_relays],
    ["Community", data.community_relays],
    ["Verified", data.verified_relays],
    ["Trusted", data.trusted_relays],
    ["Suspended", data.suspended_relays],
    ["Revoked", data.revoked_relays],
    ["Banned", data.banned_relays],
    ["Heartbeats/min", data.heartbeats_per_minute],
    ["Discovery/min", data.discovery_requests_per_minute],
    ["Federation Sessions", data.federation_sessions_active],
  ];
  dashboardGrid.innerHTML = cards
    .map(([label, value]) => `
      <article class="kpi">
        <div class="kpi-label">${escapeHtml(label)}</div>
        <div class="kpi-value">${Number.isFinite(value) ? value : 0}</div>
      </article>
    `)
    .join("");
  if (healthOutput) {
    healthOutput.innerHTML = [
      `Registry Health: ${badge(data.health?.status || "unknown")}`,
      `Last Policy Publish: ${escapeHtml(formatEpoch(data.health?.last_policy_publish))}`,
      `Last Revocation Feed Publish: ${escapeHtml(formatEpoch(data.health?.last_revocation_feed_publish))}`,
    ].join("");
  }
}

function renderMetrics(data) {
  if (!metricsGrid) return;
  const cards = [
    ["Heartbeats/min", data.heartbeats_per_minute],
    ["Heartbeats/sec", data.heartbeats_per_second],
    ["Discovery/min", data.discovery_requests_per_minute],
    ["Discovery/sec", data.discovery_requests_per_second],
    ["Certificate Issues", data.certificate_issues],
    ["Certificate Revocations", data.certificate_revocations],
    ["Policy Updates", data.policy_updates],
    ["Audit Events", data.audit_events],
    ["Session Issues", data.federation_session_issues],
    ["Session Denials", data.federation_session_denials],
    ["Active Sessions", data.federation_sessions_active],
    ["Expired Sessions", data.federation_sessions_expired],
    ["Revoked Sessions", data.federation_sessions_revoked],
  ];
  metricsGrid.innerHTML = cards
    .map(([label, value]) => `
      <article class="kpi">
        <div class="kpi-label">${escapeHtml(label)}</div>
        <div class="kpi-value">${Number.isFinite(value) ? value : 0}</div>
      </article>
    `)
    .join("");
  if (metricsMeta) {
    metricsMeta.innerHTML = [
      `Window: ${escapeHtml(String(data.window_seconds || 60))}s`,
      `Generated At: ${escapeHtml(formatEpoch(data.now))}`,
    ].join("");
  }
  if (metricsRatesGrid) {
    metricsRatesGrid.innerHTML = [
      detailItem("Heartbeats / Minute", String(data.heartbeats_per_minute || 0)),
      detailItem("Heartbeats / Second", String(data.heartbeats_per_second || 0)),
      detailItem("Discovery / Minute", String(data.discovery_requests_per_minute || 0)),
      detailItem("Discovery / Second", String(data.discovery_requests_per_second || 0)),
      detailItem("Active Certificates", String(data.certificates_active || 0)),
      detailItem("Audit Events", String(data.audit_events || 0)),
    ].join("");
  }
  if (metricsFederationGrid) {
    metricsFederationGrid.innerHTML = [
      detailItem("Session Issues", String(data.federation_session_issues || 0)),
      detailItem("Session Denials", String(data.federation_session_denials || 0)),
      detailItem("Active Sessions", String(data.federation_sessions_active || 0)),
      detailItem("Expired Sessions", String(data.federation_sessions_expired || 0)),
      detailItem("Revoked Sessions", String(data.federation_sessions_revoked || 0)),
      detailItem("Policy Updates", String(data.policy_updates || 0)),
    ].join("");
  }
  if (metricsNotes) {
    metricsNotes.innerHTML = [
      detailItem("Heartbeat Source", "Recent relay last-seen activity"),
      detailItem("Discovery Source", "Registry runtime counter baseline"),
      detailItem("Certificate Source", "Admin audit actions plus active certificate inventory"),
      detailItem("Federation Source", "Session inventory and federation audit actions"),
    ].join("");
  }
}

function renderRelays(total = relays.length) {
  if (!relaysBody) return;
  if (relays.length === 0) {
    relaysBody.innerHTML = "<tr><td colspan=\"8\">No relays found.</td></tr>";
    renderPagination(relayPagination, relayTableState, total, "Relays", (nextOffset) => {
      relayTableState.offset = nextOffset;
      loadAll().catch((err) => {
        if (loginStatus) loginStatus.textContent = err.message;
      });
    });
    return;
  }
  relaysBody.innerHTML = relays
    .map((relay) => `
      <tr>
        <td data-label="Relay">
          <strong>${escapeHtml(relay.relay_id)}</strong>
          <div>${relay.active ? "active heartbeat" : "inactive heartbeat"}</div>
        </td>
        <td data-label="Endpoint">${escapeHtml(relay.endpoint || "-")}</td>
        <td data-label="Region">${escapeHtml(relay.region || "-")}</td>
        <td data-label="Trust">${badge(relay.trust_level)}</td>
        <td data-label="Status">${badge(relay.status)}</td>
        <td data-label="Federation">${relay.federation_enabled ? "enabled" : "disabled"}</td>
        <td data-label="Last Seen">${formatEpoch(relay.last_seen)}</td>
        <td data-label="Actions"><div class="actions">${relayActionButtons(relay)}</div></td>
      </tr>
    `)
    .join("");
  renderPagination(relayPagination, relayTableState, total, "Relays", (nextOffset) => {
    relayTableState.offset = nextOffset;
    loadAll().catch((err) => {
      if (loginStatus) loginStatus.textContent = err.message;
    });
  });
}

function renderRelayDetails(data) {
  currentRelay = data.item;
  if (!currentRelay) return;
  if (relayTitle) relayTitle.textContent = currentRelay.relay_id;
  if (relayDetailGrid) {
    relayDetailGrid.innerHTML = [
      detailItem("Relay ID", currentRelay.relay_id),
      detailItem("Endpoint", currentRelay.endpoint),
      detailItem("Region", currentRelay.region),
      detailItem("Trust Level", currentRelay.trust_level),
      detailItem("Status", currentRelay.status),
      detailItem("Federation Enabled", currentRelay.federation_enabled ? "yes" : "no"),
      detailItem("First Seen", formatEpoch(currentRelay.created_at)),
      detailItem("Last Seen", formatEpoch(currentRelay.last_seen)),
    ].join("");
  }
  if (relayActions) relayActions.innerHTML = relayActionButtons(currentRelay);
  if (capabilitiesList) {
    capabilitiesList.innerHTML = (currentRelay.capabilities || [])
      .map((capability) => `<span class="pill">${escapeHtml(capability)}</span>`)
      .join("") || "<span class=\"muted\">No capabilities reported.</span>";
  }
  if (certificateDetail) {
    const certificate = data.certificate;
    certificateDetail.innerHTML = certificate
      ? [
        detailItem("Certificate Status", certificate.status),
        detailItem("Issued At", formatEpoch(certificate.issued_at)),
        detailItem("Expires At", formatEpoch(certificate.expires_at)),
        detailItem("Revoked At", formatEpoch(certificate.revoked_at)),
      ].join("")
      : "<p>No relay identity certificate issued.</p>";
  }
  renderAudit(data.audit || []);
}

function renderCertificates(items) {
  if (!certificatesBody) return;
  if (!items || items.length === 0) {
    certificatesBody.innerHTML = "<tr><td colspan=\"5\">No certificates issued.</td></tr>";
    return;
  }
  certificatesBody.innerHTML = items
    .map((certificate) => `
      <tr>
        <td data-label="Relay"><a href="/admin/relays/${encodeURIComponent(certificate.relay_id)}">${escapeHtml(certificate.relay_id)}</a></td>
        <td data-label="Status">${badge(certificate.status)}</td>
        <td data-label="Issued At">${formatEpoch(certificate.issued_at)}</td>
        <td data-label="Expires At">${formatEpoch(certificate.expires_at)}</td>
        <td data-label="Actions">
          <div class="actions">
            <button data-action="certificate-renew" data-id="${escapeHtml(certificate.id)}">Renew</button>
            <button data-action="certificate-revoke" data-id="${escapeHtml(certificate.id)}">Revoke</button>
          </div>
        </td>
      </tr>
    `)
    .join("");
}

function renderAudit(items, total = items?.length || 0) {
  if (!auditBody) return;
  refreshAuditExportLinks();
  if (!items || items.length === 0) {
    auditBody.innerHTML = "<tr><td colspan=\"6\">No audit entries.</td></tr>";
    renderPagination(auditPagination, auditTableState, total, "Audit entries", (nextOffset) => {
      auditTableState.offset = nextOffset;
      loadAll().catch((err) => {
        if (loginStatus) loginStatus.textContent = err.message;
      });
    });
    return;
  }
  auditBody.innerHTML = items
    .map((entry) => `
      <tr>
        <td data-label="Time">${formatEpoch(entry.created_at)}</td>
        <td data-label="Actor">${escapeHtml(entry.actor)}</td>
        <td data-label="Action">${escapeHtml(entry.action)}</td>
        <td data-label="Resource">${escapeHtml(entry.resource_type)}${entry.resource_id ? `:${escapeHtml(entry.resource_id)}` : ""}</td>
        <td data-label="Result">${badge(entry.result || "success")}</td>
        <td data-label="Reason">${escapeHtml(entry.reason || "")}</td>
      </tr>
    `)
    .join("");
  renderPagination(auditPagination, auditTableState, total, "Audit entries", (nextOffset) => {
    auditTableState.offset = nextOffset;
    loadAll().catch((err) => {
      if (loginStatus) loginStatus.textContent = err.message;
    });
  });
}

function renderFederationSessions(items, total = items?.length || 0) {
  if (!federationSessionsBody) return;
  if (!items || items.length === 0) {
    federationSessionsBody.innerHTML = "<tr><td colspan=\"9\">No federation sessions.</td></tr>";
    renderPagination(federationPagination, federationTableState, total, "Sessions", (nextOffset) => {
      federationTableState.offset = nextOffset;
      loadAll().catch((err) => {
        if (loginStatus) loginStatus.textContent = err.message;
      });
    });
    return;
  }
  federationSessionsBody.innerHTML = items
    .map((session) => `
      <tr>
        <td data-label="Session"><strong>${escapeHtml(session.session_id)}</strong></td>
        <td data-label="Source Relay"><a href="/admin/relays/${encodeURIComponent(session.source_relay_id)}">${escapeHtml(session.source_relay_id)}</a></td>
        <td data-label="Peers">${session.peer_count}</td>
        <td data-label="Permissions">${escapeHtml(joinList(session.permissions))}</td>
        <td data-label="Allowed GIDs">${escapeHtml(joinList(session.requested_gids))}</td>
        <td data-label="Issued">${formatEpoch(session.issued_at)}</td>
        <td data-label="Expires">${formatEpoch(session.expires_at)}</td>
        <td data-label="Status">${badge(session.status)}</td>
        <td data-label="Actions"><div class="actions">${sessionActionButtons(session)}</div></td>
      </tr>
    `)
    .join("");
  renderPagination(federationPagination, federationTableState, total, "Sessions", (nextOffset) => {
    federationTableState.offset = nextOffset;
    loadAll().catch((err) => {
      if (loginStatus) loginStatus.textContent = err.message;
    });
  });
}

function renderTopology(topology) {
  if (federationSummary) {
    federationSummary.innerHTML = [
      `Active Sessions: ${escapeHtml(String(topology?.active_session_count || 0))}`,
      `Edges: ${escapeHtml(String(topology?.edge_count || 0))}`,
      `Unassigned Relays: ${escapeHtml(joinList(topology?.unassigned_relays || []))}`,
    ].join("");
  }
  if (topologySummary) {
    topologySummary.innerHTML = [
      `Generated: ${escapeHtml(formatEpoch(topology?.generated_at))}`,
      `Nodes: ${escapeHtml(String((topology?.nodes || []).length))}`,
      `Edges: ${escapeHtml(String((topology?.edges || []).length))}`,
    ].join("");
  }
  if (!topologyNodes) return;
  const nodes = topology?.nodes || [];
  topologyNodes.innerHTML = nodes.length > 0
    ? nodes.map((node) => [
      detailItem("Relay", node.relay_id),
      detailItem("Trust", node.trust_level),
      detailItem("Status", node.status),
      detailItem("Assigned Peers", String(node.assigned_peer_count || 0)),
      detailItem("Active Sessions", String(node.active_session_count || 0)),
      detailItem("Inbound Sessions", String(node.inbound_session_count || 0)),
      detailItem("Last Session Issue", formatEpoch(node.last_session_issue)),
    ].join("")).join("")
    : "<p>No federation topology data.</p>";
}

function renderFederationDenials(items) {
  if (!federationDenialsBody) return;
  if (!items || items.length === 0) {
    federationDenialsBody.innerHTML = "<tr><td colspan=\"3\">No recent denials.</td></tr>";
    return;
  }
  federationDenialsBody.innerHTML = items
    .map((entry) => `
      <tr>
        <td data-label="Time">${formatEpoch(entry.created_at)}</td>
        <td data-label="Resource">${escapeHtml(entry.resource_id || "-")}</td>
        <td data-label="Reason">${escapeHtml(entry.reason || "-")}</td>
      </tr>
    `)
    .join("");
}

function renderFederationSessionDetails(data) {
  const session = data.item;
  if (!session) return;
  if (federationSessionTitle) federationSessionTitle.textContent = session.session_id;
  if (federationSessionDetailGrid) {
    federationSessionDetailGrid.innerHTML = [
      detailItem("Session ID", session.session_id),
      detailItem("Source Relay", session.source_relay_id),
      detailItem("Permissions", joinList(session.permissions)),
      detailItem("Allowed GIDs", joinList(session.requested_gids)),
      detailItem("Issued At", formatEpoch(session.issued_at)),
      detailItem("Expires At", formatEpoch(session.expires_at)),
      detailItem("Policy Version", String(session.policy_version)),
      detailItem("Status", session.status),
      detailItem("Peer Count", String(session.peer_count)),
    ].join("");
  }
  if (federationSessionActions) {
    federationSessionActions.innerHTML = session.status === "revoked" || session.status === "expired"
      ? ""
      : `<button data-action="session-revoke" data-id="${escapeHtml(session.session_id)}">Revoke Session</button>`;
  }
  if (federationSessionPeersBody) {
    federationSessionPeersBody.innerHTML = (session.peers || []).length > 0
      ? session.peers.map((peer) => `
        <tr>
          <td data-label="Relay"><a href="/admin/relays/${encodeURIComponent(peer.relay_id)}">${escapeHtml(peer.relay_id)}</a></td>
          <td data-label="Endpoint">${escapeHtml(peer.endpoint || "-")}</td>
          <td data-label="Permissions">${escapeHtml(joinList(peer.permissions))}</td>
          <td data-label="Allowed GIDs">${escapeHtml(joinList(peer.allowed_gids))}</td>
          <td data-label="Expires">${formatEpoch(peer.expires_at)}</td>
        </tr>
      `).join("")
      : "<tr><td colspan=\"5\">No assigned peers.</td></tr>";
  }
  if (federationSessionRelayGrid) {
    const relay = data.relay;
    federationSessionRelayGrid.innerHTML = relay
      ? [
        detailItem("Relay ID", relay.relay_id),
        detailItem("Endpoint", relay.endpoint),
        detailItem("Trust", relay.trust_level),
        detailItem("Status", relay.status),
        detailItem("Federation Enabled", relay.federation_enabled ? "yes" : "no"),
        detailItem("Last Seen", formatEpoch(relay.last_seen)),
      ].join("")
      : "<p>Source relay not found.</p>";
  }
  renderAudit(data.audit || []);
}

function renderModerationReports(items, total = items?.length || 0) {
  if (!moderationReportsBody) return;
  if (!items || items.length === 0) {
    moderationReportsBody.innerHTML = "<tr><td colspan=\"8\">No moderation reports.</td></tr>";
    renderPagination(moderationReportsPagination, moderationReportTableState, total, "Reports", (nextOffset) => {
      moderationReportTableState.offset = nextOffset;
      loadAll().catch((err) => {
        if (loginStatus) loginStatus.textContent = err.message;
      });
    });
    return;
  }
  moderationReportsBody.innerHTML = items
    .map((report) => `
      <tr>
        <td data-label="Report">
          <strong>${escapeHtml(report.report_id)}</strong>
          <div>${escapeHtml(report.group_id)}</div>
        </td>
        <td data-label="Target">${badge(report.target_type)}</td>
        <td data-label="Reason">${escapeHtml(report.reason_code)}</td>
        <td data-label="Reporter">${escapeHtml(report.reporter_peer_id)}</td>
        <td data-label="Reported Peer">${escapeHtml(report.reported_peer_id)}</td>
        <td data-label="Status">${badge(report.status)}</td>
        <td data-label="Received">${formatEpoch(report.received_at)}</td>
        <td data-label="Actions"><div class="actions">${moderationReportActionButtons(report)}</div></td>
      </tr>
    `)
    .join("");
  renderPagination(moderationReportsPagination, moderationReportTableState, total, "Reports", (nextOffset) => {
    moderationReportTableState.offset = nextOffset;
    loadAll().catch((err) => {
      if (loginStatus) loginStatus.textContent = err.message;
    });
  });
}

function renderModerationReportDetails(data) {
  const report = data.item;
  if (!report) return;
  if (moderationReportTitle) moderationReportTitle.textContent = report.report_id;
  if (moderationReportDetailGrid) {
    moderationReportDetailGrid.innerHTML = [
      detailItem("Report ID", report.report_id),
      detailItem("Group ID", report.group_id),
      detailItem("Target Type", report.target_type),
      detailItem("Reason", report.reason_code),
      detailItem("Reporter", report.reporter_peer_id),
      detailItem("Reported Peer", report.reported_peer_id),
      detailItem("Message ID", report.message_id),
      detailItem("Event ID", report.event_id),
      detailItem("Status", report.status),
      detailItem("Assigned Operator", report.assigned_operator),
      detailItem("Received At", formatEpoch(report.received_at)),
      detailItem("Resolved At", formatEpoch(report.resolved_at)),
    ].join("");
  }
  if (moderationReportActions) {
    moderationReportActions.innerHTML = `
      <button data-action="moderation-status" data-id="${escapeHtml(report.report_id)}" data-value="in_review">Mark In Review</button>
      <button data-action="moderation-status" data-id="${escapeHtml(report.report_id)}" data-value="resolved_actioned">Resolve Actioned</button>
      <button data-action="moderation-status" data-id="${escapeHtml(report.report_id)}" data-value="resolved_no_action">Resolve No Action</button>
      <button data-action="moderation-status" data-id="${escapeHtml(report.report_id)}" data-value="rejected_invalid">Reject Invalid</button>
      <button data-action="moderation-note" data-id="${escapeHtml(report.report_id)}">Add Note</button>
    `;
  }
  if (moderationReportEvidence) {
    moderationReportEvidence.textContent = JSON.stringify({
      user_note: report.user_note,
      include_plaintext: report.include_plaintext,
      plaintext_excerpt: report.plaintext_excerpt,
      ciphertext_ref: report.ciphertext_ref,
      client_created_at_ms: report.client_created_at_ms,
      app_version: report.app_version,
      protocol_version: report.protocol_version,
      duplicate_of_report_id: report.duplicate_of_report_id,
    }, null, 2);
  }
  if (moderationReportNotes) {
    moderationReportNotes.innerHTML = Array.isArray(data.notes) && data.notes.length > 0
      ? data.notes.map((note) => `
        <article class="stack-card">
          <strong>${escapeHtml(note.operator)}</strong>
          <code>${escapeHtml(formatEpoch(note.created_at))}</code>
          <div>${escapeHtml(note.note)}</div>
        </article>
      `).join("")
      : "<div class=\"panel-state empty\">No operator notes.</div>";
  }
  renderAudit(data.audit || []);
}

function feedbackPreview(message) {
  const text = String(message || "").replace(/\s+/g, " ").trim();
  if (!text) return "-";
  return text.length > 120 ? `${text.slice(0, 117)}...` : text;
}

function renderFeedback(items, total = items?.length || 0) {
  if (!feedbackBody) return;
  if (!items || items.length === 0) {
    feedbackBody.innerHTML = "<tr><td colspan=\"6\">No feedback found.</td></tr>";
    renderPagination(feedbackPagination, feedbackTableState, total, "Feedback", (nextOffset) => {
      feedbackTableState.offset = nextOffset;
      loadAll().catch((err) => {
        if (loginStatus) loginStatus.textContent = err.message;
      });
    });
    return;
  }
  feedbackBody.innerHTML = items
    .map((item) => `
      <tr>
        <td data-label="Feedback">
          <strong>${escapeHtml(item.feedback_id)}</strong>
          <div>${escapeHtml(feedbackPreview(item.message))}</div>
        </td>
        <td data-label="Category">${badge(item.category)}</td>
        <td data-label="Type">${badge(item.feedback_type || "user_feedback")}</td>
        <td data-label="Source">${badge(item.source)}</td>
        <td data-label="Device">${escapeHtml(item.device_model || item.local_device_id || "-")}</td>
        <td data-label="Received">${formatEpoch(item.received_at)}</td>
        <td data-label="Actions">
          <div class="actions">
            <a class="button-link" href="/admin/feedback/${encodeURIComponent(item.feedback_id)}">View</a>
          </div>
        </td>
      </tr>
    `)
    .join("");
  renderPagination(feedbackPagination, feedbackTableState, total, "Feedback", (nextOffset) => {
    feedbackTableState.offset = nextOffset;
    loadAll().catch((err) => {
      if (loginStatus) loginStatus.textContent = err.message;
    });
  });
}

function renderFeedbackDetails(data) {
  const item = data.item;
  if (!item) return;
  if (feedbackTitle) feedbackTitle.textContent = item.feedback_id;
  if (feedbackDetailGrid) {
    feedbackDetailGrid.innerHTML = [
      detailItem("Feedback ID", item.feedback_id),
      detailItem("Category", item.category),
      detailItem("Feedback Type", item.feedback_type || "user_feedback"),
      detailItem("Status", item.status),
      detailItem("Source", item.source),
      detailItem("Created At", formatEpoch(item.created_at)),
      detailItem("Received At", formatEpoch(item.received_at)),
      detailItem("App Version", item.app_version),
      detailItem("Build Number", item.build_number),
      detailItem("Android Version", item.android_version),
      detailItem("Device Model", item.device_model),
      detailItem("Local Device ID", item.local_device_id),
    ].join("");
  }
  if (feedbackMessage) {
    feedbackMessage.textContent = item.message || "";
  }
  renderAudit(data.audit || []);
}

async function loadRelayPage() {
  if (!relaysBody) return { items: [], total: 0 };
  const data = await api(`/admin/api/relays${encodeQuery(relayTableState)}`);
  relays = data.items || [];
  renderRelays(data.total || 0);
  return data;
}

async function loadAuditPage() {
  if (!auditBody) return { items: [], total: 0 };
  const data = await api(`/admin/api/audit${encodeQuery(auditTableState)}`);
  renderAudit(data.items || [], data.total || 0);
  return data;
}

async function loadFederationSessionPage() {
  if (!federationSessionsBody) return null;
  const data = await api(`/admin/api/federation/sessions${encodeQuery(federationTableState)}`);
  renderFederationSessions(data.items || [], data.total || 0);
  renderTopology(data.topology || {});
  renderFederationDenials(data.recent_denials || []);
  return data;
}

async function loadModerationReportsPage() {
  if (!moderationReportsBody) return null;
  const data = await api(`/admin/api/client/moderation-reports${encodeQuery(moderationReportTableState)}`);
  renderModerationReports(data.items || [], data.total || 0);
  return data;
}

async function loadFeedbackPage() {
  if (!feedbackBody) return null;
  const data = await api(`/admin/api/client/feedback${encodeQuery(feedbackTableState)}`);
  renderFeedback(data.items || [], data.total || 0);
  return data;
}

async function loadAll() {
  setLoadingStates();
  const relayId = currentRelayId();
  if (relayId && relayDetailGrid) {
    await loadPanel(
      async () => {
        renderRelayDetails(await api(`/admin/api/relays/${encodeURIComponent(relayId)}`));
      },
      (err) => {
        if (relayTitle) relayTitle.textContent = relayId;
        setBlockState(relayDetailGrid, "error", err.message || DEFAULT_ERROR_MESSAGE);
        if (capabilitiesList) setBlockState(capabilitiesList, "error", "Unable to load relay capabilities.");
        if (certificateDetail) setBlockState(certificateDetail, "error", "Unable to load certificate state.");
        if (auditBody) setTableState(auditBody, 6, "error", "Unable to load relay audit history.");
      }
    );
    return;
  }

  const sessionId = currentFederationSessionId();
  if (sessionId && federationSessionDetailGrid) {
    await loadPanel(
      async () => {
        renderFederationSessionDetails(await api(`/admin/api/federation/sessions/${encodeURIComponent(sessionId)}`));
      },
      (err) => {
        if (federationSessionTitle) federationSessionTitle.textContent = sessionId;
        setBlockState(federationSessionDetailGrid, "error", err.message || DEFAULT_ERROR_MESSAGE);
        if (federationSessionPeersBody) {
          setTableState(federationSessionPeersBody, 5, "error", "Unable to load assigned peers.");
        }
        if (federationSessionRelayGrid) {
          setBlockState(federationSessionRelayGrid, "error", "Unable to load source relay.");
        }
        if (auditBody) setTableState(auditBody, 6, "error", "Unable to load federation audit history.");
      }
    );
    return;
  }

  const moderationReportId = currentModerationReportId();
  if (moderationReportId && moderationReportDetailGrid) {
    await loadPanel(
      async () => {
        renderModerationReportDetails(
          await api(`/admin/api/client/moderation-reports/${encodeURIComponent(moderationReportId)}`)
        );
      },
      (err) => {
        if (moderationReportTitle) moderationReportTitle.textContent = moderationReportId;
        setBlockState(moderationReportDetailGrid, "error", err.message || DEFAULT_ERROR_MESSAGE);
        if (moderationReportEvidence) moderationReportEvidence.textContent = "Unable to load moderation evidence.";
        if (moderationReportNotes) setBlockState(moderationReportNotes, "error", "Unable to load operator notes.");
        if (auditBody) setTableState(auditBody, 6, "error", "Unable to load moderation report audit history.");
      }
    );
    return;
  }

  const feedbackId = currentFeedbackId();
  if (feedbackId && feedbackDetailGrid) {
    await loadPanel(
      async () => {
        renderFeedbackDetails(await api(`/admin/api/client/feedback/${encodeURIComponent(feedbackId)}`));
      },
      (err) => {
        if (feedbackTitle) feedbackTitle.textContent = feedbackId;
        setBlockState(feedbackDetailGrid, "error", err.message || DEFAULT_ERROR_MESSAGE);
        if (feedbackMessage) feedbackMessage.textContent = "Unable to load feedback message.";
        if (auditBody) setTableState(auditBody, 6, "error", "Unable to load feedback audit history.");
      }
    );
    return;
  }

  const federationPage = Boolean(federationSessionsBody);
  const metricsPage = Boolean(metricsGrid);
  const moderationReportsPage = Boolean(moderationReportsBody);
  const feedbackPage = Boolean(feedbackBody);
  const betaProgramPage = Boolean(betaProgramOutput);
  const [dashboard, metrics, relayData, certificates, audit, federationData, moderationData, feedbackData, policyData, revocationData, betaProgramData] = await Promise.all([
    dashboardGrid ? loadPanel(() => api("/admin/api/dashboard"), (err) => {
      setBlockState(dashboardGrid, "error", err.message || DEFAULT_ERROR_MESSAGE);
      setTextState(healthOutput, "error", "Unable to load registry health.");
    }) : Promise.resolve(null),
    metricsPage ? loadPanel(() => api("/admin/api/metrics"), (err) => {
      setBlockState(metricsGrid, "error", err.message || DEFAULT_ERROR_MESSAGE);
      setTextState(metricsMeta, "error", "Unable to load metrics metadata.");
      setBlockState(metricsRatesGrid, "error", "Unable to load traffic rate metrics.");
      setBlockState(metricsFederationGrid, "error", "Unable to load federation metrics.");
      setBlockState(metricsNotes, "error", "Unable to load metric notes.");
    }) : Promise.resolve(null),
    relaysBody ? loadPanel(() => loadRelayPage(), (err) => {
      setTableState(relaysBody, 8, "error", err.message || DEFAULT_ERROR_MESSAGE);
      if (relayPagination) relayPagination.innerHTML = "";
    }) : Promise.resolve({ items: [], total: 0 }),
    certificatesBody ? loadPanel(() => api("/admin/api/certificates"), (err) => {
      setTableState(certificatesBody, 5, "error", err.message || DEFAULT_ERROR_MESSAGE);
    }) : Promise.resolve({ items: [] }),
    auditBody ? loadPanel(() => loadAuditPage(), (err) => {
      setTableState(auditBody, 6, "error", err.message || DEFAULT_ERROR_MESSAGE);
      if (auditPagination) auditPagination.innerHTML = "";
    }) : Promise.resolve({ items: [], total: 0 }),
    federationPage ? loadPanel(() => loadFederationSessionPage(), (err) => {
      setTableState(federationSessionsBody, 9, "error", err.message || DEFAULT_ERROR_MESSAGE);
      setTextState(federationSummary, "error", "Unable to load federation summary.");
      setTextState(topologySummary, "error", "Unable to load topology snapshot.");
      setBlockState(topologyNodes, "error", "Unable to load topology nodes.");
      setTableState(federationDenialsBody, 3, "error", "Unable to load session denials.");
      if (federationPagination) federationPagination.innerHTML = "";
    }) : Promise.resolve(null),
    moderationReportsPage ? loadPanel(() => loadModerationReportsPage(), (err) => {
      setTableState(moderationReportsBody, 8, "error", err.message || DEFAULT_ERROR_MESSAGE);
      if (moderationReportsPagination) moderationReportsPagination.innerHTML = "";
    }) : Promise.resolve(null),
    feedbackPage ? loadPanel(() => loadFeedbackPage(), (err) => {
      setTableState(feedbackBody, 6, "error", err.message || DEFAULT_ERROR_MESSAGE);
      if (feedbackPagination) feedbackPagination.innerHTML = "";
    }) : Promise.resolve(null),
    policyOutput ? loadPanel(() => api("/admin/api/policy"), () => {
      policyOutput.textContent = "Unable to load policy feed.";
    }) : Promise.resolve(null),
    revocationsOutput ? loadPanel(() => api("/admin/api/revocations"), () => {
      revocationsOutput.textContent = "Unable to load revocation feed.";
    }) : Promise.resolve(null),
    betaProgramPage ? loadPanel(() => loadBetaProgramPage(), (err) => {
      if (betaProgramOutput) betaProgramOutput.textContent = err.message || DEFAULT_ERROR_MESSAGE;
      setBetaProgramStatus("Unable to load Beta Program config.", "error");
    }) : Promise.resolve(null),
  ]);

  if (dashboard) renderDashboard(dashboard);
  if (metrics) renderMetrics(metrics);
  if (certificates) renderCertificates(certificates.items || []);
  if (auditBody && audit) renderAudit(audit.items || [], audit.total || 0);
  if (policyOutput && policyData) {
    policyOutput.textContent = JSON.stringify(policyData, null, 2);
  }
  if (revocationsOutput && revocationData) {
    revocationsOutput.textContent = JSON.stringify(revocationData, null, 2);
  }
  if (betaProgramPage && betaProgramData) {
    renderBetaProgram(betaProgramData);
  }
}

async function checkSession() {
  try {
    const session = await api("/admin/api/auth/me");
    csrfToken = session.csrf_token || "";
    setLoggedIn(true);
    await loadAll();
  } catch (_) {
    csrfToken = "";
    setLoggedIn(false);
  }
}

loginForm?.addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = new FormData(loginForm);
  try {
    const session = await api("/admin/api/auth/login", {
      method: "POST",
      body: JSON.stringify({
        username: form.get("username"),
        password: form.get("password"),
      }),
    });
    csrfToken = session.csrf_token || "";
    loginStatus.textContent = "";
    setLoggedIn(true);
    await loadAll();
  } catch (err) {
    loginStatus.textContent = err.message;
  }
});

logoutButton?.addEventListener("click", async () => {
  await api("/admin/api/auth/logout", { method: "POST", body: "{}" }).catch(() => {});
  csrfToken = "";
  setLoggedIn(false);
});

refreshButton?.addEventListener("click", () => {
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});

onboardingForm?.addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    await generateOnboardingBundle();
  } catch (err) {
    setOnboardingStatus(err.message || DEFAULT_ERROR_MESSAGE, "error");
  }
});

onboardingCopyButton?.addEventListener("click", () => {
  copyOnboardingBundle().catch((err) => {
    setOnboardingStatus(err.message || "Clipboard copy failed.", "error");
  });
});

onboardingDownloadButton?.addEventListener("click", downloadOnboardingBundle);

betaProgramForm?.addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    await saveBetaProgram();
  } catch (err) {
    setBetaProgramStatus(err.message || DEFAULT_ERROR_MESSAGE, "error");
  }
});

betaProgramReset?.addEventListener("click", () => {
  loadAll().catch((err) => {
    setBetaProgramStatus(err.message || DEFAULT_ERROR_MESSAGE, "error");
  });
});

topologyRebalanceButton?.addEventListener("click", async () => {
  try {
    const reason = confirmDangerousAction({
      title: "Rebalance federation topology?",
      target: "all active federation sessions",
      reasonLabel: "Topology rebalance",
    });
    if (!reason) return;
    await api("/admin/api/federation/topology/rebalance", {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
    await loadAll();
  } catch (err) {
    if (loginStatus) loginStatus.textContent = err.message;
  }
});

relaySearch?.addEventListener("input", () => {
  relayTableState.q = String(relaySearch.value || "").trim();
  relayTableState.offset = 0;
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});
moderationReportStatus?.addEventListener("change", () => {
  moderationReportTableState.status = moderationReportStatus.value || "";
  moderationReportTableState.offset = 0;
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});
moderationReportTargetType?.addEventListener("change", () => {
  moderationReportTableState.target_type = moderationReportTargetType.value || "";
  moderationReportTableState.offset = 0;
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});
moderationReportGroupId?.addEventListener("input", () => {
  moderationReportTableState.group_id = String(moderationReportGroupId.value || "").trim();
  moderationReportTableState.offset = 0;
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});
moderationReportReportedPeerId?.addEventListener("input", () => {
  moderationReportTableState.reported_peer_id = String(moderationReportReportedPeerId.value || "").trim();
  moderationReportTableState.offset = 0;
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});
feedbackCategory?.addEventListener("change", () => {
  feedbackTableState.category = feedbackCategory.value || "";
  feedbackTableState.offset = 0;
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});
feedbackSearch?.addEventListener("input", () => {
  feedbackTableState.q = String(feedbackSearch.value || "").trim();
  feedbackTableState.offset = 0;
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});
relaySort?.addEventListener("change", () => {
  relayTableState.sort = relaySort.value || "relay_id";
  relayTableState.offset = 0;
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});
relayOrder?.addEventListener("change", () => {
  relayTableState.order = relayOrder.value || "asc";
  relayTableState.offset = 0;
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});
auditSort?.addEventListener("change", () => {
  auditTableState.sort = auditSort.value || "created_at";
  auditTableState.offset = 0;
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});
auditOrder?.addEventListener("change", () => {
  auditTableState.order = auditOrder.value || "desc";
  auditTableState.offset = 0;
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});
federationSort?.addEventListener("change", () => {
  federationTableState.sort = federationSort.value || "issued_at";
  federationTableState.offset = 0;
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});
federationOrder?.addEventListener("change", () => {
  federationTableState.order = federationOrder.value || "desc";
  federationTableState.offset = 0;
  loadAll().catch((err) => {
    if (loginStatus) loginStatus.textContent = err.message;
  });
});

document.addEventListener("click", async (event) => {
  const button = event.target.closest("button[data-action]");
  if (!button) return;
  const id = button.dataset.id;
  const action = button.dataset.action;
  const value = button.dataset.value;

  try {
    if (action === "trust") {
      const reason = confirmDangerousAction({
        title: `Change trust level to ${value}?`,
        target: id,
        reasonLabel: "Trust change",
      });
      if (!reason) return;
      await api(`/admin/api/relays/${encodeURIComponent(id)}/trust`, {
        method: "PATCH",
        body: JSON.stringify({ trust_level: value, reason }),
      });
    }
    if (action === "status") {
      const reason = confirmDangerousAction({
        title: `Set relay status to ${value}?`,
        target: id,
        reasonLabel: "Status change",
      });
      if (!reason) return;
      await api(`/admin/api/relays/${encodeURIComponent(id)}/status`, {
        method: "PATCH",
        body: JSON.stringify({ status: value, reason }),
      });
    }
    if (action === "certificate-renew") {
      const reason = confirmDangerousAction({
        title: "Renew relay certificate?",
        target: id,
        reasonLabel: "Certificate renewal",
      });
      if (!reason) return;
      await api(`/admin/api/certificates/${encodeURIComponent(id)}/renew`, {
        method: "PATCH",
        body: JSON.stringify({ reason }),
      });
    }
    if (action === "certificate-revoke") {
      const reason = confirmDangerousAction({
        title: "Revoke relay certificate?",
        target: id,
        reasonLabel: "Certificate revocation",
      });
      if (!reason) return;
      await api(`/admin/api/certificates/${encodeURIComponent(id)}/revoke`, {
        method: "PATCH",
        body: JSON.stringify({ reason }),
      });
    }
    if (action === "session-revoke") {
      const reason = confirmDangerousAction({
        title: "Revoke federation session?",
        target: id,
        reasonLabel: "Federation session revocation",
      });
      if (!reason) return;
      await api(`/admin/api/federation/sessions/${encodeURIComponent(id)}/revoke`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      });
    }
    if (action === "moderation-status") {
      const reason = confirmDangerousAction({
        title: `Set moderation report status to ${value}?`,
        target: id,
        reasonLabel: "Moderation report status change",
      });
      if (!reason) return;
      await api(`/admin/api/client/moderation-reports/${encodeURIComponent(id)}/status`, {
        method: "PATCH",
        body: JSON.stringify({ status: value, reason }),
      });
    }
    if (action === "moderation-note") {
      const note = window.prompt("Add operator note", "") || "";
      const trimmed = note.trim();
      if (!trimmed) return;
      await api(`/admin/api/client/moderation-reports/${encodeURIComponent(id)}/notes`, {
        method: "POST",
        body: JSON.stringify({ note: trimmed }),
      });
    }
    await loadAll();
  } catch (err) {
    if (loginStatus) loginStatus.textContent = err.message;
  }
});

certificateForm?.addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = new FormData(certificateForm);
  const relayId = String(form.get("relay_id") || "").trim();
  const defaultReason = String(form.get("reason") || "").trim();
  const reason = defaultReason || confirmDangerousAction({
    title: "Issue relay certificate?",
    target: relayId,
    reasonLabel: "Certificate issuance",
  });
  if (!reason) return;
  await api("/admin/api/certificates", {
    method: "POST",
    body: JSON.stringify({
      relay_id: relayId,
      reason,
    }),
  });
  certificateForm.reset();
  await loadAll();
});

if (relaySort) relaySort.value = relayTableState.sort;
if (relayOrder) relayOrder.value = relayTableState.order;
if (auditSort) auditSort.value = auditTableState.sort;
if (auditOrder) auditOrder.value = auditTableState.order;
if (federationSort) federationSort.value = federationTableState.sort;
if (federationOrder) federationOrder.value = federationTableState.order;
if (moderationReportStatus) moderationReportStatus.value = moderationReportTableState.status;
if (moderationReportTargetType) moderationReportTargetType.value = moderationReportTableState.target_type;
if (feedbackCategory) feedbackCategory.value = feedbackTableState.category;
refreshAuditExportLinks();
renderOnboardingBundle(null);

checkSession();
