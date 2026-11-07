#!/usr/bin/env node
/* eslint-disable no-console */

const { createIngestRetryQueue } = require("../src/ingest_retry_queue");
const { createMetrics } = require("../src/metrics");

const testTimeoutMs = Number(process.env.INGEST_RETRY_TEST_TIMEOUT_MS || "8000");

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function run() {
  let failed = 0;
  const watchdog = setTimeout(() => {
    console.log("FAIL ingest_retry_queue");
    console.log(`  error: test_timeout_${testTimeoutMs}ms`);
    process.exit(1);
  }, testTimeoutMs);

  const metrics = createMetrics();
  metrics.registerCollector(() => {});
  metrics.setGauge("ingest_retry_queue_size", 0);
  let attempts = 0;
  const storage = {
    ingestEvents: async () => {
      attempts += 1;
      if (attempts < 3) {
        const err = new Error("db down");
        err.code = "ECONNREFUSED";
        throw err;
      }
      return { accepted: ["ok"], rejected: [] };
    },
  };

    const queue = createIngestRetryQueue({
    storage,
    metrics,
    config: {
      ingestRetryEnabled: true,
      ingestRetryMaxEntries: 10,
      ingestRetryMaxAttempts: 5,
      ingestRetryBaseDelayMs: 50,
      ingestRetryMaxDelayMs: 200,
      ingestRetryIntervalMs: 20,
    },
      onLog: () => {},
    });

    try {
      console.log("ingest_retry_queue_test_start");
      const enqueued = queue.enqueue([{ eid: "e1", gid: "g1" }], "db_error");
    if (!enqueued) {
      failed += 1;
      console.log("FAIL ingest_retry_queue_enqueue");
    }

    const deadline = Date.now() + testTimeoutMs;
    while (Date.now() < deadline) {
      if (metrics.getCounter("ingest_retry_success_total") > 0) {
        console.log("PASS ingest_retry_queue_success");
        break;
      }
      await sleep(50);
    }

    if (metrics.getCounter("ingest_retry_success_total") === 0) {
      failed += 1;
      console.log("FAIL ingest_retry_queue_success");
    }

    let dropAttempts = 0;
    metrics.setGauge("ingest_retry_queue_size", 0);
    const dropStorage = {
      ingestEvents: async () => {
        dropAttempts += 1;
        const err = new Error("db down");
        err.code = "ECONNREFUSED";
        throw err;
      },
    };

    const dropQueue = createIngestRetryQueue({
      storage: dropStorage,
      metrics,
      config: {
        ingestRetryEnabled: true,
        ingestRetryMaxEntries: 10,
        ingestRetryMaxAttempts: 2,
        ingestRetryBaseDelayMs: 30,
        ingestRetryMaxDelayMs: 60,
        ingestRetryIntervalMs: 20,
      },
      onLog: () => {},
    });

    dropQueue.enqueue([{ eid: "e2", gid: "g1" }], "db_error");
    const dropDeadline = Date.now() + testTimeoutMs;
    while (Date.now() < dropDeadline) {
      if (metrics.getCounter("ingest_retry_dropped_total") > 0) {
        console.log("PASS ingest_retry_queue_drop");
        break;
      }
      await sleep(50);
    }

    if (metrics.getCounter("ingest_retry_dropped_total") === 0) {
      failed += 1;
      console.log("FAIL ingest_retry_queue_drop");
    }

    dropQueue.shutdown();
  } catch (err) {
    failed += 1;
    console.log("FAIL ingest_retry_queue");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    clearTimeout(watchdog);
    queue.shutdown();
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
