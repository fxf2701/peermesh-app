#!/usr/bin/env node
/* eslint-disable no-console */

const { spawn } = require("child_process");
const fs = require("fs");
const path = require("path");

const baselinePath =
  process.env.LOAD_BASELINE_PATH ||
  path.join(__dirname, "..", "tests", "load_baseline.json");
const regressionP95Pct = Number(process.env.LOAD_REGRESSION_P95_PCT || "0.2");
const regressionErrorRate = Number(process.env.LOAD_REGRESSION_ERROR_RATE || "0.01");
const writeBaseline = process.env.LOAD_BASELINE_WRITE === "1";

function runLoad() {
  return new Promise((resolve, reject) => {
    const child = spawn("node", ["scripts/test-load.js"], {
      cwd: path.join(__dirname, ".."),
      env: {
        ...process.env,
        LOAD_OUTPUT_JSON: "1",
        LOAD_MAX_P95_MS: process.env.LOAD_MAX_P95_MS || "999999",
        LOAD_MAX_ERROR_RATE: process.env.LOAD_MAX_ERROR_RATE || "1",
      },
      stdio: ["ignore", "pipe", "pipe"],
    });

    let output = "";
    child.stdout.on("data", (chunk) => {
      const text = chunk.toString();
      output += text;
      process.stdout.write(text);
    });
    child.stderr.on("data", (chunk) => process.stderr.write(chunk));
    child.on("exit", (code) => {
      if (code !== 0) {
        return reject(new Error(`load test failed with code ${code}`));
      }
      return resolve(output);
    });
  });
}

function extractSummary(output) {
  const line = output
    .split("\n")
    .find((entry) => entry.startsWith("LOAD_RESULT_JSON "));
  if (!line) return null;
  try {
    return JSON.parse(line.replace("LOAD_RESULT_JSON ", ""));
  } catch (err) {
    return null;
  }
}

function compareMetric(label, current, baseline, maxRatio, maxDelta) {
  if (baseline <= 0) return current <= maxDelta;
  const ratio = current / baseline;
  return ratio <= 1 + maxRatio && current - baseline <= maxDelta;
}

async function run() {
  let failed = 0;
  try {
    const output = await runLoad();
    const summary = extractSummary(output);
    if (!summary) {
      console.log("FAIL load_baseline");
      console.log("  error: missing JSON summary");
      process.exit(1);
    }

    if (writeBaseline) {
      fs.mkdirSync(path.dirname(baselinePath), { recursive: true });
      fs.writeFileSync(baselinePath, `${JSON.stringify(summary, null, 2)}\n`);
      console.log("PASS load_baseline_written");
      return;
    }

    if (!fs.existsSync(baselinePath)) {
      console.log("FAIL load_baseline");
      console.log(`  error: baseline not found at ${baselinePath}`);
      process.exit(1);
    }

    const baseline = JSON.parse(fs.readFileSync(baselinePath, "utf8"));
    if (baseline.ingest.requests <= 0 || baseline.fetch.requests <= 0) {
      console.log("FAIL load_baseline");
      console.log("  error: baseline not initialized (run with LOAD_BASELINE_WRITE=1)");
      process.exit(1);
    }

    const ingestOk = compareMetric(
      "ingest_p95",
      summary.ingest.p95_ms,
      baseline.ingest.p95_ms,
      regressionP95Pct,
      50
    );
    const fetchOk = compareMetric(
      "fetch_p95",
      summary.fetch.p95_ms,
      baseline.fetch.p95_ms,
      regressionP95Pct,
      50
    );
    const ingestErrOk =
      summary.ingest.error_rate <= baseline.ingest.error_rate + regressionErrorRate;
    const fetchErrOk =
      summary.fetch.error_rate <= baseline.fetch.error_rate + regressionErrorRate;

    if (ingestOk && fetchOk && ingestErrOk && fetchErrOk) {
      console.log("PASS load_baseline_regression");
    } else {
      failed += 1;
      console.log("FAIL load_baseline_regression");
      console.log(
        `  ingest_p95_ms: ${summary.ingest.p95_ms} (baseline ${baseline.ingest.p95_ms})`
      );
      console.log(
        `  fetch_p95_ms: ${summary.fetch.p95_ms} (baseline ${baseline.fetch.p95_ms})`
      );
      console.log(
        `  ingest_error_rate: ${summary.ingest.error_rate} (baseline ${baseline.ingest.error_rate})`
      );
      console.log(
        `  fetch_error_rate: ${summary.fetch.error_rate} (baseline ${baseline.fetch.error_rate})`
      );
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL load_baseline");
    console.log(`  error: ${err.message || String(err)}`);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
