// Entry point for the relay server skeleton.

const { createServer } = require("http");
const { createApp } = require("./app");
const { loadConfig } = require("./config");
const { logEvent } = require("./logger");

const config = loadConfig();
const app = createApp({ config });

const server = createServer(app.handleRequest);

async function start() {
  try {
    if (app.startup) {
      await app.startup();
    }
    server.listen(config.port, () => {
      logEvent("info", "federation_identity_config", {
        self_url: config.federationSelfUrl || null,
        self_id: config.federationSelfId || null,
      });
      logEvent("info", "server_listening", { port: config.port });
    });
  } catch (err) {
    logEvent("error", "startup_failed", { error: err });
    process.exit(1);
  }
}

start();

let shuttingDown = false;

async function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  logEvent("info", "server_shutdown", { signal });

  await new Promise((resolve) => server.close(resolve));
  await app.shutdown();
  process.exit(0);
}

process.on("SIGTERM", () => {
  shutdown("SIGTERM");
});

process.on("SIGINT", () => {
  shutdown("SIGINT");
});
