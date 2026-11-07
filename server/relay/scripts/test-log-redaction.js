#!/usr/bin/env node
/* eslint-disable no-console */

const { logEvent } = require("../src/logger");

function run() {
  let failed = 0;
  const originalLog = console.log;
  const entries = [];

  console.log = (line) => {
    entries.push(line);
  };

  try {
    logEvent("info", "redaction_test", {
      token: "secret-token",
      password: "super-secret",
      sig: "sig-value",
      body: { nonce: "nonce-value" },
      cursor: "cursor-value",
      gid: "gid-value",
      "x-relay-peer-token": "peer-token",
      nested: { secret: "nested-secret", keep: "ok" },
      keep: "visible",
    });
  } finally {
    console.log = originalLog;
  }

  const raw = entries[0];
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch (err) {
    failed += 1;
    console.log("FAIL log_redaction_json");
    console.log(`  error: ${err.message || String(err)}`);
  }

  if (payload) {
    const redacted = "[redacted]";
    const checks = [
      payload.token === redacted,
      payload.password === redacted,
      payload.sig === redacted,
      payload.body === redacted,
      payload.cursor === redacted,
      payload.gid === redacted,
      payload["x-relay-peer-token"] === redacted,
      payload.nested && payload.nested.secret === redacted,
      payload.keep === "visible",
      payload.nested && payload.nested.keep === "ok",
    ];

    if (checks.every(Boolean)) {
      console.log("PASS log_redaction");
    } else {
      failed += 1;
      console.log("FAIL log_redaction");
      console.log(`  payload: ${raw}`);
    }
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
