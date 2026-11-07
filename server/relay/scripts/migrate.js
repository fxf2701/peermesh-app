// Migration runner for Postgres.

const fs = require("fs");
const path = require("path");
const { Client } = require("pg");

const rawArgs = process.argv.slice(2);
let direction = "up";
let steps = null;

if (rawArgs.includes("--up")) {
  direction = "up";
  const index = rawArgs.indexOf("--up");
  const value = rawArgs[index + 1];
  steps = value ? Number(value) : null;
} else if (rawArgs.includes("--down")) {
  direction = "down";
  const index = rawArgs.indexOf("--down");
  const value = rawArgs[index + 1];
  steps = value ? Number(value) : null;
} else if (rawArgs[0] === "down") {
  direction = "down";
} else if (rawArgs[0] === "up") {
  direction = "up";
}
const databaseUrl = process.env.DATABASE_URL || "postgres://postgres:postgres@localhost:5432/relay";
const migrationsDir = path.join(__dirname, "..", "migrations");

function readMigrations() {
  const files = fs.readdirSync(migrationsDir);
  const migrations = new Map();

  for (const file of files) {
    const match = file.match(/^(\d+_[a-z0-9_]+)\.(up|down)\.sql$/i);
    if (!match) continue;

    const version = match[1];
    const kind = match[2];
    const entry = migrations.get(version) || { version };
    entry[kind] = path.join(migrationsDir, file);
    migrations.set(version, entry);
  }

  return Array.from(migrations.values()).sort((a, b) => a.version.localeCompare(b.version));
}

async function ensureMigrationsTable(client) {
  await client.query(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version TEXT PRIMARY KEY,
      applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
  `);
}

async function getAppliedVersions(client) {
  const result = await client.query("SELECT version FROM schema_migrations ORDER BY version ASC");
  return result.rows.map((row) => row.version);
}

async function applyMigration(client, migration, kind, index) {
  const filePath = migration[kind];
  if (!filePath) {
    throw new Error(`missing ${kind} migration for ${migration.version}`);
  }

  const label = `${index}. ${path.basename(filePath)}`;
  const sql = fs.readFileSync(filePath, "utf8");
  await client.query("BEGIN");
  try {
    await client.query(sql);
    if (kind === "up") {
      await client.query(
        "INSERT INTO schema_migrations (version) VALUES ($1) ON CONFLICT (version) DO NOTHING",
        [migration.version]
      );
    } else {
      try {
        await client.query("DELETE FROM schema_migrations WHERE version = $1", [migration.version]);
      } catch (err) {
        if (err.code !== "42P01") throw err;
      }
    }
    await client.query("COMMIT");
    // eslint-disable-next-line no-console
    console.log(`${label} PASS`);
  } catch (err) {
    await client.query("ROLLBACK");
    // eslint-disable-next-line no-console
    console.log(`${label} FAILED`);
    throw err;
  }
}

async function migrateUp(client, limit) {
  const migrations = readMigrations();
  const applied = new Set(await getAppliedVersions(client));
  const pending = migrations.filter((migration) => !applied.has(migration.version));
  const toApply = typeof limit === "number" ? pending.slice(0, limit) : pending;

  for (let i = 0; i < toApply.length; i += 1) {
    await applyMigration(client, toApply[i], "up", i + 1);
  }
}

async function migrateDown(client, limit) {
  const migrations = readMigrations();
  const applied = await getAppliedVersions(client);
  const count = typeof limit === "number" ? limit : 1;
  const toRollback = applied.slice(-count).reverse();

  if (toRollback.length === 0) return;

  for (let i = 0; i < toRollback.length; i += 1) {
    const version = toRollback[i];
    const migration = migrations.find((entry) => entry.version === version);
    if (!migration) {
      throw new Error(`missing migration files for ${version}`);
    }
    await applyMigration(client, migration, "down", i + 1);
  }
}

async function run() {
  const client = new Client({ connectionString: databaseUrl });
  await client.connect();

  try {
    await ensureMigrationsTable(client);
    if (direction === "down") {
      await migrateDown(client, steps);
    } else {
      await migrateUp(client, steps);
    }
  } finally {
    await client.end();
  }
}

run().catch((err) => {
  // eslint-disable-next-line no-console
  console.error(err);
  process.exit(1);
});
