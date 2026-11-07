#!/usr/bin/env node

const { randomBytes, scryptSync } = require("crypto");

const password = process.argv[2] || process.env.REGISTRY_ADMIN_PASSWORD || "";

if (!password) {
  console.error("usage: node scripts/hash-admin-password.js <password>");
  process.exit(1);
}

const N = 16384;
const r = 8;
const p = 1;
const salt = randomBytes(16);
const key = scryptSync(password, salt, 32, { N, r, p });
console.log(`scrypt$${N}$${r}$${p}$${salt.toString("hex")}$${key.toString("hex")}`);
