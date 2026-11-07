#!/usr/bin/env node
/* eslint-disable no-console */

const { createDbCooldown } = require("../src/db_cooldown");
const { createMetrics } = require("../src/metrics");

const testTimeoutMs = Number(process.env.DB_COOLDOWN_TEST_TIMEOUT_MS || "8000");

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function parseGauge(body, name) {
  const line = body
    .split("\n")
    .find((entry) => entry.startsWith(`${name} `));
  if (!line) return null;
  const parts = line.trim().split(/\s+/);
  const value = Number(parts[parts.length - 1]);
  return Number.isFinite(value) ? value : null;
}

async function run() {
  let failed = 0;
  let nowMs = Date.UTC(2025, 0, 1, 0, 0, 0);
  const metrics = createMetrics();
  metrics.registerCollector(() => {});

  const cooldown = createDbCooldown({
    getStats: null,
    metrics,
    config: {
      dbCooldownEnabled: true,
      dbCooldownWaitingThreshold: 5,
      dbCooldownWaitMsThreshold: 50,
      dbCooldownMinSeconds: 2,
      dbCooldownStableWindows: 2,
      dbCooldownCheckIntervalMs: 1000,
    },
    now: () => nowMs,
    onLog: () => {},
  });

  const watchdog = setTimeout(() => {
    console.log("FAIL db_cooldown");
    console.log(`  error: test_timeout_${testTimeoutMs}ms`);
    process.exit(1);
  }, testTimeoutMs);

  try {
    console.log("db_cooldown_test_start");
    cooldown.evaluate({ waitingCount: 0, waitCount: 0, waitSumMs: 0 });
    if (cooldown.isActive()) {
      failed += 1;
      console.log("FAIL db_cooldown_initial_state");
    }

    cooldown.evaluate({ waitingCount: 7, waitCount: 1, waitSumMs: 80 });
    if (!cooldown.isActive()) {
      failed += 1;
      console.log("FAIL db_cooldown_activation");
    } else {
      console.log("PASS db_cooldown_activation");
    }

    nowMs += 1000;
    cooldown.evaluate({ waitingCount: 0, waitCount: 0, waitSumMs: 0 });
    if (!cooldown.isActive()) {
      failed += 1;
      console.log("FAIL db_cooldown_min_duration");
    }

    nowMs += 2000;
    cooldown.evaluate({ waitingCount: 0, waitCount: 0, waitSumMs: 0 });
    cooldown.evaluate({ waitingCount: 0, waitCount: 0, waitSumMs: 0 });
    if (cooldown.isActive()) {
      failed += 1;
      console.log("FAIL db_cooldown_deactivation");
    } else {
      console.log("PASS db_cooldown_deactivation");
    }

    const rendered = metrics.renderPrometheus();
    const activeGauge = parseGauge(rendered, "db_cooldown_active");
    if (activeGauge !== 0) {
      failed += 1;
      console.log("FAIL db_cooldown_metric");
    } else {
      console.log("PASS db_cooldown_metric");
    }

    await sleep(10);
  } catch (err) {
    failed += 1;
    console.log("FAIL db_cooldown");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    clearTimeout(watchdog);
    cooldown.shutdown();
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
