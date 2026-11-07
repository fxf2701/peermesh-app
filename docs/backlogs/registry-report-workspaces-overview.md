# Registry Report Workspaces Overview

Status:
- Draft
- Scope: registry-hosted intake and operator handling for report-style workflows

Related documents:
- `docs/backlogs/moderation-v2-backlog.md`
- `docs/moderation/peermesh-play-moderation-model.md`
- `docs/public/peermesh-moderation-policy.md`
- `docs/server/REGISTRY_PROTOCOL.md`
- `docs/server/FEDERATION_PROTOCOL.md`

## 1. Purpose

PeerMesh now needs a clearer cloud-side model for reports and operator review.

Several report families are emerging:

- federation/operator abuse reports
- client moderation reports
- client bug reports
- client feedback submissions

These should not be treated as ordinary relay-federated event data.
They should be handled by the registry server as a control-plane intake authority with explicit storage, audit, and operator workflows.

## 2. Core Direction

The registry server should become the central intake point for report workflows.

This does not change the registry into a message relay or event source of truth.
It means the registry is the designated authority for:

- accepting report submissions
- validating request shape and authenticity
- storing report records
- exposing operator review surfaces
- recording audit history

## 3. Why Registry-Centered Intake

This direction is preferred because:

- responsibility is clear: the app can say reports are sent to a defined platform authority
- Google Play posture is easier to explain than a model where reports may land on arbitrary relay operators
- report privacy is easier to reason about
- operator review and audit can live in one place
- relay federation data-plane stays focused on event transport, not moderation-ticket replication

## 4. Architectural Rule

Reports submitted to the registry must not automatically replicate through relay federation as ordinary transport objects.

In particular:

- a report is not a chat event
- a report is not a federation-synced opaque payload
- a report is not implicitly visible to every relay operator in the network

If any future workflow needs multi-operator visibility, that replication must be explicit, policy-governed, and scoped to the relevant workspace.

## 5. Workspace Model

Each report family should be implemented as a separate registry workspace with its own storage table, validation rules, lifecycle, and operator tooling.

Initial workspaces:

### 5.1 Federation Operator Reports Workspace

Purpose:

- record complaints and abuse signals related to relay and federation behavior

Examples:

- fake relay note
- spam relay note
- relay misconduct complaint
- operator abuse report
- future relay-to-relay or operator governance tickets

Notes:

- this workspace is distinct from low-level scoring or policy-signal tables
- automated abuse signals may later open or enrich operator-review tickets here

### 5.2 Client Moderation Reports Workspace

Purpose:

- receive and process `report peer` and `report message` submissions from apps

Examples:

- report a peer for harassment
- report a message for threat or spam

Notes:

- this is the first workspace that should be completed
- it directly supports the current Android moderation backlog

### 5.3 Client Bug Reports Workspace

Purpose:

- receive app crash reports, client-side error reports, and optional diagnostic bundles

Examples:

- uncaught exception report
- structured crash snapshot
- user-submitted debug package after a failed flow

Notes:

- this workspace is operational, not moderation-oriented
- privacy and retention policy may differ materially from moderation reports

### 5.4 Client Feedback Workspace

Purpose:

- receive product feedback, UX issues, and feature suggestions from the app

Examples:

- user feedback form submission
- feature request
- complaint about confusing UI or degraded flow

Notes:

- this workspace is product-facing rather than enforcement-facing
- workflows may include tagging, triage, and product routing rather than abuse handling

## 6. Storage Direction

Each workspace should have its own primary table.

Recommended initial table shape:

- `federation_operator_reports`
- `client_moderation_reports`
- `client_bug_reports`
- `client_feedback_reports`

This is preferred over one generic `reports` table because:

- payload schemas are materially different
- lifecycle states differ
- privacy requirements differ
- operator permissions may differ
- duplicate rules differ
- retention rules differ

## 7. Shared Infrastructure That Can Still Be Reused

Although each workspace should store data separately, the registry can still share common infrastructure:

- request signature verification helpers
- transient source IP capture for workspace-specific abuse controls where appropriate
- audit logging through registry audit facilities
- pagination and filtering helpers
- admin authentication
- common operator identity and assignment helpers
- common note / label utilities if they prove reusable

The shared layer should not force all workspaces into one flat schema.

## 8. Common Metadata Baseline

Even with separate tables, the following fields should be standardized where practical:

- `report_id`
- `status`
- `created_at`
- `updated_at`
- `assigned_operator`
- `resolved_at`

Additional fields should remain workspace-specific.

Privacy note:

- `source_ip` is not a default report metadata field for all workspaces.
- Workspaces may use raw IP only when there is a concrete abuse-control need, with workspace-specific retention and visibility rules.
- For `client_moderation_reports`, reporter IP is limited to transient rate limiting and is not shown to operators or exported as report evidence.

Examples:

- moderation reports need `reported_peer_id`, `message_id`, `reason_code`
- bug reports need `app_version`, `stack_trace`, `device_info`
- feedback reports need `category`, `message`, `contact_preference`
- federation operator reports may need `relay_id`, `operator_id`, `evidence_ref`

## 9. Lifecycle Principles

Each workspace may define its own detailed state machine, but the general pattern should be:

- accepted into registry
- visible to operators
- triaged
- reviewed
- resolved or rejected

The exact states should remain workspace-specific and not be over-normalized too early.

## 10. Privacy And Trust Model

The registry is the named report authority.

That implies:

- clients should know where reports go
- policy text should describe the registry-side handling model accurately
- reports should not silently fan out across independent relay operators
- optional sensitive evidence must be minimized by default

For client moderation specifically:

- metadata-only by default where possible
- plaintext evidence should remain opt-in where relevant
- operator access should be auditable

## 11. Initial API Direction

Each workspace should expose a distinct ingest route.

Examples:

- `POST /v1/registry/client/moderation-reports`
- `POST /v1/registry/client/bug-reports`
- `POST /v1/registry/client/feedback`
- `POST /v1/registry/federation/operator-reports`

This avoids conflating unrelated workflows and makes policy, validation, and audit behavior easier to reason about.

## 12. Suggested Delivery Order

Recommended implementation order:

1. Client Moderation Reports workspace
2. Client Bug Reports workspace
3. Client Feedback workspace
4. Federation Operator Reports workspace

Reasoning:

- moderation reports already exist on Android and have an immediate product gap
- bug reports are usually the next highest operational value
- feedback is straightforward but lower risk
- federation operator reports will likely require the most policy and workflow design

## 13. Immediate Next Step

The first concrete implementation should define the `client_moderation_reports` workspace in the registry with:

- ingest endpoint
- validation rules
- dedicated persistence table
- duplicate and retry semantics
- admin/operator retrieval path
- audit logging

That work should be treated as the first slice of the broader registry report-workspace architecture.
