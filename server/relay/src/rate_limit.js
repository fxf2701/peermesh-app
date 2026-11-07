// Token bucket rate limiter with optional Redis backend.

function createRateLimiter({ limit, windowSeconds, maxEntries = 10000, redis, keyPrefix }) {
  if (!Number.isFinite(limit) || limit <= 0) {
    return async () => ({ ok: true });
  }
  if (!Number.isFinite(windowSeconds) || windowSeconds <= 0) {
    return async () => ({ ok: true });
  }

  if (redis) {
    return async function check(key) {
      const now = Date.now() / 1000;
      const windowIndex = Math.floor(now / windowSeconds);
      const redisKey = `${keyPrefix}:${key}:${windowIndex}`;
      const count = await redis.incr(redisKey);
      if (count === 1) {
        await redis.expire(redisKey, Math.ceil(windowSeconds) + 1);
      }
      if (count > limit) {
        const retryAfterSeconds =
          Math.ceil((windowIndex + 1) * windowSeconds - now) || 1;
        return { ok: false, retryAfterSeconds };
      }
      return { ok: true };
    };
  }

  const refillRate = limit / windowSeconds;
  const buckets = new Map();

  function pruneIfNeeded() {
    if (buckets.size <= maxEntries) return;
    const overflow = buckets.size - maxEntries;
    for (let i = 0; i < overflow; i += 1) {
      const firstKey = buckets.keys().next().value;
      if (!firstKey) break;
      buckets.delete(firstKey);
    }
  }

  return async function check(key) {
    const now = Date.now() / 1000;
    const entry = buckets.get(key);
    let tokens = limit;
    let lastRefill = now;

    if (entry) {
      const elapsed = Math.max(0, now - entry.lastRefill);
      tokens = Math.min(limit, entry.tokens + elapsed * refillRate);
      lastRefill = now;
    }

    if (tokens < 1) {
      const retryAfter = Math.ceil((1 - tokens) / refillRate);
      buckets.set(key, { tokens, lastRefill });
      pruneIfNeeded();
      return { ok: false, retryAfterSeconds: retryAfter };
    }

    buckets.set(key, { tokens: tokens - 1, lastRefill });
    pruneIfNeeded();
    return { ok: true };
  };
}

module.exports = { createRateLimiter };
