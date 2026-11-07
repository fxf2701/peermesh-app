#!/usr/bin/env node
const { signRegistryRequest } = require("../src/auth");

const secret = process.env.REGISTRY_SHARED_SECRET || "dev-registry-secret";
const path = "/v1/registry/relays:heartbeat";
const method = "POST";
const timestamp = Math.floor(Date.now() / 1000).toString();
const rawBody = JSON.stringify({
  relay_id: process.env.RELAY_ID || "relay-dev-01",
  public_key: process.env.RELAY_PUBLIC_KEY || "dev-public-key",
  endpoint: process.env.RELAY_ENDPOINT || "http://relay:8080",
  region: process.env.RELAY_REGION || "dev-local",
  capabilities: ["store_forward", "capsule_push", "capsule_pull"],
  version: "dev",
});
const signature = signRegistryRequest({ secret, method, path, timestamp, rawBody });

console.log(`curl -sS -X POST http://localhost:8090${path} \\
  -H 'content-type: application/json' \\
  -H 'x-registry-timestamp: ${timestamp}' \\
  -H 'x-registry-signature: ${signature}' \\
  --data '${rawBody}'`);
