## PeerMesh --- Gantt Timeline & Action Plan

Start: 2026-05-20 Goal: First 100 paid users

Planning rule for this document:
- this Gantt is a directional product-and-delivery plan toward the first 100 paid users
- it should reflect the real state of the project, not an idealized schedule
- `docs/milestones/Phase_3_1_Task_List.md` and `docs/milestones/Phase_3_2_Task_List.md` are the source of truth for detailed implementation status
- `docs/specs/ble_dtn_transport_kpi_slo.md` is the source of truth for BLE/DTN quality gates and acceptance outcomes
- if a gate slips, this plan should be re-baselined rather than pretending the gate was already cleared
- this document should track milestone gates and sequencing, not duplicate every engineering task

Current public infrastructure already live:
- Website: `https://peermesh.app`
- Public registry: `https://registry.peermesh.app`
- Public relay: `https://relay.peermesh.app`
- Contact mailbox: `hello@peermesh.app`

### Re-baselined Gantt

``` text
Rebaseline start: 2026-06-20

Tuần →                           R0      R1      R2      R3      R4      R5      R6      R7
Ngày →                         20-26   27-03   04-10   11-17   18-24   25-31   01-07   08-14
------------------------------------------------------------------------------------------------
DTN physical stabilization      ███████ ███████ ▒▒▒▒
Release hardening               ███     ███████ ███████ ▒▒▒
Phase 3.1 device QA/polish      ███     ███████ ▒▒▒
Moderation V2 hardening         ███     █████   ▒▒▒
Google/Play review loop         ███     █████
Closed testing prep                     ███████
Closed testing cohort                           ███████ ███████
Crash/battery/usability fixes                   ███████ ███████ █████
Pricing/billing/funnel polish                   ███     ███████ █████
Website/content/support ops                     ███     █████   █████
Early paid user acquisition                                     ███████ ███████
Onboarding optimization                                        █████   ███████
100-user push                                                          ▒▒▒▒▒▒▒
```

### Current Status Snapshot

- `Phase 3.1` reality:
  - most core UX work is complete
  - remaining open items are mainly device validation, readability during churn, and release polish
  - primary source: `docs/milestones/Phase_3_1_Task_List.md`
- `Phase 3.2` reality:
  - relay/registry split, managed relay access, managed federation baseline, and registry admin are already in place
  - `report peer` and `report message` are now end-to-end to registry moderation ingest and operator review
  - broader Moderation V2 remains incomplete because rejoin, membership consistency hardening, and some public-policy alignment are still open
  - primary source: `docs/milestones/Phase_3_2_Task_List.md`
- release-readiness reality:
  - some infrastructure work is ahead of the original optimistic timeline
  - the true remaining gates are DTN repeated physical stability, release hardening, closed testing with outside users, and conversion/onboarding follow-through

### Milestone Notes

#### R0: Rebaseline And Evidence Lock

- Goal:
  - lock the real project baseline and stop planning against stale week numbers
- Maps to `Phase_3_1_Task_List.md`:
  - review all open device-validation items and separate true release gates from nice-to-have polish
  - current likely gates:
    - BLE header control discoverability
    - Network Status readability during churn
    - physical re-validation of header tap/hold behavior
    - Cloud Sync switch/device validation
    - Trails local-only copy/device validation
- Maps to `Phase_3_2_Task_List.md`:
  - confirm current moderation/reporting baseline as shipped capability, not future work
  - keep `client_moderation_reports` marked as baseline-complete but not fully hardened
  - keep rejoin and membership consistency explicitly outside the current shipped baseline
- Exit signal:
  - Gantt, milestone notes, and task lists all describe the same current state

#### R1: Stability And Hardening

- Goal:
  - reduce technical uncertainty before broader testing
  - evaluate transport using delivery correctness, durability, eventual convergence, bounded normal-condition latency, and observable protocol progress; do not use low average latency alone as a stability signal
- Maps to `Phase_3_1_Task_List.md`:
  - close or downgrade the remaining device-QA blockers
  - validate UX behavior under BLE churn, carrier-only mode, Cloud Sync toggle, and trails copy
- Maps to `Phase_3_2_Task_List.md`:
  - continue DTN and relay physical stabilization work that still blocks confidence claims
  - finish moderation-report hardening that is close to baseline:
    - operator-facing accepted/permanent-failure evidence
    - any minimal diagnostics still needed for retry visibility
    - redacted export / audit handling policy only if required for operator workflow
- Exit signal:
  - physical runtime evidence satisfies the normative BLE/DTN KPI/SLO contract, or each remaining limitation has an explicit non-`PASS` classification and is clearly bounded

#### R2: Testing Preparation

- Goal:
  - turn the current engineering baseline into something suitable for wider outside testing
- Maps to `Phase_3_1_Task_List.md`:
  - verify open UX items that affect tester comprehension rather than internal engineering confidence
- Maps to `Phase_3_2_Task_List.md`:
  - prioritize testability and release hardening over new architecture
  - keep Moderation V2 scope narrow unless a reviewer or tester reveals a concrete blocker
  - do not expand into rejoin implementation unless it becomes the agreed next gate
- Exit signal:
  - build, runtime, moderation baseline, and tester flows are coherent enough to support a real closed-testing cohort

#### R3-R4: Closed Testing And Feedback Reduction

- Goal:
  - run a real tester cohort and reduce issues found there
- Maps to `Phase_3_1_Task_List.md`:
  - address usability, comprehension, and readability issues reported by testers
- Maps to `Phase_3_2_Task_List.md`:
  - fix operational bugs in relay/registry/moderation paths based on real feedback
  - keep policy docs aligned with shipped behavior as the workflow evolves
- Exit signal:
  - critical crash, battery, usability, and moderation-workflow issues from testers are reduced to an acceptable level

#### R5-R6: Conversion Setup

- Goal:
  - move from technical readiness into conversion readiness
- Maps to `Phase_3_1_Task_List.md`:
  - refine onboarding-critical UX rather than broad polish
- Maps to `Phase_3_2_Task_List.md`:
  - server/platform work should mostly be bug-fix and operations support, not major new foundational systems
- Product focus:
  - pricing assumptions
  - billing clarity
  - landing-page and support flow quality
  - early paid-user acquisition
- Exit signal:
  - the project is learning from real user behavior, not just internal validation

#### R7: Push Toward 100 Paid Users

- Goal:
  - operate the growth loop with the current product baseline
- Focus:
  - onboarding optimization
  - support workflow
  - conversion improvements
  - acquisition repetition
- Constraint:
  - avoid reopening major foundational work unless real user evidence demands it

### Gate Interpretation

- Moderation/reporting is no longer a zero-to-one blocker:
  - `report peer` and `report message` already submit to registry
  - registry admin can review, annotate, resolve, and audit those reports
  - remaining moderation work is mostly hardening and broader governance, not baseline existence
- Rejoin is still out of current baseline:
  - it remains part of broader Moderation V2
  - it should not be silently treated as done or implied by the current report pipeline
- Closed testing should depend on practical reliability, not perfect scope completion:
  - the project does not need every idealized milestone closed before learning from outside testers
  - but it does need enough DTN/runtime stability that feedback is about product behavior, not constant infra noise

### Update Rule

- update this Gantt only when milestone sequencing or release gates materially change
- update `Phase_3_1_Task_List.md` and `Phase_3_2_Task_List.md` whenever detailed execution status changes
- if the two task lists and this Gantt diverge, rebaseline this document instead of stretching old week labels
