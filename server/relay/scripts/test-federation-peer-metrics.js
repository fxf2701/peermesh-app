#!/usr/bin/env node
/* eslint-disable no-console */

const { createFederationSync } = require("../src/federation");
const { createMetrics } = require("../src/metrics");

const testPeerPort = Number(process.env.FEDERATION_TEST_PEER_PORT || "19093");
const testPeerUrl = `http://127.0.0.1:${testPeerPort}`;

function metricValue(body, prefix) {
  const line = body.split("\n").find((entry) => entry.startsWith(prefix));
  if (!line) return null;
  const parts = line.trim().split(/\s+/);
  const value = Number(parts[parts.length - 1]);
  return Number.isFinite(value) ? value : null;
}

async function runFailureMetricScenario() {
  const config = {
    federationPeers: [testPeerUrl],
    federationSyncGids: ["gid-a"],
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
    registryFederationSessionsEnabled: false,
  };
  const storage = {
    getFederationCursor: async () => null,
    ingestEvents: async () => {},
    setFederationCursor: async () => true,
  };
  const metrics = createMetrics();
  metrics.registerCollector(() => {});

  const sync = createFederationSync({ config, storage, metrics });
  sync.start();
  await new Promise((resolve) => setTimeout(resolve, 1500));
  sync.stop();

  const body = metrics.renderPrometheus();
  const abuseScore = metricValue(
    body,
    'federation_peer_abuse_score{peer="127.0.0.1:19093"}'
  );
  const queueQuota = metricValue(
    body,
    'federation_peer_quota_remaining{peer="127.0.0.1:19093",quota="queue"}'
  );
  const inflightQuota = metricValue(
    body,
    'federation_peer_quota_remaining{peer="127.0.0.1:19093",quota="in_flight"}'
  );

  if (abuseScore !== null && abuseScore >= 1) {
    console.log("PASS federation_peer_metrics_failure_abuse_score");
  } else {
    console.log("FAIL federation_peer_metrics_failure_abuse_score");
    console.log(`  abuse_score: ${abuseScore}`);
    process.exit(1);
  }

  if (queueQuota !== null && inflightQuota !== null) {
    console.log("PASS federation_peer_metrics_failure_quota");
  } else {
    console.log("FAIL federation_peer_metrics_failure_quota");
    console.log(`  queue_quota: ${queueQuota}`);
    console.log(`  inflight_quota: ${inflightQuota}`);
    process.exit(1);
  }
}

async function runQuarantineMetricScenario() {
  const config = {
    federationPeers: [],
    federationSyncGids: ["gid-a"],
    federationSyncIntervalSeconds: 1,
    federationSyncMaxBackoffSeconds: 1,
    federationSyncJitterSeconds: 0,
    federationSyncLimit: 1,
    federationSyncMaxInFlightPerPeer: 1,
    federationSyncMaxQueuePerPeer: 1,
    federationSelfId: "relay-a",
    federationSelfUrl: "http://relay-a:8080",
    federationMtlsEnabled: false,
    registryFederationSessionsEnabled: false,
  };
  const storage = {
    async getPeerMappingByUrl(peerUrl) {
      if (peerUrl !== testPeerUrl) return null;
      return {
        peer_url: testPeerUrl,
        enabled: true,
        auto_sync: true,
        peer_state: "quarantined",
        quarantine_reason: "manual review",
        quarantined_until: new Date(Date.now() + 60_000).toISOString(),
      };
    },
    async listPeerMappings() {
      return [];
    },
  };
  const metrics = createMetrics();
  metrics.registerCollector(() => {});
  const sync = createFederationSync({ config, storage, metrics });
  const ok = await sync.triggerResync(testPeerUrl);
  sync.stop();
  const body = metrics.renderPrometheus();
  const quarantined = metricValue(
    body,
    'federation_peer_quarantined{peer="127.0.0.1:19093"}'
  );
  const abuseScore = metricValue(
    body,
    'federation_peer_abuse_score{peer="127.0.0.1:19093"}'
  );

  if (ok !== false) {
    console.log("FAIL federation_peer_metrics_quarantine_skip");
    process.exit(1);
  }
  console.log("PASS federation_peer_metrics_quarantine_skip");

  if (quarantined === 1 && abuseScore !== null && abuseScore >= 1) {
    console.log("PASS federation_peer_metrics_quarantine_gauge");
  } else {
    console.log("FAIL federation_peer_metrics_quarantine_gauge");
    console.log(`  quarantined: ${quarantined}`);
    console.log(`  abuse_score: ${abuseScore}`);
    process.exit(1);
  }
}

async function main() {
  await runFailureMetricScenario();
  await runQuarantineMetricScenario();
}

main().catch((err) => {
  console.error("FAIL federation_peer_metrics", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
