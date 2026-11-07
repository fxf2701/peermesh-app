#!/usr/bin/env node

const { createNonceCache } = require("../src/storage");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function createFakeRedis() {
  const store = new Map();
  return {
    async set(key, value, options = {}) {
      if (options.NX && store.has(key)) {
        return null;
      }
      store.set(key, { value, expiresAt: options.EX ? Date.now() + options.EX * 1000 : null });
      return "OK";
    },
  };
}

async function testInMemoryRemember() {
  const cache = createNonceCache({
    maxEntries: 100,
    ttlSeconds: 60,
    redis: null,
    prefix: "nonce",
  });
  const now = Math.floor(Date.now() / 1000);
  const results = await Promise.all([
    cache.remember("nonce-1", now),
    cache.remember("nonce-1", now),
  ]);
  const accepted = results.filter(Boolean).length;
  const rejected = results.filter((value) => !value).length;
  assert(accepted === 1, "in-memory cache should accept nonce exactly once");
  assert(rejected === 1, "in-memory cache should reject duplicate nonce");
  console.log("PASS nonce_cache_atomic_memory");
}

async function testRedisRemember() {
  const cache = createNonceCache({
    maxEntries: 100,
    ttlSeconds: 60,
    redis: createFakeRedis(),
    prefix: "nonce",
  });
  const results = await Promise.all([
    cache.remember("nonce-redis-1"),
    cache.remember("nonce-redis-1"),
  ]);
  const accepted = results.filter(Boolean).length;
  const rejected = results.filter((value) => !value).length;
  assert(accepted === 1, "redis cache should accept nonce exactly once");
  assert(rejected === 1, "redis cache should reject duplicate nonce");
  console.log("PASS nonce_cache_atomic_redis");
}

async function main() {
  await testInMemoryRemember();
  await testRedisRemember();
}

main().catch((err) => {
  console.error("FAIL nonce_cache_atomic");
  console.error(err && err.message ? err.message : String(err));
  process.exit(1);
});
