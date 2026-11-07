#!/usr/bin/env node
/* eslint-disable no-console */

const relayBaseUrl = process.env.RELAY_BASE_URL || "http://127.0.0.1:8080";
const adminUsername = process.env.ADMIN_USERNAME || "admin";
const adminPassword = process.env.ADMIN_PASSWORD || "admin1234";

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function request(pathName, { method = "GET", body, cookie } = {}) {
  const res = await fetch(`${relayBaseUrl}${pathName}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(cookie ? { Cookie: cookie } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const contentType = res.headers.get("content-type") || "";
  const payload = contentType.includes("application/json")
    ? await res.json().catch(() => ({}))
    : await res.text();
  return { res, payload };
}

async function main() {
  const dashboardPage = await request("/admin/dashboard");
  assert(dashboardPage.res.status === 200, "dashboard page should load");
  assert(
    typeof dashboardPage.payload === "string" &&
      dashboardPage.payload.includes("Dashboard"),
    "dashboard page should contain Dashboard content"
  );
  console.log("PASS relay_admin_dashboard_page");

  const assetJs = await request("/admin/assets/admin.js");
  assert(assetJs.res.status === 200, "admin.js should load");
  const assetCss = await request("/admin/assets/admin.css");
  assert(assetCss.res.status === 200, "admin.css should load");
  console.log("PASS relay_admin_dashboard_assets");

  const login = await request("/admin/api/auth/login", {
    method: "POST",
    body: { username: adminUsername, password: adminPassword },
  });
  assert(login.res.status === 200, "admin login should succeed");
  const rawCookie = login.res.headers.get("set-cookie");
  assert(rawCookie, "admin login should return a session cookie");
  const cookie = rawCookie.split(";", 1)[0];
  console.log("PASS relay_admin_login");

  const me = await request("/admin/api/auth/me", { cookie });
  assert(me.res.status === 200, "auth me should succeed");
  assert(me.payload.ok === true, "auth me payload should be ok");
  console.log("PASS relay_admin_session");

  const summary = await request("/admin/api/metrics/summary", { cookie });
  assert(summary.res.status === 200, "metrics summary should succeed");
  assert(summary.payload.ok !== false, "metrics summary payload should be valid");
  console.log("PASS relay_admin_metrics_summary");

  const trafficDetail = await request("/admin/api/metrics/detail?domain=traffic", { cookie });
  assert(trafficDetail.res.status === 200, "metrics detail should succeed");
  assert(trafficDetail.payload.ok !== false, "metrics detail payload should be valid");
  console.log("PASS relay_admin_metrics_detail");

  const backup = await request("/admin/api/backup/status", { cookie });
  assert(backup.res.status === 200, "backup status should succeed");
  assert(backup.payload.last_status, "backup status should include last_status");
  console.log("PASS relay_admin_backup_status");

  const retention = await request("/admin/api/retention/status", { cookie });
  assert(retention.res.status === 200, "retention status should succeed");
  console.log("PASS relay_admin_retention_status");

  const alerts = await request("/admin/api/alerts", { cookie });
  assert(alerts.res.status === 200, "alerts should succeed");
  assert(Array.isArray(alerts.payload.items), "alerts should include items array");
  assert(Array.isArray(alerts.payload.incidents), "alerts should include incidents array");
  console.log("PASS relay_admin_alerts");
}

main().catch((err) => {
  console.error("FAIL relay_admin_dashboard_smoke");
  console.error(err && err.message ? err.message : String(err));
  process.exit(1);
});
