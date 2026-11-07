# Relay Edge / WAF Guidance (nginx + Traefik)

This document provides minimal edge/WAF rules for the relay service.
Adjust rates, IP rules, and header handling to your environment.

## Goals
- Protect ingress endpoints from abuse (rate limits, size limits).
- Keep health/metrics safe (allow internal only).
- Preserve client IP via trusted proxies.

## nginx example

```nginx
# /etc/nginx/conf.d/relay.conf
limit_req_zone $binary_remote_addr zone=relay_ingest:10m rate=30r/s;
limit_req_zone $binary_remote_addr zone=relay_fetch:10m rate=60r/s;

server {
  listen 443 ssl http2;
  server_name relay.example.com;

  # TLS config omitted for brevity.

  client_max_body_size 1m;

  location = /healthz { allow 10.0.0.0/8; deny all; proxy_pass http://relay; }
  location = /readyz  { allow 10.0.0.0/8; deny all; proxy_pass http://relay; }
  location = /metrics { allow 10.0.0.0/8; deny all; proxy_pass http://relay; }

  location = /v1/events:ingest {
    limit_req zone=relay_ingest burst=60 nodelay;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_pass http://relay;
  }

  location = /v1/events:fetch {
    limit_req zone=relay_fetch burst=120 nodelay;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_pass http://relay;
  }
}
```

## Traefik example

```yaml
# docker-compose snippet
services:
  traefik:
    image: traefik:v3.0
    command:
      - "--entrypoints.websecure.address=:443"
      - "--providers.docker=true"
    ports:
      - "443:443"
    volumes:
      - "/var/run/docker.sock:/var/run/docker.sock:ro"

  relay:
    image: relay
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.relay.rule=Host(`relay.example.com`)"
      - "traefik.http.routers.relay.entrypoints=websecure"
      - "traefik.http.routers.relay.tls=true"
      - "traefik.http.routers.relay.middlewares=relay-ratelimit,relay-ipallow,relay-headers"
      - "traefik.http.middlewares.relay-ratelimit.ratelimit.average=30"
      - "traefik.http.middlewares.relay-ratelimit.ratelimit.burst=60"
      - "traefik.http.middlewares.relay-ipallow.ipallowlist.sourcerange=10.0.0.0/8"
      - "traefik.http.middlewares.relay-headers.headers.customrequestheaders.X-Forwarded-Proto=https"

  relay-metrics:
    image: relay
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.relay-metrics.rule=Host(`relay.example.com`) && Path(`/metrics`)"
      - "traefik.http.routers.relay-metrics.entrypoints=websecure"
      - "traefik.http.routers.relay-metrics.tls=true"
      - "traefik.http.routers.relay-metrics.middlewares=relay-ipallow"
```

## Notes
- Keep `/metrics` and `/readyz` internal-only.
- Align edge limits with relay env limits (RATE_LIMIT_*).
- Trust `X-Forwarded-For` only from known proxy IPs (TRUSTED_PROXY_IPS).
