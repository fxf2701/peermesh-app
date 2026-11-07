#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");

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

async function api(baseUrl, pathName, { method = "GET", body } = {}) {
  const res = await fetch(`${baseUrl}${pathName}`, {
    method,
    headers: {
      "Content-Type": "application/json",
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await res.json().catch(() => ({}));
  return { res, payload };
}

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-client-feedback-"));
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
    },
    stdio: "ignore",
  });

  try {
    await waitForHealth(baseUrl, child);

    const baseFeedback = {
      feedback_id: "feedback-1",
      category: "general",
      feedback_type: "user_feedback",
      message: "Nearby Rooms is promising, but the empty state was confusing.",
      created_at: Date.now(),
      app_version: "0.1.0",
      build_number: "1",
      android_version: "Android 15 (SDK 35)",
      device_model: "Google Pixel",
      local_device_id: "device-123",
      source: "android_beta",
    };

    const created = await api(baseUrl, "/v1/registry/client/feedback", {
      method: "POST",
      body: baseFeedback,
    });
    assert(created.res.status === 201, "feedback should be accepted");
    assert(created.payload.ok === true, "feedback create should return ok");
    assert(created.payload.feedback?.feedback_id === "feedback-1", "feedback id should echo");
    assert(created.payload.feedback?.status === "received", "feedback status should be received");
    assert(created.payload.feedback?.feedback_type === "user_feedback", "feedback type should echo");
    assert(created.payload.duplicate_collapsed === false, "new feedback should not be duplicate");
    console.log("PASS client_feedback_accept");

    const duplicate = await api(baseUrl, "/v1/registry/client/feedback", {
      method: "POST",
      body: baseFeedback,
    });
    assert(duplicate.res.status === 200, "duplicate feedback should be idempotent");
    assert(duplicate.payload.duplicate_collapsed === true, "duplicate should collapse");
    console.log("PASS client_feedback_duplicate");

    const conflict = await api(baseUrl, "/v1/registry/client/feedback", {
      method: "POST",
      body: {
        ...baseFeedback,
        message: "Changed message for same id",
      },
    });
    assert(conflict.res.status === 409, "changed payload should conflict");
    assert(conflict.payload.error?.code === "feedback_id_conflict", "conflict code should match");
    console.log("PASS client_feedback_conflict");

    const invalid = await api(baseUrl, "/v1/registry/client/feedback", {
      method: "POST",
      body: {
        ...baseFeedback,
        feedback_id: "feedback-2",
        category: "invalid",
      },
    });
    assert(invalid.res.status === 400, "invalid category should be rejected");
    console.log("PASS client_feedback_validation");

    const legacyWithoutType = await api(baseUrl, "/v1/registry/client/feedback", {
      method: "POST",
      body: {
        feedback_id: "feedback-legacy",
        category: "general",
        message: "Legacy client payload without feedback type.",
        created_at: Date.now(),
        app_version: "0.1.0",
        build_number: "1",
        android_version: "Android 15 (SDK 35)",
        device_model: "Google Pixel",
        local_device_id: "device-legacy",
        source: "android_beta",
      },
    });
    assert(legacyWithoutType.res.status === 201, "legacy feedback without type should be accepted");
    assert(legacyWithoutType.payload.feedback?.feedback_type === "user_feedback", "legacy feedback should default to user_feedback");
    console.log("PASS client_feedback_legacy_default_type");
  } finally {
    child.kill("SIGTERM");
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
