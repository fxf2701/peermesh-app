#!/usr/bin/env node
/* eslint-disable no-console */

const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");

const repoRoot = path.resolve(__dirname, "..");
const backupDirHost =
  process.env.BACKUP_DIR_HOST || path.join(repoRoot, "backups");
const containerName = process.env.CONTAINER_NAME || "disaster-app-postgres";
const dbUser = process.env.DB_USER || "postgres";
const dbName = process.env.DB_NAME || "relay";

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    stdio: "inherit",
    ...options,
  });
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed`);
  }
}

function latestBackupFile(dir) {
  const files = fs
    .readdirSync(dir)
    .filter((name) => name.startsWith("relay_") && name.endsWith(".dump"))
    .map((name) => path.join(dir, name));
  if (files.length === 0) return null;
  files.sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);
  return files[0];
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

function dockerAvailable() {
  const result = spawnSync("docker", ["version"], { stdio: "ignore" });
  return result.status === 0;
}

async function runTest() {
  if (!dockerAvailable()) {
    console.error("docker is required for backup/restore tests.");
    process.exit(1);
  }

  ensureDir(backupDirHost);

  let failed = 0;

  try {
    console.log("Running backup script...");
    run("bash", ["scripts/backup-postgres-docker.sh"], {
      cwd: repoRoot,
      env: {
        ...process.env,
        BACKUP_DIR: backupDirHost,
        CONTAINER_NAME: containerName,
        DB_USER: dbUser,
        DB_NAME: dbName,
      },
    });

    const latest = latestBackupFile(backupDirHost);
    if (!latest) {
      failed += 1;
      console.log("FAIL backup_file_created");
      console.log("  no backup file found");
      return;
    }
    console.log("PASS backup_file_created");

    const restoreDb = `relay_restore_test_${Date.now()}`;
    console.log("Running restore workflow...");
    run("bash", ["scripts/restore-validate-postgres-docker-workflow.sh", latest], {
      cwd: repoRoot,
      env: {
        ...process.env,
        CONTAINER_NAME: containerName,
        DB_USER: dbUser,
        DB_NAME: restoreDb,
      },
    });
    console.log("PASS restore_workflow");

    console.log("Cleaning up restore database...");
    run("docker", [
      "exec",
      "-i",
      containerName,
      "psql",
      "-U",
      dbUser,
      "-d",
      "postgres",
      "-c",
      `DROP DATABASE IF EXISTS ${restoreDb};`,
    ]);
    console.log("PASS cleanup_restore_db");
  } catch (err) {
    failed += 1;
    console.log("FAIL backup_restore_tests");
    console.log(`  error: ${err.message || String(err)}`);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

runTest();
