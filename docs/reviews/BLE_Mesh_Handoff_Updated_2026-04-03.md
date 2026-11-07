# BLE Mesh Handoff - Updated 2026-04-03 (AI-Ready)

Normative quality gate: `docs/specs/ble_dtn_transport_kpi_slo.md`. Use that
contract for operating-condition classification, bounded-latency targets,
required evidence, stability-first decisions, and acceptance outcomes. This
historical handoff does not override it.

## 🔴 Current Reality (Must Understand First)

System is **NOT blocked anymore**, but also **NOT stable yet**.

### What is working
- Direct path (A → B/C) can reach high coverage when lanes are healthy
- Sender-side batching, queueing, retry separation (FRESH vs BACKLOG) are correct
- Peer delivery ledger is in place and usable
- Delayed relay is now observable (scheduled / dropped)

### What is NOT working yet
- First batch (#1) can still die on BOTH peers
- Direct path becomes asymmetric after early burst
- Relay is **NOT rescuing gaps yet**
- Delayed relay mostly **drops without firing**

👉 **Conclusion:**  
System is now in **diagnosable state**, but not yet in **self-healing state**

---

## 🧠 Mental Model (Important for new devs & AI)

Think of system in layers:

### Layer 1 — Direct Path (PRIMARY)
- A sends directly to B & C
- This decides 80–90% of outcome

### Layer 2 — Relay (RECOVERY)
- B/C forward only if peer is missing
- Currently weak / incomplete

### Layer 3 — Delayed Relay (RESCUE)
- Runs after ~10s
- Only if peer still missing
- Currently: mostly skipped

👉 DO NOT treat relay as main path.  
👉 Always fix direct path first.

---

## 🔥 Current Top Problems (Updated)

### 1. First-batch dual failure still exists
- `queued_no_progress_timeout 0/75`
- Happens on BOTH peers
- Breaks entire burst early

### 2. Lane asymmetry after early burst
- From ~#5 onward:
  - only 1 peer stays active
  - other peer starves or times out

### 3. Rotation damage still real
- `device_id_rotated`
- `status_133`
- kills one lane mid-burst

### 4. Relay not yet rescuing
- still depends on:
  - `source_device_id`
  - candidate selection
- delayed relay:
  - scheduled ✅
  - fired ❌
  - mostly dropped

---

## 📊 Latest Behavior Pattern (Important)

### What we now observe

- Delayed relay:
  - scheduled correctly
  - dropped with reason: `target_no_longer_missing`

👉 Meaning:
- Either:
  - direct path already delivered
  - OR missing detection is inaccurate
  - OR timing window too late

---

## 🧱 Architecture Summary

### Sender
UserOutbox → Dispatcher → EnvelopeSender → AppBatcher → Transport

### Receiver
Transport → Reassemble → Ingest → DB → UI → Optional relay

### Storage
- OutboundBatchEntity
- PeerDelivery ledger (PENDING / DELIVERED / FAILED)

---

## ✅ What is DONE (Do NOT revisit)

- Batch model (APP_BATCH v2)
- ACK disabled
- FRESH vs BACKLOG
- Timestamp correctness
- No-target backoff
- Duplicate complete fix
- Peer delivery ledger

---

## ❌ What is STILL OPEN

- First batch reliability
- Rotation-safe sending
- Missing-peer-only relay
- True relay recovery
- Stable 10/10 coverage

---

## 🧭 Two-Branch Plan (Still VALID)

### Branch 1 — Direct Path Hardening (PRIORITY 1)

Focus:
- first batch reliability
- lane stability
- rotation recovery

#### Must do:
- stronger warmup gate
- early fail + requeue for first batch
- prevent 0/75 stall

👉 This is STILL the most important branch.

---

### Branch 2 — Missing-Peer-Only Relay (PRIORITY 2)

Focus:
- relay only to missing peer
- NOT nearby-first

#### Must do:
- use peer delivery ledger strictly
- never relay to DELIVERED peer
- tighten relay seed

---

## 🆕 Branch 3 — Delayed Rescue Relay (NEW)

### Goal
Recover missed batches after direct path

### Current state
- scheduling works
- dropping works
- firing NOT proven

### Important rule
Delayed relay is:
- NOT primary path
- NOT broadcast
- ONLY rescue

---

## 🚫 IMPORTANT: What NOT to build yet

Do NOT implement:

- delayed_relay_queue (DB)
- persistence layer for relay
- complex retry trees

👉 Reason:
Delayed relay has NOT proven effectiveness yet

---

## ✅ When to build delayed_relay_queue

ONLY if:

- delayed relay fires successfully
- and improves coverage
- and survives multiple rounds

Then:
- move to DB queue
- add retry + TTL

---

## 🎯 Immediate Next Steps (Clear for team)

### Step 1 — Fix direct path asymmetry
Investigate:
- why only 1 lane survives after #5
- why second peer drops out

### Step 2 — Fix first-batch dual fail
Goal:
- zero occurrences of 0/75 on both peers

### Step 3 — Observe delayed relay (DO NOT change yet)
Look for:
- first appearance of "fired"
- cases where it rescues missing batch

### Step 4 — Improve missing-peer detection
Possible issue:
- peer marked "not missing" too early

---

## 🆕 Update 2026-04-13 (3 latest rounds)

### Scope reviewed
- Round 1: `20260413_172258/172259/172303` (A:10, B:5, C:5)
- Round 2: `20260413_174820/174822/174822` (A:5, B:5, C:5)
- Reference before that: round immediately prior with `compact matrix` baseline

### Stable findings across rounds
- `queued_no_progress_timeout` is still the dominant failure mode.
- Failures are often followed by retry loops, then `broadcast_rejected`/`transport skipped`, and message remains missing.
- Relay path is active but not consistently rescuing missing peers.
- Source identity on relay path is frequently soft/unknown (`source_device_unknown_soft`), reducing deterministic routing quality.

### Regressions / high-risk symptoms
- Sender B path is the most unstable:
  - round `17:22` lost `B->A` for 3/5.
  - round `17:48` lost `B->A` and `B->C` for 5/5 (total collapse for B-originated messages).
- A-originated messages still show burst asymmetry:
  - one message can be lost to both peers (`A #2` in `17:48` round).
- C-originated path improved vs previous round, but still shows timeout-heavy retries before convergence.

### Operational conclusion
- System is **partially recoverable**, not reliably convergent.
- Current bottleneck is no longer envelope format/ledger basics; bottleneck is **dispatch-progress failure handling and per-peer completion semantics**.

---

## 🆕 Update 2026-04-15 (round `20260414_235447/235446/235445`)

### Scope reviewed
- Sender log: `logs/device-A_activity_log_20260414_235447-1c984fad.txt`
- Receiver logs: `logs/device-B_activity_log_20260414_235446-3813ed13.txt`, `logs/device-C_activity_log_20260414_235445-652d4ded.txt`

### Before vs after snapshot (aggregate 3 devices)
- Baseline round (`22:59`):
  - `queued_no_progress_timeout=51`
  - `BLE batch send failed=27`
  - `APP dispatch job failed=26`
  - `APP dispatch job completed=36`
  - `BLE connect cap blocked=58`
  - `status=133=24`
- Current round (`23:54`):
  - `queued_no_progress_timeout=0`
  - `BLE batch send failed=5`
  - `APP dispatch job failed=5`
  - `APP dispatch job completed=24`
  - `BLE connect cap blocked=87`
  - `status=133=0`

### Confirmed improvements
- `queued_no_progress_timeout` is no longer dominant in this round (0 hits).
- `status=133` disappeared in this round (0 hits).
- Retry-after-failure path still converges for several batches (late delivery observed on B/C).

### Newly confirmed failure mode
- **Mid-progress stall without terminal state** still exists.
  - Example: `batch_id=asW6ci6_FwLH` stalls at `chunks=48/79` and then has no `BLE batch send complete` / `BLE batch send failed` until end of capture.
  - Same shape also observed for `batch_id=c5MRhQLq03UD` (tail ends at `48/79` without terminal in capture window).

### Receiver coverage for sender A test burst (`TEST 5: A #1..#10`)
- B received: `#1,#2,#3,#4,#5,#6,#7,#9,#10` (missing `#8`)
- C received: `#1,#3,#4,#5,#6,#7,#9,#10` (missing `#2,#8`)

### Operational conclusion update
- System moved from **timeout-dominated** to **stall/interrupt-dominated**.
- Next blocker is not `queued_no_progress_timeout`; it is **inflight write stall with missing terminal transition**.

---

## ✅ Bug-Fix Checklist (Implementation Plan)

### P0 — Dispatch semantics (must fix first)
- [x] Ensure batch is **not globally failed/completed** while any peer is still `PENDING` or `FAILED_RETRYABLE`.
- [ ] For `queued_no_progress_timeout`, mark peer-level state as retryable and force fast requeue for that peer.
- [ ] Prevent transition to `broadcast_rejected` skip path when `missing_peer_count > 0`.
- [x] Keep retry ownership per-peer (do not collapse to a global batch decision).
- [ ] Add attempt fencing between transport and dispatcher so stale timeout/fail from old attempt cannot override a newer in-flight attempt for the same `batch_id + device_id`.

### P0 — Reconcile correctness
- [x] Reconfirm `reconcileBatchAfterPeerUpdate` only closes batch when:
  - has `DELIVERED`
  - and has no `PENDING`
  - and has no `FAILED_RETRYABLE`.
- [ ] Add explicit test for `DELIVERED + FAILED_FINAL` behavior (must remain closed only if no retryable peer remains).

### P1 — Transport recovery
- [ ] Add immediate reconnect/re-enqueue policy for `0/N progress` stalls (avoid long dead window).
- [ ] Add bounded "early retry burst" for first failure before entering long backoff.
- [ ] Guard rotation/disconnect events so in-flight peer jobs are resumed, not silently skipped.
- [x] Align connect-cap waiting detection with all queue states (`pending + staged + held`), not `pending` only, before applying `queued_no_progress_timeout`.
- [ ] Add watchdog/terminalization for **mid-progress** stall (`writtenChunks > 0` but no completion/failure), to avoid infinite `IN_PROGRESS` hang.

### P1 — Relay determinism
- [ ] Tighten relay target selection to strict missing-peer ledger view.
- [ ] Improve source-device attribution to reduce `source_device_unknown_soft` fallback decisions.
- [ ] Ensure delayed relay only drops when target is verifiably delivered (not inferred too early).
- [x] Add sender-origin suppression: receiver must not relay a batch back to the original sender device (`target_device_id == source_device_id` => skip).
- [x] Add explicit diagnostic log for suppression path (e.g. `skip_sender_origin`) to avoid silent traffic waste.
- [x] Normalize origin identity to **transport peer `device_id` namespace** (do not use actor DID for sender-origin suppression decisions).
- [x] Ensure `sender_device_id` in `APP_BATCH` is populated with transport peer identity and preserved across append/rebuild/retry flows.
- [ ] Receiver precedence rule for origin: `batch.sender_device_id` (if valid) -> cached `sourceAddress -> device_id` mapping -> unknown.
- [x] Add pre-send hard guard in transport queueing path: if `targetDeviceId` equals resolved batch origin, drop with `skip_sender_origin`.
- [ ] Add regression test: in mixed A/B/C round, no `BLE batch forward queued` line may target batch origin device.

### P2 — Observability and gate criteria
- [ ] Add metrics:
  - `peer_delivery_success_ratio`
  - `queued_no_progress_timeout_count`
  - `retry_success_after_timeout`
  - `broadcast_rejected_skip_count`
- [ ] Add metric: `source_device_unknown_soft_count` and alert when it exceeds threshold per round.
- [ ] Add metric: `skip_sender_origin_count` and track by sender/receiver pair.
- [ ] Add round gate: fail test if any cross-device missing after fixed window (e.g. 90s).
- [ ] Keep using `compact matrix` for each test round and attach to review notes.

---

## 🧪 Verification Checklist (after patch)

- [ ] Scenario A: sender A burst (10 msgs) must deliver to B and C with 0 missing.
- [ ] Scenario B: sender B burst (5 msgs) must deliver to A and C with 0 missing.
- [ ] Scenario C: sender C burst (5 msgs) must deliver to A and B with 0 missing.
- [ ] No message may end in `broadcast_rejected skip` while some peer is still missing.
- [ ] Timeout events can occur, but must converge via retry/relay inside SLA window.
- [ ] No relay hop should target the batch origin device when origin is known.

---

## 🧪 How to Review a Round

Always include:

### 1. Findings
- what improved
- what regressed

### 2. Timeline
```
time:
  A:
  B:
  C:
```

### 3. Coverage estimate
- A → B
- A → C

### 4. Root cause
- direct path?
- rotation?
- relay?

---

## 📂 Key Files

### Start here
- BleMeshTransport.kt
- OutboundBatchDispatcher.kt
- BleMeshDiagnosticsLogger.kt

### Then
- EnvelopeReceiverImpl.kt
- EventIngestor.kt
- OutboundBatchDao.kt

---

## 🧠 Final Summary

- System is **close but not stable**
- Direct path is still king
- Relay is not mature
- Delayed relay is experimental

👉 **Do not over-engineer relay yet**
👉 **Fix sender path first**

---

## 🔚 Handoff Status

This document is now:
- ready for team review
- ready for AI continuation
- safe to branch further work from
