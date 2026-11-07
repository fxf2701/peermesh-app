# Relay TLS Edge Guide (nginx/traefik)

This guide provides concrete TLS edge configurations for the relay server
in Docker, Kubernetes, and VM deployments. The relay itself runs HTTP on
`PORT` (default 8080); TLS termination happens at the edge.

## 1) Common Requirements

- Relay listens on `http://127.0.0.1:8080` or a container network.
- Edge proxy terminates TLS and forwards to relay over HTTP.
- Restrict sensitive paths (`/metrics`, `/readyz`, `/admin`) by IP allowlist.
- Forward client IP and protocol headers (`X-Forwarded-For`, `X-Forwarded-Proto`).

Suggested TLS policy:
- TLS 1.2+ only
- Strong ciphers (ECDHE + AES-GCM/ChaCha20)
- HSTS enabled for public domains

## 2) Docker + nginx (single host)

### 2.1 Compose layout (nginx + relay)

```yaml
services:
  relay:
    image: relay-server
    environment:
      - PORT=8080
    expose:
      - "8080"

  nginx:
    image: nginx:1.25-alpine
    ports:
      - "443:443"
    volumes:
      - ./nginx/conf.d:/etc/nginx/conf.d:ro
      - ./nginx/certs:/etc/nginx/certs:ro
    depends_on:
      - relay
```

### 2.2 nginx TLS config (conf.d/relay.conf)

```nginx
server {
  listen 443 ssl http2;
  server_name relay.example.org;

  ssl_certificate     /etc/nginx/certs/fullchain.pem;
  ssl_certificate_key /etc/nginx/certs/privkey.pem;
  ssl_protocols TLSv1.2 TLSv1.3;
  ssl_ciphers HIGH:!aNULL:!MD5;

  add_header Strict-Transport-Security "max-age=31536000" always;

  # Allow only required paths
  location ~ ^/(healthz|readyz|metrics|v1/events:ingest|v1/events:fetch|admin) {
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_pass http://relay:8080;
  }

  # Restrict /metrics and /readyz
  location ~ ^/(metrics|readyz) {
    allow 10.0.0.0/8;
    allow 192.168.0.0/16;
    deny all;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_pass http://relay:8080;
  }

  # Restrict /admin
  location /admin {
    allow 10.0.0.0/8;
    allow 192.168.0.0/16;
    deny all;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_pass http://relay:8080;
  }
}
```

Notes:
- Provide certificates via mounted volume or use an ACME companion.
- Keep the relay container on an internal network only.

## 3) Docker + Traefik (single host)

### 3.1 Traefik static config (traefik.yml)

```yaml
entryPoints:
  websecure:
    address: ":443"

certificatesResolvers:
  letsencrypt:
    acme:
      email: ops@example.org
      storage: /letsencrypt/acme.json
      httpChallenge:
        entryPoint: web
```

### 3.2 Docker labels on relay

```yaml
services:
  relay:
    image: relay-server
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.relay.rule=Host(`relay.example.org`)"
      - "traefik.http.routers.relay.entrypoints=websecure"
      - "traefik.http.routers.relay.tls.certresolver=letsencrypt"
      - "traefik.http.services.relay.loadbalancer.server.port=8080"

      # IP allowlist for admin
      - "traefik.http.routers.relay-admin.rule=Host(`relay.example.org`) && PathPrefix(`/admin`)"
      - "traefik.http.routers.relay-admin.entrypoints=websecure"
      - "traefik.http.routers.relay-admin.tls.certresolver=letsencrypt"
      - "traefik.http.middlewares.admin-allow.ipwhitelist.sourcerange=10.0.0.0/8,192.168.0.0/16"
      - "traefik.http.routers.relay-admin.middlewares=admin-allow"
```

## 4) Kubernetes + nginx Ingress

### 4.1 Ingress with TLS + allowlist

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: relay-ingress
  annotations:
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    nginx.ingress.kubernetes.io/proxy-body-size: "1m"
    nginx.ingress.kubernetes.io/whitelist-source-range: "10.0.0.0/8,192.168.0.0/16"
spec:
  tls:
    - hosts:
        - relay.example.org
      secretName: relay-tls
  rules:
    - host: relay.example.org
      http:
        paths:
          - path: /admin
            pathType: Prefix
            backend:
              service:
                name: relay
                port:
                  number: 8080
          - path: /metrics
            pathType: Prefix
            backend:
              service:
                name: relay
                port:
                  number: 8080
```

Notes:
- Apply per-path allowlists via separate Ingress resources if needed.
- Use cert-manager for ACME certificate automation.

## 5) Kubernetes + Traefik IngressRoute

```yaml
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: relay
spec:
  entryPoints:
    - websecure
  routes:
    - match: Host(`relay.example.org`)
      kind: Rule
      services:
        - name: relay
          port: 8080
  tls:
    certResolver: letsencrypt
```

Add admin allowlist middleware:

```yaml
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: admin-allow
spec:
  ipWhiteList:
    sourceRange:
      - 10.0.0.0/8
      - 192.168.0.0/16
```

Then attach it on the admin route:

```yaml
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: relay-admin
spec:
  entryPoints:
    - websecure
  routes:
    - match: Host(`relay.example.org`) && PathPrefix(`/admin`)
      kind: Rule
      middlewares:
        - name: admin-allow
      services:
        - name: relay
          port: 8080
  tls:
    certResolver: letsencrypt
```

## 6) VM (systemd + nginx)

### 6.1 nginx config

```nginx
server {
  listen 443 ssl http2;
  server_name relay.example.org;

  ssl_certificate     /etc/letsencrypt/live/relay.example.org/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/relay.example.org/privkey.pem;
  ssl_protocols TLSv1.2 TLSv1.3;
  ssl_ciphers HIGH:!aNULL:!MD5;

  add_header Strict-Transport-Security "max-age=31536000" always;

  location / {
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_pass http://127.0.0.1:8080;
  }

  location ~ ^/(metrics|readyz|admin) {
    allow 10.0.0.0/8;
    allow 192.168.0.0/16;
    deny all;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_pass http://127.0.0.1:8080;
  }
}
```

### 6.2 TLS automation

- Use `certbot` or `acme.sh` for certificate renewal.
- Add cron/systemd timer for renewals.

## 7) mTLS for Federation (optional)

If you enable mTLS between peers:
- Configure client cert auth at the edge for `/v1/events:fetch` and `/v1/events:ingest`.
- Limit allowed client certs to known peer CA.
- Keep non-peer traffic blocked or authenticated separately.

## 8) Checklist

- TLS termination at edge.
- HSTS enabled for public domains.
- `/metrics`, `/readyz`, `/admin` restricted by IP allowlist.
- Relay HTTP port not exposed directly to the internet.
- `TRUSTED_PROXY_IPS` set to the edge proxy IPs.

