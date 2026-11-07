# Phase 3 Task List (DTN Layer Implementation)

Purpose:
- This file tracks only milestone-level Phase 3 tasks.
- Detailed implementation history, investigations, and physical-test snapshots live in `docs/tech_notes/DTN_DEVELOPMENT_NOTES.md`.
- Blueprint reference: `docs/specs/DTN_IMPLEMENTATION_BLUEPRINT.md`.

## 0) Scope and Tracking Rules

- [x] Phase 3 scope is DTN Store-Carry-Forward on mobile + BLE transport.
- [x] `docs/specs/DTN_IMPLEMENTATION_BLUEPRINT.md` is the primary implementation blueprint.
- [x] `docs/tech_notes/DTN_DEVELOPMENT_NOTES.md` is the running snapshot of progress and evidence.

## 1) Blueprint Core Delivery

- [x] Task A - Envelope and DTN metadata normalization
- [x] Task B - DTN control protocol and sync engine foundations
- [x] Task C - Room DTN state schema and persistence
- [x] Task D - Event/inventory query support for DTN and carrier flow
- [x] Task F - DTN observability and debug/reporting foundation

## 2) Carrier Architecture

- [x] Opaque carrier store for encrypted app-batch / envelope forwarding
- [x] Manifest key exchange and signed manifest offer / accept flow
- [x] Carrier DATA sender and receiver validation path
- [x] Opaque capsule forwarding path for non-member carrier nodes
- [x] Multi-scope DTN hello discovery for carrier-held gids
- [x] Carrier-only BLE mesh lifecycle independent of active group
- [x] Carrier hygiene guardrails: TTL, hop count, storage caps, dedupe/rate limits

## 3) Transport Hardening Completed

- [x] Canonical device identity unification (`peer_id == device_id`, `did/sid/rid`)
- [x] BLE address rebind / stale address recovery before first write
- [x] Last-hop identity fallback for relayed app batches
- [x] Queue watchdog delay for held packets to reduce false `chunks=0` timeout
- [x] Env-blocked debounce / active-work grace to reduce interrupted transport stop
- [x] Cross-peer active-lane progress grace for `queued_no_write_attempt_timeout`
- [x] `held/staged -> connect` scheduling so queued work can earn a fresh GATT lane before first write
- [x] Source-side persistent outbound cleanup after first peer delivery
- [x] Opaque replay bookkeeping improvements
- [x] Fallback from stale explicit preferred targets to auto selection
- [x] Keep `chunks=0` opaque retry failures retryable instead of forcing long cooldown
- [x] DTN report Phase 1 sections: Delivery, Carrier, Source-side Noise, Per-batch Verdict
- [x] DTN report Phase 2 sections: Queue Health, Transport Stability, Routing / Identity
- [x] DTN report now scores delivery by `Received message at`, with `Rendered message at` treated as UI-only evidence
- [x] DTN report BLE mesh state timeline now includes churn/restart summary and timeline verdicts
- [x] DTN report now excludes `SYS.ACK` control traffic from user-visible delivery scoring and adds a separate `ACK Activity` section
- [x] DTN report delivery parser now correlates non-`Test ...` user messages correctly
  - Fixed in:
    - `scripts/debug-kit/mesh_debug.sh`
  - Validated by:
    - `logs/analysis/dtn_round_20260523_033800_332849735_2.md`
  - Result:
    - `Delivery` now correctly reports `A -> B = 3/3` for `DTN gap 2: A #1..#3`

## 4) Current Open Milestone Tasks

- [x] Priority: decouple BLE mesh-local runtime from cloud relay / entitlement / background orchestration
  - Goal:
    - keep local BLE mesh delivery stable even when relay sync lifecycle, entitlement refresh, or app foreground/background transitions change
  - Status:
    - implementation complete; physical validation remains tracked in development notes

- [ ] Priority: harden peer-session arbitration so app-data in flight outranks broad DTN control backlog
  - Goal:
    - if `C -> B` is already relaying app data, the same peer session must not be flooded first by large `INV_HAVE` / `MANIFEST_*` backlog
  - Status:
    - in progress; DTN session queue foundation exists but control-vs-relay arbitration is not yet strong enough on the same peer session

- [ ] Priority: soften stalled recovery before full transport reset
  - Goal:
    - avoid resetting the whole transport too early when a relay leg has made recent progress and the peer is still nearby
  - Status:
    - open; failure mode has been reproduced and analyzed, but no direct transport patch has landed yet

- [x] Prove full DTN control/data path on physical devices with forced inventory mismatch
  - Goal:
    - prove `hello -> inventory -> want -> data -> ack` on real devices
  - Status:
    - complete; evidence is tracked in development notes

- [x] Close opaque retry lifecycle on successful carrier delivery
  - Goal:
    - make success-side opaque retry cleanup match real successful carrier forward
  - Status:
    - complete; markers and validation references are tracked in development notes

- [x] Instrument opaque delivery row lifecycle for success-side bookkeeping
  - Goal:
    - expose opaque success lifecycle strongly enough to debug carrier success cleanup
  - Status:
    - complete; implementation detail lives in development notes

- [x] Re-run a strong carrier-success round after opaque bookkeeping instrumentation
  - Goal:
    - confirm the new opaque lifecycle instrumentation against a strong carrier-success round
  - Status:
    - complete; evidence is tracked in development notes

- [ ] Re-validate stale-target fallback under round-2 topology churn
  - Goal:
    - confirm stale preferred-target fallback works under topology churn
  - Status:
    - postponed; no longer blocks the current hybrid transport milestone

- [x] Codify `mesh-first, DTN-repair-second` runtime policy
  - Goal:
    - make mesh the explicit fast path for nearby / relay delivery,
    - make DTN the explicit repair / reconciliation path for missed batches,
    - suppress unnecessary duplicate delivery across both paths once success is known.
  - Status:
    - complete; validation detail and follow-up notes live in development notes

- [x] Add DTN outstanding-want memory by `event ID`
  - Goal:
    - track missing-interest by stable payload identity instead of transport container identity
  - Status:
    - complete; follow-up detail is tracked in development notes

- [x] Add `want > have/backlog` DTN control priority
  - Goal:
    - serve explicit `INV_WANT` before broad backlog advertisement
  - Status:
    - complete; follow-up detail is tracked in development notes

- [x] Add per-peer DTN session task queue
  - Goal:
    - stop mixing app-data relay and large DTN control bursts on the same short-lived peer session
    - enforce one active DTN control task at a time per `peer + gid + sessionId`
    - cleanup session-local queue state on disconnect
  - Status:
    - complete at DTN-control level; remaining transport-level arbitration work stays open under the peer-session arbitration priority task

- [x] Re-enable and validate `SYS.ACK`
  - Goal:
    - restore ACK control traffic and validate end-to-end return-path behavior
  - Status:
    - complete; validation detail is tracked in development notes

- [ ] Add top-level opaque capsule metadata for `sid` / `rid`
  - Goal:
    - persist original source device id and most recent relay device id explicitly in durable capsule metadata for stronger anti-loop filtering and per-batch path inference.
  - Status:
    - open

- [ ] Follow-up: refactor relay server to match current app cloud-relay contract
  - Goal:
    - make server ingest/fetch behavior compatible with the app-owned `RelayEvent` / `RelayFetchResponse` contract without forcing app schema or protocol changes first
  - Status:
    - open; detailed server-refactor backlog lives in development notes

## 5) Scenario-Level Progress Against Blueprint

- [x] Scenario 4 baseline: carrier-only C can stay alive, store opaque payloads, and replay traffic between partitions
- [ ] Scenario 4 completion: robust end-to-end delayed delivery without shallow control-plane behavior
- [ ] Scenario 5 - Node power cycle recovery
- [ ] Scenario 6 - Rogue node spam / abuse resistance
- [ ] Scenario 7 - Sparse environment / low-density propagation
- [ ] Scenario 8 - Multi-radio integration

## 6) Current Physical Validation Focus

- [ ] Round validation: `C always on`, `A/B alternate Bluetooth`
- [ ] Round validation: peer-session queue reduces relay/control lane contention
  - Goal:
    - confirm that when `C -> B` has app-data in flight, `B` no longer floods the same session with large `inv_have` / `manifest_*` backlog first
    - confirm fewer `stalled_write -> transport_stop` failures during delayed relay
  - Current target scenario:
    - `A` creates data alone
    - `A` meets `C`
    - `C` later meets `B`
- [x] Round validation: baseline clean steady-state carrier-success round
  - Baseline:
    - `logs/analysis/dtn_round_20260521_120558_498542893_17537.md`
- [x] Round validation: post-cleanup app-transport sanity round
  - Reference:
    - `logs/analysis/dtn_round_20260522_223856_204219975_16408.md`
  - Interpretation:
    - targeted DB cleanup removed major stale backlog noise
    - app transport recovered to `10/10` both directions
    - DTN still stalls after `inv_have recv`
- [ ] Round validation: `A/B always on`, `C Bluetooth power cycle`
- [x] Round validation: topology relay with `A/B` separated `13m-14m`, `C` in the middle
  - Best current evidence:
    - `logs/analysis/dtn_round_20260521_132951_973827175_23094.md`
  - Interpretation:
    - relay through `C` is validated
    - `Received message at` is the correct delivery signal for this scenario
- [ ] Round validation: strong carrier-success bookkeeping round
  - Current preferred baseline:
    - `logs/analysis/dtn_round_20260521_120558_498542893_17537.md`
- [ ] Round validation: stable `inv_have recv -> inv_want` progression after DB cleanup
  - Goal:
    - prove the remaining blocker is runtime DTN behavior, not stale DB backlog
  - Current best evidence:
    - `logs/analysis/dtn_round_20260522_223856_204219975_16408.md`
  - Status:
    - `inv_have recv` reached
    - `inv_want` not yet stable
- [x] Round validation: forced-mismatch DTN proof round
  - Reference:
    - `logs/analysis/dtn_round_20260523_013818_861433545_2.md`
  - Result:
    - proved `inv_have -> inv_want -> want load -> data lookup -> data recv`
- [x] Round validation: app ACK return-path proof round
  - Reference:
    - `logs/analysis/dtn_round_20260523_033800_332849735_2.md`
  - Result:
    - proved `SYS.ACK delivered` and `SYS.ACK seen` from B back to A
- [x] Round validation: BT/Environment churn diagnostic round with timeline evidence
  - Reference:
    - `logs/analysis/dtn_round_20260521_121732_325060007_18639.md`
- [ ] Compare new reports against:
  - `logs/analysis/dtn_round_20260518_004326_074688230_28354.md`
  - `logs/analysis/dtn_round_20260518_010031_219000366_29839.md`

## 7) Notes

- Obsolete or deeply detailed subtasks were intentionally removed from this milestone file.
- If a task needs rationale, root cause, or patch-by-patch history, look in `docs/tech_notes/DTN_DEVELOPMENT_NOTES.md`.
