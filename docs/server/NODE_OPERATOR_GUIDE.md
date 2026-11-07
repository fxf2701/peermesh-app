# NODE_OPERATOR_GUIDE.md

# Running a PeerMesh Relay Node

> This guide explains how to deploy and operate a PeerMesh relay node.

---

# 1. Deployment Types

PeerMesh supports multiple deployment models.

Common options:

- Home Relay (dynamic IP)
- Home Relay + Dynamic DNS
- VPS Relay
- Organization Relay

For deployment model selection see:

`RELAY_DEPLOYMENT_MODELS.md`

---

# 2. Requirements

Minimum VPS:

* 1 vCPU
* 512 MB RAM
* 10 GB disk

Recommended:

* Linux
* Docker
* Docker Compose

Production layout:

* Relay service: `server/relay/docker-compose.yml`, `server/relay/.env`, `server/relay/start.sh`, `server/relay/stop.sh`
* Registry service: `server/registry/docker-compose.yml`, `server/registry/.env`, `server/registry/start.sh`, `server/registry/stop.sh`

Developer/local layout is separate:

* `server/docker-compose.dev.yml`
* `server/.env.dev`
* `server/start.sh`
* `server/stop.sh`

---

# 3. Endpoint Configuration

Relay operators may use:

- HTTPS domain
- Dynamic DNS hostname
- Public IP address

Preferred order:

1. HTTPS domain
2. Dynamic DNS hostname
3. Public IP address

Example domain:

```text
relay01.example.com
```

Enable HTTPS using:

* Nginx
* Caddy
* Traefik

---

# 4. Home Relay Networking

Home relay operators may need:

- router port forwarding
- firewall configuration
- dynamic DNS

Some ISPs may block inbound traffic or use CGNAT.

---

# 5. Registry Access

A relay may register with:

- official PeerMesh registry
- private organization registry
- self-hosted registry

Registry participation is optional.

Nodes may operate without registry participation.

Example registry URL if you opt in:

```env
REGISTRY_URL=https://registry.peermesh.app
```

---

# 6. Relay Configuration

Standalone baseline:

```env
SERVER_MODE=community
REQUEST_SIGNATURE_REQUIRED=1
REQUEST_SIGNATURE_SECRET=replace-me

REGISTRY_HEARTBEAT_ENABLED=0
REGISTRY_CLIENT_ACCESS_GRANTS_ENABLED=0
CLIENT_RELAY_SESSIONS_ENABLED=0
REGISTRY_FEDERATION_SESSIONS_ENABLED=0
```

Optional registry-managed mode:

```env
FEDERATION_SELF_ID=relay01

FEDERATION_SELF_URL=https://relay01.example.com

REGISTRY_HEARTBEAT_ENABLED=1

REGISTRY_URL=https://registry.peermesh.app

REGISTRY_SHARED_SECRET=replace-me

REGISTRY_ADVERTISED_ENDPOINT=https://relay01.example.com

REGISTRY_RELAY_REGION=US
```

---

# 7. Relay Trust Levels

Community Relay

- self-hosted
- self-registered

Verified Relay

- PeerMesh-issued identity certificate

Trusted Federation Relay

- approved for federation participation

Trust levels are defined by registry policy.

---

# 8. Relay Identity Certificates

Verified relays may receive a PeerMesh-issued relay identity certificate.

These certificates:

- establish relay identity
- support trust verification
- are not TLS certificates

TLS remains the responsibility of the relay operator.

---

# 9. Start the Node

```bash
server/relay/start.sh --build
```

To stop:

```bash
server/relay/stop.sh
```

---

# 10. Verify Heartbeat

Check relay logs:

```bash
docker logs relay
```

Look for:

```text
registry_heartbeat_started
registry_heartbeat_ok
```

Heartbeat confirmation should appear before registry discovery succeeds.

---

# 11. Verify Relay Health

Check relay:

```bash
curl https://relay01.example.com/healthz
```

Expected:

```json
{
  "status":"ok"
}
```

---

# 12. Verify Registry Registration

Check registry:

```bash
curl https://registry.peermesh.app/v1/registry/relays
```

Expected:

```json
{
  "relays":[
    {
      "relay_id":"relay01"
    }
  ]
}
```

---

# 13. Upgrading

Pull new source:

```bash
git pull
```

Rebuild:

```bash
server/relay/start.sh --build
```

---

# 14. Backup

Recommended backups:

* relay database
* relay configuration
* TLS certificates

Registry registration is recreated automatically through heartbeat.

---

# 15. Troubleshooting

## Relay not appearing in registry

Check:

```bash
docker logs relay
```

Look for:

```text
registry_heartbeat_ok
```

---

## Registry returns empty relay list

Verify:

* REGISTRY_URL
* REGISTRY_SHARED_SECRET
* relay heartbeat logs

---

## Signature errors

Verify that:

```text
REGISTRY_SHARED_SECRET
```

matches the registry configuration.

---

## Home Relay Not Reachable

Verify:

- router port forwarding
- firewall rules
- public IP visibility
- ISP restrictions
- CGNAT usage

If CGNAT is present, inbound connections may not be possible without additional networking solutions.

---

## Dynamic DNS Issues

Verify:

- DNS resolution
- DDNS updater status
- registry advertised endpoint

Ensure that the advertised endpoint resolves to the current public IP.

---

# 16. Self-hosting Philosophy

Anyone may operate:

* public relay nodes
* private relay nodes
* organization-owned infrastructure

Participation in the official PeerMesh ecosystem is optional.

---

# 17. Summary

A PeerMesh relay node is a store-and-forward server that can optionally register with a registry service for discovery while remaining fully self-hostable.
