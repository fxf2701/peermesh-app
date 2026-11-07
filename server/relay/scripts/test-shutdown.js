#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const { spawn } = require("child_process");

const databaseUrl = process.env.DATABASE_URL || process.env.RELAY_DATABASE_URL;

function requestHealth(port) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: "127.0.0.1",
        port,
        path: "/health",
        method: "GET",
      },
      (res) => {
        res.resume();
        res.on("end", () => resolve(res.statusCode));
      }
    );
    req.on("error", reject);
    req.end();
  });
}

function waitForHealthy(port, attempts = 20) {
  return new Promise((resolve, reject) => {
    let remaining = attempts;
    const timer = setInterval(async () => {
      try {
        const status = await requestHealth(port);
        if (status === 200) {
          clearInterval(timer);
          resolve();
        } else {
          remaining -= 1;
        }
      } catch (err) {
        remaining -= 1;
      }
      if (remaining <= 0) {
        clearInterval(timer);
        reject(new Error("health check timeout"));
      }
    }, 200);
  });
}

async function run() {
  if (!databaseUrl) {
    console.error("DATABASE_URL is required for shutdown tests.");
    process.exit(1);
  }

  const port = 18080 + Math.floor(Math.random() * 1000);
  const child = spawn("node", ["src/index.js"], {
    cwd: `${__dirname}/..`,
    env: {
      ...process.env,
      PORT: String(port),
      DATABASE_URL: databaseUrl,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });

  child.stdout.on("data", (chunk) => process.stdout.write(chunk));
  child.stderr.on("data", (chunk) => process.stderr.write(chunk));

  try {
    await waitForHealthy(port);
    console.log("PASS health_before_shutdown");
  } catch (err) {
    console.log("FAIL health_before_shutdown");
    console.log(`  error: ${err.message || String(err)}`);
    child.kill("SIGTERM");
    process.exit(1);
  }

  child.kill("SIGTERM");

  const exitResult = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      child.kill("SIGKILL");
      reject(new Error("shutdown timeout"));
    }, 5000);
    child.on("exit", (code, signal) => {
      clearTimeout(timeout);
      resolve({ code, signal });
    });
  });

  if (exitResult.code === 0 || exitResult.signal === "SIGTERM") {
    console.log("PASS graceful_exit");
  } else {
    console.log("FAIL graceful_exit");
    console.log(`  exit_code: ${exitResult.code}`);
    console.log(`  exit_signal: ${exitResult.signal || "none"}`);
  }

  try {
    await requestHealth(port);
    console.log("FAIL server_closed");
    process.exit(1);
  } catch (err) {
    console.log("PASS server_closed");
  }
}

run();
