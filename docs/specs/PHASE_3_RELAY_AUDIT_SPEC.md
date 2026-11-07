# Phase 3 — Relay Audit Specification
## Audit Spec for Federation Relay (Security • Privacy • Governance • Reliability)

**Version:** 1.0  
**Date:** 2026-01-21  
**Applies to:** Relay Server + Federation Layer + Admin UI (Operator Console)  
**Goal:** Provide a structured, repeatable audit plan to validate the relay is safe to deploy and distribute, aligned with the project's Threat Model and "hybrid federation" philosophy (health metrics, bounded auto-peering, emergency controls without social authority).

---

## 0) Why This Document Exists

By Phase 3, the project moves from "implemented" to **operated and distributed**.  
A relay can be technically correct yet still be unsafe in practice due to:
- authority illusion (operators or communities treating the relay as an "official source")
- metadata leakage (even with encrypted payloads)
- federation amplification (sybil peers, spam storms, peer churn)
- operational fragility (backup failure, incident handling gaps)

This audit spec defines:
- **what to check**
- **how to test**
- **what evidence to collect**
- **pass/fail criteria**
- **what must be fixed before distribution**

---

## 1) Audit Objectives

### O1 — Security Fundamentals
- Prevent abuse (DoS, replay, malformed payloads)
- Ensure authentication and federation handshakes are robust

### O2 — Privacy & Metadata Minimization
- Ensure relay does not become a surveillance point
- Ensure logs/metrics do not leak sensitive patterns

### O3 — Governance & Authority Boundaries
- Ensure admin features cannot be easily misused to create social authority
- Ensure emergency controls are constrained and auditable

### O4 — Reliability & Resilience
- Ensure relay survives real-world conditions: churn, partial outages, DB pressure
- Ensure backup/restore works under stress

### O5 — Distribution Readiness
- Ensure defaults and documentation are safe for community operators
- Ensure "limitations" are clearly communicated

---

## 2) Audit Scope

### In Scope
- API endpoints: ingest/push, pull/cursor, health, admin endpoints
- Federation: discovery, join, authentication, bounded peer selection, anti-loop
- Storage: DB schema, retention, TTL cleanup/compaction
- Abuse controls: rate limits, nonce/replay cache, backpressure
- Logging/metrics: what is logged, how long retained, who can access
- Admin UI: wording, controls, workflows, audit trail
- Config presets / deployment artifacts: docker, env defaults, runbooks

### Out of Scope
- Mobile client correctness (audited separately)
- Physical security of hosts (documented assumptions only)
- Legal compliance across jurisdictions (provide guidance but not formal legal advice)

---

## 3) Threat Model Mapping (What We Must Not Accidentally Enable)

Audit MUST explicitly check for:
- **False authority signals** (labels like “trusted/verified”, rankings implying truth)
- **Amplification effects** (federation choices that privilege malicious peers)
- **Single-operator catastrophic power** (no guardrails, no immutable audit)
- **Metadata leakage** (IP, group identifiers, timing correlation in logs)
- **Abuse pathways** (join endpoint brute force, cursor scraping, replay storms)

---

## 4) Audit Phases & Deliverables

### Phase A — Static Review (Design & Code)
**Deliverables**
- Findings list (by severity)
- Recommended remediations
- Updated docs/copy where needed

### Phase B — Dynamic Testing (Staging)
**Deliverables**
- Test plan execution logs
- Load and failure reports
- Security test outputs

### Phase C — Operational Drill (SRE-style)
**Deliverables**
- Backup/restore drill report
- Incident runbook exercise report
- Postmortem template + example run

### Phase D — Release Gate
**Deliverables**
- Final "Go/No-Go" checklist signed by maintainers
- Public distribution notes including limitations & safe defaults

---

## 5) Audit Categories & Checklists

### 5.1 Architecture & Data Boundaries
**Checks**
- Relay treats payload as opaque; no content parsing beyond schema validation
- No group semantics inferred; identifiers are opaque
- No client/device identity tracking unless explicitly designed and documented
**Evidence**
- Code paths showing payload handling
- DB schema review for unintended identifiers

**Pass Criteria**
- No content inspection, no group classification, no user tracking by default

---

### 5.2 Authentication & Federation Trust
**Checks**
- Join authentication is clearly defined and strong enough for intended deployment
- If token-only exists: labeled **lab/low-trust** with clear warnings
- Preferred Phase 3: mTLS or peer-key pinning / signed joins
- Rate limits and alerts for join attempts
**Evidence**
- Config and code for join
- Logs showing join attempt throttling
- Docs stating security posture

**Pass Criteria**
- Strong peer identity binding for production distribution OR strict warnings + safe defaults

---

### 5.3 Peer Selection & Amplification Safety (Hybrid Federation)
**Checks**
- Bounded peers: MIN/MAX enforced
- Rotation with decay/cooldowns
- Health metrics are purely technical, not “trust”
- Health metrics do NOT affect event acceptance/priority
- Anti-loop protections validated (no infinite re-propagation)
**Evidence**
- Peer manager config and tests
- Simulation logs: churn + sybil attempts
- UI wording review

**Pass Criteria**
- Auto-peering cannot be trivially captured by sybil peers; no authority ranking is exposed

---

### 5.4 Abuse Controls & Availability
**Checks**
- Rate limits: per-IP, per-gid (if applicable), per-peer
- Backpressure/circuit breaker on DB pressure
- Nonce/replay cache works and is bounded
- Payload size limits, request body limits, timeouts
**Evidence**
- Load test report (p50/p95/p99 latency, error rate)
- DoS-style test outcomes
- Metrics graphs during tests

**Pass Criteria**
- Relay stays responsive under expected load; fails gracefully under overload

---

### 5.5 Storage, Retention, and Cleanup
**Checks**
- TTL cleanup works and does not stall the DB
- Retention profiles exist (Community/Strict/Emergency) with safe defaults
- Compaction strategy documented and tested
- Partitioning (if used) auto-creates safely and avoids runaway growth
**Evidence**
- DB size over time in tests
- Cleanup job logs
- Restore test results

**Pass Criteria**
- Relay cannot silently accumulate indefinite sensitive metadata by default

---

### 5.6 Logging, Metrics, and Privacy
**Checks**
- No payload bodies logged
- IP handling policy: minimized, hashed, short retention if stored
- Metrics avoid leaking sensitive patterns (e.g., per-group activity)
- Access controls on logs/metrics endpoints
**Evidence**
- Log samples and configuration
- Metrics endpoint output review
- Documentation of retention/access

**Pass Criteria**
- Observability does not become surveillance

---

### 5.7 Admin UI — Authority & Governance Guardrails
**Checks**
- UI wording avoids “trusted/verified/official/safe”
- "Health" labels are clearly technical (uptime/latency/protocol compliance)
- Emergency controls (ban/allow) are clearly marked as operational safety only
- Every sensitive action requires reason code + is logged
- Audit trail is append-only or tamper-evident
- Optional SOP: two-person review for sensitive actions (even if operational/manual)
**Evidence**
- Screenshots / UI strings
- Admin action logs
- Documentation snippets

**Pass Criteria**
- UI cannot be used as an “authority amplifier” by default

---

### 5.8 Incident Response & Recovery
**Checks**
- Runbooks exist: DoS, DB failure, federation meltdown, token compromise, key rotation
- Backup/restore drill: verified end-to-end
- Disaster recovery: redeploy from clean state without data integrity loss
**Evidence**
- Drill reports, timestamps, success criteria
- Postmortem template and sample

**Pass Criteria**
- Operator can recover without improvisation

---

### 5.9 Supply Chain & Build Integrity
**Checks**
- Dependencies pinned, verified
- CI reproducibility
- Container image provenance (if distributed)
- Secrets handling (no secrets in repo; env-based; rotation)
**Evidence**
- SBOM (if available), lockfiles, CI logs

**Pass Criteria**
- Distribution does not introduce preventable risk

---

## 6) Severity Model for Findings

- **S0 Critical:** Enables takeover, surveillance, or major availability collapse
- **S1 High:** Material privacy leak, governance abuse likely, federation capture possible
- **S2 Medium:** Degrades safety posture, confusing authority signals, operational fragility
- **S3 Low:** Cosmetic/doc issues; minor hardening opportunities

**Release Gate Rule**
- S0/S1 must be fixed before public distribution
- S2 must have mitigation plan + owner + timeline
- S3 can be backlog

---

## 7) Required Evidence Pack (What to Collect)

1. **Config snapshots** (sanitized) for default and “Strict” profile
2. **Log samples** showing no payload content + IP policy
3. **Metrics snapshots** during normal + stress tests
4. **Load tests**: throughput, latency percentiles, error rates
5. **Federation simulations**: churn, sybil-like peers, token rotation
6. **Backup/restore drill report**
7. **Admin UI string review** (authority-illusion checklist)
8. **Audit trail integrity proof** (append-only / tamper-evident)
9. **Docs**: limitations, operator SOP, safe defaults

---

## 8) Go/No-Go Checklist (Phase 3 Distribution)

- [ ] Production-grade peer authentication (mTLS/pinning/signed joins) OR explicit “lab-only” gate
- [ ] Bounded auto-peering enabled with rotation/decay
- [ ] Health metrics are technical-only, no “trust” language anywhere
- [ ] Ban/allow controls: disabled by default; audited; reason codes required
- [ ] Logs: no payload bodies; IP policy documented; retention minimal by default
- [ ] Retention profiles published; Strict/Emergency tested
- [ ] Load test passes at target capacity
- [ ] Backup/restore drill passes
- [ ] Operator runbooks complete
- [ ] Admin UI authority-illusion checklist passes
- [ ] Public “Limitations & Safety Statement” published

---

## 9) Appendix — Authority-Illusion Review Checklist (UI & Docs)

**Fail if any of these appear in defaults**
- “trusted relay”, “verified peer”, “official network”, “safe zone confirmed”
- rankings that imply truthfulness of content
- operator messages that imply authority over content correctness

**Prefer**
- “reachable”, “available”, “authenticated (protocol)”, “allowlisted (operator)”
- “operational safety control”, “abuse prevention”, “protocol compliance”
- “reported / observed / unverified” language standards

---

## 10) Notes for Auditors

- The relay is not a truth engine.  
- This audit is as much about **human factors** (wording, governance, defaults) as it is about security.  
- A technically strong relay can still be harmful if it accidentally becomes an authority amplifier.

---

**End of document.**
