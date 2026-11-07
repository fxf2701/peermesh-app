#!/usr/bin/env node
/* eslint-disable no-console */

const fs = require("fs");
const path = require("path");

const repoRoot = path.resolve(__dirname, "..", "..");
const isDev = process.argv.includes("--dev");
const composePath = path.join(
  repoRoot,
  isDev ? "docker-compose.dev.yml" : "docker-compose.yml"
);

function hasAll(needleList, haystack) {
  return needleList.every((needle) => haystack.includes(needle));
}

function run() {
  let failed = 0;
  console.log(`compose_profiles_test_start (${isDev ? "dev" : "prod"})`);

  if (!fs.existsSync(composePath)) {
    console.log("FAIL compose_profiles_missing");
    console.log(`  missing: ${composePath}`);
    process.exit(1);
  }

  const content = fs.readFileSync(composePath, "utf8");

  const checks = [
    {
      name: "compose_profiles_multi",
      ok: isDev
        ? hasAll(
            [
              "relay_2:",
              'profiles: ["multi"]',
              "postgres_2:",
              "redis:",
              "redis_2:",
            ],
            content
          )
        : true,
    },
    {
      name: "compose_profiles_metrics",
      ok: hasAll(
        ["prometheus:", 'profiles: ["metrics"]', "grafana:"],
        content
      ),
    },
    {
      name: "compose_prometheus_config",
      ok: content.includes("./relay/config/prometheus.yml:/etc/prometheus/prometheus.yml:ro"),
    },
  ];

  for (const check of checks) {
    if (check.ok) {
      console.log(`PASS ${check.name}`);
    } else {
      failed += 1;
      console.log(`FAIL ${check.name}`);
    }
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
