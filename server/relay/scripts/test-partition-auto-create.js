#!/usr/bin/env node
/* eslint-disable no-console */

const { spawn } = require("child_process");
const { Client } = require("pg");
const { createStorage } = require("../src/storage");

const testDatabaseUrl =
  process.env.MIGRATIONS_TEST_DATABASE_URL || process.env.DATABASE_URL || "";
const testTimeoutMs = Number(process.env.PARTITION_AUTO_CREATE_TEST_TIMEOUT_MS || "15000");

function runMigrate(direction) {
  return new Promise((resolve, reject) => {
    const child = spawn("node", ["scripts/migrate.js", direction], {
      cwd: `${__dirname}/..`,
      env: {
        ...process.env,
        DATABASE_URL: testDatabaseUrl,
      },
      stdio: ["ignore", "pipe", "pipe"],
    });

    child.stdout.on("data", (chunk) => process.stdout.write(chunk));
    child.stderr.on("data", (chunk) => process.stderr.write(chunk));
    const timer = setTimeout(() => {
      child.kill("SIGTERM");
      reject(new Error(`migrate ${direction} timed out after ${testTimeoutMs}ms`));
    }, testTimeoutMs);

    child.on("exit", (code) => {
      clearTimeout(timer);
      if (code === 0) return resolve();
      return reject(new Error(`migrate ${direction} failed with code ${code}`));
    });
  });
}

function buildConfig() {
  return {
    databaseUrl: testDatabaseUrl,
    dbPoolMax: 2,
    dbIdleTimeoutMs: 1000,
    dbConnTimeoutMs: 1000,
    cleanupIntervalSeconds: 0,
    cleanupGraceSeconds: 0,
    cleanupUseAdvisoryLock: false,
    nonceCacheMaxEntries: 0,
    nonceCacheTtlSeconds: 0,
  };
}

async function listPartitions(client) {
  const { rows } = await client.query(
    `SELECT inhrelid::regclass::text AS name
     FROM pg_inherits
     WHERE inhparent = 'events'::regclass`
  );
  return rows.map((row) => row.name);
}

async function dropPartitions(client) {
  const partitions = await listPartitions(client);
  for (const name of partitions) {
    await client.query(`DROP TABLE IF EXISTS ${name}`);
  }
}

async function run() {
  if (!testDatabaseUrl) {
    console.error("MIGRATIONS_TEST_DATABASE_URL is required for partition auto-create tests.");
    process.exit(1);
  }

  const client = new Client({
    connectionString: testDatabaseUrl,
    connectionTimeoutMillis: Math.min(testTimeoutMs, 5000),
  });
  await client.connect();
  await client.query("SET statement_timeout TO 5000");
  await client.query("SET lock_timeout TO 2000");

  let failed = 0;
  const watchdog = setTimeout(() => {
    console.log("FAIL partition_auto_create");
    console.log(`  error: test_timeout_${testTimeoutMs}ms`);
    process.exit(1);
  }, testTimeoutMs);

  try {
    console.log("partition_auto_create_test_start");
    await client.query("DROP TABLE IF EXISTS events CASCADE");
    await client.query("DROP TABLE IF EXISTS schema_migrations");
    await client.query("DROP TABLE IF EXISTS federation_cursors");

    await runMigrate("up");
    await dropPartitions(client);

    const storage = createStorage({ config: buildConfig(), redis: null });
    try {
      const now = new Date(Date.UTC(2025, 0, 15, 0, 0, 0));
      const result = await storage.ensurePartitions({
        monthsAhead: 2,
        backfillMonths: 1,
        now,
      });

      const expected = [
        "events_exp_2024_12",
        "events_exp_2025_01",
        "events_exp_2025_02",
        "events_exp_2025_03",
      ];

      const partitions = await listPartitions(client);
      const missing = expected.filter((name) => !partitions.includes(name));
      if (!result.partitioned || missing.length > 0) {
        failed += 1;
        console.log("FAIL partition_auto_create");
        console.log(`  missing: ${missing.join(", ")}`);
      } else {
        console.log("PASS partition_auto_create");
      }
    } finally {
      await storage.close();
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL partition_auto_create");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    clearTimeout(watchdog);
    await client.end();
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
