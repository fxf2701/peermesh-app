# RELAY_DEPLOYMENT_MODELS.md

# PeerMesh Relay Deployment Models

> This document describes the supported relay deployment models and the operational trade-offs of each approach.

---

# 1. Overview

PeerMesh supports multiple relay deployment models.

The goal is to allow:

* hobby operators,
* community operators,
* organizations,
* and official infrastructure

to participate using different levels of complexity.

Not all relay nodes require a VPS or domain name.

---

# 2. Home Relay (Dynamic IP)

## Description

A relay node running on:

* home PC
* mini PC
* NAS
* Raspberry Pi

connected through a residential internet connection.

Example:

```text
Home PC
    ↓
Router Port Forward
    ↓
Public Internet
```

---

## Requirements

* internet connection
* port forwarding
* registry access

Optional:

* dynamic DNS

---

## Endpoint Advertisement

Option A:

```json
{
  "public_ip": "113.x.x.x",
  "port": 8080
}
```

Option B:

```json
{
  "endpoint": "https://myhome.ddns.net"
}
```

---

## Advantages

* lowest cost
* easy experimentation
* community participation

---

## Limitations

* dynamic IP changes
* ISP restrictions
* possible downtime

---

## Recommended Trust Level

```text
community
```

---

# 3. Home Relay with Dynamic DNS

## Description

A home relay using:

* Cloudflare
* DuckDNS
* No-IP
* other DDNS providers

Example:

```text
home-relay.example.com
    ↓
dynamic DNS
    ↓
changing public IP
```

---

## Advantages

* stable hostname
* easier discovery
* HTTPS support

---

## Recommended Trust Level

```text
verified
```

---

# 4. VPS Relay

## Description

A relay hosted on:

* DigitalOcean
* Vultr
* Hetzner
* Linode
* AWS
* Azure

Example:

```text
relay01.example.com
    ↓
static VPS IP
```

---

## Advantages

* stable uptime
* static IP
* easier maintenance
* production ready

---

## Recommended Trust Level

```text
verified
```

---

# 5. Organization Relay

## Description

Relay infrastructure operated by:

* companies
* NGOs
* universities
* local communities

Example:

```text
Organization Network
    ↓
Multiple Relay Nodes
```

---

## Characteristics

* managed infrastructure
* operational policies
* dedicated operators

---

## Recommended Trust Level

```text
trusted
```

---

# 6. Official PeerMesh Relay

## Description

Relay infrastructure operated by the PeerMesh project.

Responsibilities:

* public access
* federation bootstrap
* relay discovery support

---

## Additional Responsibilities

* registry operation
* trust issuance
* federation coordination

---

## Recommended Trust Level

```text
trusted
```

---

# 7. Registry Participation

Relay nodes may:

* register with official registry
* register with private registry
* operate without registry

Registry participation remains optional.

---

# 8. Trust Progression

A typical progression:

```text
Home Relay
    ↓
Community Relay
    ↓
Verified Relay
    ↓
Trusted Federation Relay
```

Trust levels are determined by registry policy.

---

# 9. Choosing a Deployment Model

For experimentation:

```text
Home Relay
```

For community use:

```text
Home Relay + Dynamic DNS
```

For production:

```text
VPS Relay
```

For large deployments:

```text
Organization Relay
```

---

# 10. Summary

PeerMesh supports a spectrum of relay deployment models ranging from hobby-operated home relays to trusted federation infrastructure.

The protocol does not require a single hosting model and remains compatible with self-hosted, community-operated, and official infrastructure.
