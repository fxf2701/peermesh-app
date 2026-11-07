#!/usr/bin/env node
/* eslint-disable no-console */

const { createRateLimitSpikeDetector } = require("../src/rate_limit_spike");
const { createMetrics } = require("../src/metrics");

const testTimeoutMs = Number(process.env.RATE_LIMIT_SPIKE_TEST_TIMEOUT_MS || "8000");

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
  const metrics = createMetrics();
  metrics.registerCollector(() => {});
  metrics.setGauge("rate_limited_spike_active", 0);

  let logged = false;
  const detector = createRateLimitSpikeDetector({
    metrics,
    config: {
      rateLimitSpikeWindowSeconds: 1,
      rateLimitSpikeThreshold: 3,
      rateLimitSpikeCooldownSeconds: 2,
    },
    onLog: () => {
      logged = true;
    },
  });

  const watchdog = setTimeout(() => {
    console.log("FAIL rate_limit_spike");
    console.log(`  error: test_timeout_${testTimeoutMs}ms`);
    process.exit(1);
  }, testTimeoutMs);

  try {
    console.log("rate_limit_spike_test_start");
    metrics.inc("rate_limited_total", 4);
    await sleep(1200);

    const rendered = metrics.renderPrometheus();
    const active = parseGauge(rendered, "rate_limited_spike_active");
    if (active !== 1 || !logged) {
      failed += 1;
      console.log("FAIL rate_limit_spike_trigger");
    } else {
      console.log("PASS rate_limit_spike_trigger");
    }

    await sleep(2200);
    metrics.inc("rate_limited_total", 1);
    await sleep(1200);
    const renderedAfter = metrics.renderPrometheus();
    const activeAfter = parseGauge(renderedAfter, "rate_limited_spike_active");
    if (activeAfter !== 0) {
      failed += 1;
      console.log("FAIL rate_limit_spike_cooldown");
    } else {
      console.log("PASS rate_limit_spike_cooldown");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL rate_limit_spike");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    clearTimeout(watchdog);
    if (detector) detector.shutdown();
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
