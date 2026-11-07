#!/usr/bin/env node
/* eslint-disable no-console */

const { Client } = require("pg");
const { loadConfig } = require("../src/config");

async function run() {
  let failed = 0;
  const config = loadConfig();
  const checkTimeoutMs = Number(process.env.PARTITION_SELF_CHECK_TIMEOUT_MS || "5000");
  const connectTimeoutMs = Number(process.env.PARTITION_SELF_CHECK_CONN_TIMEOUT_MS || "2000");
  const databaseUrl = process.env.DATABASE_URL || config.databaseUrl;
  if (!databaseUrl) {
    console.log("FAIL partition_self_check");
    console.log("  error: missing_DATABASE_URL");
    process.exit(1);
  }
  const client = new Client({
    connectionString: databaseUrl,
    connectionTimeoutMillis: connectTimeoutMs,
  });
  const watchdog = setTimeout(() => {
    console.log("FAIL partition_self_check");
    console.log(`  error: test_timeout_${checkTimeoutMs}ms`);
    process.exit(1);
  }, checkTimeoutMs);

  try {
    await client.connect();
    const statementTimeoutMs = Math.min(checkTimeoutMs, 5000);
    await client.query(`SET statement_timeout TO ${statementTimeoutMs}`);
    const partitioned = await client.query(
      `SELECT partstrat
       FROM pg_partitioned_table pt
       JOIN pg_class c ON c.oid = pt.partrelid
       WHERE c.relname = 'events'`
    );
    const isPartitioned = partitioned.rows.length > 0;
    let partitionsCount = 0;
    if (isPartitioned) {
      const partitions = await client.query(
        `SELECT inhrelid::regclass::text AS name
         FROM pg_inherits
         WHERE inhparent = 'events'::regclass`
      );
      partitionsCount = partitions.rows.length;
    }

    if (!isPartitioned) {
      console.log("PASS partition_self_check (not_partitioned)");
    } else if (partitionsCount > 0) {
      console.log("PASS partition_self_check (partitioned)");
    } else {
      failed += 1;
      console.log("FAIL partition_self_check");
      console.log("  partitioned schema has no partitions");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL partition_self_check");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    clearTimeout(watchdog);
    try {
      await client.end();
    } catch (err) {
      // ignore close errors
    }
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
