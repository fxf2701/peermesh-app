#!/usr/bin/env node
/* eslint-disable no-console */

const fs = require("fs");
const os = require("os");
const path = require("path");
const { createFederationTlsOptions } = require("../src/federation_tls");

const testTimeoutMs = Number(process.env.FEDERATION_MTLS_TEST_TIMEOUT_MS || "8000");

async function run() {
  let failed = 0;
  const watchdog = setTimeout(() => {
    console.log("FAIL federation_mtls_config");
    console.log(`  error: test_timeout_${testTimeoutMs}ms`);
    process.exit(1);
  }, testTimeoutMs);

  try {
    console.log("federation_mtls_test_start");
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "relay-mtls-"));
    const certPath = path.join(dir, "client.crt");
    const keyPath = path.join(dir, "client.key");
    const caPath = path.join(dir, "ca.crt");
    fs.writeFileSync(certPath, "dummy-cert");
    fs.writeFileSync(keyPath, "dummy-key");
    fs.writeFileSync(caPath, "dummy-ca");

    const options = createFederationTlsOptions({
      federationMtlsEnabled: true,
      federationMtlsCertPath: certPath,
      federationMtlsKeyPath: keyPath,
      federationMtlsCaPath: caPath,
      federationMtlsRejectUnauthorized: true,
    });

    if (!options || !options.cert || !options.key || !options.ca) {
      failed += 1;
      console.log("FAIL federation_mtls_config");
    } else {
      console.log("PASS federation_mtls_config");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL federation_mtls_config");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    clearTimeout(watchdog);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
