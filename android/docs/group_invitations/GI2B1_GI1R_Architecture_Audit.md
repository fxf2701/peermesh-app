# GI2B1 — GI1R Architecture Audit

**Audited document:** `GI2B1_Nearby_Self_Info_Additive_Protocol_Contract.md`  
**Audit basis:** Product Spec v2, GI1R, Implementation Roadmap v2  
**Verdict:** PASS WITH REQUIRED DOCUMENT CORRECTIONS

## 1. Summary

The GI2B1 protocol direction remains valid after the Group Invitation architecture realignment.

No rollback of the Self Info extension is required.

The contract does not introduce:

- Invitation Card persistence;
- Invitation Template persistence;
- invitation transport kinds;
- invitation event or batch envelopes;
- invitation-specific relay/retry;
- invitation content inside Self Info.

The five additive envelope fields remain narrowly scoped to `SELF_INFO_V1` and `SELF_INFO_ACK_V1`.

## 2. Findings

### F1 — Obsolete upstream roadmap reference

The contract names `Group_Invitation_Roadmap_v1.0.md` as a locked upstream authority.

This is no longer valid after Product Spec v2, GI1R and Implementation Roadmap v2.

**Correction:** replace the authority list and explicitly supersede obsolete product/domain assumptions from historical documents.

### F2 — Capability rationale became outdated

The contract correctly states that `recipient_encryption_supported` does not mean Group Invitation support.

However, its rationale says GI2B1 has only one capability use case.

GI1R now explicitly identifies a second, separate future claim:

```text
structured Group Invitation v1 supported
```

**Correction:** retain the dedicated recipient-encryption boolean, but state that a future Group Invitation capability must remain separate and may be added by a later reviewed contract. Do not create a generic registry in GI2B1.

### F3 — GI2C wording could imply a dedicated trust subsystem

The contract separates claimed identity and TOFU identity correctly, but historical wording could lead an implementer to create a dedicated trust table/framework.

GI1R prohibits unnecessary persistence subsystems.

**Correction:** preserve first-key/conflict semantics while deferring the minimal schema decision to GI2C architecture review.

### F4 — Implementation status is stale

A GI2B1-P1 patch has been prepared, but repository compile and focused tests have not yet been verified.

**Correction:** mark implementation as patch prepared, not verified. Do not close GI2B1 yet.

## 3. Compatibility Review

**PASS**

- No Self Info kind change.
- Existing required fields remain unchanged.
- New fields are optional and key-based.
- Old clients may ignore them.
- New clients accept missing fields.
- Old relay stripping degrades to unknown capability.

## 4. Product Architecture Review

**PASS**

GI2B1 only advertises recipient-encryption identity.

It does not define or transport Group Invitation cards or structured chat content.

## 5. Capability Separation Review

**PASS AFTER CORRECTION**

The following claims remain distinct:

```text
recipient encryption supported
structured Group Invitation v1 supported
```

The current `res` field proves only the first claim.

## 6. Persistence Review

**PASS WITH GI2C DEFERRAL**

GI2B1 performs no migration.

GI2C must choose the smallest safe persistence model that supports:

- room scope;
- first-key pinning;
- same-key acceptance;
- key conflict detection;
- restart persistence;
- no silent replacement.

No dedicated invitation table or broad trust subsystem is implied.

## 7. Security Review

**PASS**

- Only public identity material is serialized.
- Key ID is recomputed.
- SEC1/Base64URL validation remains strict.
- Capability is all-or-nothing.
- Revision remains transport snapshot identity, not trust.
- Private invitation security is not weakened.

## 8. Required Next Action

Replace the current contract with:

```text
GI2B1_Nearby_Self_Info_Additive_Protocol_Contract_v2_Realigned.md
```

Then:

1. Apply or review the prepared GI2B1 patch.
2. Run focused codec and protocol tests.
3. Review compile errors and dependency direction.
4. Fix findings.
5. Close GI2B1 only after verification.

## 9. Final Verdict

```text
GI2B1 GI1R AUDIT

Architecture: PASS
Product alignment: PASS
Wire compatibility: PASS
Capability separation: PASS AFTER DOCUMENT CORRECTION
Persistence boundary: PASS WITH GI2C DEFERRAL
Implementation verification: PENDING

Next:
APPLY AND VERIFY GI2B1-P1 PATCH
```
