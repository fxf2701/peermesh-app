# Project Checklist — Release + First 100 Paid Users

Purpose:
- Consolidate the actionable work from `docs/milestones/*.md` into one project-level checklist.
- Separate work that is already done, still open, or only partially complete.
- Highlight where current milestone docs are strong (engineering) and where they are missing (go-to-market / paid-user growth).

## 1) Already Done

### Relay backend and operator console
- Phase 1 baseline relay server is complete.
- Phase 1.1 relay hardening is complete.
- Phase 1.2 production hardening is complete.
- Phase 1.3 community ops is functionally closed, with some accepted pending tests and documentation-only follow-ups.
- Phase 1.4 Admin UI is largely complete:
  - Federation peers, detail pages, capacity/heartbeat, join flows.
  - Banlist, reputation, audit trail, metrics dashboard, alerts/incidents, settings.
  - Backup/retention/cleanup visibility in UI.

### Android app foundation
- Android project setup, local DB, crypto model, ingest pipeline, reducers, relay sync base flow, and large parts of Chat / Map / Trails UX are implemented.
- Relay test deployment on mini PC + Funnel is mostly in place.
- Core Android UI foundation is in place; remaining UI polish and validation are now tracked in `Phase 3.1`.

### DTN/carrier architecture
- Core DTN foundation is implemented:
  - envelope/metadata normalization
  - DTN control protocol foundations
  - Room persistence
  - inventory/query support
  - observability foundation
- Carrier architecture and many transport hardening tasks are already done.

## 2) Still Needed Before Release

These are the highest-signal open items still standing between the current state and a reliable public release.

### A. Release-critical mobile reliability
- Finish Android background jobs:
  - cleanup job
  - expired entity marking
  - retention/tombstone handling
  - periodic sync worker
  - BLE ingress backpressure
  - WorkManager scheduling
- Close remaining Phase 2 runtime/release items:
  - decide whether request signatures stay disabled for relay test or are implemented in app
  - ensure the app uses the HTTPS Funnel relay URL for remote testing
- Finish Android tests and release gates:
  - reducer / schema / TTL unit tests
  - BLE ingest integration tests
  - relay sync integration tests
  - cleanup integration tests
  - 2-3 device field tests
  - noisy mesh / duplicate storm tests
  - log counters and QA visibility

### B. Release-critical DTN validation
- Complete open Phase 3 DTN work:
  - prove full control-plane on physical devices beyond `hello`
  - close opaque retry lifecycle on successful carrier delivery
  - instrument success-side opaque delivery bookkeeping
  - re-run carrier-success validation after instrumentation
  - re-validate stale-target fallback under churn
  - re-enable and validate `SYS.ACK`
  - add top-level capsule metadata for `sid` / `rid`
- Complete Phase 3 scenario validation:
  - delayed delivery robustness
  - power cycle recovery
  - rogue/spam resistance
  - sparse propagation
  - multi-radio integration

### C. Release-critical UX validation
- Finish Phase 3.1 physical-device UX validation:
  - BLE header control discoverability
  - readability during churn / recovery
  - tap/hold validation after transport patches
  - non-member carrier mode copy/state validation
  - Cloud Sync switch visual validation
  - Pro unlock CTA validation on Play-enabled build
  - Trails local-only copy validation
- Finish Android UI carry-over items now tracked in `Phase 3.1`:
  - relay sync toggle outside Test screen
  - relay status refresh on more screens
  - permissions/system section verification
  - TTL/freshness/corroboration visibility
  - neutral wording / no authority signals
  - limitations section if required for Emergency flavor
  - marker tooltip and map/trail presentation polish

### D. Release ops / governance gaps still open
- Phase 1.4 items deferred to Phase 3 remain open:
  - RBAC + multi-approver workflow for bulk bans/imports
  - protected peers / trust scoring
  - banlist diff review
  - optional allowlist mode
  - auto topology / peer selection
  - concrete retention profiles

## 3) Partially Done / Needs Closure

These are not fully open from zero, but they are not safely “done done”.

### Relay ops items marked partial in Phase 1.3
- Production backup automation
- Offsite backup automation
- Secret management guidance
- Token rotation playbook
- Hardened metrics exposure
- Peer reputation signals
- Admin UI auth

### Android items marked partial in Phase 2
- BLE payload/chunk TTL tuning
- Samsung GATT 133 churn mitigation

### Android UX/UI backlog now moved to Phase 3.1
- Relay sync toggle outside the Test screen
- Deleted placeholders / TTL / freshness / corroboration UI
- Neutral wording and no-authority presentation
- Permissions/System section verification
- Map tooltip / trail rendering polish / optional clustering decisions

## 4) Definition-of-Done Items Not Yet Met

Per Phase 2 docs, these are still explicitly unchecked:
- two Android devices can join the same group and exchange chat + marker over BLE
- relay push/pull works online without duplicates
- dedupe holds under flood
- TTL + cleanup behave correctly
- app remains useful offline
- app does not crash under heavy traffic
- DB rebuild is correct after restart
- BLE foreground UX is stable and clear
- battery usage is acceptable
- unit/integration/field tests are green
- counters for received / accepted / duplicate / forwarded are visible

Until these pass, the project is not at a defensible release state.

## 5) Missing From Current Docs For “100 Paid Users”

Current milestone docs are strong on engineering and ops, but they do not yet define the commercial work needed for first paid-user acquisition.

Missing workstreams:
- target customer definition and ICP
- pricing / packaging / free-vs-pro boundaries
- onboarding funnel and activation metrics
- billing production checklist
- Play Store / app listing conversion plan
- landing page and waitlist / email capture
- analytics for acquisition, activation, retention, conversion
- support process and feedback intake
- launch plan, beta cohort, and early adopter outreach
- retention loop for first 100 paying users

## 6) Recommended Project-Level Priority Order

### P0 — must finish for release
- Phase 2 reliability + tests + field validation
- Phase 3 DTN physical validation and ACK/bookkeeping closure
- Phase 3.1 device-level UX validation
- Phase 2 Definition-of-Done items

### P1 — should finish before wider rollout
- Close partial Phase 1.3 / Phase 2 items that affect operations or trust
- Production billing / Pro unlock validation
- In-app transparency items: TTL, freshness, limitations, neutral wording

### P2 — needed for first 100 paid users
- Create a separate growth/commercial milestone:
  - pricing
  - monetization funnel
  - analytics
  - launch assets
  - customer support workflow
  - beta-to-paid conversion plan

## 7) Practical Conclusion

Based on current milestone docs:
- Relay backend/admin side is largely ready.
- Android client and DTN reliability are not yet release-ready.
- The docs do not yet contain a complete plan for getting the first 100 paid users.

So the remaining project work splits into two tracks:
- release-readiness track: mostly `Phase 2`, `Phase 3`, and final `Phase 3.1` validation
- paid-user growth track: largely missing from current milestone docs and needs its own milestone plan
