#!/usr/bin/env node
/* eslint-disable no-console */

const fs = require("fs");
const path = require("path");
const { hashPassword, saveAdminAuth } = require("../src/admin_auth");
const { loadConfig } = require("../src/config");

process.chdir(path.resolve(__dirname, ".."));

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--password") {
      args.password = argv[i + 1];
      i += 1;
      continue;
    }
    if (arg === "--username") {
      args.username = argv[i + 1];
      i += 1;
      continue;
    }
    if (arg === "--file") {
      args.file = argv[i + 1];
      i += 1;
      continue;
    }
  }
  return args;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const config = loadConfig();

  if (args.file) {
    config.adminAuthFile = args.file;
  }

  const username = args.username || process.env.ADMIN_USERNAME || "admin";
  const password = args.password || process.env.ADMIN_DEFAULT_PASSWORD || "admin";

  if (password.length < 8) {
    console.error("Password must be at least 8 characters.");
    process.exit(1);
  }

  const authFile = path.resolve(process.cwd(), config.adminAuthFile);
  fs.mkdirSync(path.dirname(authFile), { recursive: true });

  const passwordHash = hashPassword(password);
  const saved = saveAdminAuth({ config, username, passwordHash });
  if (!saved.ok) {
    console.error("Failed to write admin auth file.");
    process.exit(1);
  }

  console.log(`Admin auth updated: ${authFile}`);
}

main();
