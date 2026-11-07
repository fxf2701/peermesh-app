// Federation TLS helpers (optional mTLS).

const fs = require("fs");

function readFile(path) {
  if (!path) return null;
  return fs.readFileSync(path);
}

function createFederationTlsOptions(config) {
  if (!config.federationMtlsEnabled) return null;
  if (!config.federationMtlsCertPath || !config.federationMtlsKeyPath) {
    throw new Error("federation mTLS enabled but cert/key path missing");
  }

  const cert = readFile(config.federationMtlsCertPath);
  const key = readFile(config.federationMtlsKeyPath);
  const ca = config.federationMtlsCaPath ? readFile(config.federationMtlsCaPath) : null;

  return {
    cert,
    key,
    ca: ca || undefined,
    rejectUnauthorized: config.federationMtlsRejectUnauthorized,
  };
}

module.exports = { createFederationTlsOptions };
