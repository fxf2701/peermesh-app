#!/usr/bin/env node
/* eslint-disable no-console */

const fs = require("fs");
const os = require("os");
const path = require("path");
const { createAuditLogger } = require("../src/audit_log");

const testTimeoutMs = Number(process.env.AUDIT_LOG_TEST_TIMEOUT_MS || "8000");

async function run() {
  let failed = 0;
  const watchdog = setTimeout(() => {
    console.log("FAIL audit_log");
    console.log(`  error: test_timeout_${testTimeoutMs}ms`);
    process.exit(1);
  }, testTimeoutMs);

  try {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "relay-audit-"));
    const filePath = path.join(dir, "audit.log");
    const logger = createAuditLogger({
      config: {
        auditLogEnabled: true,
        auditLogPath: filePath,
        auditLogSecret: "test-secret",
        dataDir: dir,
      },
      onLog: () => {},
    });

    console.log("audit_log_test_start");
    logger.log({ action: "ingest", status: 200, req_id: "r1" });
    logger.log({ action: "fetch", status: 200, req_id: "r2" });

    const verify = logger.verify();
    if (!verify.ok || verify.entries !== 2) {
      failed += 1;
      console.log("FAIL audit_log_verify");
    } else {
      console.log("PASS audit_log_verify");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL audit_log");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    clearTimeout(watchdog);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
