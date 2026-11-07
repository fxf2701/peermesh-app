# Phase 3 — Technical Specification (Relay/Federation + Ops)

## 1) Goals
Phase 3 focuses on **relay/federation**, governance, and operational readiness.

Primary goals:
- Relay discovery + auto-selection (server + mobile).
- Auto-connect + topology control to avoid overload/churn.
- Private relay mode (server-side MAC verification for trusted deployments).
- Production-grade governance, retention, and operational runbooks.

Non-goals:
- Threaded chat UX (handled in Phase 3.1).
- Real-time guarantees.
- Global identity or centralized registry.

---

## 2) Federation & Topology

### 2.1 Discovery
- Discovery surfaces peer candidates + health signals (latency, last_seen, success rate).
- Discovery is **advisory**, not a forced join.

### 2.2 Auto-connect & Topology
- Auto-connect is policy-controlled (operator toggle + safeguards).
- Topology allocator enforces bounded N-regular peering.
- Anti-oscillation: cool-downs and churn control.

### 2.3 Runtime Network-aware Allocation
- Peer selection adapts to current network conditions.
- Capacity limits: min/max peers with backpressure.

### 2.4 Blind Gateway / Ferry Mode (Store-and-Forward)
Combine real-time relay forwarding with offline caching:
- If online: forward opaque envelopes to relay immediately.
- If offline: cache raw envelopes locally and push when connectivity returns.
- Relay accepts opaque blobs without decrypting; clients with keys will decode after pull.
- Must enforce rate limits, size caps, and TTL on cached blobs.

**Product policy (Commercial vs Emergency):**
- **Commercial:** Ferry mode is **opt-in** (default off).
- **Emergency:** Ferry mode is **default on** with **opt-out**.

Notes:
- Ferry mode forwards opaque blobs even when the carrier device is not a group member.
- UX should surface the mode clearly and allow disabling in Emergency builds.

**UI / Settings (Commercial vs Emergency):**
- **Commercial (default off):**
  - Setting label: “Ferry mode (carry encrypted messages for others)”.
  - Toggle is OFF by default; enabling shows a short warning about battery/data usage.
  - Optional per-user limits (daily quota, max storage for cached blobs).
- **Emergency (default on):**
  - Toggle is ON by default with clear opt-out.
  - Warning text is shorter; emphasize humanitarian benefit + battery impact.
  - Provide a quick “Pause ferry for 1 hour” action.

**Telemetry / Metrics (Ferry traffic):**
- Counters: `ferry_envelopes_received`, `ferry_envelopes_forwarded`, `ferry_envelopes_dropped`.
- Reasons for drops: TTL expired, size cap, rate limit, duplicate, storage full.
- Volume: bytes in/out per time window.
- Cache health: queue depth, oldest age, eviction count.
- Per-device budgets: current usage vs quota (if enabled).

**Abuse guards (quota + budgets):**
- **Time-window quota:** max envelopes and max bytes per device per rolling window (e.g. 10 min / 1 hr).
- **Per-device budget:** daily cap on forwarded bytes + envelope count.
- **Backoff on violation:** exponential cooldown before accepting new ferry blobs.
- **Eviction policy:** drop oldest or lowest-priority blobs when storage cap reached.
- **Dedupe cache:** short-lived hash cache to reject repeated blobs quickly.

---

## 3) Security & Governance

### 3.1 RBAC + Multi-approver
- Sensitive actions (ban/import) require multi-approver workflow.

### 3.2 Audit & Diff Review
- Bulk banlist changes require diff review + audit logs.

### 3.3 Allowlist Mode
- Federation join can be default-deny (allowlist).
- CRUD UI and policy flags required.

### 3.4 Peer Trust Scoring
- Technical-only signals for scoring.
- Protected peers: tiered ban policy.

### 3.5 Secrets Management
- Token/HMAC key rotation with safe fallback.
- Operator-friendly procedures and alerts.

---

## 4) Private Relay Mode
- Optional server-side MAC verification for trusted groups.
- Operator key provisioning + rotation.

---

## 5) Data Durability & Retention
- Retention profiles: community/strict/emergency.
- TTL/cleanup mapping documented and enforced.
- Restore drill process (scheduled or automated) with validation.

---

## 6) Ops & Runbooks
- Relay audit checklist (Phase 3 Relay Audit Spec).
- Runbook PDFs (incident response, backup/restore, deploy/rollback).
- Diagnostics: cursor resets, duplicate storms, peer sync lag.

---

## 7) QA Scenarios
- Auto-connect under intermittent network.
- Topology convergence without oscillation.
- Allowlist blocks unknown peers.
- Secrets rotation without downtime.
- Restore drill success + data integrity verification.
- BLE battery impact: Does BLE scanning/advertising noticeably drain battery in normal use?
- Map performance: Does the map lag when rendering ~200 markers?
- TTL cleanup: Do expired items auto-cleanup as expected?
- DB growth: Does the database grow unbounded after 1 month of typical usage?
- Reconnect duplicates: Are duplicate events/records created on reconnect?
- Out-of-order events: Does state remain correct when events arrive out of order?

---

## 8) References
- `docs/specs//PHASE_3_RELAY_AUDIT_SPEC.md`
- `docs/server/INTERNET_RELAY_OPTION.md`
- `docs/specs//Project_Overview_Technical_Spec.md`
