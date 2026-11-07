#!/usr/bin/env node
/* eslint-disable no-console */

const { spawn } = require("child_process");
const { Client } = require("pg");

const testDatabaseUrl =
  process.env.MIGRATIONS_TEST_DATABASE_URL || process.env.DATABASE_URL || "";

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
    child.on("exit", (code) => {
      if (code === 0) return resolve();
      return reject(new Error(`migrate ${direction} failed with code ${code}`));
    });
  });
}

async function tableExists(client, table) {
  const res = await client.query(
    "SELECT to_regclass($1) AS name",
    [`public.${table}`]
  );
  return res.rows[0].name === table;
}

async function run() {
  if (!testDatabaseUrl) {
    console.error("MIGRATIONS_TEST_DATABASE_URL is required for migration tests.");
    process.exit(1);
  }

  const client = new Client({ connectionString: testDatabaseUrl });
  await client.connect();

  let failed = 0;

  try {
    await client.query("DROP TABLE IF EXISTS events");
    await client.query("DROP TABLE IF EXISTS schema_migrations");

    await runMigrate("up");
    const eventsExists = await tableExists(client, "events");
    if (eventsExists) {
      console.log("PASS migrate_up");
    } else {
      failed += 1;
      console.log("FAIL migrate_up");
      console.log("  events table missing after migrate up");
    }

    const versions = await client.query("SELECT count(*)::int AS count FROM schema_migrations");
    if (versions.rows[0].count > 0) {
      console.log("PASS migrations_recorded");
    } else {
      failed += 1;
      console.log("FAIL migrations_recorded");
      console.log("  no migrations recorded");
    }

    await runMigrate("down");
    const eventsAfterDown = await tableExists(client, "events");
    if (!eventsAfterDown) {
      console.log("PASS migrate_down");
    } else {
      failed += 1;
      console.log("FAIL migrate_down");
      console.log("  events table still exists after migrate down");
    }

    const versionsAfter = await client.query("SELECT count(*)::int AS count FROM schema_migrations");
    if (versionsAfter.rows[0].count === 0) {
      console.log("PASS migrations_cleared");
    } else {
      failed += 1;
      console.log("FAIL migrations_cleared");
      console.log(`  remaining versions: ${versionsAfter.rows[0].count}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL migration_tests");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    await client.end();
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
