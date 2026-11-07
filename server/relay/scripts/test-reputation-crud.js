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

function main() {
  const repoRoot = path.resolve(__dirname, "..");
  const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";
  const adminPass = process.env.ADMIN_PASSWORD || "admin1234";

  console.log("Creating admin auth file...");
  run("bash", [path.join(repoRoot, "scripts/reset-admin-password.sh"), adminPass]);

  console.log("Logging in to admin API...");
  run("curl", [
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

  console.log("Creating reputation entry...");
  run("curl", [
    "-s",
    "-o",
    "/tmp/reputation_create.json",
    "-b",
    "/tmp/admin.cookies",
    "-X",
    "POST",
    `${baseUrl}/admin/api/reputation`,
    "-H",
    "Content-Type: application/json",
    "-d",
    JSON.stringify({ peer_id: "peer-test", score: 2, note: "initial" }),
  ]);

  const created = JSON.parse(fs.readFileSync("/tmp/reputation_create.json", "utf8")).item;
  if (!created || !created.id) {
    throw new Error("create response missing item.id");
  }

  console.log("Updating reputation entry...");
  run("curl", [
    "-s",
    "-o",
    "/tmp/reputation_update.json",
    "-b",
    "/tmp/admin.cookies",
    "-X",
    "PATCH",
    `${baseUrl}/admin/api/reputation/${created.id}`,
    "-H",
    "Content-Type: application/json",
    "-d",
    JSON.stringify({ score: 5, note: "updated" }),
  ]);

  const updated = JSON.parse(fs.readFileSync("/tmp/reputation_update.json", "utf8")).item;
  if (!updated || updated.score !== 5) {
    throw new Error("update response missing updated score");
  }

  console.log("Listing reputation entries...");
  run("curl", [
    "-s",
    "-o",
    "/tmp/reputation_list.json",
    "-b",
    "/tmp/admin.cookies",
    "-X",
    "GET",
    `${baseUrl}/admin/api/reputation`,
  ]);

  const list = JSON.parse(fs.readFileSync("/tmp/reputation_list.json", "utf8")).items;
  if (!Array.isArray(list) || !list.find((item) => item.id === created.id)) {
    throw new Error("list response missing created item");
  }

  console.log("Deleting reputation entry...");
  run("curl", [
    "-s",
    "-o",
    "/tmp/reputation_delete.json",
    "-b",
    "/tmp/admin.cookies",
    "-X",
    "DELETE",
    `${baseUrl}/admin/api/reputation/${created.id}`,
  ]);

  console.log("PASS reputation_crud");
}

main();
