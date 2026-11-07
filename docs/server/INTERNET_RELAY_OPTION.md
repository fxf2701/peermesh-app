# INTERNET_RELAY_OPTION.md

# Optional Internet Relay (Federation) – Updated Architecture

> **Purpose**: Extend local offline exchange beyond short-range proximity by optionally bridging events over the internet.
>
> **Key principle**: PeerMesh must remain fully usable without internet infrastructure. Relay support is an optional enhancement layer built on top of the offline-first mesh architecture.

---

## Related Documents

This document focuses on architecture and deployment models.

For protocol details see:

- `FEDERATION_PROTOCOL.md` — relay-to-relay federation protocol
- `REGISTRY_PROTOCOL.md` — relay registry discovery protocol

For operational deployment guidance see:

- `NODE_OPERATOR_GUIDE.md` — relay node setup and operations

---

# 1. Philosophy

PeerMesh is designed around these principles:

- Offline-first operation
- Delay-tolerant communication
- Minimal server-side trust
- Federation instead of mandatory centralization
- User freedom to self-host infrastructure

Internet relay support exists to:

- connect separated offline clusters,
- improve propagation range,
- improve resilience,
- and support large-scale deployments.

The protocol itself is not tied to a single company-controlled backend.

However, the official PeerMesh ecosystem may provide default infrastructure for usability and bootstrap purposes.

---

# 2. Network Topology Overview

PeerMesh supports multiple relay deployment models.

## 2.1 Official PeerMesh Network

The official PeerMesh app may include preconfigured bootstrap relays operated by the PeerMesh project.

Example:

- `https://peermesh.app`

The official network may contain:

- a primary seed relay,
- additional federation relays,
- load-balancing nodes,
- geographically distributed nodes,
- community-approved federation peers.

These nodes operate under the governance and approval policies of the PeerMesh project.

Recommended production mode for the official network:

- permissioned federation, not public open federation
- relay membership governed by registry policy
- short-lived registry-issued federation sessions between relays
- explicit trust/status controls for participating nodes

Purpose of the official network:

- easier onboarding,
- stable bootstrap,
- relay discovery seed,
- public availability,
- operational monitoring,
- federation coordination.

The official network is optional and not required for the protocol itself.

The official infrastructure may include:

- public relay nodes,
- registry services,
- federation seed nodes,
- operational monitoring systems.

Registry services are responsible for relay discovery and bootstrap only. They do not participate in event storage or federation synchronization.

---

## 2.2 Trusted Federation Relay

Trusted federation relays are independently operated nodes that are approved to join the official federation network.

Examples:

- VPS relay nodes,
- community-operated relays,
- partner infrastructure,
- operator-managed PC nodes.

These relays may synchronize with official federation peers using:

- registry-issued federation sessions,
- peer allowlists as additional local control,
- optional mTLS,
- authenticated federation sync.

Joining the official federation network requires approval under registry/governance policy rather than ad hoc open peering.

---

## 2.3 Independent Relay Network

Any user or organization may host completely independent relay infrastructure.

Characteristics:

- self-hosted,
- self-managed,
- independent federation topology,
- custom relay URLs,
- custom security policy,
- optional isolation from official PeerMesh infrastructure.

The mobile app may allow users to:

- add relay URLs manually,
- replace default relays,
- use private federation networks,
- operate fully isolated deployments.

This preserves decentralization and deployment flexibility.

Independent networks may choose a more open federation posture, but this is not the recommended production baseline for the official or managed PeerMesh ecosystem.

When an independent network still wants registry-orchestrated federation, it may run the registry in a `private_self_hosted` deployment mode so managed federation sessions remain available without requiring official-network trust promotion.

---

# 3. Design Goals

- Offline-first operation remains primary
- Relay infrastructure is optional
- Federation instead of mandatory centralization
- Opaque event transport
- Minimal metadata exposure
- Short-lived retention
- Best-effort propagation
- Independent self-hosting support
- Secure authenticated relay communication

---

# 4. Roles and Components

## 4.1 Client

The mobile application acts as an offline-first DTN node.

Responsibilities:

- BLE/local event exchange
- local event persistence
- reducer/state materialization
- optional relay bridging
- request signing for relay communication

Clients may communicate with:

- official relays,
- trusted federation relays,
- independent private relays.

---

## 4.2 Gateway Mode (Client-as-Bridge)

Any client with internet connectivity may temporarily act as a bridge between:

- local offline mesh,
- internet relay infrastructure.

Responsibilities:

- push local events to relays,
- fetch remote events,
- re-inject remote events into local mesh.

Gateway mode is:

- optional,
- intermittent,
- best-effort,
- safe-by-default.

---

## 4.3 Relay Node

Relay nodes provide store-and-forward infrastructure.

Responsibilities:

- accept opaque event blobs,
- deduplicate events,
- enforce expiration,
- serve incremental fetch,
- optionally federate with peers.

Relay nodes should avoid:

- interpreting application content,
- building user identity graphs,
- materializing application state,
- storing unnecessary metadata.

---

# 5. Security Model

## 5.1 Signed Relay Communication

Relay communication should use authenticated request signing.

This applies to:

- mobile client → relay,
- relay → relay federation traffic.

Recommended mechanisms:

- HMAC request signatures,
- timestamp validation,
- replay protection,
- optional mTLS for federation peers.

Public relays should not accept unauthenticated federation traffic.

---

## 5.2 Federation Authentication

Federation peers may authenticate using:

- peer allowlists,
- rotated federation tokens,
- shared federation secrets,
- optional mTLS certificates.

Federation topology should remain bounded and configurable.

Recommended operational bounds:

- minimum 2 peers,
- maximum 10 peers per relay.

---

## 5.3 Abuse Resistance

Relay operators may apply:

- rate limits,
- request size caps,
- replay protection,
- expiration enforcement,
- federation peer restrictions,
- IP allowlists/banlists.

These protections exist to improve network stability and reduce abuse risk.

---

# 6. Data Visibility

Relay nodes should only require minimal metadata:

- group id (`gid`)
- event id (`eid`)
- timestamps (`ts`, `exp`)
- opaque event payload (`body`)
- integrity/signature fields

Relay nodes should not require:

- accounts,
- user profiles,
- plaintext message content,
- social graphs.

Even when encrypted, metadata may remain sensitive.

Operators should minimize retention and collection.

---

# 7. Opaque Event Model

Relay infrastructure stores and forwards opaque event blobs.

Relay nodes:

- validate envelopes,
- deduplicate by event id,
- store append-only events,
- avoid reducer execution,
- avoid application-level interpretation.

Application semantics remain client-side.

This minimizes trust requirements and reduces operational risk.

---

# 8. Relay Discovery

PeerMesh supports multiple relay discovery mechanisms.

## 8.1 Registry-Based Discovery

A registry service may provide dynamic relay discovery.

Responsibilities:

- relay registration,
- relay heartbeat tracking,
- active relay listing,
- relay metadata distribution.

Relay nodes periodically publish heartbeat updates to the registry.

Clients may retrieve active relay lists from:

`GET /v1/registry/relays`

Registry services are specified in:

`REGISTRY_PROTOCOL.md`

Registry participation is optional.

Independent deployments may operate private registries.

---

## 8.2 Official Bootstrap Registry

The official PeerMesh ecosystem may operate a public registry service.

Purposes:

- onboarding,
- relay discovery,
- bootstrap connectivity.

The official registry does not participate in event storage or federation synchronization.

---

## 8.3 Official Bootstrap Relays

The official PeerMesh app may include default relay endpoints.

These relays provide:

- initial connectivity,
- federation entry points,
- public relay access.

Users remain free to:

- remove official relays,
- replace relay lists,
- use private infrastructure only.

---

## 8.4 Manual Relay Configuration

Users may manually configure:

- relay URL,
- federation peers,
- registry URL,
- TLS options,
- private relay infrastructure.

Manual configuration remains fully supported.

---

# 9. Client Relay Strategy

Recommended strategy:

- push to multiple relays (2–3),
- maintain per-node cursors,
- use incremental fetch,
- tolerate intermittent connectivity,
- avoid hard dependency on any single relay.

Clients must continue functioning without internet access.

---

# 10. Federation Model

Federation nodes may synchronize events using anti-entropy replication.

Characteristics:

- eventual consistency,
- bounded peer topology,
- periodic synchronization,
- cursor-based incremental replication,
- authenticated federation traffic.

The protocol does not require a globally centralized coordinator.

However, official federation networks may operate trusted seed relays for operational stability.

---

# 11. Minimal Relay API

## 11.1 Ingest Events

`POST /v1/events:ingest`

Responsibilities:

- validate envelope,
- validate signature,
- deduplicate,
- enforce limits,
- enqueue/store events.

Must remain idempotent by event id.

---

## 11.2 Fetch Events

`GET /v1/events:fetch`

Responsibilities:

- incremental fetch,
- stable cursor semantics,
- bounded pagination,
- authenticated access if required.

---

## 11.3 Health Endpoints

Examples:

- `/healthz`
- `/readyz`
- `/metrics`

Operational endpoints should be protected appropriately.

---

# 12. Storage and Retention

Relay nodes should:

- store append-only events,
- enforce TTL expiration,
- periodically clean expired data,
- minimize retention duration.

Long-term archival is discouraged.

---

# 13. Non-goals

The internet relay system is not intended to provide:

- guaranteed real-time delivery,
- global consensus,
- mandatory central infrastructure,
- centralized identity systems,
- centralized moderation,
- full anonymity guarantees,
- permanent archives.

The protocol is designed to function without requiring official infrastructure for basic operation.

---

# 14. Node Operations

Relay node deployment and operational procedures are documented separately.

See:

- `NODE_OPERATOR_GUIDE.md`

Topics include:

- VPS deployment
- Docker installation
- Relay configuration
- Registry registration
- TLS setup
- Backup and maintenance

---

# 15. Summary

PeerMesh internet relay support extends offline communication across disconnected clusters while preserving core system principles:

- offline-first operation,
- optional infrastructure,
- federation support,
- opaque transport,
- short-lived retention,
- independent self-hosting,
- secure authenticated relay communication.

The official PeerMesh ecosystem may operate public bootstrap infrastructure for usability and reliability, while still allowing independent relay networks and self-hosted deployments.
