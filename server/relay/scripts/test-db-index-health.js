#!/usr/bin/env node
/* eslint-disable no-console */

const { Client } = require("pg");

const databaseUrl = process.env.DATABASE_URL || process.env.RELAY_DATABASE_URL;
const testTimeoutMs = Number(process.env.DB_INDEX_HEALTH_TEST_TIMEOUT_MS || "10000");

async function run() {
  if (!databaseUrl) {
    console.error("DATABASE_URL is required for DB index health tests.");
    process.exit(1);
  }

  const client = new Client({
    connectionString: databaseUrl,
    connectionTimeoutMillis: Math.min(testTimeoutMs, 5000),
  });
  await client.connect();
  await client.query("SET statement_timeout TO 5000");

  let failed = 0;
  const watchdog = setTimeout(() => {
    console.log("FAIL db_index_health");
    console.log(`  error: test_timeout_${testTimeoutMs}ms`);
    process.exit(1);
  }, testTimeoutMs);

  try {
    console.log("db_index_health_test_start");
    const tables = await client.query(
      `SELECT 'events' AS name
       UNION ALL
       SELECT inhrelid::regclass::text
       FROM pg_inherits
       WHERE inhparent = 'events'::regclass`
    );
    const tableNames = tables.rows.map((row) => row.name);
    const indexes = await client.query(
      `SELECT tablename, indexname, indexdef
       FROM pg_indexes
       WHERE tablename = ANY($1::text[])`,
      [tableNames]
    );
    const defs = indexes.rows.map((row) => row.indexdef.toLowerCase());
    const hasExp = defs.some((def) => def.includes("(exp"));
    const hasFetch = defs.some((def) => def.includes("(gid, ts, eid"));
    if (hasExp && hasFetch) {
      console.log("PASS db_index_required");
    } else {
      failed += 1;
      console.log("FAIL db_index_required");
      console.log(`  events_exp_idx: ${hasExp}`);
      console.log(`  events_gid_ts_eid_idx: ${hasFetch}`);
      if (indexes.rows.length > 0) {
        console.log(`  indexes: ${indexes.rows.map((row) => row.indexname).join(", ")}`);
      }
    }

    try {
      const bloat = await client.query(
        `SELECT schemaname, relname, n_dead_tup, n_live_tup
         FROM pg_stat_user_tables
         WHERE relname = 'events'`
      );
      if (bloat.rows.length > 0) {
        console.log("PASS db_index_bloat_check");
        const row = bloat.rows[0];
        const dead = Number(row.n_dead_tup || 0);
        const live = Number(row.n_live_tup || 0);
        if (live > 0) {
          const ratio = dead / live;
          if (ratio > 0.2) {
            console.log("WARN db_index_bloat_ratio");
            console.log(`  dead: ${dead} live: ${live} ratio: ${ratio.toFixed(3)}`);
          }
        }
      } else {
        console.log("WARN db_index_bloat_check");
        console.log("  pg_stat_user_tables has no events row");
      }
    } catch (err) {
      console.log("WARN db_index_bloat_check");
      console.log(`  error: ${err.message || String(err)}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL db_index_health");
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
