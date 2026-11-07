# PeerMesh Relay Protocol Specification (v1)

> Status: Draft / Implementation Contract
>
> This document defines the exact wire-level protocol contract between:
>
> - Android clients
> - relay servers
> - federation relay peers
>
> The goal of this document is to prevent protocol drift and implementation mismatch.

---

# 1. Scope

This specification defines:

- JSON request/response schemas
- relay event envelope format
- request signing
- replay protection
- ingest/fetch semantics
- client relay access-grant and pairwise-session enforcement expectations
- cursor behavior
- federation authentication expectations
- compatibility tests and test-vector expectations

This document intentionally focuses on implementation-level compatibility.

Related higher-level documents:

- `../server/INTERNET_RELAY_OPTION.md` — architecture and deployment overview
- `../server/FEDERATION_PROTOCOL.md` — relay-to-relay federation model
- `../server/REGISTRY_PROTOCOL.md` — relay discovery and registry protocol
- `../SECURITY.md` — threat model and limitations
- `../server/NODE_OPERATOR_GUIDE.md` — node deployment and operations

---

# 2. Transport

## 2.1 Protocol

Relay communication uses HTTPS.

Recommended:

- TLS 1.2+
- TLS certificate validation
- optional certificate pinning for official/default relays
- optional mTLS for trusted federation peers

---

## 2.2 Content Type

All JSON requests with a body must use:

```http
Content-Type: application/json
```

---

# 3. Timestamp Rules

All protocol-level timestamps use:

```text
Unix epoch seconds (UTC)
```

Example:

```json
1716900000
```

Do not use:

- epoch milliseconds
- ISO datetime strings
- local timezone timestamps

This applies to:

- event `ts`
- event `exp`
- request signing timestamp headers
- federation protocol timestamps
- registry protocol timestamps

Implementation note:

- local app/UI/runtime code may use milliseconds internally,
- but relay/federation/registry wire protocol timestamps must remain epoch seconds.

---

# 4. Relay Event Envelope

## 4.1 Canonical JSON Shape

```json
{
  "gid": "group-id",
  "eid": "event-id",
  "ts": 1716900000,
  "exp": 1716903600,
  "body": {
    "v": 1,
    "enc": "AES-256-GCM",
    "kid": "group-key-2026-06",
    "cty": "peermesh/app-event+json",
    "nonce": "base64url-96bit-nonce",
    "ct": "base64url-ciphertext",
    "tag": "base64url-auth-tag"
  },
  "sig": "base64-signature"
}
```

---

## 4.2 Required Fields

| Field | Type | Required | Meaning |
|---|---|---|---|
| `gid` | string | yes | group namespace |
| `eid` | string | yes | globally unique relay event id |
| `ts` | number | yes | creation time, epoch seconds |
| `exp` | number | yes | expiry time, epoch seconds |
| `body` | object | yes | opaque encrypted app payload envelope |
| `sig` | string | yes | event integrity field |

---

## 4.3 Event Invariants

Events must satisfy:

- immutable after creation
- globally unique `eid`
- deterministic client-side serialization
- bounded lifetime via `exp`
- client-verifiable integrity

---

## 4.4 Opaque Payload Rule

Relay nodes must treat `body` as opaque application payload data.

Relay nodes must not:

- interpret app event semantics
- mutate payload content
- require plaintext access
- require internal fields such as `body.nonce`
- run reducers or materialize app state

Client-side app code remains responsible for decrypting, verifying, and reducing events.

---

## 4.5 Encrypted Client Payload Envelope

Production clients must place encrypted application content inside `event.body`.
Relay nodes store and forward this object unchanged.

Canonical encrypted body shape:

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

Fields:

| Field | Type | Required | Meaning |
|---|---|---|---|
| `v` | number | yes | encrypted payload envelope version |
| `enc` | string | yes | AEAD algorithm identifier |
| `kid` | string | yes | application/group key id or rotation id, never the raw key |
| `cty` | string | yes | plaintext content type after decryption |
| `nonce` | string | yes | base64url nonce unique per `kid` |
| `ct` | string | yes | base64url ciphertext |
| `tag` | string | yes | base64url AEAD authentication tag |

Initial supported algorithm:

- `AES-256-GCM`

AEAD additional authenticated data is derived by clients from immutable relay metadata:

```text
gid + "\n" + eid + "\n" + ts + "\n" + exp + "\n" + body.v + "\n" + body.enc + "\n" + body.kid
```

This binds ciphertext to the relay event identity and prevents ciphertext transplant between events or groups.

Relay constraints:

- relays must not require decryption keys,
- relays must not inspect decrypted content,
- relays must not rewrite encrypted body fields,
- relays may reject malformed `body` objects at coarse schema level,
- relays may support plaintext `body` only in explicit dev/test compatibility mode.

Key material is managed by client-side group/session logic and is never sent to relay or registry infrastructure.

---

# 5. Request Signing

## 5.1 Required Headers

Authenticated relay requests must include:

```http
X-Relay-Timestamp: 1716900000
X-Relay-Signature: <hex-hmac-sha256>
```

In registry-managed client access mode, relays may additionally require:

```http
X-Relay-Access-Grant: <registry-issued-client-grant-id>
X-Relay-Access-Token: <registry-issued-client-access-token>
X-Client-Relay-Session: <client-relay-pairwise-session-id>
```

Timestamp format:

```text
Unix epoch seconds (UTC)
```

---

## 5.2 Signature Algorithm

Algorithm:

```text
HMAC-SHA256
```

Signature encoding:

```text
hex lowercase
```

---

## 5.3 Request Target

The signing input uses the HTTP request target:

```text
path + optional query string
```

Examples:

```text
/v1/events:ingest
/v1/events:fetch?gid=group-1&cursor=abc&limit=50
```

Important:

- For `GET /v1/events:fetch`, the query string is included in the signature.
- Clients and servers must use the exact same encoded query string order and escaping.
- Android should build the URL once, derive the same request target string, and sign that exact value.
- Servers should verify using `req.url` or equivalent raw request target.

---

## 5.4 Canonical Signing String

Canonical signing string format:

```text
HTTP_METHOD + "\n" +
REQUEST_TARGET + "\n" +
TIMESTAMP_SECONDS + "\n" +
BODY_SHA256_HEX
```

Example:

```text
POST
/v1/events:ingest
1716900000
1f8ac10f23c5b5bc1167bda84b833e5c057a77d2...
```

---

## 5.5 Body Hash

Request body hash uses:

```text
SHA256(raw request body bytes)
```

Encoding:

```text
hex lowercase
```

Important:

- hash raw bytes exactly as transmitted
- do not pretty-format JSON before hashing
- do not normalize whitespace
- do not re-serialize parsed JSON before hashing
- for GET requests, body bytes are empty

The SHA256 of an empty body is:

```text
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

---

## 5.6 Signature Generation

Pseudo-code:

```text
body_hash = SHA256_HEX(raw_body_bytes)

canonical = METHOD_UPPERCASE + "\n" +
            REQUEST_TARGET + "\n" +
            TIMESTAMP_SECONDS + "\n" +
            body_hash

signature = HMAC_SHA256_HEX(secret, canonical)
```

---

## 5.7 Replay Window

Servers should reject requests when:

```text
abs(server_now_seconds - X-Relay-Timestamp) > allowed_skew_seconds
```

Recommended:

```text
300 seconds
```

Request-level replay protection may additionally use a bounded cache keyed by:

```text
timestamp + signature
```

or:

```text
timestamp + body_hash + request_target
```

Replay protection must not depend on `body.nonce`.

When client-managed relay access is enabled, replay protection should be layered with registry-issued access-grant validation and pairwise session enforcement rather than replacing request signing.

## 5.8 Client-Relay Pairwise Session Establishment

Managed client access requires an explicit session-establishment step after registry bootstrap.

Recommended endpoint:

```http
POST /v1/client/session:open
```

Recommended request:

```json
{
  "grant_id": "cg_01hzt9aa",
  "access_token": "registry-issued-short-lived-client-token",
  "client_instance_id": "android-device-01",
  "relay_id": "relay-vn-01",
  "requested_gids": [
    "group-1"
  ],
  "requested_operations": [
    "ingest",
    "fetch"
  ],
  "client_nonce": "base64-random",
  "client_session_material": {
    "version": 1,
    "mode": "session_binding"
  }
}
```

Recommended success response:

```json
{
  "ok": true,
  "session": {
    "session_id": "crs_01hzt9bb",
    "relay_id": "relay-vn-01",
    "client_instance_id": "android-device-01",
    "allowed_gids": [
      "group-1"
    ],
    "permissions": [
      "ingest",
      "fetch"
    ],
    "issued_at": 1716900010,
    "expires_at": 1716900310,
    "relay_nonce": "base64-random",
    "session_binding_token": "relay-issued-short-lived-session-token",
    "relay_session_material": {
      "version": 1,
      "mode": "session_binding"
    }
  }
}
```

Rules:

- relay MUST validate the registry access grant before opening a pairwise session
- relay MUST reject session-open requests when `relay_id` does not match the current relay
- relay MUST narrow requested gids/operations to the scope already allowed by the registry grant
- a successful `session:open` binds the selected relay to that pairwise session
- one access grant MAY be tried against multiple candidate relays until one accepts or the grant expires
- once a pairwise session is established, the client SHOULD stop trying other candidates with that same active flow unless failover is needed

## 5.9 Pairwise Session Lifecycle

Recommended management endpoints:

```http
POST /v1/client/session/{session_id}:renew
POST /v1/client/session/{session_id}:close
```

Renewal rules:

- pairwise session renewal MUST fail if the underlying registry access grant is expired or no longer valid
- relay MAY shorten renewal TTL below the registry grant TTL due to local load or abuse controls
- client SHOULD renew before 50% of the pairwise session TTL has elapsed

Close rules:

- either side MAY terminate the pairwise session locally at any time
- relay SHOULD treat a closed or evicted session as immediately invalid for subsequent request authorization
- client SHOULD discard cached session state after explicit close or local block
- client MAY locally block a relay after pairwise-session abuse or repeated invalid session behavior
- a local client block SHOULD be time-bounded and policy-driven rather than permanent by default
- recommended local block TTL: 1 to 24 hours
- current app baseline default local block TTL: 6 hours
- implementations MAY treat repeated session integrity failures such as `grant_mismatch`, `client_session_token_mismatch`, or unexpected early `client_session_expired` responses as local block triggers

## 5.10 Request Authentication and Session Interaction

Current baseline:

- `X-Relay-Timestamp` and `X-Relay-Signature` remain required for authenticated relay requests
- registry access grant validation is an authorization layer
- pairwise session validation is a binding/state layer

This means managed client requests should normally carry all of:

```http
X-Relay-Timestamp: 1716900000
X-Relay-Signature: <hex-hmac-sha256>
X-Relay-Access-Grant: cg_01hzt9aa
X-Relay-Access-Token: registry-issued-client-access-token
X-Client-Relay-Session: crs_01hzt9bb
X-Client-Relay-Session-Token: relay-issued-short-lived-session-token
```

Validation order should be:

1. request timestamp / request signature
2. registry access grant validity and scope
3. pairwise session validity and binding to the selected relay
4. request-specific authorization such as `gid`, operation, rate limit, and size limit

Future transport-specific session MAC or derived session-key authentication is allowed, but it MUST be introduced as an explicit protocol version because it changes the wire contract.

## 5.11 Candidate Retry and Failover Rules

Client selection and retry should follow deterministic rules.

Recommended behavior:

- client SHOULD rank candidates using registry order plus local signals such as latency, recent failures, or local block state
- client MUST NOT select a relay outside the candidate set authorized by the registry grant
- if a relay is unreachable or rejects `session:open`, the client MAY try the next candidate while the same grant is still valid
- if all candidates fail, the client SHOULD back off and request a new access grant rather than spinning on the same candidate set indefinitely
- local client block of one relay MUST NOT invalidate the whole grant if other authorized candidates remain
- if a pairwise session was already established and the relay later fails, the client MAY fail over to another candidate only by opening a new pairwise session
- if the registry grant has expired, the client MUST obtain a fresh grant before opening any new pairwise session
- when a client locally blocks a relay due to pairwise-session abuse, it SHOULD skip that relay during candidate retry until the local block expires or is cleared

---

# 6. Ingest API

## 6.1 Endpoint

```http
POST /v1/events:ingest
```

In registry-managed client access mode, this endpoint should require:

- a valid registry-issued client access grant
- a valid pairwise client-relay session

for non-public writes.

---

## 6.2 Request Body

```json
{
  "events": [
    {
      "gid": "group-id",
      "eid": "event-id",
      "ts": 1716900000,
      "exp": 1716903600,
      "body": {},
      "sig": "..."
    }
  ]
}
```

---

## 6.3 Success Response

HTTP:

```http
200 OK
```

Response:

```json
{
  "accepted": [
    "event-id-1"
  ],
  "rejected": []
}
```

---

## 6.4 Queued Success Response

If the server accepts the request for retry/background processing, it may return:

```http
202 Accepted
```

The body must remain app-compatible with `RelayIngestResponse`:

```json
{
  "accepted": [
    "event-id-1"
  ],
  "rejected": []
}
```

Do not return incompatible shapes such as:

```json
{
  "status": "queued",
  "queued": 1
}
```

unless the mobile client model has explicitly been versioned to support it.

---

## 6.5 Partial Failure Response

```json
{
  "accepted": [
    "event-id-1"
  ],
  "rejected": [
    {
      "eid": "event-id-2",
      "reason": "expired"
    }
  ]
}
```

---

## 6.6 Duplicate Event Semantics

Duplicate events should be treated as idempotent success.

Recommended response:

```json
{
  "accepted": [
    "existing-event-id"
  ],
  "rejected": []
}
```

Rationale:

- mobile clients may retry after timeouts,
- federation peers may re-send already-synced events,
- duplicate delivery is expected in delay-tolerant systems.

---

## 6.7 Reject Reasons

Recommended reject reasons:

- `malformed`
- `expired`
- `invalid_signature`
- `rate_limited`
- `too_large`
- `unauthorized`

---

# 7. Fetch API

## 7.1 Endpoint

```http
GET /v1/events:fetch
```

In registry-managed client access mode, private fetch should require:

- a valid registry-issued client access grant
- a valid pairwise client-relay session

when the relay policy does not expose open read access.

---

## 7.2 Query Parameters

| Parameter | Required | Meaning |
|---|---|---|
| `gid` | yes | group namespace |
| `cursor` | no | opaque server cursor |
| `limit` | no | max events to return |

Example request target:

```text
/v1/events:fetch?gid=group-1&cursor=abc&limit=50
```

This full request target must be signed.

---

## 7.3 Fetch Response

```json
{
  "events": [],
  "next_cursor": "opaque-cursor"
}
```

---

## 7.4 Cursor Rules

Cursors are:

- opaque
- node-specific
- monotonic
- retry-safe

Clients must not interpret cursor contents.

---

## 7.5 Pagination

Recommended limits:

- default: 50
- maximum: 200

---

# 8. Federation Authentication

Federation relay communication should use:

- request signing
- rotated federation tokens
- peer allowlists
- optional mTLS

Public federation relays should not accept unauthenticated peer traffic.

Federation authentication is layered on top of the same base request-signing principles defined in this spec.

Client-managed relay access is a separate control-plane layer from federation sessions:

- client relay access grants are issued by the registry for client bootstrap and relay authorization
- pairwise client-relay sessions are established directly between the client and the selected relay
- federation sessions are issued by the registry for relay-to-relay traffic
- neither session type replaces request signing at the HTTP layer

---

# 9. Replay Protection

Replay resistance should include:

- request timestamp validation
- recently-seen request cache
- event id deduplication
- bounded replay window
- optional rate limits and request-size limits

Replay protection must not depend on `body.nonce`.

---

# 10. Error Handling

Relay implementations must tolerate:

- duplicate deliveries
- partial failures
- network partitions
- intermittent connectivity
- retry storms

All operations should remain idempotent where possible.

---

# 11. Compatibility Requirements

Implementations must maintain:

- stable field names
- epoch-seconds timestamps
- stable request target signing rules
- stable signature format
- stable response schemas

Breaking protocol changes require:

- explicit versioning
- migration strategy
- Android/server compatibility tests

---

# 12. Recommended Compatibility Tests

Implementations should include:

- Android-generated payload fixture tests
- request signature test vectors
- cursor pagination tests
- duplicate retry tests
- timestamp skew tests
- malformed payload tests
- GET request-target signing tests
- raw-body hash tests

---

# 13. Example Ingest Request

```http
POST /v1/events:ingest
Content-Type: application/json
X-Relay-Timestamp: 1716900000
X-Relay-Signature: abcdef123456...

{
  "events": [
    {
      "gid": "group-1",
      "eid": "evt-123",
      "ts": 1716900000,
      "exp": 1716903600,
      "body": {
        "type": "message"
      },
      "sig": "payload-signature"
    }
  ]
}
```

---

# 14. Summary

This specification defines the implementation contract between PeerMesh clients and relay infrastructure.

The protocol prioritizes:

- offline-first compatibility
- opaque event transport
- authenticated relay communication
- epoch-seconds timestamp consistency
- retry-safe operation
- federation flexibility
- implementation stability
