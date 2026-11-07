#!/usr/bin/env node
/* eslint-disable no-console */

const { createStorage } = require("../src/storage");

const testDatabaseUrl =
  process.env.MIGRATIONS_TEST_DATABASE_URL || process.env.DATABASE_URL || "";

const testTimeoutMs = Number(process.env.CLEANUP_WATCHDOG_TEST_TIMEOUT_MS || "10000");

function buildConfig() {
  return {
    databaseUrl: testDatabaseUrl,
    dbPoolMax: 2,
    dbIdleTimeoutMs: 1000,
    dbConnTimeoutMs: 1000,
    cleanupIntervalSeconds: 1,
    cleanupGraceSeconds: 0,
    cleanupUseAdvisoryLock: false,
    cleanupWatchdogTimeoutSeconds: 1,
    cleanupTestHangMs: 3000,
    nonceCacheMaxEntries: 0,
    nonceCacheTtlSeconds: 0,
  };
}

function isCleanupStuckLog(line) {
  try {
    const payload = JSON.parse(line);
    return payload && payload.msg === "cleanup_stuck";
  } catch (err) {
    return false;
  }
}

async function run() {
  if (!testDatabaseUrl) {
    console.error("MIGRATIONS_TEST_DATABASE_URL is required for cleanup watchdog tests.");
    process.exit(1);
  }

  const logs = [];
  const originalLog = console.log;
  console.log = (line) => {
    logs.push(line);
    originalLog(line);
  };

  let failed = 0;
  const watchdog = setTimeout(() => {
    console.log("FAIL cleanup_watchdog_stuck_detected");
    console.log(`  error: test_timeout_${testTimeoutMs}ms`);
    process.exit(1);
  }, testTimeoutMs);

  const storage = createStorage({ config: buildConfig(), redis: null });

  try {
    console.log("cleanup_watchdog_test_start");
    const deadline = Date.now() + testTimeoutMs;
    while (Date.now() < deadline) {
      if (logs.some(isCleanupStuckLog)) {
        console.log("PASS cleanup_watchdog_stuck_detected");
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 200));
    }

    if (!logs.some(isCleanupStuckLog)) {
      failed += 1;
      console.log("FAIL cleanup_watchdog_stuck_detected");
      console.log("  cleanup_stuck log not emitted");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL cleanup_watchdog_tests");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    clearTimeout(watchdog);
    await storage.close();
    console.log = originalLog;
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
