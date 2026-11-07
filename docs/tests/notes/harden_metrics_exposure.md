# Harden Metrics Exposure Test Notes

Status: not tested yet.

## Why not tested

- Requires reverse proxy or firewall configuration.
- Depends on the deployment environment.

## Preconditions

- Reverse proxy (nginx/traefik) or firewall rules in place.
- `/metrics` and `/readyz` restricted to internal IPs.

## Validation Steps

- Access `/metrics` from an allowed IP (should succeed).
- Access `/metrics` from a disallowed IP (should be blocked).
- Confirm access logs do not include sensitive params.
