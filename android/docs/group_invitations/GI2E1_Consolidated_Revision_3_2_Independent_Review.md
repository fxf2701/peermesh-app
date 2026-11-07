PASS — READY FOR EXPLICIT LOCK

BLOCKER count: 0  
MAJOR count: 0  
MINOR count: 0  
NOTE count: 5

# GI2E1 Consolidated Revision 3.2 — Independent Architecture Review

**Candidate reviewed:** `GI2E1_Structured_Content_Contract_Revision_3_2_CONSOLIDATED_FINAL_CANDIDATE.md`  
**Review date:** 2026-08-05  
**Candidate SHA-256:** `c1662236ddef09ab5be40e8563fb39ac7704f01aa0b33d8316ff328111d604b6`  
**Production source changed:** No  
**Lock action performed by this review:** No

## Review conclusion

The candidate is self-contained and ready for an explicit lock action. It incorporates the required GI2E1 rules directly, rather than relying on an unavailable full Revision 3.1 document. No contradiction with the reviewed higher-precedence product, roadmap, GI2A–GI2D or current-source boundaries was found.

## Mandatory checks

| Check | Result | Evidence |
|---|---|---|
| Self-contained authority | PASS | The candidate declares itself to supersede incomplete/delta-only GI2E1 documents and contains ownership detection, canonical bodies, preparation, expiry, quota, persistence, Hide/Unhide, immutability, retry/relay/BLE, phase and test rules in one file (candidate Sections 1–20). |
| Structured ownership split | PASS | Exact top-level `t == "gi"` exclusively owns immutable structured treatment; only supported valid canonical decoding supplies public/private semantics and quota keys (Sections 4 and 12). Malformed/unsupported exact-GI rows are protected without generating quota evidence. |
| Canonical outer and inner values | PASS | Both outer forms require decode → canonical re-encode → exact String equality; local private source has the same rule (Sections 5.1 and 5.4). The candidate correctly states that the current private-inner model has no card or target field, so it does not require impossible checks (Section 5.4). |
| Repository-owned preparation | PASS | `NearbyRoomRepository` owns frozen ID, sender, creation time, expiry, audience/target/key identity and opaque capability. Atomic claim, bounded memory-only registry, expiry, eviction and process-death behavior are specified without a table, worker or durable state (Section 6). |
| Transaction safety | PASS | Time is sampled inside the transaction; room expiry must equal frozen expiry; sender/mute are rechecked; candidate query, quota decision and ABORT insert share one Room transaction; exceptions escape before result mapping; insert precedes bookkeeping (Sections 7–9). |
| Private sender-local source | PASS | Source is mandatory for a locally sent private invitation, is canonical-validated only against fields it actually owns, and is operationally associated with the outer body by one claimed preparation and one atomic row insertion. No GI2D decrypt/re-encrypt is required or authorized (Section 5.4). |
| Immutability and local fields | PASS | Exact-GI local/remote guards cover supported, malformed and unsupported content; merges preserve both local fields; retry/relay use only persisted outer body; source leakage is prohibited and audited (Sections 10, 12, 13 and 15). |
| Hide ownership | PASS | The one-production-actor-per-`peer_mesh.db` invariant, debug-only actor-switch limitation, and production multi-actor stop condition are explicit (Section 11). Current source has no production actor-switch path: the switch call is in `TestActivity`, while release disables debug tools and removes that activity. |
| GI2C/GI2D compatibility | PASS | The frozen private target/key identity, ID-before-encryption, immutable expiry and encrypt-once outer body preserve GI2C pinning and GI2D context/AAD ownership. GI2E does not alter GI2D cryptography. |
| Nearby Room and BLE ownership | PASS | The candidate keeps persistence, ordering, duplicate handling, retry, relay, retention and transport handoff in Nearby Room; it permits only the existing generic final-envelope BLE admission seam and no Invitation-specific transport path (Section 13). |

## Source consistency

- Current `NearbyRoomMessageEntity` has the expected `(room_id, message_id)` identity and ordering-related fields; the candidate's two proposed additions are additive and its 54 → 55 migration is consistent with current Room version 54.
- Current `GroupInvitationProtocol` has canonical public/private outer encoders and the private-inner model contains only title, description and credential. The candidate accurately avoids card/target checks on that inner model.
- Current protocol re-encoding preserves envelope string fields. GI2E2 must therefore implement the candidate's required field-level canonical Base64URL equality check where applicable, e.g. `Base64Url.encode(Base64Url.decode(value)) == value`; the candidate already requires tests for alternate noncanonical primitive encodings. This is an implementation obligation, not a GI1 protocol change.
- Current Room DAO still has legacy `REPLACE`; the dedicated `ABORT` seam and guard scope specified by the candidate correctly limit the change to prepared invitations, leaving normal-message behavior intact.

## Notes

### N01 — Explicit base authority

The candidate resolves the prior unavailable-Revision-3.1 concern by carrying all required rules itself. Historical GI2E1 draft/delta documents are not needed to interpret implementation authority after this candidate is locked.

### N02 — Capability registry is justified and bounded

The in-memory registry adds the minimum non-durable state needed to prove a repository-issued, one-shot preparation. Section 6 constrains it to 64 pending records, a five-minute lifetime, deterministic eviction, atomic claim and no worker. It does not become quota, message, transport or retry state.

### N03 — Cryptographic source binding is intentionally not invented

The candidate records the real limitation: source/outer association is operational, not cryptographic. This follows the current private-inner model and avoids an unauthorized GI1/GI2D redesign. GI2D continues to bind the envelope's actual card, target, key identity and expiry through its existing context/AAD rules.

### N04 — Existing implementation is intentionally incomplete

The current entity, DAO, repository and reducer do not yet implement these contract changes. That is expected: the candidate explicitly forbids production implementation before this independent review and explicit lock. It is not a finding against the candidate.

### N05 — Test coverage is contractually sufficient

The mandatory test matrix covers token claim races, canonical outer/source values, migration, quota concurrency, expiry waiting, rollback, malformed/unsupported immutability, local-field preservation, retry/relay and reporting leakage. These tests are sufficient to detect the architectural violations identified in prior reviews.

## Exact lock recommendation

Perform one explicit lock action, without changing the candidate's substantive content, that records:

```text
candidate path:
android/docs/group_invitations/GI2E1_Structured_Content_Contract_Revision_3_2_CONSOLIDATED_FINAL_CANDIDATE.md

revision:
3.2 Consolidated

SHA-256:
c1662236ddef09ab5be40e8563fb39ac7704f01aa0b33d8316ff328111d604b6

independent review:
android/docs/group_invitations/GI2E1_Consolidated_Revision_3_2_Independent_Review.md

verdict:
PASS — READY FOR EXPLICIT LOCK

date:
2026-08-05

post-lock authorization:
GI2E2 implementation scope only, as defined by candidate Section 18
```

No GI2E3–GI2E5 production work, contract rewrite, product change or lock-state mutation is authorized by this review itself.

## Exact files modified

```text
android/docs/group_invitations/GI2E1_Consolidated_Revision_3_2_Independent_Review.md
```
