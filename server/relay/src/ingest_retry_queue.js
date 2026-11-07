// Ingest retry queue with bounded backoff.

const { logEvent } = require("./logger");

function createIngestRetryQueue({ storage, metrics, config, onLog = logEvent }) {
  if (!config.ingestRetryEnabled) return null;

  const queue = [];
  let timer = null;
  let processing = false;

  const maxEntries = config.ingestRetryMaxEntries;
  const maxAttempts = config.ingestRetryMaxAttempts;
  const baseDelayMs = config.ingestRetryBaseDelayMs;
  const maxDelayMs = config.ingestRetryMaxDelayMs;
  const intervalMs = config.ingestRetryIntervalMs;

  function backoffDelay(attempts) {
    const delay = baseDelayMs * Math.pow(2, Math.max(0, attempts - 1));
    return Math.min(maxDelayMs, delay);
  }

  function updateGauge() {
    if (metrics) metrics.setGauge("ingest_retry_queue_size", queue.length);
  }

  function enqueue(events, reason) {
    if (!Array.isArray(events) || events.length === 0) return false;
    if (queue.length >= maxEntries) {
      if (metrics) metrics.inc("ingest_retry_dropped_total", events.length);
      onLog("warn", "ingest_retry_queue_full", {
        reason,
        queue_size: queue.length,
        dropped_events: events.length,
      });
      return false;
    }

    queue.push({
      events,
      attempts: 0,
      nextAttemptAt: Date.now() + baseDelayMs,
      reason,
    });
    if (metrics) metrics.inc("ingest_retry_queued_total", events.length);
    updateGauge();
    return true;
  }

  async function processItem(item) {
    if (!item) return;
    processing = true;
    if (metrics) metrics.inc("ingest_retry_attempts_total", 1);
    try {
      await storage.ingestEvents(item.events);
      if (metrics) metrics.inc("ingest_retry_success_total", item.events.length);
      onLog("info", "ingest_retry_success", {
        attempts: item.attempts + 1,
        events: item.events.length,
      });
    } catch (err) {
      item.attempts += 1;
      if (metrics) metrics.inc("ingest_retry_fail_total", 1);
      if (item.attempts >= maxAttempts) {
        if (metrics) metrics.inc("ingest_retry_dropped_total", item.events.length);
        onLog("warn", "ingest_retry_dropped", {
          attempts: item.attempts,
          events: item.events.length,
          error: err,
        });
      } else {
        item.nextAttemptAt = Date.now() + backoffDelay(item.attempts);
        queue.push(item);
      }
    } finally {
      processing = false;
      updateGauge();
    }
  }

  function tick() {
    if (processing) return;
    if (queue.length === 0) return;
    const now = Date.now();
    const index = queue.findIndex((item) => item.nextAttemptAt <= now);
    if (index === -1) return;
    const [item] = queue.splice(index, 1);
    updateGauge();
    processItem(item);
  }

  timer = setInterval(tick, intervalMs);
  updateGauge();

  return {
    enqueue,
    shutdown: () => {
      if (timer) clearInterval(timer);
      timer = null;
    },
    size: () => queue.length,
  };
}

module.exports = { createIngestRetryQueue };
