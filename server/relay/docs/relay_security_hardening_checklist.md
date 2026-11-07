# Relay Security Hardening Checklist

Use this checklist before exposing a relay to the public internet.

## Edge/WAF

- Enforce TLS at the edge (TLS 1.2+).
- Apply rate limits (ingest/fetch) and request size limits.
- Restrict `/metrics` and `/readyz` to internal IPs.
- Allow only required paths.

## Headers

- Set `X-Forwarded-Proto` and `X-Forwarded-For` at the proxy.
- Ensure the relay trusts only `TRUSTED_PROXY_IPS`.

## Auth and Access Control

- Enable request signatures or mTLS for federation peers.
- Set `FEDERATION_PEER_ALLOWLIST`.
- Keep Admin UI internal and protected.

## Secrets

- Store secrets outside git (vault or secret manager).
- Rotate peer tokens and request signatures on a schedule.

## Logging

- Confirm log redaction rules are active.
- Avoid logging sensitive query params (e.g., `gid`) at the proxy.

## Backups

- Verify backup automation and restore drills are working.
- Ensure offsite backup storage is configured.
