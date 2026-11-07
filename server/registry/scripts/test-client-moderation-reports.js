#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");

const TEST_ADMIN_PASSWORD = "admin1234";
const TEST_ADMIN_PASSWORD_HASH =
  "scrypt$16384$8$1$c880d2df7323d9fdfc95083fd301508c$2b4a5142d0b35d012e7df68ae6e8f368d81bf48af40f8cb594fe7ad379cd9ca2";

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function waitForHealth(baseUrl, child, attempts = 50) {
  for (let i = 0; i < attempts; i += 1) {
    if (child.exitCode !== null) {
      throw new Error(`registry server exited early with code ${child.exitCode}`);
    }
    try {
      const res = await fetch(`${baseUrl}/healthz`);
      if (res.ok) return;
    } catch (_) {
      // ignore startup races
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("registry server did not become healthy");
}

async function api(baseUrl, pathName, { method = "GET", body, cookie, csrfToken } = {}) {
  const res = await fetch(`${baseUrl}${pathName}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(cookie ? { Cookie: cookie } : {}),
      ...(csrfToken ? { "X-CSRF-Token": csrfToken } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await res.json().catch(() => ({}));
  return { res, payload };
}

async function fetchText(baseUrl, pathName) {
  const res = await fetch(`${baseUrl}${pathName}`);
  const text = await res.text();
  return { res, text };
}

async function loginAdmin(baseUrl) {
  const login = await api(baseUrl, "/admin/api/auth/login", {
    method: "POST",
    body: { username: "admin", password: TEST_ADMIN_PASSWORD },
  });
  assert(login.res.status === 200, "admin login should succeed");
  const rawCookie = login.res.headers.get("set-cookie");
  assert(rawCookie, "admin login should set session cookie");
  const cookie = rawCookie.split(";")[0];
  const csrfToken = login.payload.data?.csrf_token;
  assert(csrfToken, "admin login should return csrf token");
  return { cookie, csrfToken };
}

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-client-moderation-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 23000 + Math.floor(Math.random() * 1000);
  const baseUrl = `http://127.0.0.1:${port}`;

  const child = spawn(process.execPath, ["src/index.js"], {
    cwd: path.join(__dirname, ".."),
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      REGISTRY_DB_PATH: dbPath,
      REGISTRY_SHARED_SECRET: "test-registry-secret",
      REGISTRY_REQUIRE_SIGNATURE: "0",
      REGISTRY_ADMIN_USERNAME: "admin",
      REGISTRY_ADMIN_PASSWORD_HASH: TEST_ADMIN_PASSWORD_HASH,
    },
    stdio: "ignore",
  });

  try {
    await waitForHealth(baseUrl, child);

    const baseReport = {
      report_id: "report-1",
      group_id: "group-1",
      reporter_peer_id: "peer-a",
      reported_peer_id: "peer-b",
      target_type: "message",
      message_id: "message-1",
      event_id: "event-1",
      reason_code: "spam",
      user_note: "User says this was spam.",
      include_plaintext: true,
      plaintext_excerpt: "spam message text",
      ciphertext_ref: "event-1",
      created_at_ms: Date.now(),
      app_version: "1.2.3",
      protocol_version: 1,
    };

    const created = await api(baseUrl, "/v1/registry/client/moderation-reports", {
      method: "POST",
      body: baseReport,
    });
    assert(created.res.status === 201, "moderation report should be accepted");
    assert(created.payload.ok === true, "accepted moderation report should return ok");
    assert(created.payload.report?.report_id === "report-1", "response should include report id");
    assert(created.payload.report?.status === "received", "new report should have received status");
    assert(created.payload.duplicate_collapsed === false, "new report should not be duplicate");
    console.log("PASS moderation_report_accept");

    const duplicate = await api(baseUrl, "/v1/registry/client/moderation-reports", {
      method: "POST",
      body: baseReport,
    });
    assert(duplicate.res.status === 200, "duplicate moderation report should be accepted idempotently");
    assert(duplicate.payload.duplicate_collapsed === true, "duplicate should be marked collapsed");
    console.log("PASS moderation_report_duplicate");

    const conflict = await api(baseUrl, "/v1/registry/client/moderation-reports", {
      method: "POST",
      body: {
        ...baseReport,
        reason_code: "harassment",
      },
    });
    assert(conflict.res.status === 409, "same report id with changed payload should conflict");
    assert(conflict.payload.error?.code === "report_id_conflict", "conflict should expose report_id_conflict");
    console.log("PASS moderation_report_conflict");

    const invalidPeerPlaintext = await api(baseUrl, "/v1/registry/client/moderation-reports", {
      method: "POST",
      body: {
        report_id: "report-2",
        group_id: "group-1",
        reporter_peer_id: "peer-a",
        reported_peer_id: "peer-c",
        target_type: "peer",
        reason_code: "spam",
        include_plaintext: true,
        plaintext_excerpt: "should not be allowed",
        created_at_ms: Date.now(),
        protocol_version: 1,
      },
    });
    assert(invalidPeerPlaintext.res.status === 400, "peer report should reject plaintext evidence");
    console.log("PASS moderation_report_validation");

    const moderationListPage = await fetchText(baseUrl, "/admin/moderation-reports");
    assert(moderationListPage.res.status === 200, "moderation reports admin page should load");
    assert(
      moderationListPage.text.includes("Moderation Reports"),
      "moderation reports admin page should contain heading"
    );
    const moderationDetailPage = await fetchText(baseUrl, "/admin/moderation-reports/report-1");
    assert(moderationDetailPage.res.status === 200, "moderation report detail page should load");
    assert(
      moderationDetailPage.text.includes("Moderation Report Details"),
      "moderation report detail page should contain heading"
    );
    console.log("PASS moderation_report_admin_pages");

    const { cookie, csrfToken } = await loginAdmin(baseUrl);
    const list = await api(baseUrl, "/admin/api/client/moderation-reports?status=received", {
      cookie,
    });
    assert(list.res.status === 200, "admin list should succeed");
    assert(list.payload.data?.total === 1, "admin list should contain one accepted report");
    assert(list.payload.data?.items?.[0]?.report_id === "report-1", "admin list should show accepted report");
    console.log("PASS moderation_report_admin_list");

    const detail = await api(baseUrl, "/admin/api/client/moderation-reports/report-1", {
      cookie,
    });
    assert(detail.res.status === 200, "admin detail should succeed");
    assert(detail.payload.data?.item?.report_id === "report-1", "admin detail should return report");
    assert(
      !Object.prototype.hasOwnProperty.call(detail.payload.data?.item || {}, "source_ip"),
      "admin detail should not expose reporter source_ip"
    );
    assert(
      Array.isArray(detail.payload.data?.audit) &&
        detail.payload.data.audit.some((entry) => entry.action === "client_moderation_report_received"),
      "admin detail should include moderation report audit history"
    );
    console.log("PASS moderation_report_admin_detail");

    const statusUpdate = await api(baseUrl, "/admin/api/client/moderation-reports/report-1/status", {
      method: "PATCH",
      body: {
        status: "in_review",
        reason: "Triaging report",
      },
      cookie,
      csrfToken,
    });
    assert(statusUpdate.res.status === 200, "admin status update should succeed");
    assert(statusUpdate.payload.data?.item?.status === "in_review", "status should update to in_review");
    assert(
      statusUpdate.payload.data?.item?.assigned_operator === "admin",
      "status update should assign current operator"
    );
    console.log("PASS moderation_report_status_update");

    const noteCreate = await api(baseUrl, "/admin/api/client/moderation-reports/report-1/notes", {
      method: "POST",
      body: {
        note: "Needs follow-up with moderation policy review.",
      },
      cookie,
      csrfToken,
    });
    assert(noteCreate.res.status === 201, "admin note create should succeed");
    assert(noteCreate.payload.data?.item?.operator === "admin", "note should capture operator");
    console.log("PASS moderation_report_note_create");

    const detailAfterWorkflow = await api(baseUrl, "/admin/api/client/moderation-reports/report-1", {
      cookie,
    });
    assert(detailAfterWorkflow.res.status === 200, "admin detail after workflow should succeed");
    assert(
      detailAfterWorkflow.payload.data?.item?.status === "in_review",
      "detail should reflect updated status"
    );
    assert(
      Array.isArray(detailAfterWorkflow.payload.data?.notes) &&
        detailAfterWorkflow.payload.data.notes.length === 1,
      "detail should include operator notes"
    );
    assert(
      Array.isArray(detailAfterWorkflow.payload.data?.audit) &&
        detailAfterWorkflow.payload.data.audit.some((entry) => entry.action === "client_moderation_report_status_changed") &&
        detailAfterWorkflow.payload.data.audit.some((entry) => entry.action === "client_moderation_report_note_added"),
      "detail audit should include operator workflow actions"
    );
    console.log("PASS moderation_report_operator_workflow");

    for (let i = 2; i <= 30; i += 1) {
      const rateLimitWarmup = await api(baseUrl, "/v1/registry/client/moderation-reports", {
        method: "POST",
        body: {
          ...baseReport,
          report_id: `rate-limit-${i}`,
          message_id: `rate-limit-message-${i}`,
          event_id: `rate-limit-event-${i}`,
          ciphertext_ref: `rate-limit-event-${i}`,
        },
      });
      assert(rateLimitWarmup.res.status === 201, `moderation report ${i} should be accepted before IP limit`);
    }
    const rateLimited = await api(baseUrl, "/v1/registry/client/moderation-reports", {
      method: "POST",
      body: {
        ...baseReport,
        report_id: "rate-limit-31",
        message_id: "rate-limit-message-31",
        event_id: "rate-limit-event-31",
        ciphertext_ref: "rate-limit-event-31",
      },
    });
    assert(rateLimited.res.status === 429, "moderation report should be source-IP rate limited");
    assert(
      rateLimited.payload.error?.code === "source_ip_report_rate_limited",
      "rate limit should expose source_ip_report_rate_limited"
    );
    console.log("PASS moderation_report_source_ip_rate_limit");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL client_moderation_reports", err && err.stack ? err.stack : String(err));
  process.exitCode = 1;
});
