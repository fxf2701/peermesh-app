// Alert on rate-limit spikes.

const { logEvent } = require("./logger");

function createRateLimitSpikeDetector({ metrics, config, onLog = logEvent }) {
  if (!metrics) return null;
  if (config.rateLimitSpikeWindowSeconds <= 0) return null;

  const windowSeconds = config.rateLimitSpikeWindowSeconds;
  const threshold = config.rateLimitSpikeThreshold;
  const cooldownSeconds = config.rateLimitSpikeCooldownSeconds;
  let lastCount = metrics.getCounter("rate_limited_total");
  let cooldownUntil = 0;
  let active = false;

  function check() {
    const current = metrics.getCounter("rate_limited_total");
    const delta = Math.max(0, current - lastCount);
    lastCount = current;

    const now = Date.now();
    if (now < cooldownUntil) {
      return;
    }

    if (delta >= threshold) {
      cooldownUntil = now + cooldownSeconds * 1000;
      active = true;
      metrics.setGauge("rate_limited_spike_active", 1);
      metrics.inc("rate_limited_spike_total", 1);
      onLog("warn", "rate_limit_spike", {
        window_seconds: windowSeconds,
        threshold,
        rate_limited: delta,
        cooldown_seconds: cooldownSeconds,
      });
    } else if (active) {
      active = false;
      metrics.setGauge("rate_limited_spike_active", 0);
    }
  }

  const timer = setInterval(check, windowSeconds * 1000);

  return {
    shutdown: () => {
      clearInterval(timer);
    },
  };
}

module.exports = { createRateLimitSpikeDetector };
