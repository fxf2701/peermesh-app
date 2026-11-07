#!/usr/bin/env node

const { computeSignature, createRequestReplayCache, sha256Hex, verifySignature } = require("../src/request_signatures");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function signRequest({ secret, method, path, rawBody, timestamp }) {
  return computeSignature(secret, [
    method.toUpperCase(),
    path,
    String(timestamp),
    sha256Hex(rawBody || ""),
  ]);
}

function createFakeRedis() {
  const store = new Map();
  return {
    async exists(key) {
      const entry = store.get(key);
      if (!entry) return 0;
      if (entry.expiresAt != null && entry.expiresAt <= Date.now()) {
        store.delete(key);
        return 0;
      }
      return 1;
    },
    async set(key, value, options = {}) {
      const existing = store.get(key);
      if (existing && existing.expiresAt != null && existing.expiresAt <= Date.now()) {
        store.delete(key);
      }
      if (options.NX && store.has(key)) {
        return null;
      }
      store.set(key, {
        value,
        expiresAt: options.EX ? Date.now() + options.EX * 1000 : null,
      });
      return "OK";
    },
  };
}

async function testInMemoryReplayCache() {
  const secret = "request-replay-secret";
  const timestamp = Math.floor(Date.now() / 1000);
  const method = "GET";
  const path = "/v1/events:fetch?gid=test-gid&limit=1";
  const signature = signRequest({ secret, method, path, rawBody: "", timestamp });
  const verification = verifySignature({
    secret,
    method,
    path,
    rawBody: "",
    timestamp: String(timestamp),
    signature,
    skewSeconds: 300,
  });
  assert(verification.ok, "first request should verify");
  assert(verification.replayKey, "verified request should expose replay key");

  const cache = createRequestReplayCache({
    redis: null,
    maxEntries: 32,
    ttlSeconds: 60,
  });
  const first = await cache.remember(verification.replayKey, timestamp);
  const second = await cache.remember(verification.replayKey, timestamp);
  assert(first === true, "first replay-cache insert should succeed");
  assert(second === false, "duplicate replay-cache insert should fail");

  const differentPath = "/v1/events:fetch?gid=test-gid&limit=2";
  const differentSignature = signRequest({
    secret,
    method,
    path: differentPath,
    rawBody: "",
    timestamp,
  });
  const differentVerification = verifySignature({
    secret,
    method,
    path: differentPath,
    rawBody: "",
    timestamp: String(timestamp),
    signature: differentSignature,
    skewSeconds: 300,
  });
  assert(differentVerification.ok, "different request target should still verify");
  assert(
    differentVerification.replayKey !== verification.replayKey,
    "different signed request should produce a different replay key"
  );
  const third = await cache.remember(differentVerification.replayKey, timestamp);
  assert(third === true, "different signed request should be accepted");
  console.log("PASS request_replay_cache_memory");
}

async function testRedisReplayCache() {
  const secret = "request-replay-secret";
  const timestamp = Math.floor(Date.now() / 1000);
  const method = "POST";
  const path = "/v1/events:ingest";
  const rawBody = JSON.stringify({
    gid: "gid-redis",
    events: [{ id: "evt-1", body: { hello: "world" } }],
  });
  const signature = signRequest({ secret, method, path, rawBody, timestamp });
  const verification = verifySignature({
    secret,
    method,
    path,
    rawBody,
    timestamp: String(timestamp),
    signature,
    skewSeconds: 300,
  });
  assert(verification.ok, "redis path request should verify");

  const cache = createRequestReplayCache({
    redis: createFakeRedis(),
    maxEntries: 32,
    ttlSeconds: 60,
  });
  const first = await cache.remember(verification.replayKey);
  const second = await cache.remember(verification.replayKey);
  assert(first === true, "redis replay-cache should accept request once");
  assert(second === false, "redis replay-cache should reject duplicate request");
  const has = await cache.has(verification.replayKey);
  assert(has === true, "redis replay-cache should report remembered request");
  console.log("PASS request_replay_cache_redis");
}

async function main() {
  await testInMemoryReplayCache();
  await testRedisReplayCache();
}

main().catch((err) => {
  console.error("FAIL request_replay_cache");
  console.error(err && err.message ? err.message : String(err));
  process.exit(1);
});
