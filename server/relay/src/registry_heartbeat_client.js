// server/relay/src/registry_heartbeat_client.js

const { signRegistryRequest } = require("./registry_request_signing");

function nowEpochSeconds() {
  return Math.floor(Date.now() / 1000);
}

function createRegistryHeartbeatClient({
  config,
  onLog = () => {},
}) {
  let timer = null;

  function enabled() {
    return (
      config.registryHeartbeatEnabled === true &&
      !!config.registryUrl &&
      !!config.registrySharedSecret
    );
  }

  async function sendHeartbeat() {
    try {
      const relayId =
        config.federationSelfId ||
        config.federationSelfUrl ||
        "relay";

      const endpoint =
        config.registryAdvertisedEndpoint ||
        config.federationSelfUrl;

      if (!endpoint) {
        onLog("warn", "registry_heartbeat_skipped", {
          reason: "missing_endpoint",
        });
        return;
      }

      const payload = {
        relay_id: relayId,
        public_key: relayId,
        endpoint,
        region: config.registryRelayRegion || null,
        capabilities: [
          "store_forward",
          "capsule_push",
          "capsule_pull",
          "federation",
        ],
      };

      const heartbeatPath = "/v1/registry/relays:heartbeat";
      const body = JSON.stringify(payload);
      const timestamp = String(nowEpochSeconds());
      const signature = signRegistryRequest({
        secret: config.registrySharedSecret,
        method: "POST",
        path: heartbeatPath,
        timestamp,
        rawBody: body,
      });

      const response = await fetch(
        `${config.registryUrl}${heartbeatPath}`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-Registry-Timestamp": timestamp,
            "X-Registry-Signature": signature,
          },
          body,
        }
      );

      if (!response.ok) {
        const text = await response.text();

        onLog("warn", "registry_heartbeat_failed", {
          status: response.status,
          body: text,
        });

        return;
      }

      onLog("info", "registry_heartbeat_ok", {
        relay_id: relayId,
        endpoint,
      });
    } catch (err) {
      onLog("warn", "registry_heartbeat_failed", {
        error: err.message,
      });
    }
  }

  function start() {
    if (!enabled()) {
      onLog("info", "registry_heartbeat_disabled");
      return;
    }

    const intervalSeconds =
      config.registryHeartbeatIntervalSeconds || 60;

    onLog("info", "registry_heartbeat_started", {
      registry_url: config.registryUrl,
      interval_seconds: intervalSeconds,
    });

    sendHeartbeat();

    timer = setInterval(
      sendHeartbeat,
      intervalSeconds * 1000
    );
  }

  function stop() {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  return {
    start,
    stop,
  };
}

module.exports = {
  createRegistryHeartbeatClient,
};
