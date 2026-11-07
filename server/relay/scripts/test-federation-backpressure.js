#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const { createFederationSync } = require("../src/federation");

const testPeerPort = Number(process.env.FEDERATION_TEST_PEER_PORT || "19092");
const testPeerUrl = `http://127.0.0.1:${testPeerPort}`;
const testGids = ["gid-a", "gid-b", "gid-c"];

const config = {
  federationPeers: [testPeerUrl],
  federationSyncGids: testGids,
  federationSyncIntervalSeconds: 1,
  federationSyncMaxBackoffSeconds: 1,
  federationSyncJitterSeconds: 0,
  federationSyncLimit: 1,
  federationSyncMaxInFlightPerPeer: 1,
  federationSyncMaxQueuePerPeer: 1,
  federationSelfId: "",
  federationSelfUrl: "",
  federationMtlsEnabled: false,
  federationAllowCursorRegression: true,
};

const storage = {
  getFederationCursor: async () => null,
  ingestEvents: async () => {},
  setFederationCursor: async () => true,
};

const metrics = {
  dropCount: 0,
  maxInFlight: 0,
  maxQueue: 0,
  inc(name, value) {
    if (name === "federation_backpressure_drops_total") {
      this.dropCount += value;
    }
  },
  setGauge(name, value) {
    if (name === "federation_backpressure_in_flight") {
      this.maxInFlight = Math.max(this.maxInFlight, value);
    }
    if (name === "federation_backpressure_queue_size") {
      this.maxQueue = Math.max(this.maxQueue, value);
    }
  },
};

async function run() {
  let failed = 0;
  let inFlight = 0;
  let maxConcurrent = 0;
  let requestCount = 0;

  const peerServer = http.createServer((req, res) => {
    if (!req.url.startsWith("/v1/events:fetch")) {
      res.writeHead(404);
      res.end("not_found");
      return;
    }
    inFlight += 1;
    maxConcurrent = Math.max(maxConcurrent, inFlight);
    requestCount += 1;
    setTimeout(() => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ events: [], next_cursor: null }));
      inFlight -= 1;
    }, 500);
  });

  await new Promise((resolve) => peerServer.listen(testPeerPort, resolve));

  const sync = createFederationSync({ config, storage, metrics });
  sync.start();

  await new Promise((resolve) => setTimeout(resolve, 2500));
  sync.stop();
  peerServer.close();

  const backpressureOk = maxConcurrent <= 1 && metrics.maxInFlight <= 1 && metrics.maxQueue <= 1;
  if (backpressureOk) {
    console.log("PASS federation_backpressure_limits");
  } else {
    failed += 1;
    console.log("FAIL federation_backpressure_limits");
    console.log(`  max_concurrent: ${maxConcurrent}`);
    console.log(`  max_in_flight_metric: ${metrics.maxInFlight}`);
    console.log(`  max_queue_metric: ${metrics.maxQueue}`);
  }

  if (metrics.dropCount >= 1) {
    console.log("PASS federation_backpressure_drop");
  } else {
    failed += 1;
    console.log("FAIL federation_backpressure_drop");
    console.log(`  drop_count: ${metrics.dropCount}`);
  }

  if (requestCount >= 2) {
    console.log("PASS federation_backpressure_requests");
  } else {
    failed += 1;
    console.log("FAIL federation_backpressure_requests");
    console.log(`  requests: ${requestCount}`);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
