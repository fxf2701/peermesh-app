# FEDERATION_PROTOCOL.md

# Federated Relay Protocol (Updated Specification)

> This document defines the federation protocol used by PeerMesh relay nodes to exchange opaque events across disconnected local networks.
>
> The protocol is designed to support:
>
> - offline-first communication,
> - authenticated federation,
> - registry-orchestrated managed federation,
> - optional private registry deployments.

---

# 1. Design Principles

- Offline-first operation
- Event-based replication
- Opaque payload transport
- Federated architecture
- Optional official infrastructure
- Eventual consistency
- Idempotent operations
- Bounded federation topology
- Authenticated relay communication
- Registry-issued federation sessions
- Push-first propagation with scoped reconciliation

Managed PeerMesh federation uses the registry as the federation control-plane authority.

Relay nodes do not self-select peers in managed networks. They obtain short-lived federation sessions from the registry and exchange event data only with peers authorized by those sessions.

Independent deployments may run their own registry and policy engine.

In self-hosted deployments, a private registry profile may intentionally permit `community` relays to participate in managed federation sessions. This is a deployment choice for that private network and does not change the production recommendation for the official ecosystem.

---

# 2. Terminology

## Event
Immutable canonical event envelope produced by a client.

## Event ID (`eid`)
Globally unique identifier of an event.

## Group ID (`gid`)
Logical namespace for related events.

## Relay Node
Store-and-forward federation server.

## Gateway
A client bridging local offline mesh and internet relay infrastructure.

## Peer Node
Another relay participating in federation sync.

## Registry
Control-plane authority that assigns federation sessions, peers, limits and permissions.

## Federation Session
Short-lived registry-issued authorization that permits a relay to exchange events with specific peers under specific limits.

## Official Relay
Relay operated by the PeerMesh project.

## Trusted Federation Relay
Approved federation relay synchronized with official infrastructure.

## Independent Relay
Self-hosted relay outside the official federation network.

---

# 3. Event Invariants

All events MUST satisfy:

- immutable after creation,
- globally unique `eid`,
- bounded lifetime (`exp`),
- deterministic canonical serialization,
- integrity protection.

Relay nodes MUST NOT mutate event payloads.

---

# 4. Event Envelope

Relay nodes rely only on minimal envelope fields:

- `gid`
- `eid`
- `ts`
- `exp`
- `body`
- `sig`

`body` is treated as an opaque JSON object that contains encrypted application payload data.

Relay nodes must avoid semantic interpretation of payload content.
Relay nodes must forward `body` byte-for-byte at the JSON value level and must not decrypt, rewrite, normalize, or derive routing decisions from encrypted payload fields.

Recommended production body shape:

```json
{
  "v": 1,
  "enc": "AES-256-GCM",
  "kid": "group-key-2026-06",
  "cty": "peermesh/app-event+json",
  "nonce": "base64url-96bit-nonce",
  "ct": "base64url-ciphertext",
  "tag": "base64url-auth-tag"
}
```

Relay federation peers validate only top-level relay metadata, registry-granted session scope, expiry, replay windows, and coarse body shape.
Application plaintext validation remains client-side only.

Registry-signed peer grants used on the federation data plane should carry the effective session `limits` as signed fields so the destination relay can enforce:

- `max_events_per_minute`
- `max_bytes_per_minute`
- `max_in_flight`

---

# 5. Security Layers

## 5.1 Transport Authentication

Relay communication should use authenticated request signing.

Applies to:

- mobile client → relay
- relay → relay federation

Recommended mechanisms:

- HMAC request signatures
- timestamp validation
- replay window checks
- optional mTLS

Transport authentication is separate from application payload encryption.

---

## 5.2 Event Integrity

Application-level event integrity is verified by clients.

Examples:

- MAC verification
- event signature verification
- canonical envelope validation

Relay nodes should not require plaintext access to event content.

---

# 6. Relay Responsibilities

Relay nodes are responsible for:

- ingest validation,
- request authentication,
- deduplication,
- expiration enforcement,
- incremental fetch,
- registry session enforcement,
- push-first federation,
- scoped reconciliation.

Relay nodes should avoid:

- user identity tracking,
- reducer execution,
- application state materialization,
- unnecessary metadata retention.

---

# 7. Federation Topology

Managed PeerMesh federation is registry-orchestrated.

Relay operators do not manually form the official peer graph.

The registry decides which relays may connect during a short-lived session.

## 7.1 Official Federation Network

Official federation relays may provide:

- bootstrap infrastructure,
- public relay access,
- operational monitoring,
- trusted federation coordination.

The official app may include default bootstrap relay URLs.

Official federation relay topology is assigned by the registry.

Relays must not exchange federation traffic unless both sides can validate an active registry-issued session.

---

## 7.2 Trusted Federation Relays

Trusted federation relays are independently operated nodes approved to join official federation infrastructure.

Production recommendation:

- official or managed production deployments should run as permissioned federation
- registry-approved relay membership and registry-issued sessions are the normal production baseline
- public open federation is not the recommended production mode for official PeerMesh infrastructure

Federation authentication may use:

- registry-issued session tokens,
- registry-signed session grants,
- peer allowlists as an additional local control,
- optional mTLS.

---

## 7.3 Private Managed Federation Networks

Users may operate completely independent managed federation networks.

Characteristics:

- self-hosted,
- private registry,
- custom topology policy,
- isolated infrastructure,
- custom security policy.

Private networks should still use registry-issued sessions rather than static trust between relay nodes.

Independent operators may choose more open admission policies for their own networks, but that should be considered a separate risk posture from the recommended production baseline.

---

## 7.4 Control Plane vs Data Plane

Registry is the control plane:

- relay trust and status
- certificate lifecycle
- federation policy
- session issuance
- session revocation
- topology assignment

Relays are the data plane:

- event ingest
- event deduplication
- event storage
- event push
- scoped reconciliation

Registry never proxies PeerMesh event payloads.

---

# 8. Federation Session Model

Managed federation traffic requires an active registry-issued federation session.

Session grants define:

- source relay
- destination peers
- allowed operations
- allowed `gid` scope
- event and byte limits
- expiration time
- policy version
- registry signature

Example:

```json
{
  "session_id": "fs_01hzt8v7",
  "relay_id": "relay-a",
  "issued_at": 1716900000,
  "expires_at": 1716900300,
  "policy_version": 12,
  "peers": [
    {
      "relay_id": "relay-b",
      "endpoint": "https://relay-b.example.com",
      "allowed_gids": [
        "group-1"
      ],
      "permissions": [
        "push",
        "pull_reconcile"
      ],
      "session_token": "short-lived-token"
    }
  ],
  "limits": {
    "max_events_per_minute": 600,
    "max_bytes_per_minute": 10485760,
    "max_in_flight": 4
  },
  "registry_signature": "hex-signature"
}
```

Relay requirements:

- request sessions from registry
- cache only active sessions
- stop federation when a session expires
- reject incoming traffic outside an active session
- renew sessions before expiry
- replace local topology when registry returns a new session
- discard sessions after revocation

Sessions should be short-lived. Recommended TTL: 5 to 15 minutes.

---

# 9. Ingest Protocol

## 9.1 Endpoint

`POST /v1/events:ingest`

## 9.2 Responsibilities

Relays should:

- validate request signatures,
- validate envelope structure,
- reject malformed events,
- reject expired events,
- deduplicate by `eid`,
- enforce limits and quotas.

---

# 10. Fetch Protocol

## 10.1 Endpoint

`GET /v1/events:fetch`

## 10.2 Responsibilities

Relays should provide:

- stable incremental fetch,
- cursor-based pagination,
- monotonic cursor progression,
- bounded result sets.

Fetch semantics must remain retry-safe and idempotent.

---

# 11. Push-First Federation Sync

Federation uses push-first propagation.

Characteristics:

- eventual consistency,
- direct relay-to-relay event push,
- registry-scoped reconciliation,
- retry-safe transfers,
- authenticated federation traffic.

When a relay receives new valid events from an app or local gateway, it should push those events to peers authorized by the current registry session.

Push is used for fast propagation.

Reconciliation is used for correctness after missed pushes, disconnects, queue drops, process restarts or network partitions.

## 11.1 Event Push Endpoint

```http
POST /v1/federation/events:push
```

Request:

```json
{
  "session_id": "fs_01hzt8v7",
  "source_relay_id": "relay-a",
  "events": [
    {
      "gid": "group-1",
      "eid": "event-1",
      "ts": 1716900001,
      "exp": 1716986401,
      "body": {
        "type": "OPAQUE.APP.EVENT"
      },
      "sig": "..."
    }
  ]
}
```

Required headers:

```http
X-Federation-Session: fs_01hzt8v7
X-Federation-Token: <session-token>
X-Federation-Timestamp: 1716900000
X-Federation-Signature: <hex-hmac-sha256>
```

Response:

```json
{
  "accepted": [
    "event-1"
  ],
  "rejected": []
}
```

The receiver must:

- validate the registry-issued session
- validate source relay and destination relay
- validate token expiry
- validate allowed operation `push`
- validate allowed `gid`
- enforce event and byte limits
- deduplicate by `eid`
- reject expired or malformed events

## 11.2 Scoped Reconciliation

Reconciliation uses existing cursor-based fetch semantics, but only within an active session.

```http
GET /v1/events:fetch?gid=group-1&cursor=abc&limit=50
```

Required federation headers:

```http
X-Federation-Session: fs_01hzt8v7
X-Federation-Token: <session-token>
X-Federation-Operation: pull_reconcile
X-Federation-Grant: <base64url registry-signed peer grant>
X-Federation-Timestamp: <unix-seconds>
X-Federation-Signature: <hmac-sha256>
```

Relays must reject reconciliation if:

- session is missing
- session is expired
- session is known revoked by the registry revocation feed
- peer is not assigned by registry
- `gid` is outside the session scope
- operation is not allowed
- registry-signed peer grant is missing, invalid, or not bound to this source/destination pair

## 11.3 Why Push-First Is Not Push-Only

Push-only federation is not sufficient because relay peers may be offline, overloaded, disconnected or restarted.

Scoped reconciliation keeps eventual consistency while preserving registry control over which peers may exchange data.

## 11.4 Fanout Rules

Relays may only fan out events to peers in the active session.

Relays must not infer new peers from received traffic.

Relays must not forward events beyond registry-assigned topology unless the active session explicitly permits it.

Recommended managed topology:

- minimum 2 peers,
- maximum 10 peers per relay
- default 2 to 4 peers per session

---

# 12. Failure Handling

All federation operations must tolerate:

- network partitions,
- duplicate deliveries,
- intermittent connectivity,
- partial sync failure.

Duplicate events must be ignored safely.

If push delivery fails, relay should:

- queue for retry within local limits
- use exponential backoff
- avoid blocking client ingest
- rely on scoped reconciliation for missed events

If session expires, relay must:

- stop pushing
- stop accepting federation traffic for that session
- request or renew a session through registry

If registry is temporarily unavailable, relay may continue using unexpired sessions but must not create new peer connections.

---

# 13. Abuse Resistance

Relay operators may apply:

- request size limits,
- rate limits,
- replay protection,
- registry-issued session restrictions,
- IP allowlists/banlists,
- bounded federation capacity.

Abuse controls should avoid requiring user identity systems.

---

# 14. Storage & Retention

Relay nodes should:

- store append-only events,
- enforce TTL expiration,
- periodically clean expired data,
- minimize long-term retention.

Long-term archival is discouraged.

---

# 15. Non-goals

This protocol does not attempt to provide:

- guaranteed real-time delivery,
- global ordering,
- global consensus,
- user identity systems,
- registry-based content moderation,
- full anonymity guarantees.

---

# 16. Summary

The PeerMesh federation protocol enables authenticated relay synchronization across disconnected local clusters while preserving:

- offline-first operation,
- opaque transport,
- registry-governed federation,
- private registry self-hosting,
- bounded operational topology,
- resilient delay-tolerant replication.

Managed federation is registry-orchestrated and session-scoped.

Data propagation is push-first, while cursor-based reconciliation remains available only inside active registry-issued sessions.
