#!/usr/bin/env node
/* eslint-disable no-console */

const { spawnSync } = require("child_process");
const fs = require("fs");
const path = require("path");

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    stdio: "inherit",
    ...options,
  });
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed`);
  }
}

function dockerAvailable() {
  const result = spawnSync("docker", ["version"], { stdio: "ignore" });
  return result.status === 0;
}

function main() {
  const repoRoot = path.resolve(__dirname, "..");
  if (!dockerAvailable()) {
    console.error("docker is required for banlist test.");
    process.exit(1);
  }

  const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";
  const adminPass = process.env.ADMIN_PASSWORD || "admin1234";

  console.log("Creating admin auth file...");
  run("bash", [path.join(repoRoot, "scripts/reset-admin-password.sh"), adminPass]);

  console.log("Logging in to admin API...");
  const login = spawnSync("curl", [
    "-s",
    "-o",
    "/tmp/admin_login.json",
    "-c",
    "/tmp/admin.cookies",
    "-X",
    "POST",
    `${baseUrl}/admin/api/auth/login`,
    "-H",
    "Content-Type: application/json",
    "-d",
    JSON.stringify({ username: "admin", password: adminPass }),
  ]);
  if (login.status !== 0) {
    throw new Error("login failed");
  }

  console.log("Creating banlist entry...");
  const create = spawnSync("curl", [
    "-s",
    "-o",
    "/tmp/admin_banlist_create.json",
    "-b",
    "/tmp/admin.cookies",
    "-X",
    "POST",
    `${baseUrl}/admin/api/banlist`,
    "-H",
    "Content-Type: application/json",
    "-d",
    JSON.stringify({ type: "peer_id", value: "peer-test", reason: "test" }),
  ]);
  if (create.status !== 0) {
    throw new Error("banlist create failed");
  }

  console.log("Validating banlist enforcement...");
  const ingest = spawnSync("curl", [
    "-s",
    "-o",
    "/tmp/banlist_ingest.json",
    "-X",
    "POST",
    `${baseUrl}/v1/events:ingest`,
    "-H",
    "Content-Type: application/json",
    "-H",
    "X-Relay-Peer: 1",
    "-H",
    "X-Relay-From: peer-test",
    "-H",
    "X-Relay-Peer-Token: dev-peer-token",
    "-d",
    JSON.stringify({
      events: [
        {
          eid: "11111111-1111-1111-1111-111111111111",
          gid: "Z29vZGd",
          ts: 1730000000,
          exp: 1730003600,
          body: { type: "CHAT.MSG", text: "hello" },
          sig: "abcd",
        },
      ],
    }),
  ]);
  if (ingest.status !== 0) {
    throw new Error("ingest failed");
  }
  try {
    const payload = JSON.parse(fs.readFileSync("/tmp/banlist_ingest.json", "utf8"));
    if (!payload.error || payload.error.code !== "forbidden") {
      throw new Error("expected forbidden response for banned peer");
    }
  } catch (err) {
    throw new Error(`unexpected ingest response: ${err.message || String(err)}`);
  }

  console.log("PASS banlist_dynamic");
}

main();
