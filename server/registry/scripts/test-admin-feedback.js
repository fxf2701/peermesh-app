#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");

const TEST_ADMIN_PASSWORD = "admin1234";
const TEST_ADMIN_PASSWORD_HASH = "scrypt$16384$8$1$c880d2df7323d9fdfc95083fd301508c$2b4a5142d0b35d012e7df68ae6e8f368d81bf48af40f8cb594fe7ad379cd9ca2";

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

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-admin-feedback-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 24000 + Math.floor(Math.random() * 1000);
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

    for (const [feedbackId, category, message] of [
      ["feedback-1", "bug", "Maps view flickers after switching rooms."],
      ["feedback-2", "groups", "Group join flow was clear and quick."],
      ["feedback-3", "feature_request", "Would like a room unread badge."],
    ]) {
      const feedback = await api(baseUrl, "/v1/registry/client/feedback", {
        method: "POST",
        body: {
          feedback_id: feedbackId,
          category,
          feedback_type: feedbackId === "feedback-1" ? "crash_report" : "user_feedback",
          message,
          created_at: Date.now(),
          app_version: "0.1.0",
          build_number: "7",
          android_version: "Android 15",
          device_model: "Pixel Test",
          local_device_id: `device-${feedbackId}`,
          source: "android_beta",
        },
      });
      assert(feedback.res.status === 201, `feedback submit failed for ${feedbackId}`);
    }

    const login = await api(baseUrl, "/admin/api/auth/login", {
      method: "POST",
      body: { username: "admin", password: TEST_ADMIN_PASSWORD },
    });
    assert(login.res.status === 200, "admin login should succeed");
    const cookie = login.res.headers.get("set-cookie")?.split(";")[0];
    const csrfToken = login.payload.data?.csrf_token;
    assert(cookie, "admin login should set session cookie");
    assert(csrfToken, "admin login should return csrf token");

    const list = await api(baseUrl, "/admin/api/client/feedback?limit=2&offset=0&sort=feedback_id&order=asc", { cookie });
    assert(list.res.status === 200, "feedback list should succeed");
    assert(list.payload.data.items.length === 2, "feedback list should honor limit");
    assert(list.payload.data.total === 3, "feedback list should report total");
    assert(list.payload.data.items[0].feedback_id === "feedback-1", "feedback sort should honor feedback id asc");
    console.log("PASS admin_feedback_list");

    const filtered = await api(baseUrl, "/admin/api/client/feedback?category=groups&q=clear", { cookie });
    assert(filtered.res.status === 200, "feedback filter should succeed");
    assert(filtered.payload.data.items.length === 1, "feedback filter should narrow results");
    assert(filtered.payload.data.items[0].feedback_id === "feedback-2", "feedback filter should match groups item");
    console.log("PASS admin_feedback_filters");

    const detail = await api(baseUrl, "/admin/api/client/feedback/feedback-1", { cookie, csrfToken });
    assert(detail.res.status === 200, "feedback detail should succeed");
    assert(detail.payload.data.item.feedback_id === "feedback-1", "feedback detail should return requested item");
    assert(detail.payload.data.item.feedback_type === "crash_report", "feedback detail should return feedback type");
    assert(Array.isArray(detail.payload.data.audit), "feedback detail should include audit entries");
    console.log("PASS admin_feedback_detail");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL admin_feedback", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
