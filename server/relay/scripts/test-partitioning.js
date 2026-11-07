#!/usr/bin/env node
/* eslint-disable no-console */

const { Client } = require("pg");

const databaseUrl = process.env.DATABASE_URL || process.env.RELAY_DATABASE_URL;

async function run() {
  if (!databaseUrl) {
    console.error("DATABASE_URL is required for partitioning tests.");
    process.exit(1);
  }

  const client = new Client({ connectionString: databaseUrl });
  await client.connect();

  let failed = 0;

  try {
    const partitioned = await client.query(
      `SELECT partstrat
       FROM pg_partitioned_table pt
       JOIN pg_class c ON c.oid = pt.partrelid
       WHERE c.relname = 'events'`
    );

    if (partitioned.rows.length === 0) {
      failed += 1;
      console.log("FAIL partitioning_enabled");
      console.log("  events is not partitioned; apply schema_partitioned.sql");
    } else {
      console.log("PASS partitioning_enabled");
    }

    const partitions = await client.query(
      `SELECT inhrelid::regclass::text AS name
       FROM pg_inherits
       WHERE inhparent = 'events'::regclass`
    );

    if (partitions.rows.length === 0) {
      failed += 1;
      console.log("FAIL partitions_exist");
      console.log("  no child partitions found; create at least one range partition");
    } else {
      console.log("PASS partitions_exist");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL partitioning_tests");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    await client.end();
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
