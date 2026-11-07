#!/usr/bin/env node
/* eslint-disable no-console */

const fs = require("fs");
const path = require("path");

const repoRoot = path.resolve(__dirname, "..", "..", "..");
const unitPath = path.join(repoRoot, "server", "relay", "config", "relay.service");
const logrotatePath = path.join(repoRoot, "server", "relay", "config", "relay.logrotate");

function run() {
  let failed = 0;
  console.log("systemd_logrotate_test_start");

  if (!fs.existsSync(unitPath)) {
    console.log("FAIL systemd_unit_missing");
    console.log(`  missing: ${unitPath}`);
    process.exit(1);
  }

  if (!fs.existsSync(logrotatePath)) {
    console.log("FAIL logrotate_missing");
    console.log(`  missing: ${logrotatePath}`);
    process.exit(1);
  }

  const unit = fs.readFileSync(unitPath, "utf8");
  const logrotate = fs.readFileSync(logrotatePath, "utf8");

  const unitOk =
    unit.includes("ExecStart=") &&
    unit.includes("EnvironmentFile=") &&
    unit.includes("LogsDirectory=disaster-app");

  if (unitOk) {
    console.log("PASS systemd_unit_valid");
  } else {
    failed += 1;
    console.log("FAIL systemd_unit_valid");
  }

  const logrotateOk = logrotate.includes("/var/log/disaster-app/relay.log");
  if (logrotateOk) {
    console.log("PASS logrotate_valid");
  } else {
    failed += 1;
    console.log("FAIL logrotate_valid");
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
