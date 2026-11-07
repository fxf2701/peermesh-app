// In-memory metrics collector with Prometheus rendering.

const DEFAULT_LATENCY_BUCKETS_MS = [10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000];
const DEFAULT_DB_WAIT_BUCKETS_MS = [1, 5, 10, 25, 50, 100, 250, 500, 1000];

function createMetrics() {
  const meta = new Map();
  const counters = new Map();
  const gauges = new Map();
  const histograms = new Map();
  const collectors = new Set();

  function defineMetric(name, type, help, buckets) {
    if (!meta.has(name)) {
      meta.set(name, { type, help, buckets });
    }
  }

  function labelKey(labels) {
    if (!labels) return "";
    const keys = Object.keys(labels).sort();
    if (keys.length === 0) return "";
    return keys.map((key) => `${key}=${labels[key]}`).join("|");
  }

  function escapeLabel(value) {
    return String(value).replace(/\\/g, "\\\\").replace(/"/g, '\\"').replace(/\n/g, "\\n");
  }

  function formatLabels(labels) {
    if (!labels) return "";
    const keys = Object.keys(labels).sort();
    if (keys.length === 0) return "";
    const rendered = keys.map((key) => `${key}="${escapeLabel(labels[key])}"`).join(",");
    return `{${rendered}}`;
  }

  function getSeries(store, name, labels) {
    const key = `${name}:${labelKey(labels)}`;
    if (!store.has(key)) {
      store.set(key, { name, labels: labels || null, value: 0 });
    }
    return store.get(key);
  }

  function getHistogram(name, labels) {
    const key = `${name}:${labelKey(labels)}`;
    if (!histograms.has(key)) {
      const definition = meta.get(name);
      const buckets = definition && definition.buckets ? [...definition.buckets] : [];
      buckets.sort((a, b) => a - b);
      histograms.set(key, {
        name,
        labels: labels || null,
        buckets,
        counts: buckets.map(() => 0),
        count: 0,
        sum: 0,
      });
    }
    return histograms.get(key);
  }

  function inc(name, value = 1, labels) {
    defineMetric(name, "counter", name);
    const series = getSeries(counters, name, labels);
    series.value += value;
  }

  function setGauge(name, value, labels) {
    defineMetric(name, "gauge", name);
    const series = getSeries(gauges, name, labels);
    series.value = value;
  }

  function observe(name, value, labels) {
    if (!Number.isFinite(value)) return;
    const series = getHistogram(name, labels);
    series.count += 1;
    series.sum += value;
    for (let i = 0; i < series.buckets.length; i += 1) {
      if (value <= series.buckets[i]) {
        series.counts[i] += 1;
        break;
      }
    }
  }

  function registerCollector(fn) {
    collectors.add(fn);
  }

  function collect() {
    for (const collector of collectors) {
      try {
        collector();
      } catch (err) {
        // Ignore collector errors to avoid breaking /metrics.
      }
    }
  }

  function getCounter(name) {
    const series = getSeries(counters, name, null);
    return series.value;
  }

  function renderPrometheus() {
    collect();
    const lines = [];

    for (const [name, info] of meta.entries()) {
      lines.push(`# HELP ${name} ${info.help}`);
      lines.push(`# TYPE ${name} ${info.type}`);

      if (info.type === "counter") {
        for (const series of counters.values()) {
          if (series.name !== name) continue;
          const labels = formatLabels(series.labels);
          lines.push(`${name}${labels} ${series.value}`);
        }
      } else if (info.type === "gauge") {
        for (const series of gauges.values()) {
          if (series.name !== name) continue;
          const labels = formatLabels(series.labels);
          lines.push(`${name}${labels} ${series.value}`);
        }
      } else if (info.type === "histogram") {
        for (const series of histograms.values()) {
          if (series.name !== name) continue;
          let cumulative = 0;
          for (let i = 0; i < series.buckets.length; i += 1) {
            cumulative += series.counts[i];
            const bucketLabels = { ...(series.labels || {}), le: String(series.buckets[i]) };
            lines.push(`${name}_bucket${formatLabels(bucketLabels)} ${cumulative}`);
          }
          const infLabels = { ...(series.labels || {}), le: "+Inf" };
          lines.push(`${name}_bucket${formatLabels(infLabels)} ${series.count}`);
          lines.push(`${name}_sum${formatLabels(series.labels)} ${series.sum}`);
          lines.push(`${name}_count${formatLabels(series.labels)} ${series.count}`);
        }
      }
    }

    return lines.join("\n") + "\n";
  }

  defineMetric("requests_total", "counter", "Total HTTP requests received.");
  defineMetric("errors_total", "counter", "Total HTTP error responses (status >= 400).");
  defineMetric("rate_limited_total", "counter", "Total requests rejected due to rate limits.");
  defineMetric(
    "rate_limited_spike_total",
    "counter",
    "Total rate-limit spike alerts triggered."
  );
  defineMetric("rate_limited_spike_active", "gauge", "Rate-limit spike alert active.");
  defineMetric("ingest_requests_total", "counter", "Total ingest requests accepted.");
  defineMetric("fetch_requests_total", "counter", "Total fetch requests accepted.");
  defineMetric("health_requests_total", "counter", "Total health endpoint requests.");
  defineMetric("ready_requests_total", "counter", "Total readiness endpoint requests.");
  defineMetric("http_responses_total", "counter", "HTTP responses by status code.");
  defineMetric(
    "http_request_duration_ms",
    "histogram",
    "HTTP request duration in milliseconds.",
    DEFAULT_LATENCY_BUCKETS_MS
  );
  defineMetric("db_pool_total", "gauge", "Total DB pool connections.");
  defineMetric("db_pool_in_use", "gauge", "In-use DB pool connections.");
  defineMetric("db_pool_idle", "gauge", "Idle DB pool connections.");
  defineMetric("db_pool_waiting", "gauge", "Waiting DB pool requests.");
  defineMetric(
    "db_pool_wait_ms",
    "histogram",
    "DB pool wait time in milliseconds.",
    DEFAULT_DB_WAIT_BUCKETS_MS
  );
  defineMetric("db_pool_wait_ms_last", "gauge", "Last DB pool wait time in milliseconds.");
  defineMetric("db_pool_wait_ms_avg", "gauge", "Average DB pool wait time in milliseconds.");
  defineMetric("federation_sync_success_total", "counter", "Federation sync successes.");
  defineMetric("federation_sync_fail_total", "counter", "Federation sync failures.");
  defineMetric("federation_backoff_total", "counter", "Federation sync backoff events.");
  defineMetric(
    "federation_backpressure_in_flight",
    "gauge",
    "Federation sync in-flight requests per peer."
  );
  defineMetric(
    "federation_backpressure_queue_size",
    "gauge",
    "Federation sync queued requests per peer."
  );
  defineMetric(
    "federation_backpressure_drops_total",
    "counter",
    "Federation sync requests dropped due to backpressure."
  );
  defineMetric(
    "federation_push_validation_failures_total",
    "counter",
    "Managed federation push validation failures."
  );
  defineMetric(
    "federation_reconciliation_total",
    "counter",
    "Managed federation reconciliation requests authorized."
  );
  defineMetric(
    "federation_reconciliation_events_total",
    "counter",
    "Events returned through managed federation reconciliation."
  );
  defineMetric(
    "federation_reconciliation_validation_failures_total",
    "counter",
    "Managed federation reconciliation validation failures."
  );
  defineMetric(
    "federation_session_limit_rejections_total",
    "counter",
    "Managed federation session limit rejections."
  );
  defineMetric("federation_peer_abuse_score", "gauge", "Federation peer abuse score by peer.");
  defineMetric("federation_peer_quarantined", "gauge", "Federation peer quarantine state by peer (1 quarantined, 0 active).");
  defineMetric(
    "federation_peer_quota_remaining",
    "gauge",
    "Federation peer remaining runtime quota by peer and quota type."
  );
  defineMetric("federation_cursor_lag_seconds", "gauge", "Federation cursor lag in seconds.");
  defineMetric(
    "federation_cursor_regressions_total",
    "counter",
    "Federation cursor regressions detected."
  );
  defineMetric("partitioning_enabled", "gauge", "Partitioned schema enabled for events table.");
  defineMetric("partition_partitions_total", "gauge", "Total partitions for events table.");
  defineMetric("partition_missing", "gauge", "Partitioned schema without partitions.");
  defineMetric("partition_check_failures_total", "counter", "Partition self-check failures.");
  defineMetric("health_db_check_ms", "histogram", "DB health check duration in milliseconds.", [
    5,
    10,
    25,
    50,
    100,
    250,
    500,
    1000,
    2000,
    5000,
  ]);
  defineMetric("health_db_check_timeouts_total", "counter", "DB health check timeouts.");
  defineMetric("health_db_check_failures_total", "counter", "DB health check failures.");
  defineMetric("cleanup_stuck_total", "counter", "Cleanup watchdog stuck events.");
  defineMetric("cleanup_last_duration_ms", "gauge", "Last cleanup duration in milliseconds.");
  defineMetric("cleanup_in_flight", "gauge", "Cleanup task currently running (1) or idle (0).");
  defineMetric("ingest_retry_queue_size", "gauge", "Current ingest retry queue size.");
  defineMetric("ingest_retry_queued_total", "counter", "Total ingests queued for retry.");
  defineMetric("ingest_retry_attempts_total", "counter", "Total ingest retry attempts.");
  defineMetric("ingest_retry_success_total", "counter", "Total ingest retries succeeded.");
  defineMetric("ingest_retry_fail_total", "counter", "Total ingest retry attempts failed.");
  defineMetric("ingest_retry_dropped_total", "counter", "Total ingests dropped from retry queue.");
  defineMetric("db_cooldown_active", "gauge", "DB cooldown active (1) or inactive (0).");
  defineMetric("db_cooldown_activations_total", "counter", "DB cooldown activations.");
  defineMetric("db_cooldown_deactivations_total", "counter", "DB cooldown deactivations.");

  return {
    inc,
    setGauge,
    observe,
    registerCollector,
    renderPrometheus,
    getCounter,
  };
}

module.exports = { createMetrics };
