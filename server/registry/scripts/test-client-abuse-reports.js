#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");
const { createClientAbuseReportManager } = require("../src/client_abuse_reports");

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
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-client-abuse-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 22000 + Math.floor(Math.random() * 1000);
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
      REGISTRY_CLIENT_ACCESS_GRANT_SIGNING_SECRET: "test-client-grant-secret",
      REGISTRY_CLIENT_ACCESS_MAX_CANDIDATES: "2",
      REGISTRY_CLIENT_ACCESS_GRANT_TTL_SECONDS: "300",
      REGISTRY_CLIENT_ACCESS_MIN_TRUST_LEVEL: "community",
      REGISTRY_CLIENT_ABUSE_PAIR_COOLDOWN_SECONDS: "1800",
      REGISTRY_CLIENT_ABUSE_DEPRIORITIZE_WINDOW_SECONDS: "3600",
      REGISTRY_CLIENT_ABUSE_SINGLE_REPORT_WEIGHT: "2",
      REGISTRY_CLIENT_ABUSE_DISTINCT_CLIENT_BONUS: "3",
    },
    stdio: "ignore",
  });

  try {
    await waitForHealth(baseUrl, child);

    const relays = [
      ["relay-vn-01", "https://relay-vn-01.test", "vn-south"],
      ["relay-vn-02", "https://relay-vn-02.test", "vn-south"],
      ["relay-sg-02", "https://relay-sg-02.test", "sg"],
    ];
    for (const [relayId, endpoint, region] of relays) {
      const heartbeat = await api(baseUrl, "/v1/registry/relays:heartbeat", {
        method: "POST",
        body: {
          relay_id: relayId,
          public_key: `${relayId}-public-key`,
          endpoint,
          region,
          capabilities: ["store_forward"],
          version: "test",
        },
      });
      assert(heartbeat.res.status === 200, `heartbeat failed for ${relayId}`);
    }

    const { cookie, csrfToken } = await loginAdmin(baseUrl);
    const policyPatch = await api(baseUrl, "/admin/api/policy", {
      method: "PATCH",
      body: {
        client_abuse_pair_cooldown_seconds: 1800,
        client_abuse_deprioritize_window_seconds: 3600,
        client_abuse_single_report_weight: 2,
        client_abuse_distinct_client_bonus: 3,
        client_abuse_duplicate_bucket_seconds: 300,
        client_abuse_rate_limit_window_seconds: 900,
        client_abuse_max_reports_per_client_window: 1,
        client_abuse_max_reports_per_ip_window: 10,
        client_abuse_max_reports_per_grant_window: 3,
        client_abuse_corroboration_client_threshold: 2,
        client_abuse_corroboration_score_threshold: 6,
        reason: "abuse report selection test",
      },
      cookie,
      csrfToken,
    });
    assert(policyPatch.res.status === 200, "abuse policy patch should succeed");

    const grantA = await api(baseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "client-a",
        requested_gids: ["group-1"],
        requested_operations: ["fetch"],
        network_hints: { region: "vn-south", transport: "https" },
      },
    });
    assert(grantA.res.status === 201, "grant A should succeed");
    assert(
      grantA.payload.grant.candidate_relays.length === 2 &&
        grantA.payload.grant.candidate_relays.some((item) => item.relay_id === "relay-vn-01") &&
        grantA.payload.grant.candidate_relays.some((item) => item.relay_id === "relay-vn-02"),
      "grant A should authorize the two same-region relays"
    );

    const reportA = await api(baseUrl, "/v1/registry/client/reports", {
      method: "POST",
      body: {
        grant_id: grantA.payload.grant.grant_id,
        relay_id: "relay-vn-01",
        client_instance_id: "client-a",
        reason: "spam",
        timestamp: Math.floor(Date.now() / 1000),
        counters: {
          unexpected_messages: 4,
          rejected_frames: 4,
        },
      },
    });
    assert(reportA.res.status === 201, "abuse report should be accepted");
    assert(reportA.payload.report.report_id, "abuse report should return report id");
    assert(
      reportA.payload.selection_effect.blocked_for_client === true,
      "report should activate pair cooldown for the reporting client"
    );
    console.log("PASS client_abuse_report_accept");

    const duplicateReport = await api(baseUrl, "/v1/registry/client/reports", {
      method: "POST",
      body: {
        grant_id: grantA.payload.grant.grant_id,
        relay_id: "relay-vn-01",
        client_instance_id: "client-a",
        reason: "spam",
        timestamp: reportA.payload.report.reported_at,
        counters: {
          unexpected_messages: 5,
        },
      },
    });
    assert(duplicateReport.res.status === 200, "duplicate report should be collapsed");
    assert(duplicateReport.payload.duplicate_collapsed === true, "duplicate flag should be returned");
    assert(
      duplicateReport.payload.report.report_id === reportA.payload.report.report_id,
      "duplicate collapse should reuse the original report"
    );
    console.log("PASS client_abuse_duplicate_collapse");

    const grantAAfterReport = await api(baseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "client-a",
        requested_gids: ["group-1"],
        requested_operations: ["fetch"],
        network_hints: { region: "vn-south", transport: "https" },
      },
    });
    assert(grantAAfterReport.res.status === 201, "grant after report should succeed");
    assert(
      !grantAAfterReport.payload.grant.candidate_relays.some((item) => item.relay_id === "relay-vn-01"),
      "reported relay should be excluded for the same client during cooldown"
    );
    console.log("PASS client_abuse_pair_cooldown");

    const invalidRelayReport = await api(baseUrl, "/v1/registry/client/reports", {
      method: "POST",
      body: {
        grant_id: grantA.payload.grant.grant_id,
        relay_id: "relay-sg-02",
        client_instance_id: "client-a",
        reason: "spam",
        timestamp: Math.floor(Date.now() / 1000),
      },
    });
    assert(invalidRelayReport.res.status === 403, "report for unauthorized relay should be denied");
    assert(
      invalidRelayReport.payload.error.code === "relay_not_authorized_by_grant",
      "unauthorized relay report code should match"
    );
    console.log("PASS client_abuse_report_rejects_unauthorized_relay");

    const grantB = await api(baseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "client-b",
        requested_gids: ["group-2"],
        requested_operations: ["fetch"],
        network_hints: { region: "vn-south", transport: "https" },
      },
    });
    assert(grantB.res.status === 201, "grant B should succeed");
    assert(
      grantB.payload.grant.candidate_relays.some((item) => item.relay_id === "relay-vn-01"),
      "relay-vn-01 should still be visible before corroborating report"
    );

    const grantBSecondRelay = await api(baseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "client-b",
        requested_gids: ["group-2"],
        requested_operations: ["fetch"],
        network_hints: { region: "sg", transport: "https" },
      },
    });
    assert(grantBSecondRelay.res.status === 201, "grant B second relay should succeed");

    const reportB = await api(baseUrl, "/v1/registry/client/reports", {
      method: "POST",
      body: {
        grant_id: grantB.payload.grant.grant_id,
        relay_id: "relay-vn-01",
        client_instance_id: "client-b",
        reason: "protocol_violation",
        timestamp: Math.floor(Date.now() / 1000),
        counters: {
          rejected_frames: 2,
        },
      },
    });
    assert(reportB.res.status === 201, "second abuse report should be accepted");

    const clientRateLimit = await api(baseUrl, "/v1/registry/client/reports", {
      method: "POST",
      body: {
        grant_id: grantBSecondRelay.payload.grant.grant_id,
        relay_id: "relay-sg-02",
        client_instance_id: "client-b",
        reason: "connection_flood",
        timestamp: Math.floor(Date.now() / 1000),
      },
    });
    assert(clientRateLimit.res.status === 429, "per-client rate limit should apply");
    assert(
      clientRateLimit.payload.error.code === "client_report_rate_limited",
      "per-client rate-limit code should match"
    );
    console.log("PASS client_abuse_client_rate_limit");

    const grantC = await api(baseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "client-c",
        requested_gids: ["group-3"],
        requested_operations: ["fetch"],
        network_hints: { region: "vn-south", transport: "https" },
      },
    });
    assert(grantC.res.status === 201, "grant C should succeed");
    assert(
      grantC.payload.grant.candidate_relays[0].relay_id === "relay-vn-02",
      "candidate selection should deprioritize the relay with corroborated abuse reports"
    );
    console.log("PASS client_abuse_global_deprioritization");

    const ipPolicyPatch = await api(baseUrl, "/admin/api/policy", {
      method: "PATCH",
      body: {
        client_abuse_max_reports_per_client_window: 20,
        client_abuse_max_reports_per_ip_window: 20,
        client_abuse_max_reports_per_grant_window: 20,
        reason: "prepare ip/grant rate limit tests",
      },
      cookie,
      csrfToken,
    });
    assert(ipPolicyPatch.res.status === 200, "policy reset for ip/grant tests should succeed");

    const grantIp1 = await api(baseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "client-ip-1",
        requested_gids: ["group-ip-1"],
        requested_operations: ["fetch"],
        network_hints: { region: "sg", transport: "https" },
      },
    });
    assert(grantIp1.res.status === 201, "grant ip1 should succeed");

    const ipReport1 = await api(baseUrl, "/v1/registry/client/reports", {
      method: "POST",
      body: {
        grant_id: grantIp1.payload.grant.grant_id,
        relay_id: "relay-sg-02",
        client_instance_id: "client-ip-1",
        reason: "suspicious_payload_pattern",
        timestamp: Math.floor(Date.now() / 1000),
      },
    });
    assert(ipReport1.res.status === 201, "first ip-scoped report should succeed");

    const grantPolicyPatch = await api(baseUrl, "/admin/api/policy", {
      method: "PATCH",
      body: {
        client_abuse_max_reports_per_client_window: 20,
        client_abuse_max_reports_per_ip_window: 20,
        client_abuse_max_reports_per_grant_window: 1,
        reason: "grant rate limit test",
      },
      cookie,
      csrfToken,
    });
    assert(grantPolicyPatch.res.status === 200, "grant rate-limit policy patch should succeed");

    const grantRateLimit = await api(baseUrl, "/v1/registry/client/reports", {
      method: "POST",
      body: {
        grant_id: grantIp1.payload.grant.grant_id,
        relay_id: "relay-sg-02",
        client_instance_id: "client-ip-1",
        reason: "connection_flood",
        timestamp: Math.floor(Date.now() / 1000),
      },
    });
    assert(grantRateLimit.res.status === 429, "per-grant rate limit should apply");
    assert(
      grantRateLimit.payload.error.code === "grant_report_rate_limited",
      "per-grant rate-limit code should match"
    );
    console.log("PASS client_abuse_grant_rate_limit");

    const ipPolicyPatch2 = await api(baseUrl, "/admin/api/policy", {
      method: "PATCH",
      body: {
        client_abuse_max_reports_per_client_window: 20,
        client_abuse_max_reports_per_ip_window: 1,
        client_abuse_max_reports_per_grant_window: 20,
        reason: "ip rate limit test",
      },
      cookie,
      csrfToken,
    });
    assert(ipPolicyPatch2.res.status === 200, "ip rate-limit policy patch should succeed");

    const grantIp2 = await api(baseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "client-ip-2",
        requested_gids: ["group-ip-2"],
        requested_operations: ["fetch"],
        network_hints: { region: "sg", transport: "https" },
      },
    });
    assert(grantIp2.res.status === 201, "grant ip2 should succeed");

    const ipReport2 = await api(baseUrl, "/v1/registry/client/reports", {
      method: "POST",
      body: {
        grant_id: grantIp2.payload.grant.grant_id,
        relay_id: "relay-sg-02",
        client_instance_id: "client-ip-2",
        reason: "connection_flood",
        timestamp: Math.floor(Date.now() / 1000),
      },
    });
    assert(ipReport2.res.status === 429, "per-ip rate limit should apply");
    assert(
      ipReport2.payload.error.code === "source_ip_report_rate_limited",
      "per-ip rate-limit code should match"
    );
    console.log("PASS client_abuse_ip_rate_limit");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }

  const decayConfig = {
    clientAbuseReportAcceptanceWindowSeconds: 900,
    clientAbuseDeprioritizeWindowSeconds: 100,
    clientAbusePairCooldownSeconds: 30,
    clientAbuseSingleReportWeight: 1,
    clientAbuseDistinctClientBonus: 0,
    clientAbuseDuplicateBucketSeconds: 60,
    clientAbuseRateLimitWindowSeconds: 300,
    clientAbuseMaxReportsPerClientWindow: 10,
    clientAbuseMaxReportsPerIpWindow: 10,
    clientAbuseMaxReportsPerGrantWindow: 10,
    clientAbuseCorroborationClientThreshold: 99,
    clientAbuseCorroborationScoreThreshold: 99,
  };
  const decayPolicy = {
    client_abuse_report_acceptance_window_seconds: decayConfig.clientAbuseReportAcceptanceWindowSeconds,
    client_abuse_deprioritize_window_seconds: decayConfig.clientAbuseDeprioritizeWindowSeconds,
    client_abuse_pair_cooldown_seconds: decayConfig.clientAbusePairCooldownSeconds,
    client_abuse_single_report_weight: decayConfig.clientAbuseSingleReportWeight,
    client_abuse_distinct_client_bonus: decayConfig.clientAbuseDistinctClientBonus,
    client_abuse_duplicate_bucket_seconds: decayConfig.clientAbuseDuplicateBucketSeconds,
    client_abuse_rate_limit_window_seconds: decayConfig.clientAbuseRateLimitWindowSeconds,
    client_abuse_max_reports_per_client_window: decayConfig.clientAbuseMaxReportsPerClientWindow,
    client_abuse_max_reports_per_ip_window: decayConfig.clientAbuseMaxReportsPerIpWindow,
    client_abuse_max_reports_per_grant_window: decayConfig.clientAbuseMaxReportsPerGrantWindow,
    client_abuse_corroboration_client_threshold: decayConfig.clientAbuseCorroborationClientThreshold,
    client_abuse_corroboration_score_threshold: decayConfig.clientAbuseCorroborationScoreThreshold,
  };
  const decayReports = [
    {
      report_id: "car_decay",
      grant_id: "cg_decay",
      relay_id: "relay-decay",
      client_instance_id: "decay-client",
      reason: "spam",
      reported_at: 100,
      counters: {},
      source_ip: "127.0.0.1",
      created_at: 100,
    },
  ];
  const decayStore = {
    policy() {
      return decayPolicy;
    },
    listRelayAbuseReports(relayId, since) {
      return decayReports.filter((report) => report.relay_id === relayId && report.created_at >= since);
    },
    getClientRelayLatestAbuseReport(clientInstanceId, relayId, since) {
      return (
        decayReports
          .filter(
            (report) =>
              report.client_instance_id === clientInstanceId &&
              report.relay_id === relayId &&
              report.created_at >= since
          )
          .sort((left, right) => right.created_at - left.created_at)[0] || null
      );
    },
  };
  const decayManager = createClientAbuseReportManager({ config: decayConfig, store: decayStore });
  const early = decayManager.selectionStateForRelay("relay-decay", "other-client", 110);
  const late = decayManager.selectionStateForRelay("relay-decay", "other-client", 195);
  assert(early.score > late.score, "abuse score should decay over time");
  assert(late.score > 0, "score should remain positive before the window ends");
  const expired = decayManager.selectionStateForRelay("relay-decay", "other-client", 250);
  assert(expired.score === 0, "abuse score should decay to zero after the window");
  console.log("PASS client_abuse_score_decay");
}

main().catch((err) => {
  console.error("FAIL client_abuse_reports", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
