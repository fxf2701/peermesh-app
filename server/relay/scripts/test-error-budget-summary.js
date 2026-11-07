#!/usr/bin/env node
/* eslint-disable no-console */

const { createMetrics } = require("../src/metrics");
const { createErrorBudgetReporter } = require("../src/app");

async function run() {
  let failed = 0;
  const metrics = createMetrics();
  const logs = [];
  const config = {
    observabilitySummaryIntervalSeconds: 1,
    observabilitySloTarget: 0.99,
  };

  const reporter = createErrorBudgetReporter({
    metrics,
    config,
    onLog: (level, msg, meta) => {
      logs.push({ level, msg, meta });
    },
  });

  await new Promise((resolve) => setTimeout(resolve, 100));
  metrics.inc("requests_total", 10);
  metrics.inc("errors_total", 1);

  const result = await new Promise((resolve) => {
    const timeout = setTimeout(() => resolve(null), 2500);
    const interval = setInterval(() => {
      const entry = logs.find((log) => log.msg === "error_budget_summary");
      if (entry) {
        clearTimeout(timeout);
        clearInterval(interval);
        resolve(entry);
      }
    }, 50);
  });

  reporter.stop();

  if (!result) {
    failed += 1;
    console.log("FAIL error_budget_summary_log");
  } else {
    const { meta } = result;
    const ok =
      meta &&
      meta.window_seconds === 1 &&
      typeof meta.error_rate === "number" &&
      meta.requests >= 1;
    if (ok) {
      console.log("PASS error_budget_summary_log");
    } else {
      failed += 1;
      console.log("FAIL error_budget_summary_log");
      console.log(`  meta: ${JSON.stringify(meta)}`);
    }
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
