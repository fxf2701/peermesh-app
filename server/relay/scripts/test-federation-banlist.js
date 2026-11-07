#!/usr/bin/env node
/* eslint-disable no-console */

const { isPeerBanned } = require("../src/peer_policy");

function run() {
  let failed = 0;
  console.log("federation_banlist_test_start");

  const banlist = ["127.0.0.1", "peer-banned"];
  const bannedByIp = isPeerBanned("127.0.0.1", "", banlist);
  if (bannedByIp) {
    console.log("PASS federation_peer_banned_ip");
  } else {
    failed += 1;
    console.log("FAIL federation_peer_banned_ip");
  }

  const bannedById = isPeerBanned("10.0.0.1", "peer-banned", banlist);
  if (bannedById) {
    console.log("PASS federation_peer_banned_id");
  } else {
    failed += 1;
    console.log("FAIL federation_peer_banned_id");
  }

  const allowed = isPeerBanned("10.0.0.1", "peer-ok", banlist);
  if (!allowed) {
    console.log("PASS federation_peer_allowed");
  } else {
    failed += 1;
    console.log("FAIL federation_peer_allowed");
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
