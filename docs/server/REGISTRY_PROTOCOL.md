# PeerMesh Relay Registry Protocol

> This document defines the registry protocol used by relay nodes to publish their availability, by clients to discover relay infrastructure and obtain short-lived relay access grants, and by managed federation relays to obtain short-lived federation sessions.
>
> The registry is the control-plane authority for managed federation. It is not a message relay and does not carry PeerMesh event payloads.

---

# 1. Purpose

The registry service provides relay discovery and federation coordination.

Responsibilities:

- relay registration / heartbeat
- relay availability tracking
- relay metadata distribution
- trust-level publication
- revocation and policy feed distribution
- relay discovery for clients and operators
- client relay access grant issuance
- client relay access grant renewal / reassignment
- client and relay abuse-report intake
- federation session issuance
- managed federation topology assignment
- federation session revocation

The registry does not:

- store PeerMesh events
- proxy relay-to-relay event sync traffic
- inspect message content
- perform application reducer logic
- authenticate end users directly at the data plane

---

# 2. Design Principles

- Lightweight
- Discovery and federation control-plane only
- Federation independent
- Self-hosting friendly
- No user accounts required
- Compatible with official and private deployments
- Signed relay heartbeats
- Explicit trust and revocation model
- Registry-orchestrated federation sessions for managed networks
- Registry-orchestrated client relay access grants for managed networks
- Short-lived federation authorization

---

# 3. Registry Architecture

```text
Relay Node
    |
    | signed heartbeat
    v
Registry

Mobile Client / Relay Operator
    |
    | discovery / session bootstrap
    v
Registry
    |
    | relay candidates / access grant
    v
Relay Node
```

Relay nodes publish availability.

Clients and operators retrieve relay lists and policy information.

In managed client access mode, clients may also request a short-lived relay candidate list and access grant from the registry before talking directly to a relay.

Managed federation relays request short-lived federation sessions from the registry before connecting to other relays.

Registry participation is required for official or managed federation networks.

Independent deployments may operate private registries with their own policies.

Registries may expose a deployment profile in policy feeds:

- `official_managed` for the official or permissioned production posture
- `private_self_hosted` for independent networks that intentionally allow community-trust relays to federate under their own governance

Official PeerMesh clients may ship with one or more default registry endpoints for bootstrap discovery.

Operators remain free to replace, remove, or override these defaults.

---

# 4. Relationship to Relay and Federation Protocols

The registry is separate from:

- `specs/RELAY_PROTOCOL_SPEC.md` — Android/client to relay wire contract
- `FEDERATION_PROTOCOL.md` — relay-to-relay event synchronization
- `NODE_OPERATOR_GUIDE.md` — deployment and operations

Registry endpoints do not ingest, fetch, proxy, or store events.

The registry does coordinate which relay pairs may exchange events in managed federation networks.

The registry may also coordinate which relay a client may use in managed client access networks.

In this model:

- Registry is the control plane.
- Relay nodes are the data plane.
- Client-to-relay event traffic flows directly between clients and relays after session bootstrap.
- Relay-to-relay event traffic flows directly between relays.
- Client relay access grants are short-lived and scoped by registry policy.
- Federation sessions are short-lived and scoped by registry policy.

---

# 5. Relay Lifecycle Overview

The registry follows a permissionless-first model.

Any operator may:

- run a relay
- register a relay
- publish heartbeats
- participate in relay discovery

without requiring prior approval.

Trust assignment and relay identity certificates are optional governance layers that may be applied later.

Typical lifecycle:

```text
Operator
    |
    v
Run Relay
    |
    v
Register Relay
    |
    v
community
    |
    +-------------------+
    |                   |
    v                   |
Verification Request    |
    |                   |
    v                   |
verified               |
    |                   |
    v                   |
Trusted Promotion       |
    |                   |
    v                   |
trusted                |
                        |
Compromise / Abuse -----+
    |
    v
revoked / banned
```

Trust level and operational status are independent state machines.

Trust levels:

- community
- verified
- trusted

Statuses:

- active
- suspended
- revoked
- banned

Valid examples:

- community + active
- verified + active
- trusted + active
- verified + suspended
- trusted + revoked

A relay does not need a certificate or trust assignment to function.

Certificates and trust levels primarily support:

- Official Network participation
- Trusted Federation participation
- Discovery filtering
- Trust distribution

---

# 6. Timestamp Rules

All registry protocol timestamps use:

```text
Unix epoch seconds (UTC)
```

Do not use:

- epoch milliseconds
- ISO datetime strings
- local timezone timestamps

---

# 6. Relay Identity

Each relay should expose stable identity metadata.

Example:

```json
{
  "relay_id": "relay-home-01",
  "endpoint": "https://relay01.example.com",
  "trust_level": "verified",
  "status": "active"
}
```

Requirements:

- `relay_id` must be globally unique within a registry namespace
- `relay_id` should remain stable across restarts
- `endpoint` must be reachable by clients if advertised
- relay metadata must not imply user identity

---

# 7. Endpoint Advertisement

Relay nodes may advertise connectivity using:

- HTTPS domain names
- dynamic DNS hostnames
- public IP addresses

Preferred order:

1. HTTPS domain
2. Dynamic DNS hostname
3. Public IP address

When endpoint is omitted, registry implementations may construct an endpoint from:

- reported public IP
- advertised port

Example:

```json
{
  "relay_id": "home-relay-01",
  "public_ip": "113.161.x.x",
  "port": 8080
}
```

Registry implementations may store both:

- advertised endpoint
- observed public IP

for operational diagnostics.

---

# 8. Registry Request Signing

Registry write requests must be signed.

This spec intentionally aligns registry signing with `specs/RELAY_PROTOCOL_SPEC.md` to reduce implementation complexity.

## 8.1 Required Headers

```http
X-Registry-Timestamp: 1716900000
X-Registry-Signature: <hex-hmac-sha256>
```

Timestamp format:

```text
Unix epoch seconds (UTC)
```

## 8.2 Canonical Signing String

```text
HTTP_METHOD + "\n" +
REQUEST_TARGET + "\n" +
TIMESTAMP_SECONDS + "\n" +
BODY_SHA256_HEX
```

Where:

- `REQUEST_TARGET` = path + optional query string
- `BODY_SHA256_HEX` = SHA256 of raw request body bytes
- GET requests use the SHA256 of an empty body

## 8.3 Algorithm

```text
HMAC-SHA256
```

Encoding:

```text
hex lowercase
```

## 8.4 Replay Window

Registries should reject requests when:

```text
abs(server_now_seconds - X-Registry-Timestamp) > allowed_skew_seconds
```

Recommended skew:

```text
300 seconds
```

Registry replay protection may additionally use a bounded request cache keyed by:

```text
timestamp + signature
```

or:

```text
timestamp + request_target + body_hash
```

Nonce headers are not required in the v1 registry signing model.

---

## 8.5 Registration Flow

Example lifecycle:

### Step 1 - Relay Registration

A relay is deployed by an operator and begins sending registry heartbeats.

The registry creates a relay record.

Initial state:

```json
{
  "trust_level": "community",
  "status": "active"
}
```

### Step 2 - Heartbeats

The relay periodically refreshes its presence using the heartbeat endpoint.

Heartbeats update:

- last_seen
- availability
- advertised metadata

### Step 3 - Verification

An operator may request relay verification.

Registry operators may review:

- endpoint reachability
- heartbeat history
- operational stability
- abuse history

If approved:

```text
community
    ↓
verified
```

### Step 4 - Certificate Issuance

A registry may issue a Relay Identity Certificate.

Certificates are optional.

Relays remain functional without certificates.

Certificates primarily support:

- Official Network participation
- Trusted Federation participation
- Trust distribution

### Step 5 - Trusted Promotion

A verified relay may later be promoted:

```text
verified
    ↓
trusted
```

based on registry policy.

### Step 6 - Revocation

A relay may be suspended, revoked or banned.

Examples:

- compromised credentials
- malicious activity
- repeated abuse
- federation policy violations

Revocation may affect:

- discovery visibility
- trust level
- certificate validity
- federation eligibility

---

# 9. Heartbeat Protocol

## 9.1 Endpoint

```http
POST /v1/registry/relays:heartbeat
```

## 9.2 Purpose

Publish or refresh relay availability.

## 9.3 Request

```json
{
  "relay_id": "relay-home-01",
  "public_key": "...",
  "endpoint": "https://relay01.example.com",
  "public_ip": "113.161.x.x",
  "port": 8080,
  "region": "VN",
  "capabilities": [
    "store_forward",
    "capsule_push",
    "capsule_pull"
  ]
}
```

## 9.4 Response

```json
{
  "ok": true
}
```

## 9.5 Recommended Heartbeat Interval

Recommended heartbeat interval:
```
60 seconds
```

Maximum recommended heartbeat interval:
```
TTL / 2
```

A relay should refresh its heartbeat before half of the active TTL has elapsed.

## 9.6 Relay Capabilities

Registry treats relay capabilities as opaque strings.

Recommended capabilities:

- `store_forward`
- `capsule_push`
- `capsule_pull`
- `federation`

Unknown capabilities should be ignored by clients unless explicitly supported.

Note:

Trust level is not a capability.

For example:

```json
{
  "trust_level": "trusted",
  "capabilities": [
    "store_forward",
    "federation"
  ]
}
```

Trust is assigned by registry policy, while capabilities describe technical functionality supported by a relay.

---

# 10. Relay Discovery

## 10.1 List Relays

```http
GET /v1/registry/relays
```

Example:

```json
{
  "protocol_version": 1,
  "registry_version": "1.0.0",
  "now": 1716900000,
  "ttl_seconds": 300,
  "relays": [
    {
      "relay_id": "relay-home-01",
      "endpoint": "https://relay01.example.com",
      "trust_level": "verified",
      "status": "active",
      "region": "VN"
    }
  ]
}
```

---

# 11. Relay Lookup

```http
GET /v1/registry/relays/{relay_id}
```

Returns relay metadata for a specific relay.

Registry implementations should include:

- relay_id
- endpoint
- trust_level
- status
- region
- last_seen

when available.

---

# 12. Relay Expiration

Relays are considered active if:

```text
now_seconds - last_seen_seconds <= ttl_seconds
```

Default TTL:

```text
300 seconds
```

Expired relays must not be returned in active discovery listings.

---

# 12.1 Client-Managed Relay Access

Managed client access networks may require clients to bootstrap through the registry before using a relay.

Clients MUST NOT assume that any discovered relay is usable for write or private read operations in managed mode.

The registry may introduce one or more relay candidates to a client based on:

- relay health and freshness
- trust level
- relay status
- federation eligibility
- registry policy
- relay capacity and load
- region or network locality
- abuse controls and temporary bans

The registry remains off the data plane.

Client traffic still flows directly between the client and the assigned relay.

## 12.1.1 Client Relay Bootstrap and Pairwise Session Lifecycle

```text
Client
  |
  | bootstrap / discover
  v
Registry
  |
  | relay candidates + short-lived access grant
  v
Client
  |
  | choose relay + present access grant
  v
Assigned relay
  |
  | accept + create pairwise session
  v
Client <---- direct relay traffic inside pairwise session ----> Assigned relay
  |
  | renew grant or request replacement before expiry
  v
Registry
```

Lifecycle:

1. Client contacts the registry.
2. Registry returns one or more relay candidates and a short-lived access grant.
3. Client chooses a relay from the candidate list.
4. Client presents the access grant to the chosen relay and requests a direct session.
5. Relay validates the access grant and, if accepted, establishes a pairwise client-relay session.
6. Client and relay exchange data only inside that pairwise session.
7. Client renews the grant or requests reassignment before expiry.
8. Either side rejects traffic after the access grant or pairwise session expires.

## 12.1.2 Bootstrap or Request Client Relay Access Grant

```http
POST /v1/registry/client/sessions
```

Example request:

```json
{
  "client_instance_id": "android-device-01",
  "requested_gids": [
    "group-1"
  ],
  "requested_operations": [
    "ingest",
    "fetch"
  ],
  "network_hints": {
    "region": "vn-south",
    "transport": "https"
  }
}
```

Example response:

```json
{
  "ok": true,
  "grant": {
    "grant_id": "cg_01hzt9aa",
    "issued_at": 1716900000,
    "expires_at": 1716900300,
    "policy_version": 12,
    "client_instance_id": "android-device-01",
    "candidate_relays": [
      {
        "relay_id": "relay-vn-01",
        "endpoint": "https://relay-vn-01.example.com",
        "trust_level": "trusted",
        "region": "vn-south"
      },
      {
        "relay_id": "relay-sg-02",
        "endpoint": "https://relay-sg-02.example.com",
        "trust_level": "trusted",
        "region": "sg"
      }
    ],
    "allowed_gids": [
      "group-1"
    ],
    "permissions": [
      "ingest",
      "fetch"
    ],
    "limits": {
      "max_events_per_minute": 120,
      "max_bytes_per_minute": 1048576
    },
    "access_token": "registry-issued-short-lived-client-token",
    "registry_signature": "hex-signature",
    "connection_mode": "client_selects_and_relay_establishes_session"
  }
}
```

Rules:

- The registry MAY return multiple candidate relays in priority order.
- `access_token` is short-lived and scoped to one client instance, one grant, allowed operations, allowed `gid` scope, and the candidate set returned by the registry.
- `registry_signature` signs the grant so the chosen relay can validate it without sending payloads through the registry.
- The registry SHOULD keep client access grant TTL short. Recommended TTL: 5 to 15 minutes.
- The registry MAY deny the request or return zero candidates when policy or network conditions require it.

## 12.1.3 Renew or Reassign Client Relay Access Grant

```http
POST /v1/registry/client/sessions/{session_id}:renew
```

The registry MAY:

- keep the same candidate set
- replace one or more candidate relays
- narrow or widen allowed `gid` scope
- change limits or permissions
- deny renewal and force the client to back off

Clients MUST replace the active access grant with the latest registry response.

## 12.1.4 Relay Pairwise Session Establishment

After choosing a candidate relay, the client sends a connection request carrying the registry access grant.

If the relay accepts the request, the relay and client establish a pairwise session for direct traffic.

That pairwise session should define:

- pairwise session id
- selected relay id
- client instance id
- allowed `gid` scope
- allowed operations
- issued-at timestamp
- expiry timestamp
- client-side session material
- relay-side session material

The registry does not hold the pairwise session key used for client-relay traffic.

## 12.1.5 Relay Enforcement Expectations

Assigned relays MUST validate:

- access token is present
- access token is not expired
- relay id is included in the registry-approved candidate set
- requested `gid` is within grant scope
- requested operation is allowed by the grant
- registry signature is valid
- relay and policy state still allow this client

After establishing a pairwise session, both client and relay MUST reject traffic when:

- the pairwise session is missing
- the pairwise session is expired
- pairwise session keying material does not match
- the registry access grant used to create that session is no longer valid

Managed relays MUST reject client write or private read requests outside a valid registry access grant and an active pairwise client-relay session.

## 12.1.6 Availability and Fallback

The registry is the bootstrap authority in managed mode.

Clients MAY continue using an unexpired cached access grant while the registry is temporarily unavailable.

Clients MUST request a new access grant once the cached grant expires.

Independent deployments may disable client-managed relay access grants and allow direct relay access by local policy.

## 12.1.7 Client Block and Abuse Reporting

Clients MUST be allowed to locally block a relay immediately when the relay behaves abusively or violates the expected session contract.

Examples:

- unsolicited traffic flood
- protocol violation
- repeated invalid session behavior
- suspicious payload pattern
- connection flood

Client local block is immediate and does not require registry confirmation.

Clients SHOULD also report the incident to the registry so the registry can adjust future candidate selection and renewal decisions.

Example report endpoint:

```http
POST /v1/registry/client/reports
```

Example request:

```json
{
  "grant_id": "cg_01hzt9aa",
  "relay_id": "relay-vn-01",
  "client_instance_id": "android-device-01",
  "reason": "spam",
  "timestamp": 1716900123,
  "counters": {
    "unexpected_messages": 42,
    "rejected_frames": 42
  }
}
```

Rules:

- Client reports are signals for registry policy, not proof by themselves.
- The registry SHOULD correlate reports across multiple clients, relays, time windows, and existing governance state.
- The registry MAY:
  - lower candidate priority for a relay
  - deny grant renewal toward a relay
  - stop introducing a relay to clients
  - trigger operator review or automated incident response
- The registry SHOULD protect this endpoint against report spam and false-report abuse.
- A single client report MAY immediately affect only the reporting client's future relay selection, for example by:
  - temporary cooldown for the same client/relay pair
  - mild candidate deprioritization
- Stopping global relay introduction SHOULD be treated as a corroborated action, not as the default result of a single report.

## 12.1.8 False-Report Resistance

Client abuse reports are untrusted inputs.

The registry MUST treat them as evidence candidates, not as authoritative findings.

Recommended defenses:

- require a valid unexpired or recently expired `grant_id` that was actually issued by the registry
- require `relay_id` to match the relay set authorized by that grant
- require `client_instance_id` to match the client identity bound to that grant when such binding is enabled
- reject reports outside a short acceptance window relative to grant expiry unless operator policy explicitly allows delayed incident filing
- collapse duplicate reports for the same:
  - `grant_id`
  - `relay_id`
  - `reason`
  - time bucket
- rate limit report submission per client identity, per source IP, and per grant
- assign only low initial weight to a single client report
- require corroboration before applying severe actions
- decay abuse scores over time unless refreshed by new corroborated signals

Corroboration examples:

- multiple distinct clients reporting the same relay in a short window
- relay-side logs or counters indicating protocol violation
- repeated operator-reviewed incidents against the same relay
- consistency with current relay health, governance status, or prior abuse history

Recommended automatic actions:

- temporary candidate deprioritization
- cooldown before issuing a new grant to the same client/relay pair
- deny renewal for the same client/relay pair
- increase incident review priority

Recommended escalation guidance:

- same-client cooldown and mild candidate deprioritization MAY happen after a single valid report
- global removal from future client introductions SHOULD require corroboration and/or explicit policy threshold
- implementations SHOULD keep the corroboration threshold configurable because independent deployments may use stricter or looser relay-governance rules

Recommended non-automatic actions:

- permanent ban
- global trust-level downgrade
- certificate revocation
- network-wide relay removal

Those severe actions SHOULD require operator review, explicit policy thresholds, or multi-source corroboration.

---

# 13. Managed Federation Sessions

Managed federation networks use registry-issued federation sessions.

Relays MUST NOT self-select federation peers in the official or managed network.

A relay may only push events to, pull reconciliation data from, or accept federation traffic from peers authorized by an active registry session.

The registry decides federation topology based on:

- relay trust level
- relay status
- relay identity certificate state
- registry policy
- relay capacity
- relay region
- abuse history
- current network topology

The registry does not carry event payloads. It only authorizes relay-to-relay data-plane communication.

## 13.1 Federation Session Lifecycle

```text
Relay
  |
  | signed heartbeat
  v
Registry
  |
  | request federation session
  v
Registry policy engine
  |
  | session grant
  v
Relay <---- direct federation traffic ----> Authorized peers
  |
  | renew before expiry
  v
Registry
```

Lifecycle:

1. Relay publishes heartbeat.
2. Relay requests a federation session.
3. Registry checks trust, status, certificate, policy, capacity and banlists.
4. Registry returns a short-lived session with authorized peers.
5. Relay connects only to peers included in that session.
6. Relay stops federation traffic when the session expires or is revoked.
7. Relay renews or requests a new session as needed.

## 13.2 Request Federation Session

```http
POST /v1/registry/federation/sessions
```

Request:

```json
{
  "relay_id": "relay-vn-01",
  "capabilities": [
    "store_forward",
    "capsule_push",
    "capsule_pull",
    "federation"
  ],
  "requested_gids": [
    "group-1"
  ],
  "capacity": {
    "max_peers": 4,
    "max_events_per_minute": 600,
    "max_bytes_per_minute": 10485760
  }
}
```

Response:

```json
{
  "ok": true,
  "session": {
    "session_id": "fs_01hzt8v7",
    "relay_id": "relay-vn-01",
    "issued_at": 1716900000,
    "expires_at": 1716900300,
    "policy_version": 12,
    "permissions": [
      "push",
      "pull_reconcile"
    ],
    "peers": [
      {
        "relay_id": "relay-sg-02",
        "endpoint": "https://relay-sg-02.example.com",
        "trust_level": "trusted",
        "allowed_gids": [
          "group-1"
        ],
        "permissions": [
          "push",
          "pull_reconcile"
        ],
        "session_token": "registry-issued-short-lived-token"
      }
    ],
    "limits": {
      "max_events_per_minute": 600,
      "max_bytes_per_minute": 10485760,
      "max_in_flight": 4
    },
    "registry_signature": "hex-signature"
  }
}
```

Rules:

- `session_token` is short-lived and scoped to one relay pair.
- `registry_signature` signs the session grant so peers can verify it during the session TTL.
- Sessions SHOULD expire quickly. Recommended TTL: 5 to 15 minutes.
- Relays SHOULD renew before 50% of TTL has elapsed.
- The registry MAY return zero peers when no eligible topology is available.

## 13.3 Renew Federation Session

```http
POST /v1/registry/federation/sessions/{session_id}:renew
```

Request:

```json
{
  "relay_id": "relay-vn-01"
}
```

Response:

```json
{
  "ok": true,
  "session": {
    "session_id": "fs_01hzt8v7",
    "expires_at": 1716900600,
    "policy_version": 12,
    "peers": []
  }
}
```

The registry MAY change peers, limits, permissions, or policy version during renewal.

Relays MUST replace their local active session with the renewed session response.

## 13.4 Revoke Federation Session

```http
POST /v1/registry/federation/sessions/{session_id}:revoke
```

This endpoint is intended for registry operators and automated incident response.

Revocation effects:

- assigned peers must reject the revoked session
- source relay must stop push and reconciliation traffic
- relay should request a new session only after policy allows it

## 13.5 Federation Session Revocation Feed

```http
GET /v1/registry/federation/revocations
```

Relays use this read-only feed to cache revoked federation session ids and reject late data-plane traffic that still presents an otherwise valid short-lived peer grant.

Response:

```json
{
  "ok": true,
  "generated_at": 1717000000,
  "revoked_sessions": ["fs_01hzt8v7"],
  "items": [
    {
      "session_id": "fs_01hzt8v7",
      "relay_id": "relay-a",
      "revoked_at": 1716999900,
      "expires_at": 1717000200,
      "policy_version": 1
    }
  ]
}
```

Relays should poll this feed at a short interval and treat the cached result as an additional deny list for both push and `pull_reconcile` traffic.

## 13.6 Federation Session Validation

Relays MUST validate incoming federation traffic by checking:

- session token is present
- session token is not expired
- source relay matches the session grant
- destination relay matches the session grant
- requested `gid` is allowed
- requested operation is allowed
- signed session limits are present and enforced for event rate, byte rate and in-flight concurrency
- relay status is not suspended, revoked or banned
- registry signature is valid

Relays MUST reject federation traffic outside an active session.

## 13.6 Federation Policy Fields

The registry policy feed SHOULD include managed federation fields:

```json
{
  "minimum_trust_level_for_federation": "trusted",
  "federation_session_ttl_seconds": 300,
  "max_peers_per_session": 4,
  "allowed_statuses_for_federation": [
    "active"
  ],
  "allowed_federation_permissions": [
    "push",
    "pull_reconcile"
  ]
}
```

Policy fields are advisory for clients and relay operators, but registry session issuance is authoritative.

---

# 14. Trust Model

Relay nodes may operate at different trust levels.

## 14.1 Community Relay

A community relay is self-hosted and self-registered.

Characteristics:

- no PeerMesh-issued identity certificate
- discovery allowed
- federation participation restricted by local policy
- lower trust level

Community relays may appear in registry listings but are not automatically considered trusted.

---

## 14.2 Verified Relay

A verified relay possesses a PeerMesh-issued relay identity certificate.

Characteristics:

- verified relay identity
- registry-approved metadata
- enhanced trust level
- eligible for higher-trust discovery lists

Verification does not imply endorsement of relay content.

---

## 14.3 Relay Identity Certificate

A registry may issue relay identity certificates.

Purpose:

- relay identity verification
- trust establishment
- federation participation eligibility

These certificates are not TLS certificates.

They are PeerMesh relay identity documents signed by registry infrastructure.

Example:

```json
{
  "relay_id": "relay-vn-01",
  "trust_level": "verified",
  "public_key": "...",
  "issued_at": 1716900000,
  "expires_at": 1740000000
}
```

TLS certificate management remains outside this protocol.

---

## 14.4 Trusted Federation Relay

Trusted federation relays are approved to participate in official federation infrastructure.

Typical promotion path:

```text
community
    ↓
verified
    ↓
trusted
```

Characteristics:

- federation enabled
- approved by PeerMesh operators
- subject to federation policies
- eligible for synchronization with official relay infrastructure

Trusted status is a governance decision and should be assigned through registry policy.

Trusted status may be revoked independently of relay operational status.

---

## 14.5 Relay Trust Levels and Status

Registry implementations should separate relay trust level from relay operational status.

Trust levels describe how much the registry or network trusts a relay identity.

Allowed trust levels:

- `community`
- `verified`
- `trusted`

Definitions:

- `community`: self-hosted or self-registered relay
- `verified`: registry-issued identity certificate
- `trusted`: federation-approved relay

Status describes the current operational or policy state of the relay.

Allowed statuses:

- `active`
- `suspended`
- `revoked`
- `banned`

Definitions:

- `active`: relay may appear in discovery responses
- `suspended`: relay is temporarily hidden or downgraded
- `revoked`: relay certificate or trust grant is invalidated
- `banned`: relay is blocked from participation

---

# 15. Revocation and Policy Feeds

The registry may revoke or restrict relay participation.

Reasons may include:

- spam activity
- malicious federation behavior
- protocol violations
- compromised credentials
- operator abuse

---

## 15.1 Relay Revocation

Revoked relays:

- may no longer heartbeat successfully
- may be removed from discovery listings
- may lose federation privileges

---

## 15.2 Discovery Filtering

Registry implementations may omit:

- suspended relays
- revoked relays
- banned relays

from public discovery responses.

---

## 15.3 Revocation Feed

```http
GET /v1/registry/revocations
```

Example:

```json
{
  "generated_at": 1716900000,
  "revoked_relays": [
    "relay-bad-01",
    "relay-bad-02"
  ]
}
```

Clients and relay nodes may periodically synchronize this information.

---

## 15.4 Policy Feed

```http
GET /v1/registry/policy
```

Example:

```json
{
  "registry_deployment_mode": "official_managed",
  "deny_relays": [
    "relay-bad-01"
  ],
  "suspended_relays": [
    "relay-temp-01"
  ],
  "minimum_trust_level_for_auto_sync": "verified",
  "minimum_trust_level_for_federation": "trusted",
  "allowed_statuses_for_discovery": [
    "active"
  ],
  "allowed_statuses_for_federation": [
    "active"
  ],
  "federation_session_ttl_seconds": 300,
  "max_peers_per_session": 4,
  "allowed_federation_permissions": [
    "push",
    "pull_reconcile"
  ]
}
```

Policy feeds may be used to:

- distribute deny lists
- distribute trust requirements
- restrict federation participation
- communicate federation session defaults
- communicate temporary operational restrictions

`registry_deployment_mode` is a baseline profile knob.

- `official_managed` should default managed federation to a permissioned posture
- `private_self_hosted` may relax default federation and auto-sync trust floors to `community`
- operators may still override individual trust fields explicitly after selecting a deployment mode

---

# 16. Protocol Versioning

Registry implementations should remain backward compatible whenever possible.

Rules:

- Clients should ignore unknown fields.
- New optional fields may be added without increasing the protocol version.
- Breaking changes require a protocol version increment.
- Registries may expose both protocol and implementation versions.

Example:

```json
{
  "protocol_version": 1,
  "registry_version": "1.0.0"
}
```

Version definitions:

- `protocol_version` describes wire compatibility.
- `registry_version` describes registry software implementation version.

---

# 17. Security Goals

Protect against:

- spoofed relay registration
- replay attacks
- stale relay advertisements
- unauthorized trust escalation
- unauthorized federation peer access
- stale federation sessions
- federation traffic from suspended, revoked or banned relays

The registry does not authenticate end users.

---

# 18. Non-goals

The registry is not:

- a message relay
- a federation peer
- a user directory
- a moderation service
- a source of application truth

---

# 19. Summary

The registry provides relay discovery, trust metadata distribution and managed federation session coordination while remaining independent from event transport.

In managed federation networks, registry-issued sessions are authoritative. Relays must not self-select federation peers or accept federation traffic outside active registry sessions.

The v1 registry protocol uses the same timestamp and raw-body-hash signing model as the relay protocol:

```text
METHOD
REQUEST_TARGET
TIMESTAMP_SECONDS
SHA256(raw_body_bytes)
```
