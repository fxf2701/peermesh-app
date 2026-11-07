# Client Moderation Reports Workspace Spec

Status:
- Draft
- Scope: registry workspace for `report peer` and `report message`

Related documents:
- `docs/backlogs/registry-report-workspaces-overview.md`
- `docs/backlogs/moderation-v2-backlog.md`
- `docs/moderation/peermesh-play-moderation-model.md`
- `docs/public/peermesh-moderation-policy.md`
- `docs/server/REGISTRY_PROTOCOL.md`
- `android/app/src/main/java/app/peermesh/moderation/ModerationRepository.kt`

## 1. Purpose

This document defines the first dedicated registry report workspace:

- `client_moderation_reports`

Its purpose is to receive, persist, audit, and expose for operator review the moderation reports created by app users through:

- `report peer`
- `report message`

This workspace is the first concrete implementation slice of the broader registry report-workspace architecture.

## 2. Design Goals

The workspace should make client moderation reports:

- end-to-end deliverable from Android to registry
- auditable on the operator side
- privacy-conscious by default
- explicit in lifecycle state
- safe to retry from offline-first clients
- easy to explain in Google Play review

## 3. Non-goals

This workspace does not attempt to provide:

- automatic moderation decisions
- blanket scanning of all private messages
- relay-federated replication of reports
- owner-governance actions such as member removal
- global account-ban infrastructure

## 4. Workspace Boundary

`client_moderation_reports` is a registry-side control-plane workspace.

It is not:

- a relay event stream
- a federation-synced data type
- a generic ticket table for every report family

The registry is the named moderation-report intake authority for this workflow.

## 5. Report Types

This workspace supports exactly two target types:

- `peer`
- `message`

### 5.1 Peer Report

Used when a user reports another peer generally within a group context.

Expected evidence:

- reported peer identity
- group context
- reason
- optional user note

### 5.2 Message Report

Used when a user reports a specific message within a group context.

Expected evidence:

- reported peer identity
- group context
- message identifier
- optional event identifier
- optional plaintext excerpt if the user opted in
- reason
- optional user note

## 6. Architectural Rule

Client moderation reports must not be propagated through relay federation as normal event data.

When the registry accepts a moderation report:

- it stores the report in the moderation workspace
- it records audit history
- it exposes the report to authorized operators

It does not:

- inject the report into relay event logs
- sync the report to independent relay operators by default
- treat the report as a message transport artifact

## 7. Ingest Endpoint

Initial endpoint:

- `POST /v1/registry/client/moderation-reports`

The request should use the same registry request-signature pattern already used by signed client-to-registry requests where configured.

The endpoint should remain distinct from:

- relay/federation abuse report routes
- bug-report routes
- feedback routes

## 8. Client Payload Contract

The Android client already emits a payload equivalent to:

```json
{
  "report_id": "uuid",
  "group_id": "group-id",
  "reporter_peer_id": "peer-id",
  "reported_peer_id": "peer-id",
  "target_type": "peer|message",
  "message_id": "optional-message-id",
  "event_id": "optional-event-id",
  "reason_code": "spam",
  "user_note": "optional note",
  "include_plaintext": false,
  "plaintext_excerpt": "optional excerpt",
  "ciphertext_ref": "optional-event-ref",
  "created_at_ms": 1712345678901,
  "app_version": "x.y.z",
  "protocol_version": 1
}
```

The registry workspace should accept this shape as the baseline contract for V1/V2 completion.

## 9. Validation Rules

At minimum, the registry must validate:

- `report_id` is present and non-empty
- `group_id` is present and non-empty
- `reporter_peer_id` is present and non-empty
- `reported_peer_id` is present and non-empty
- `target_type` is one of `peer` or `message`
- `reason_code` is present and non-empty
- `created_at_ms` is a valid positive integer timestamp
- `protocol_version` is present and supported

Additional target-specific validation:

### 9.1 Peer Report Validation

- `target_type = peer`
- `message_id` must be absent or null
- `event_id` may be absent
- `include_plaintext` must be `false`
- `plaintext_excerpt` must be absent or null

### 9.2 Message Report Validation

- `target_type = message`
- `message_id` must be present and non-empty
- `include_plaintext` may be `true` or `false`
- `plaintext_excerpt` may be present only when `include_plaintext = true`

### 9.3 General Constraints

- `reporter_peer_id` and `reported_peer_id` should not be identical
- `user_note` should be bounded to a safe maximum length
- `plaintext_excerpt` should be bounded to the client-side excerpt limit or lower
- unexpected oversized payloads should be rejected

## 10. Reason Codes

The registry should initially accept the reason codes already exposed in the Android UI:

- `harassment`
- `threatening_behavior`
- `spam`
- `inappropriate_content`
- `impersonation`
- `other`

The server should validate against a fixed allowlist rather than accepting arbitrary free-form reason values.

## 11. Persistence Model

Primary table:

- `client_moderation_reports`

Recommended initial columns:

- `report_id TEXT PRIMARY KEY`
- `group_id TEXT NOT NULL`
- `reporter_peer_id TEXT NOT NULL`
- `reported_peer_id TEXT NOT NULL`
- `target_type TEXT NOT NULL`
- `message_id TEXT`
- `event_id TEXT`
- `reason_code TEXT NOT NULL`
- `user_note TEXT`
- `include_plaintext INTEGER NOT NULL`
- `plaintext_excerpt TEXT`
- `ciphertext_ref TEXT`
- `client_created_at_ms INTEGER NOT NULL`
- `app_version TEXT`
- `protocol_version INTEGER NOT NULL`
- `status TEXT NOT NULL`
- `duplicate_of_report_id TEXT`
- `assigned_operator TEXT`
- `received_at INTEGER NOT NULL`
- `updated_at INTEGER NOT NULL`
- `resolved_at INTEGER`

Transient abuse-control table:

- `client_moderation_report_ip_events`

Purpose:

- use reporter network IP only for short-window rate limiting
- keep raw IP out of the primary report record and operator evidence surfaces
- prune entries outside the abuse-control window

Recommended columns:

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `source_ip TEXT NOT NULL`
- `observed_at INTEGER NOT NULL`

Recommended indexes:

- `(status, received_at DESC)`
- `(group_id, received_at DESC)`
- `(reported_peer_id, received_at DESC)`
- `(target_type, received_at DESC)`
- `(message_id)`
- transient IP events: `(source_ip, observed_at)` and `(observed_at)`

## 12. Duplicate Handling

The client already uses a stable `report_id`, so exact duplicate retries should be idempotent.

Required behavior:

- if the same `report_id` is received again with materially identical payload, return success and mark as duplicate retry of the same logical record
- do not create a second primary report row

Optional near-duplicate collapse may be added later, but should not be required in the first implementation.

Examples of future near-duplicate policy:

- same `reporter_peer_id`
- same `reported_peer_id`
- same `target_type`
- same `message_id`
- same `reason_code`
- within a bounded time window

For the first slice, exact `report_id` idempotency is sufficient.

## 13. Lifecycle States

Initial workspace states should be explicit and operator-meaningful.

Recommended first state set:

- `received`
- `duplicate`
- `in_review`
- `resolved_actioned`
- `resolved_no_action`
- `rejected_invalid`

Notes:

- `received` means accepted and awaiting operator handling
- `duplicate` is optional as a visible status; it may instead be represented through `duplicate_of_report_id`
- `rejected_invalid` is for server-side validation or integrity rejection

Client delivery state should remain separate from operator review state.

## 14. Client Delivery Semantics

The Android client needs a clear answer to:

- accepted
- retry later
- permanently failed

Recommended response semantics:

### 14.1 Accepted

Use:

- `201 Created` for first acceptance
- `200 OK` for exact duplicate retry of an already accepted report

Response body:

```json
{
  "ok": true,
  "report": {
    "report_id": "uuid",
    "status": "received"
  },
  "duplicate_collapsed": false
}
```

or for duplicate retry:

```json
{
  "ok": true,
  "report": {
    "report_id": "uuid",
    "status": "received"
  },
  "duplicate_collapsed": true
}
```

### 14.2 Retryable Failure

Use for:

- transient storage issues
- temporary overload
- timeout conditions
- rate limit conditions when client should retry later

Suggested codes:

- `429`
- `500`
- `502`
- `503`

Android should keep the report queued.

### 14.3 Permanent Failure

Use for:

- invalid payload
- unsupported protocol version
- malformed signature when policy requires signature
- semantically impossible report shape

Suggested codes:

- `400`
- `401`
- `403`
- `409` where appropriate

Android may mark the report as permanently failed.

## 15. Audit Requirements

Every ingest attempt should produce registry audit visibility.

Minimum audit events:

- `client_moderation_report_received`
- `client_moderation_report_denied`
- `client_moderation_report_duplicate`
- `client_moderation_report_status_changed`

Recommended audit metadata:

- `report_id`
- `group_id`
- `reporter_peer_id`
- `reported_peer_id`
- `target_type`
- `result`
- denial reason code where relevant

## 16. Admin And Operator Retrieval

This workspace needs operator-facing retrieval from the start.

Minimum admin capabilities:

- list reports
- filter by status
- filter by target type
- filter by group id
- filter by reported peer id
- view one report in detail

Recommended later capabilities:

- assign report to operator
- add operator notes
- update status
- export redacted records

## 17. Privacy Posture

This workspace should preserve the moderation-policy posture already documented publicly.

Required rules:

- reports are user-triggered only
- no blanket message upload
- plaintext evidence is optional and opt-in
- metadata-only evidence is the default
- operator access should be auditable
- reporter `source_ip` is not moderation evidence and must not be shown to operators
- reporter `source_ip` must not be exported with report evidence or audit review data
- reporter `source_ip` may be used only transiently for rate limiting and must be pruned after the short abuse-control window

The registry should store only the evidence intentionally included in the submitted report payload.

## 18. Security And Abuse Considerations

The workspace should defend against:

- malformed spam submissions
- oversized payload abuse
- replay of the same request
- report flooding
- fake signatures where signature enforcement is enabled

Recommended first-line controls:

- request size cap
- route-level rate limiting
- idempotent `report_id` handling
- signed-request verification when configured
- bounded string lengths

## 19. Relationship To Android Client

The first server implementation should preserve compatibility with the current Android moderation-report sender wherever reasonable.

That means:

- keep the current route path
- keep the current payload field names
- avoid requiring new mandatory client fields for the first backend slice

If a client contract change becomes necessary later, it should be versioned rather than introduced implicitly.

## 20. Suggested Implementation Order

1. Add `client_moderation_reports` table and indexes in registry DB.
2. Add validation and insert logic in a dedicated registry module.
3. Add `POST /v1/registry/client/moderation-reports`.
4. Add idempotent `report_id` duplicate handling.
5. Add registry audit events for accept and deny paths.
6. Add admin list/detail retrieval endpoints.
7. Add minimal admin UI page for operator review.

## 21. Exit Criteria

This workspace should be considered complete enough for the first milestone when:

- Android `report peer` is accepted end-to-end by the registry
- Android `report message` is accepted end-to-end by the registry
- duplicate retries do not create duplicate rows
- client can distinguish accepted vs retryable vs permanent failure
- operators can retrieve and inspect submitted reports
- reports remain outside relay federation data-plane
- audit history exists for ingest and review actions
