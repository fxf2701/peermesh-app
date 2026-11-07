// Create new migration files.

const fs = require("fs");
const path = require("path");

const name = process.argv[2];
if (!name) {
  // eslint-disable-next-line no-console
  console.error("Usage: node scripts/create-migration.js <name>");
  process.exit(1);
}

const migrationsDir = path.join(__dirname, "..", "migrations");
const files = fs.readdirSync(migrationsDir);

let max = 0;
for (const file of files) {
  const match = file.match(/^(\d+)_/);
  if (!match) continue;
  const num = Number(match[1]);
  if (Number.isFinite(num)) max = Math.max(max, num);
}

const next = String(max + 1).padStart(3, "0");
const slug = name.trim().toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "");
const base = `${next}_${slug}`;

const upPath = path.join(migrationsDir, `${base}.up.sql`);
const downPath = path.join(migrationsDir, `${base}.down.sql`);

if (fs.existsSync(upPath) || fs.existsSync(downPath)) {
  // eslint-disable-next-line no-console
  console.error("Migration already exists.");
  process.exit(1);
}

fs.writeFileSync(upPath, `-- ${base}.up.sql\n\n`);
fs.writeFileSync(downPath, `-- ${base}.down.sql\n\n`);

// eslint-disable-next-line no-console
console.log(`Created ${base}.up.sql and ${base}.down.sql`);
