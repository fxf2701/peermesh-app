// Adaptive cooldown controller for DB saturation.

const { logEvent } = require("./logger");

function createDbCooldown({ getStats, metrics, config, onLog = logEvent, now = Date.now }) {
  const enabled = config.dbCooldownEnabled;
  const waitingThreshold = config.dbCooldownWaitingThreshold;
  const waitMsThreshold = config.dbCooldownWaitMsThreshold;
  const minActiveMs = config.dbCooldownMinSeconds * 1000;
  const stableWindows = config.dbCooldownStableWindows;
  const checkIntervalMs = config.dbCooldownCheckIntervalMs;

  let active = false;
  let activatedAt = 0;
  let stableCount = 0;

  function setActive(nextActive, details) {
    if (active === nextActive) return;
    active = nextActive;
    if (metrics) metrics.setGauge("db_cooldown_active", active ? 1 : 0);
    if (active) {
      activatedAt = now();
      stableCount = 0;
      if (metrics) metrics.inc("db_cooldown_activations_total", 1);
      onLog("warn", "db_cooldown_on", details);
    } else {
      if (metrics) metrics.inc("db_cooldown_deactivations_total", 1);
      onLog("info", "db_cooldown_off", details);
    }
  }

  function evaluate(stats) {
    if (!enabled || !stats) {
      if (metrics) metrics.setGauge("db_cooldown_active", 0);
      return active;
    }

    const waitMsAvg =
      stats.waitCount && stats.waitCount > 0 ? stats.waitSumMs / stats.waitCount : 0;
    const waiting = stats.waitingCount || 0;
    const saturated = waiting >= waitingThreshold || waitMsAvg >= waitMsThreshold;

    if (!active && saturated) {
      setActive(true, {
        waiting,
        wait_ms_avg: Number(waitMsAvg.toFixed(2)),
        wait_threshold: waitMsThreshold,
        waiting_threshold: waitingThreshold,
      });
      return active;
    }

    if (active) {
      if (saturated) {
        stableCount = 0;
        return active;
      }
      const elapsed = now() - activatedAt;
      if (elapsed < minActiveMs) {
        return active;
      }
      stableCount += 1;
      if (stableCount >= stableWindows) {
        setActive(false, {
          waiting,
          wait_ms_avg: Number(waitMsAvg.toFixed(2)),
          stable_windows: stableWindows,
        });
      }
    }

    return active;
  }

  function retryAfterSeconds() {
    if (!active) return 0;
    const elapsed = now() - activatedAt;
    const remaining = Math.max(0, minActiveMs - elapsed);
    const fallback = Math.max(1000, checkIntervalMs);
    return Math.ceil(Math.max(remaining, fallback) / 1000);
  }

  let timer = null;
  if (enabled && typeof getStats === "function" && checkIntervalMs > 0) {
    timer = setInterval(() => evaluate(getStats()), checkIntervalMs);
  }

  if (metrics) metrics.setGauge("db_cooldown_active", 0);

  return {
    isActive: () => active,
    retryAfterSeconds,
    evaluate,
    shutdown: () => {
      if (timer) clearInterval(timer);
      timer = null;
    },
  };
}

module.exports = { createDbCooldown };
