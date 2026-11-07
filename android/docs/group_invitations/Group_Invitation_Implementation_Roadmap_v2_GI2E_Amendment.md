# Group Invitation Implementation Roadmap v2 — GI2E Amendment

**Amendment ID:** GI2E-ROADMAP-A1  
**Status:** DRAFT FOR REVIEW WITH GI2E1 REVISION 3  
**Purpose:** Preserve existing GI2E milestone IDs while adding prerequisite foundation work.

---

# 1. Authority Rule

This amendment does not silently rename or reorder GI2E2–GI2E5.

The existing milestone identities remain:

```text
GI2E2 — Send-limit query and enforcement
GI2E3 — Public invitation send
GI2E4 — Private invitation send
GI2E5 — Parser, fallback and presentation integration
```

Revision 3 contract must use these same boundaries.

---

# 2. GI2E2 — Send-Limit Query and Enforcement

GI2E2 may include the minimum shared foundation required to enforce send limits safely:

- additive message-row persistence needed for sender-local private rendering;
- local Hide/Unhide presentation-state persistence, if required by the approved design;
- immutable repository-prepared message context;
- insert-only prepared message DAO seam;
- authoritative transactional quota recheck plus insert;
- double-tap/concurrency protection;
- authoritative Edit/Delete/Replace rejection foundation;
- migration and transaction tests.

GI2E2 must not implement full public/private send orchestration or final card rendering.

---

# 3. GI2E3 — Public Invitation Send

GI2E3 implements:

- public creation flow;
- warning/hint text;
- canonical GI1 public encoding;
- exact body persistence;
- public send-limit integration from GI2E2;
- normal Nearby send/retry/relay ownership;
- public invitation action policy wiring.

GI2E3 does not implement private GI2D orchestration.

---

# 4. GI2E4 — Private Invitation Send

GI2E4 implements:

- private creation flow;
- target participant selection;
- GI2C pinned recipient identity read;
- immutable prepared context;
- GI2D encrypt exactly once;
- canonical private envelope persistence;
- sender-local private render source;
- private per-recipient send-limit integration;
- generic BLE admission integration;
- no Reply construction.

GI2E4 does not create Invitation-specific retry, relay, transport, repository, or lifecycle.

---

# 5. GI2E5 — Parser, Fallback and Presentation Integration

GI2E5 implements:

- pre-sanitizer GI1 classification;
- exact structured receive persistence;
- public render;
- private target decrypt/render;
- sender-local private render;
- audience hiding;
- user Hide/Unhide projection;
- hidden placeholder with Unhide;
- unsupported/malformed/unavailable fallbacks;
- shared safe projection for timeline, history, report, accessibility, notification and preview surfaces;
- remote structured edit/delete transition rejection;
- wording changes:
  - `Report invitation`
  - `Report peer`
  - no `Reply`.

---

# 6. No Scope Reassignment

No GI2E contract may redefine milestone IDs without first updating the roadmap authority through a separately approved architecture decision.
