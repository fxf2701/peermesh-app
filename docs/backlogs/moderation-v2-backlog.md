# Moderation V2 Backlog

Status:
- Draft
- Scope: post-V1 moderation hardening and completion work

Related documents:
- `docs/moderation/peermesh-android-moderation-implementation-spec.md`
- `docs/private/remove_group_members/join_by_request_and_member_removal_design.md`
- `docs/public/peermesh-moderation-policy.md`
- `docs/public/peermesh-group-membership-and-removal-policy.md`
- `docs/problems/member_removal_control_event_ble_reliability_gap.md`
- `docs/problems/group_members_list_incomplete_on_some_clients.md`
- `docs/problems/sys_metadata_profile_delivery_gap.md`

## 1. Purpose

Moderation V1 established the minimum baseline:

- local block
- report peer
- report message
- owner remove member
- removed-member enforcement on synced clients
- rejoin prevention through removal + rekey behavior

Moderation V2 should close the main product, protocol, and reliability gaps that remain after V1 so the moderation system is coherent end-to-end.

The goal is not to introduce blanket surveillance or centralized message scanning.
The goal is to make moderation and group governance:

- more reliable
- easier to operate
- clearer to users
- more defensible for Google Play review

## 2. Target Outcome For V2

Moderation should feel complete enough that:

- normal users can protect themselves locally
- owners can govern group membership with predictable results
- removed users cannot silently bypass removal
- owner-approved rejoin is possible but tightly scoped
- cloud reporting is auditable and operationally usable
- UI surfaces show consistent membership state
- transport convergence for critical control events is materially stronger than ordinary traffic

## 3. V2 Scope

### 3.1 Owner-approved rejoin flow

Implement the future rejoin model already outlined in the specs:

- removed member opens invite / QR
- app does not rejoin immediately
- app emits `SYS.MEMBER.REJOIN.REQUEST`
- owner sees pending rejoin request
- owner may approve a specific request
- app emits `SYS.MEMBER.REJOIN.APPROVED`
- approval is bound to `rejoin_request_id`
- removed state clears only after successful rejoin completion

Required behavior:

- request TTL: 24h
- approval TTL: 24h
- one active request per removed peer per group
- one active approval per request
- only current owner can approve
- approval is not a generic override for old invite material

### 3.2 Moderation report completion

Complete the report pipeline beyond the current client queueing baseline:

- registry / backend ingest for moderation reports
- operator-side storage and auditability
- clear report lifecycle states
- duplicate handling and retry semantics
- delivery observability

### 3.3 Membership and moderation UX hardening

Finish the governance UX around group membership:

- owner rejoin-request review surface
- pending / approved / expired request states
- clear copy for removed, pending rejoin, and rejoined states
- better explanation of what local block does versus what owner removal does
- fallback and disabled-state UX when deep links open stale screens

### 3.4 BLE / DTN control-event reliability hardening

Make these event types behave as critical control traffic:

- `SYS.MEMBER.REMOVED`
- `SYS.GROUP.REKEY`
- `SYS.MEMBER.REJOIN.REQUEST`
- `SYS.MEMBER.REJOIN.APPROVED`

They should have:

- strongest priority
- long retention
- explicit resend / replay support
- strong logging and traceability

### 3.5 Membership state consistency

Resolve current inconsistencies where membership-derived UI can disagree with visible group activity:

- members list must converge correctly
- owner must see the correct active member set
- removed / rejoined users must appear with correct state

## 4. Open Issues To Resolve In V2

### 4.1 Rejoin request authenticity and abuse control

Open questions:

- how should the owner trust that a rejoin request really belongs to the removed actor?
- how should replayed or duplicated rejoin requests be suppressed?
- what is the anti-spam policy if a removed user keeps scanning invites from many devices or actors?

Needed work:

- define request identity and actor binding precisely
- define duplicate suppression rules
- define local rate limits for request creation and approval

### 4.2 Invite material binding after rekey

Open questions:

- how is invite material bound to the current key epoch?
- when does old invite material become invalid?
- can a valid member safely invite a new member immediately after a removal, or only after the new invite material is definitely active?

Needed work:

- formalize epoch-bound invite material
- define invalidation timing for old invite material
- define local UX if rotation has not converged yet

### 4.3 Reliability of removal and rekey shipping

Known issue:

- control events do not yet ship reliably enough across BLE mesh in every test round

Needed work:

- strengthen transport priority
- strengthen resend / replay behavior
- add event-level observability from creation to ingest
- define repair behavior when a client is missing prerequisite control events

### 4.4 Members list correctness

Known issue:

- members list can be incomplete on some devices, including owner

Needed work:

- unify active-actor resolution logic
- harden metadata delivery
- add fallback member derivation when profile metadata is late or missing
- ensure removal / rejoin state is reflected consistently across UI

### 4.5 Report backend completeness

Known issue:

- client-side report flow exists, but backend moderation-report path is not yet complete enough end-to-end in the repo

Needed work:

- implement moderation report endpoint and persistence
- define schema and validation for peer and message reports
- define duplicate behavior
- define admin / operator retrieval path
- define privacy-preserving export and audit handling

### 4.6 Report retry and delivery semantics

Known issue:

- current retry behavior is functional but not robust enough for mixed success / failure batches

Needed work:

- improve retry policy for temporary failures
- define exponential backoff or bounded retry policy
- expose meaningful delivery states for diagnostics
- ensure operator-facing evidence that a report was accepted or permanently failed

### 4.7 Cross-surface moderation consistency

Open questions:

- should removed users be able to perform any remaining moderation actions in stale surfaces?
- should report / block actions remain available from map, trail, or member cards for a removed user?
- should owner moderation tools be surfaced from one canonical place only?

Needed work:

- inventory every moderation entry point
- apply consistent capability gating
- define one canonical owner-governance flow and one canonical personal-safety flow

### 4.8 User messaging and policy copy

Open questions:

- what exact wording best explains local block, remove, rejoin request, and approval?
- how should the app explain delayed convergence in offline conditions without undermining trust?

Needed work:

- refine user-facing copy
- keep public policy aligned with shipped behavior
- prepare concise Google-review explanations for V2 changes

### 4.9 Operator evidence privacy hardening

Decision:

- reporter `source_ip` is not moderation evidence for ordinary operator review
- reporter `source_ip` must not be shown in the registry operator UI
- reporter `source_ip` must not be exported with report evidence
- reporter `source_ip` may be used only for transient abuse-control rate limiting
- raw reporter IP must not be retained beyond the short abuse-control window

Implemented baseline:

- `client_moderation_reports` no longer stores raw reporter IP in the primary report record
- legacy `source_ip` values on existing moderation report rows are nulled during registry DB initialization
- a dedicated transient IP event table supports short-window rate limiting
- operator detail/evidence surfaces no longer expose `source_ip`

Follow-up:

- if a future forensic workflow needs IP visibility, define it as a separate higher-privilege workspace with explicit audit, retention, and privacy-policy review

## 5. Suggested Workstreams

### Workstream A: Rejoin protocol and state machine

- define final event payloads
- define local DB state
- implement owner approval UX
- implement removed-state clearing rules
- add tests for request / approval / expiry / duplicate suppression

### Workstream B: Critical control-event transport hardening

- prioritize `removed`, `rekey`, `rejoin request`, `rejoin approved`
- add stronger replay / retry paths
- add activity-log markers and debug tooling
- add repeated real-device transport test matrix

### Workstream C: Membership UI consistency

- unify active-member derivation
- repair metadata gaps
- harden members list, group settings, and owner moderation views

### Workstream D: Report pipeline completion

- implement backend route and storage
- add report delivery / retry tests
- define operator audit and review path

## 6. Manual QA Needed For V2

At minimum, V2 should have repeated real-device QA for:

- owner removes B, B requests rejoin, owner approves, B rejoins successfully
- owner removes B, approval expires, B remains removed
- non-owner tries to approve rejoin and fails
- removed user scans old invite and cannot bypass owner approval
- valid member invites new user after removal + rekey without breaking existing member visibility
- members list remains correct across A / B / C after remove, rekey, and rejoin
- moderation report still works correctly through removal / rejoin edge cases

## 7. Exit Criteria For Calling Moderation “More Complete”

Moderation should not be called complete just because V2 code exists.

A stronger exit bar is:

- owner-approved rejoin works and is request-bound
- removed users cannot re-enter through stale invite material
- critical control events converge reliably across repeated BLE runs
- members list is trustworthy for owner governance
- report peer / report message are end-to-end complete with backend ingest
- public policy docs match actual shipped behavior

## 8. Non-goals For V2

Moderation V2 still does not need to include:

- proactive centralized scanning of private chats
- global account bans across all groups
- large-scale trust-and-safety operations tooling
- automated content classification

Those can be considered later only if product and policy needs actually require them.
