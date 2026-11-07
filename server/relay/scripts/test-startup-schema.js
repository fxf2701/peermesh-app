#!/usr/bin/env node
/* eslint-disable no-console */

const { spawn } = require("child_process");
const { Client } = require("pg");
const { createStorage } = require("../src/storage");

const testDatabaseUrl =
  process.env.MIGRATIONS_TEST_DATABASE_URL || process.env.DATABASE_URL || "";
const testTimeoutMs = Number(process.env.STARTUP_SCHEMA_TEST_TIMEOUT_MS || "15000");

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
    startupSchemaValidationTimeoutMs: Math.min(testTimeoutMs, 5000),
    cleanupIntervalSeconds: 0,
    cleanupGraceSeconds: 0,
    cleanupUseAdvisoryLock: false,
    nonceCacheMaxEntries: 0,
    nonceCacheTtlSeconds: 0,
  };
}

async function run() {
  if (!testDatabaseUrl) {
    console.error("MIGRATIONS_TEST_DATABASE_URL is required for startup schema tests.");
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
    console.log("FAIL startup_schema_tests");
    console.log(`  error: test_timeout_${testTimeoutMs}ms`);
    process.exit(1);
  }, testTimeoutMs);

  try {
    console.log("startup_schema_test_start");
    await client.query("DROP TABLE IF EXISTS federation_cursors");
    await client.query("DROP TABLE IF EXISTS events");
    await client.query("DROP TABLE IF EXISTS schema_migrations");

    const storageMissing = createStorage({ config: buildConfig(), redis: null });
    try {
      console.log("startup_schema_missing_start");
      await storageMissing.validateSchema();
      failed += 1;
      console.log("FAIL startup_schema_missing");
      console.log("  expected missing schema validation to fail");
    } catch (err) {
      const missing = err && err.missingTables ? err.missingTables : [];
      if (missing.length > 0) {
        console.log("PASS startup_schema_missing");
      } else {
        failed += 1;
        console.log("FAIL startup_schema_missing");
        console.log(`  error: ${err.message || String(err)}`);
      }
    } finally {
      await storageMissing.close();
    }

    console.log("startup_schema_migrate_up_start");
    await runMigrate("up");

    const storageOk = createStorage({ config: buildConfig(), redis: null });
    try {
      console.log("startup_schema_valid_start");
      await storageOk.validateSchema();
      console.log("PASS startup_schema_valid");
    } catch (err) {
      failed += 1;
      console.log("FAIL startup_schema_valid");
      console.log(`  error: ${err.message || String(err)}`);
    } finally {
      await storageOk.close();
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL startup_schema_tests");
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
