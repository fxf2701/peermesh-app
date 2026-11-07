#!/usr/bin/env node
/* eslint-disable no-console */

const fs = require("fs");
const path = require("path");

const docPath = path.join(__dirname, "..", "..", "docs", "relay_edge_waf_guidance.md");

function hasAll(content, tokens) {
  return tokens.every((token) => content.includes(token));
}

async function run() {
  let failed = 0;
  try {
    const content = fs.readFileSync(docPath, "utf8").toLowerCase();
    const required = ["nginx", "traefik", "limit_req", "rate limit"];
    if (hasAll(content, required)) {
      console.log("PASS waf_guidance_doc");
    } else {
      failed += 1;
      console.log("FAIL waf_guidance_doc");
      console.log(`  missing tokens in ${docPath}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL waf_guidance_doc");
    console.log(`  error: ${err.message || String(err)}`);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
