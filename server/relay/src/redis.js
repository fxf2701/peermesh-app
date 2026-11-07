// Redis client helper (optional).

const { createClient } = require("redis");
const { logEvent } = require("./logger");

function createRedisClient({ url }) {
  if (!url) return null;
  const client = createClient({ url });
  client.on("error", (err) => {
    logEvent("warn", "redis_error", { error: err });
  });
  client.connect().catch((err) => {
    logEvent("warn", "redis_connect_failed", { error: err });
  });
  return client;
}

module.exports = { createRedisClient };
