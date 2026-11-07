function positiveNumber(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) return null;
  return parsed;
}

function normalizeLimits(raw) {
  const limits = raw && typeof raw === "object" ? raw : {};
  return {
    maxEventsPerMinute: positiveNumber(limits.max_events_per_minute),
    maxBytesPerMinute: positiveNumber(limits.max_bytes_per_minute),
    maxInFlight: positiveNumber(limits.max_in_flight),
  };
}

function createFederationSessionLimitManager({ windowSeconds = 60 } = {}) {
  const states = new Map();

  function bucketStart(nowSeconds) {
    const size = Math.max(1, Number(windowSeconds) || 60);
    return nowSeconds - (nowSeconds % size);
  }

  function getState(sessionId, nowSeconds) {
    const bucket = bucketStart(nowSeconds);
    const existing = states.get(sessionId);
    if (existing && existing.bucket_start === bucket) return existing;
    const next = existing
      ? { ...existing, bucket_start: bucket, events_used: 0, bytes_used: 0 }
      : { bucket_start: bucket, events_used: 0, bytes_used: 0, in_flight: 0 };
    states.set(sessionId, next);
    return next;
  }

  function retryAfterSeconds(nowSeconds) {
    const size = Math.max(1, Number(windowSeconds) || 60);
    const elapsed = nowSeconds % size;
    return Math.max(1, size - elapsed);
  }

  function begin({ sessionId, limits, nowSeconds }) {
    const normalized = normalizeLimits(limits);
    const state = getState(sessionId, nowSeconds);
    if (Number.isFinite(normalized.maxInFlight) && state.in_flight >= normalized.maxInFlight) {
      return {
        ok: false,
        code: "session_in_flight_limited",
        retryAfterSeconds: 1,
      };
    }
    state.in_flight += 1;
    return {
      ok: true,
      lease: {
        sessionId,
        nowSeconds,
        limits: normalized,
        released: false,
      },
    };
  }

  function consume(lease, { events = 0, bytes = 0 }) {
    if (!lease || lease.released) {
      return { ok: false, code: "session_limit_lease_invalid", retryAfterSeconds: 1 };
    }
    const state = getState(lease.sessionId, lease.nowSeconds);
    if (
      Number.isFinite(lease.limits.maxEventsPerMinute)
      && state.events_used + events > lease.limits.maxEventsPerMinute
    ) {
      return {
        ok: false,
        code: "session_event_rate_limited",
        retryAfterSeconds: retryAfterSeconds(lease.nowSeconds),
      };
    }
    if (
      Number.isFinite(lease.limits.maxBytesPerMinute)
      && state.bytes_used + bytes > lease.limits.maxBytesPerMinute
    ) {
      return {
        ok: false,
        code: "session_byte_rate_limited",
        retryAfterSeconds: retryAfterSeconds(lease.nowSeconds),
      };
    }
    state.events_used += Math.max(0, Number(events) || 0);
    state.bytes_used += Math.max(0, Number(bytes) || 0);
    return { ok: true };
  }

  function finish(lease) {
    if (!lease || lease.released) return;
    const state = states.get(lease.sessionId);
    if (state) {
      state.in_flight = Math.max(0, state.in_flight - 1);
    }
    lease.released = true;
  }

  return { begin, consume, finish, normalizeLimits };
}

module.exports = { createFederationSessionLimitManager, normalizeLimits };
