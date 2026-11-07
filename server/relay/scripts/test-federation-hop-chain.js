#!/usr/bin/env node
/* eslint-disable no-console */

const { validateHopChain } = require("../src/federation_hops");

function run() {
  let failed = 0;
  console.log("federation_hop_chain_test_start");

  const repeat = validateHopChain("peer-a,peer-b,peer-a", "peer-x");
  if (!repeat.ok && repeat.reason === "hop_repeat") {
    console.log("PASS federation_hop_chain_repeat");
  } else {
    failed += 1;
    console.log("FAIL federation_hop_chain_repeat");
  }

  const selfLoop = validateHopChain("peer-a,peer-b", "peer-b");
  if (!selfLoop.ok && selfLoop.reason === "self_in_chain") {
    console.log("PASS federation_hop_chain_self");
  } else {
    failed += 1;
    console.log("FAIL federation_hop_chain_self");
  }

  const ok = validateHopChain("peer-a,peer-b,peer-c", "peer-x");
  if (ok.ok) {
    console.log("PASS federation_hop_chain_ok");
  } else {
    failed += 1;
    console.log("FAIL federation_hop_chain_ok");
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
