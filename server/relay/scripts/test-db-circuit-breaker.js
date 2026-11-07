#!/usr/bin/env node
/* eslint-disable no-console */

const { createPool } = require("../src/db");

const badDatabaseUrl =
  process.env.DB_CIRCUIT_TEST_DATABASE_URL ||
  "postgres://postgres:postgres@127.0.0.1:1/relay";
const testTimeoutMs = Number(process.env.DB_CIRCUIT_TEST_TIMEOUT_MS || "8000");

async function tryQuery(pool) {
  try {
    await pool.query("SELECT 1");
    return { ok: true };
  } catch (err) {
    return { ok: false, error: err };
  }
}

async function run() {
  let failed = 0;
  const watchdog = setTimeout(() => {
    console.log("FAIL db_circuit_breaker");
    console.log(`  error: test_timeout_${testTimeoutMs}ms`);
    process.exit(1);
  }, testTimeoutMs);

  const pool = createPool({
    databaseUrl: badDatabaseUrl,
    poolMax: 1,
    idleTimeoutMs: 1000,
    connTimeoutMs: 200,
    circuitBreakerEnabled: true,
    circuitBreakerFailureThreshold: 2,
    circuitBreakerBaseBackoffMs: 200,
    circuitBreakerMaxBackoffMs: 200,
  });

  try {
    console.log("db_circuit_test_start");
    const first = await tryQuery(pool);
    if (first.ok) {
      failed += 1;
      console.log("FAIL db_circuit_breaker");
      console.log("  unexpected success on bad DB");
    }

    const second = await tryQuery(pool);
    if (second.ok) {
      failed += 1;
      console.log("FAIL db_circuit_breaker");
      console.log("  unexpected success on bad DB");
    }

    const third = await tryQuery(pool);
    if (third.ok || !third.error || third.error.code !== "DB_CIRCUIT_OPEN") {
      failed += 1;
      console.log("FAIL db_circuit_open");
      console.log(
        `  expected DB_CIRCUIT_OPEN, got ${third.error ? third.error.code : "success"}`
      );
    } else {
      console.log("PASS db_circuit_open");
    }

    await new Promise((resolve) => setTimeout(resolve, 250));
    const fourth = await tryQuery(pool);
    if (fourth.ok) {
      failed += 1;
      console.log("FAIL db_circuit_half_open");
      console.log("  unexpected success on bad DB");
    } else if (fourth.error && fourth.error.code === "DB_CIRCUIT_OPEN") {
      failed += 1;
      console.log("FAIL db_circuit_half_open");
      console.log("  circuit did not allow retry after backoff");
    } else {
      console.log("PASS db_circuit_half_open");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL db_circuit_breaker");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    clearTimeout(watchdog);
    await pool.end();
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
