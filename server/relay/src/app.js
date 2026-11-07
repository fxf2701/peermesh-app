// Minimal HTTP app wrapper for the relay server.

const { createRouter } = require("./routes");
const { createRedisClient } = require("./redis");
const { createStorage } = require("./storage");
const { createMetrics } = require("./metrics");
const { createDbCooldown } = require("./db_cooldown");
const { createRateLimitSpikeDetector } = require("./rate_limit_spike");
const { createFederationSync } = require("./federation");
const { createFederationAdminState } = require("./federation_admin_state");
const { createRegistryHeartbeatClient } = require("./registry_heartbeat_client");
const { createRegistrySessionClient } = require("./registry_session_client");
const { createFederationPush } = require("./federation_push");
const { createClientRelaySessionManager } = require("./client_relay_sessions");
const { logEvent } = require("./logger");

function createErrorBudgetReporter({ metrics, config, onLog = logEvent }) {
  let timer = null;
  if (config.observabilitySummaryIntervalSeconds > 0) {
    const intervalMs = config.observabilitySummaryIntervalSeconds * 1000;
    let lastRequests = metrics.getCounter("requests_total");
    let lastErrors = metrics.getCounter("errors_total");

    timer = setInterval(() => {
      const currentRequests = metrics.getCounter("requests_total");
      const currentErrors = metrics.getCounter("errors_total");
      const windowRequests = currentRequests - lastRequests;
      const windowErrors = currentErrors - lastErrors;
      const errorRate = windowRequests > 0 ? windowErrors / windowRequests : 0;
      const target = config.observabilitySloTarget;
      const budgetRemaining =
        target < 1 ? Math.max(0, (1 - errorRate) / (1 - target)) : 0;

      onLog("info", "error_budget_summary", {
        window_seconds: config.observabilitySummaryIntervalSeconds,
        requests: windowRequests,
        errors: windowErrors,
        error_rate: Number(errorRate.toFixed(4)),
        slo_target: target,
        budget_remaining_ratio: Number(budgetRemaining.toFixed(4)),
      });

      lastRequests = currentRequests;
      lastErrors = currentErrors;
    }, intervalMs);
  }

  function stop() {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  return { stop };
}

function createApp({ config }) {
  const metrics = createMetrics();
  if (config.allowUnsignedDevRequests) {
    logEvent("warn", "unsigned_dev_requests_enabled", {
      message: "Relay app request signatures are disabled by explicit dev/test override.",
    });
  }
  const redis = createRedisClient({ url: config.redisUrl });
  const storage = createStorage({
    config: {
      ...config,
      onCleanupStuck: () => metrics.inc("cleanup_stuck_total"),
    },
    redis,
  });
  const dbCooldown = createDbCooldown({
    getStats: () => (storage.getPoolStats ? storage.getPoolStats() : null),
    metrics,
    config,
  });
  const registrySessions = createRegistrySessionClient({
    config,
    onLog: logEvent,
  });
  const federationState = createFederationAdminState({ peers: config.federationPeers });
  const federation = createFederationSync({
    config,
    storage,
    metrics,
    cooldown: dbCooldown,
    adminState: federationState,
    registrySessions,
  });
  const registryHeartbeat = createRegistryHeartbeatClient({
    config,
    onLog: logEvent,
  });
  const federationPush = createFederationPush({
    config,
    registrySessions,
    metrics,
    onLog: logEvent,
  });
  const clientRelaySessions = createClientRelaySessionManager({
    config,
    onLog: logEvent,
  });
  const router = createRouter({
    config,
    storage,
    redis,
    metrics,
    cooldown: dbCooldown,
    federationState,
    federationSync: federation,
    registrySessions,
    federationPush,
    clientRelaySessions,
  });
  const rateLimitSpike = createRateLimitSpikeDetector({ metrics, config });

  metrics.registerCollector(() => {
    if (!storage.getPoolStats) return;
    const stats = storage.getPoolStats();
    if (!stats) return;
    metrics.setGauge("db_pool_total", stats.totalCount);
    metrics.setGauge("db_pool_in_use", Math.max(0, stats.totalCount - stats.idleCount));
    metrics.setGauge("db_pool_idle", stats.idleCount);
    metrics.setGauge("db_pool_waiting", stats.waitingCount);
    if (stats.waitCount > 0) {
      metrics.setGauge("db_pool_wait_ms_avg", stats.waitSumMs / stats.waitCount);
    }
    metrics.setGauge("db_pool_wait_ms_last", stats.lastWaitMs);
    if (storage.drainPoolWaitSamples) {
      const samples = storage.drainPoolWaitSamples();
      for (const sample of samples) {
        metrics.observe("db_pool_wait_ms", sample);
      }
    }

    if (storage.getCleanupStats) {
      const stats = storage.getCleanupStats();
      metrics.setGauge("cleanup_last_duration_ms", stats.lastDurationMs || 0);
      metrics.setGauge("cleanup_in_flight", stats.inFlight ? 1 : 0);
    }
  });

  const errorBudgetReporter = createErrorBudgetReporter({ metrics, config });

  let partitionCheckTimer = null;
  if (config.partitionCheckIntervalSeconds > 0 && storage.checkPartitionPresence) {
    const intervalMs = config.partitionCheckIntervalSeconds * 1000;
    const runPartitionCheck = async () => {
      try {
        const result = await storage.checkPartitionPresence();
        metrics.setGauge("partitioning_enabled", result.partitioned ? 1 : 0);
        metrics.setGauge("partition_partitions_total", result.partitions);
        const missing = result.partitioned && result.partitions === 0;
        metrics.setGauge("partition_missing", missing ? 1 : 0);
        if (missing) {
          logEvent("warn", "partition_missing", { partitions: result.partitions });
        }
      } catch (err) {
        metrics.inc("partition_check_failures_total");
        logEvent("warn", "partition_check_failed", { error: err });
      }
    };
    runPartitionCheck();
    partitionCheckTimer = setInterval(runPartitionCheck, intervalMs);
  }

  let partitionAutoCreateTimer = null;
  if (
    config.partitionAutoCreateEnabled &&
    storage.ensurePartitions &&
    config.partitionAutoCreateIntervalSeconds > 0
  ) {
    const intervalMs = config.partitionAutoCreateIntervalSeconds * 1000;
    const runAutoCreate = async () => {
      try {
        const result = await storage.ensurePartitions({
          monthsAhead: config.partitionAutoCreateMonthsAhead,
          backfillMonths: config.partitionAutoCreateBackfillMonths,
        });
        if (!result.partitioned) {
          logEvent("info", "partition_auto_create_skipped", { reason: "not_partitioned" });
          return;
        }
        if (result.created.length > 0) {
          logEvent("info", "partition_auto_create", {
            created: result.created,
            checked: result.checked,
          });
        }
      } catch (err) {
        logEvent("warn", "partition_auto_create_failed", { error: err });
      }
    };
    runAutoCreate();
    partitionAutoCreateTimer = setInterval(runAutoCreate, intervalMs);
  }

  federation.start();
  registryHeartbeat.start();
  registrySessions.start();
  federationPush.start();

  async function startup() {
    if (!config.startupSchemaValidation || !storage.validateSchema) return;
    try {
      await storage.validateSchema();
      logEvent("info", "startup_schema_validation_ok");
    } catch (err) {
      logEvent("error", "startup_schema_validation_failed", {
        error: err,
        missing_tables: err && err.missingTables ? err.missingTables : undefined,
      });
      throw err;
    }
  }

  function handleRequest(req, res) {
    router.route(req, res);
  }

  return {
    handleRequest,
    startup,
    shutdown: async () => {
      federation.stop();
      federationPush.stop();
      clientRelaySessions.shutdown();
      registryHeartbeat.stop();
      await registrySessions.revoke("relay_shutdown");
      registrySessions.stop();
      errorBudgetReporter.stop();
      if (partitionCheckTimer) {
        clearInterval(partitionCheckTimer);
        partitionCheckTimer = null;
      }
      if (partitionAutoCreateTimer) {
        clearInterval(partitionAutoCreateTimer);
        partitionAutoCreateTimer = null;
      }
      if (dbCooldown) {
        dbCooldown.shutdown();
      }
      if (rateLimitSpike) {
        rateLimitSpike.shutdown();
      }
      if (router.shutdown) {
        await router.shutdown();
      }
      await storage.close();
      if (redis) {
        await redis.quit();
      }
    },
  };
}

module.exports = { createApp, createErrorBudgetReporter };
