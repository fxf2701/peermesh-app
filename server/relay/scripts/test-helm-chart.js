#!/usr/bin/env node
/* eslint-disable no-console */

const fs = require("fs");
const path = require("path");

const repoRoot = path.resolve(__dirname, "..", "..", "..");
const chartRoot = path.join(repoRoot, "server", "relay", "helm", "relay");
const chartPath = path.join(chartRoot, "Chart.yaml");
const valuesPath = path.join(chartRoot, "values.yaml");
const deploymentPath = path.join(chartRoot, "templates", "deployment.yaml");
const servicePath = path.join(chartRoot, "templates", "service.yaml");

function hasAll(needles, haystack) {
  return needles.every((needle) => haystack.includes(needle));
}

function run() {
  let failed = 0;
  console.log("helm_chart_test_start");

  const files = [chartPath, valuesPath, deploymentPath, servicePath];
  for (const file of files) {
    if (!fs.existsSync(file)) {
      failed += 1;
      console.log("FAIL helm_chart_missing");
      console.log(`  missing: ${file}`);
      process.exit(1);
    }
  }

  const chart = fs.readFileSync(chartPath, "utf8");
  const values = fs.readFileSync(valuesPath, "utf8");
  const deployment = fs.readFileSync(deploymentPath, "utf8");
  const service = fs.readFileSync(servicePath, "utf8");

  const chartOk = hasAll(["apiVersion: v2", "name: relay", "version:"], chart);
  if (chartOk) {
    console.log("PASS helm_chart_metadata");
  } else {
    failed += 1;
    console.log("FAIL helm_chart_metadata");
  }

  const valuesOk = hasAll(["image:", "service:", "env:"], values);
  if (valuesOk) {
    console.log("PASS helm_chart_values");
  } else {
    failed += 1;
    console.log("FAIL helm_chart_values");
  }

  const deploymentOk = hasAll(["kind: Deployment", "containerPort"], deployment);
  if (deploymentOk) {
    console.log("PASS helm_chart_deployment");
  } else {
    failed += 1;
    console.log("FAIL helm_chart_deployment");
  }

  const serviceOk = hasAll(["kind: Service", "targetPort"], service);
  if (serviceOk) {
    console.log("PASS helm_chart_service");
  } else {
    failed += 1;
    console.log("FAIL helm_chart_service");
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
