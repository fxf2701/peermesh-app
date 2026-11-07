# PeerMesh Security Overview

> [!WARNING]
> **Implementation status — experimental, not security-audited.** This document includes protocol goals and normative requirements that are **not all enforced by the current source baseline**. In particular, the reviewed code has a shared static application-batch HMAC key, lacks independent cryptographic proof of owner authority for some privileged group events, and does not cryptographically exclude removed members from some subsequent group-key distribution. Registry v1 replay and relay plaintext-acceptance limitations are also reported. Read [Known Security Limitations](security/KNOWN_SECURITY_LIMITATIONS.md) before using any security claim here. Do not use the research baseline for sensitive or adversarial communication.

**Reading convention:** `must` / `should` in protocol descriptions state **design requirements**, not verified guarantees of current implementation. Treat any behavior without a linked test or implementation review as **unverified**.


PeerMesh supports local group communication during outdoor activities, travel in remote areas, and everyday situations with limited connectivity. This document explains how its security model handles group content, device storage, and optional relay services.

It describes design requirements and limitations. Detailed message formats and connection flows are documented in the linked protocol specifications.

## 1. Goals and Scope

PeerMesh aims to:

- Keep nearby groups connected where internet or cellular coverage is weak or unavailable.
- Protect group content with end-to-end encryption and message integrity checks.
- Keep data on the device and limit unnecessary metadata collection and retention.
- Support optional relay services without giving relay operators access to group content.
- Control access to managed relay networks through registry-issued permissions.

Typical connectivity limitations include gaps in coverage during hiking or camping, network congestion at events, temporary power outages, and service maintenance.

The security model covers content confidentiality, message integrity, access control, and service reliability. It does not guarantee anonymous or untraceable use, successful delivery of every message, or protection when a device, operating system, or group key is compromised.

## 2. Where Data Is Handled

| Component | Role | Security boundary |
| --- | --- | --- |
| Mobile device | Creates, decrypts, verifies, and stores group content. | Device security and protection of group keys remain essential. |
| Relay | Stores and forwards encrypted event envelopes when internet access is available. | Can see routing and connection metadata; must not require group decryption keys. |
| Registry | Introduces relays and manages permissions, relay status, certificates, and connection assignments. | Must not carry application event payloads or act as an event proxy. |

Basic local mesh communication does not require central servers, user accounts, or mandatory cloud services. Internet relay services are optional; managed relay connections require registry authorization.

### Local storage and expiration

Data is stored locally on the device. Expiration rules and periodic cleanup limit retention, but cleanup is best-effort and may be delayed by operating system restrictions.

Expiration and deletion do not guarantee secure erasure from flash storage. Device access, backups, and locally retained keys must also be considered when handling group data.

### Metadata visibility

Encrypting content does not hide all connection information. A relay may still see:

- Group and event identifiers (`gid`, `eid`).
- Timestamps, expiry times, and payload sizes.
- Client and relay session information.
- IP addresses, connection timing, and relay usage patterns.

Services should collect and retain only the metadata needed for their operation. Relays should avoid building user identity graphs or retaining unnecessary metadata.

## 3. Encryption and Group Keys

Group content uses end-to-end encryption. Message authentication codes (MACs) and signatures protect integrity at the relevant protocol layers.

Relay services store opaque event envelopes: they forward encrypted content without decrypting it or interpreting application behavior. Relays should not run application reducers or build application views from group content.

### Encrypted relay payloads

**Design requirement (not uniformly enforced in the reviewed baseline):** Production relay events must place encrypted application content inside `event.body`. Plaintext content must not be available to relay or registry services, their databases and backups, or relay operators through the service infrastructure.

The encrypted body has this shape:

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

Required safeguards:

- `kid` identifies a client-managed group or session key version and must not contain key material.
- Each encrypted payload must use a unique `nonce` under the same `kid`.
- Clients authenticate `gid`, `eid`, `ts`, `exp`, `body.v`, `body.enc`, and `body.kid` alongside the ciphertext as AEAD associated data, binding encrypted content to its event and group.
- Relay and registry services must not require payload decryption keys.
- **Design requirement:** Plaintext `body` should be permitted only in an explicit development/test compatibility mode, disabled in production. **Known gap:** the reviewed relay validator still accepts plaintext objects in at least one path. Do not assume this separation is enforced.

Encryption protects content confidentiality, but a relay can still drop, delay, replay within accepted windows, or refuse events. Encryption alone does not ensure availability.

See the [Relay Protocol Specification](specs/RELAY_PROTOCOL_SPEC.md#45-encrypted-client-payload-envelope) for the payload format and authenticated metadata.

### Key management

- Keys are derived from group secrets and stored locally.
- No central key escrow or recovery service exists.
- Key rotation must generate a new `kid`.
- Old keys should be retained only for the read/decrypt window required by product policy.
- Disclosure of a group secret or client key can expose affected group content, even when relay storage contains only ciphertext.

For Android storage requirements, see the [Passphrase Storage Policy](mobile/passphrase_storage_policy.md).

## 4. Request Authentication

Requests between clients, relays, and the registry should be authenticated. Relevant controls include HMAC request signatures, timestamp checks, replay protection, and optional mutual TLS (mTLS).

Public federation relays should not accept unauthenticated federation traffic. Managed deployments should require request signatures for non-public write APIs.

Canonical request signing uses:

```text
HTTP_METHOD
REQUEST_TARGET
TIMESTAMP_SECONDS
SHA256(raw_body_bytes)
```

Servers should reject requests outside the configured clock-skew window. A bounded replay cache should track either `timestamp + signature` or `timestamp + request_target + body_hash`. **Known gap:** the reviewed registry v1 path may accept replayed, correctly signed requests; do not treat this requirement as implemented across all paths.

Replay protection must work without inspecting application fields inside the opaque `event.body`. In managed client access mode, request signing is combined with registry-issued access grants and client-relay sessions; those permissions do not replace request signing.

See the [Relay Protocol Specification](specs/RELAY_PROTOCOL_SPEC.md#5-request-signing) and [Registry Protocol](server/REGISTRY_PROTOCOL.md#8-registry-request-signing) for signing rules.

## 5. Managed Client Access to Relays

An access grant is a short-lived, registry-signed permission to connect to selected relays. A pairwise session is the direct authenticated connection between a client and one relay.

The normal flow is:

1. The client contacts a bootstrap registry endpoint.
2. The registry returns candidate relays and an access grant.
3. The client selects a relay and requests a pairwise session.
4. The relay validates the grant and establishes the session.
5. The client renews the grant or requests reassignment before expiry.

Clients should need only bootstrap registry endpoints rather than a hard-coded relay list. Application events travel directly between the client and relay; the registry remains outside that path.

Access grants should specify the grant and client instance identifiers, candidate relays, allowed groups (`gid`), operations, limits, issue and expiry times, and registry signature.

Relays must reject connections with missing, expired, or invalid grants, unauthorized relay choices, or requests outside the allowed group or operation scope. After session establishment, both sides must reject traffic if the pairwise session is missing, expired, or its keying material no longer matches.

During a short registry outage, relays may honor an unexpired cached grant and pairwise session. New managed client sessions still require registry authorization.

### Local relay blocking and reports

A client may end a session, temporarily block a relay, and report repeated session integrity failures. Local blocking must be independent of registry action and should affect only that client's future relay selection, leaving the grant valid for other authorized candidates.

Recommended blocking policy:

- Use a time-limited block, normally 1 to 24 hours; the current app baseline is 6 hours.
- Possible triggers include repeated `grant_mismatch`, `client_session_token_mismatch`, or unexpectedly early `client_session_expired` errors.

The registry should treat reports as signals to investigate. It should:

- Bind each report to a valid issued grant and verify that the relay was a candidate.
- Rate-limit reports by client identity, IP address, and grant; collapse duplicates within a bounded time window.
- Give a single report low initial weight and reduce scores over time.
- Require corroborating evidence before broad restrictions on relay availability.
- Reserve permanent trust or status changes for operator review or explicit thresholds using multiple sources.

One report may trigger a cooldown or mild deprioritization for the reporting client. It should not alone stop relay introduction for everyone, revoke certificates, or permanently change relay status. Broader restrictions require multiple distinct client signals, an explicit operator policy threshold, or additional evidence such as logs or prior incident history.

See the [Registry Protocol](server/REGISTRY_PROTOCOL.md#121-client-managed-relay-access) for access grants, sessions, blocking, and reporting rules.

## 6. Relay-to-Relay Connections

Federation is the exchange of events between relays. Official and managed networks should use permissioned federation: relay participation requires registry approval, short-lived sessions, and explicit trust and status policies.

Relays must not choose their own federation peers in these networks. Open federation should not be the production default; independent operators may evaluate it as an experimental deployment option with additional risks.

A registry-issued federation session should specify:

- Session, source relay, and destination relay identifiers.
- Allowed groups and operations (`push`, `pull_reconcile`).
- Event and byte limits.
- Issue and expiry times, policy version, and registry signature.

Relays must reject federation traffic if the session is missing, expired, revoked, or invalid; if the source, destination, group, or operation is unauthorized; or if the relay is suspended, revoked, or banned.

Sessions should be short-lived, with a recommended TTL of **300 to 900 seconds**. An existing unexpired session may remain usable during a registry outage, but new federation connections require registry approval.

### Delivery and recovery

Federation should use push for prompt delivery and scoped reconciliation to recover missed events:

| Operation | Endpoint | Purpose |
| --- | --- | --- |
| Push | `POST /v1/federation/events:push` | Forward events to assigned peers. |
| Reconciliation | `GET /v1/events:fetch?gid=...&cursor=...&limit=...` | Recover missed events within an authorized group scope. |

Both operations require an active registry-issued federation session in managed mode. Push alone is insufficient for eventual consistency: reconciliation handles missed pushes, peer downtime, queue overflow, restarts, network partitions, and token expiry during delivery.

Relays must deduplicate events by `eid` and treat duplicate deliveries as expected behavior.

See the [Federation Protocol](server/FEDERATION_PROTOCOL.md) for session and delivery details.

## 7. Service Operation

### Tokens and credentials

Prefer short-lived, scoped session tokens and registry-signed grants over long-lived shared secrets for federation authorization. Optional mTLS can bind connections to service identities. Managed client access should follow the same short-lived grant model.

Federation tokens should be limited to their source relay, destination relay, session, operation, group, and expiry time.

Operators should rotate registry heartbeat credentials, registry signing keys, federation session signing keys, and admin credentials. Secrets must not be logged; tokens and signatures should be redacted from logs.

### Admin interfaces

Relay Admin and Registry Admin provide privileged access. They should:

- Require authentication and use HttpOnly session cookies.
- Use HTTPS in production.
- Use hashed/file-backed credentials or an external identity provider.
- Protect state-changing requests against cross-site request forgery (CSRF).
- Require explicit reasons for high-impact administrative changes and record an audit trail.
- Restrict access through IP allowlists or private networks where appropriate.

Registry Admin needs additional protection because it can change relay trust, certificates, policy, session permissions, and connection assignments. Keep the registry service on a private or container-only network when possible. Do not expose its raw admin port publicly; provide HTTPS through a reverse proxy or ingress.

Use an outer access control such as a VPN, private network, IP allowlist, or edge mTLS alongside application login.

### Replay and resource limits

Services should combine event deduplication, request signing, timestamp checks, bounded replay caches, scoped grants and sessions, request-size limits, per-group rate limits, and per-relay quotas.

These controls reduce duplicate traffic and service overload without requiring plaintext access. They do not eliminate denial-of-service or misleading content. Apply checks to requests, event identifiers, grants, and sessions rather than application payload internals.

Client and relay reports should inform selection, renewal, cooldown, and incident review. False-report resistance remains a required part of these controls; reports should not cause immediate global trust changes without corroboration or operator policy.

## 8. Device and Platform Limitations

Background scanning, synchronization, and cleanup depend on operating system permissions, battery settings, and app lifecycle rules. Android generally permits more background work, while iOS imposes stricter limits. Neither platform guarantees continuous background operation.

Users should protect their devices, group secrets, and stored keys, and understand who receives shared group data. If a device is lost, left unlocked, or compromised by malware, the application cannot guarantee protection of content accessible on that device. It also cannot ensure that shared information is accurate.

Use PeerMesh with an understanding of these limits: encrypted content still has visible metadata, delivery is best-effort, and data cleanup is not secure erasure.

## 9. Related Specifications

- [Project Overview](specs/Project_Overview_Technical_Spec.md): product scope and architecture.
- [Relay Protocol](specs/RELAY_PROTOCOL_SPEC.md): encrypted event envelopes, request signing, and client-relay sessions.
- [Registry Protocol](server/REGISTRY_PROTOCOL.md): access grants, relay permissions, and session lifecycle.
- [Federation Protocol](server/FEDERATION_PROTOCOL.md): relay-to-relay authentication and synchronization.
- [Passphrase Storage Policy](mobile/passphrase_storage_policy.md): Android group-key storage requirements.
