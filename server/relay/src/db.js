// Postgres connection pool.

const { Pool } = require("pg");
const { logEvent } = require("./logger");

function createBreaker({
  enabled,
  failureThreshold,
  baseBackoffMs,
  maxBackoffMs,
}) {
  const breaker = {
    state: "closed",
    failureCount: 0,
    backoffMs: baseBackoffMs,
    openUntil: 0,
  };

  function logState(level, msg, meta) {
    logEvent(level, msg, meta);
  }

  function canAttempt() {
    if (!enabled) return true;
    if (breaker.state === "open") {
      if (Date.now() >= breaker.openUntil) {
        breaker.state = "half_open";
        logState("info", "db_circuit_half_open");
        return true;
      }
      return false;
    }
    return true;
  }

  function recordSuccess() {
    if (!enabled) return;
    breaker.failureCount = 0;
    breaker.backoffMs = baseBackoffMs;
    if (breaker.state !== "closed") {
      breaker.state = "closed";
      logState("info", "db_circuit_closed");
    }
  }

  function recordFailure(err) {
    if (!enabled) return;
    breaker.failureCount += 1;
    if (breaker.failureCount >= failureThreshold) {
      breaker.state = "open";
      breaker.openUntil = Date.now() + breaker.backoffMs;
      logState("warn", "db_circuit_open", {
        reason: err && err.message ? err.message : String(err),
        failures: breaker.failureCount,
        backoff_ms: breaker.backoffMs,
      });
      breaker.backoffMs = Math.min(maxBackoffMs, breaker.backoffMs * 2);
    }
  }

  function openError() {
    const err = new Error("db circuit open");
    err.code = "DB_CIRCUIT_OPEN";
    err.retryAfterMs = Math.max(0, breaker.openUntil - Date.now());
    return err;
  }

  return {
    canAttempt,
    recordSuccess,
    recordFailure,
    openError,
  };
}

function createPool({
  databaseUrl,
  poolMax,
  idleTimeoutMs,
  connTimeoutMs,
  circuitBreakerEnabled = true,
  circuitBreakerFailureThreshold = 3,
  circuitBreakerBaseBackoffMs = 500,
  circuitBreakerMaxBackoffMs = 5000,
}) {
  const pool = new Pool({
    connectionString: databaseUrl,
    max: poolMax,
    idleTimeoutMillis: idleTimeoutMs,
    connectionTimeoutMillis: connTimeoutMs,
  });

  let waitCount = 0;
  let waitSumMs = 0;
  let lastWaitMs = 0;
  const waitSamples = [];
  const maxWaitSamples = 1000;

  const breaker = createBreaker({
    enabled: circuitBreakerEnabled,
    failureThreshold: circuitBreakerFailureThreshold,
    baseBackoffMs: circuitBreakerBaseBackoffMs,
    maxBackoffMs: circuitBreakerMaxBackoffMs,
  });

  function wrapClient(client) {
    if (client.__relayWrapped) return client;
    const originalQuery = client.query.bind(client);
    client.query = async (...args) => {
      try {
        const result = await originalQuery(...args);
        breaker.recordSuccess();
        return result;
      } catch (err) {
        breaker.recordFailure(err);
        throw err;
      }
    };
    client.__relayWrapped = true;
    return client;
  }

  const originalConnect = pool.connect.bind(pool);
  pool.connect = (...args) => {
    const callback = args.length > 0 && typeof args[args.length - 1] === "function"
      ? args[args.length - 1]
      : null;
    if (!breaker.canAttempt()) {
      const err = breaker.openError();
      if (callback) {
        return process.nextTick(callback, err);
      }
      return Promise.reject(err);
    }

    const start = Date.now();
    const recordWait = () => {
      const waitedMs = Date.now() - start;
      lastWaitMs = waitedMs;
      waitCount += 1;
      waitSumMs += waitedMs;
      waitSamples.push(waitedMs);
      if (waitSamples.length > maxWaitSamples) {
        waitSamples.shift();
      }
    };

    if (callback) {
      return originalConnect((err, client, release) => {
        if (err) {
          breaker.recordFailure(err);
          return callback(err);
        }
        wrapClient(client);
        recordWait();
        return callback(null, client, release);
      });
    }

    return originalConnect()
      .then((client) => {
        wrapClient(client);
        recordWait();
        return client;
      })
      .catch((err) => {
        breaker.recordFailure(err);
        throw err;
      });
  };

  pool.getStats = () => ({
    totalCount: pool.totalCount,
    idleCount: pool.idleCount,
    waitingCount: pool.waitingCount,
    waitCount,
    waitSumMs,
    lastWaitMs,
  });

  pool.drainWaitSamples = () => {
    if (waitSamples.length === 0) return [];
    return waitSamples.splice(0, waitSamples.length);
  };

  pool.on("error", (err) => {
    logEvent("warn", "pg_pool_error", { error: err });
  });

  return pool;
}

module.exports = { createPool };
