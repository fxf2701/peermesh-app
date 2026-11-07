# GI2E1 Revision 3.2 — Independent Delta Re-Review

**Reviewed authority:** `GI2E1_Revision3_2_Amendment.md` and `GI2E1_Structured_Content_Contract_LOCKED.md`, Revision 3.2  
**Review date:** 2026-08-05  
**Review scope:** Delta from Revision 3.1 only  
**Production source changed:** No  
**Contract modified or locked by this review:** No

## 1. Verdict

```text
PASS — READY TO LOCK
```

Revision 3.2 resolves GI2E1-R3.1-M01 by making the ownership boundary explicit: one production local actor owns one `peer_mesh.db`, while debug/test actor switching is an accepted limitation outside Hide/Unhide isolation guarantees.

Current source supports that production assumption. The only repository call site that changes the active actor is the debug Test screen. Release builds disable debug tools and remove that activity. No source evidence proves that multiple production local actors can share one database, so a viewer-keyed persistence design is not required for GI2E1.

## 2. Finding counts

| Severity | Count |
|---|---:|
| BLOCKER | 0 |
| MAJOR | 0 |
| MINOR | 0 |
| NOTE | 4 |

## 3. BLOCKER/MAJOR list

```text
None.
```

## 4. Single-production-actor invariant

```text
CONFIRMED — EXPLICIT AND CONSISTENT WITH CURRENT PRODUCTION SOURCE
```

The Amendment states exactly one production local actor per `peer_mesh.db` and defines the field as installation-local rather than actor-local state (`GI2E1_Revision3_2_Amendment.md:3-17`). The Revision 3.2 contract repeats the one-actor/one-database relationship and explicitly excludes current debug actor switching (`GI2E1_Structured_Content_Contract_LOCKED.md:20-30`).

Source evidence:

- `DeviceIdProvider.setActiveActor(...)` exists, but repository-wide source search finds only one call site: `TestActivity.switchToAltUser(...)` (`android/app/src/main/java/app/peermesh/DeviceIdProvider.kt:44-50`; `android/app/src/main/java/app/peermesh/TestActivity.kt:793-805`).
- `TestActivity` exits immediately when debug tools are disabled (`TestActivity.kt:83-88`).
- Release sets `ENABLE_DEBUG_TOOLS = false` (`android/app/build.gradle.kts:131-146`).
- The release manifest removes `TestActivity` (`android/app/src/release/AndroidManifest.xml:8-11`).
- The Test screen's alternate-user creation and switching are therefore debug/test facilities, not a production identity workflow (`TestActivity.kt:683-705,756-805`).
- Production still uses one singleton database named `peer_mesh.db` (`android/data/src/main/java/app/peermesh/data/db/AppDatabaseProvider.kt:7-28`). With one production actor, this is consistent with installation-local state.

The presence of a mutable actor API in compiled source does not prove multiple production actors share a database. There is no production call path, UI, exported activity or documented production capability that switches it.

## 5. `is_hidden_locally` persistence seam

```text
CONFIRMED — SMALLEST VALID SEAM UNDER THE REVISION 3.2 INVARIANT
```

`NearbyRoomMessageEntity.is_hidden_locally` remains the smallest persistence extension because it:

- keeps presentation state on the existing Nearby message row;
- preserves the same `(room_id, message_id)` identity and ordering position;
- survives process death and supports reversible Unhide;
- follows the message row's expiry and room-purge lifecycle;
- requires no transport event, placeholder row, table, repository, worker or Invitation lifecycle;
- is local input only and must be preserved by remote merge and delivery bookkeeping.

No current mechanism provides equivalent reversible per-message persistence. Peer blocking is peer-wide and may remove messages from presentation; the hidden relay cache is volatile. A viewer key would add state and ownership complexity without a current production multi-actor requirement.

The seam therefore remains aligned with the Engineering Constitution's reuse-first, existing-ownership and minimum-surface rules (`docs/development/Engineering_Constitution.md:13-45,81-93,117-127`).

## 6. Future multi-actor production stop condition

```text
CONFIRMED — EXPLICIT
```

The Amendment requires re-review before releasing production support for multiple local actors sharing one database, forbids using the current boolean for that model, and requires prior review and approval of a viewer-keyed design (`GI2E1_Revision3_2_Amendment.md:19-28`).

The Revision 3.2 contract independently repeats that implementation must stop if production introduces multiple local actors sharing one database (`GI2E1_Structured_Content_Contract_LOCKED.md:28-32`). This also satisfies the earlier Hide/Unhide persistence decision's stop condition for per-profile state in a shared message database.

Required future trigger:

```text
production multi-actor support sharing peer_mesh.db
→ stop GI2E persistence work
→ architecture re-review
→ approve viewer-keyed persistence before release
```

No viewer-keyed design is authorized or necessary today.

## 7. Regression audit

```text
PROTOCOL: NO REGRESSION
CRYPTO: NO REGRESSION
NEARBY ROOM OWNERSHIP: NO REGRESSION
BLE: NO REGRESSION
```

Revision 3.2 changes only the interpretation and ownership boundary of local Hide/Unhide state. It expressly leaves protocol, crypto, transport, retry, relay, ordering and persistence ownership unchanged, and incorporates every other Revision 3.1 section without modification (`GI2E1_Structured_Content_Contract_LOCKED.md:6-10,34`).

Consequently:

- GI1 candidate detection, canonical encoding, exact wire body and structured immutable merge remain unchanged;
- GI2D target gating, AAD/expiry rules, encrypt-once behavior and sender-local private-source validation remain unchanged;
- Nearby Room continues to own the message row, ordering, retention, hard purge, retry, relay and transport;
- local fields remain excluded from wire bodies, retry and relay;
- BLE continues to use the Revision 3.1 generic typed final-envelope admission boundary with no Invitation-specific transport behavior.

The delta introduces no new protocol field, cryptographic input, transport kind, BLE path, repository, entity, table or lifecycle.

## 8. Prior finding disposition

| Finding | Revision 3.2 disposition |
|---|---|
| R3-M01 — generic discriminator semantics | Remains resolved; unchanged from Revision 3.1. |
| R3-M02 — mutable structured ordering/context metadata | Remains resolved; unchanged from Revision 3.1. |
| R3-M03 — sender-local plaintext reload/threat contract | Remains resolved; unchanged from Revision 3.1. |
| R3.1-M01 — same-row state across switchable local actors | Resolved by the explicit production invariant, accepted debug/test limitation and future-production stop condition. |

## 9. Lock recommendation

```text
LOCK REVISION 3.2
```

There are zero BLOCKER and zero MAJOR findings. The lock should preserve both the Revision 3.2 Amendment and the contract's explicit incorporation of unchanged Revision 3.1 requirements.

## 10. GI2E2 authorization recommendation

```text
AUTHORIZE GI2E2 AFTER REVISION 3.2 IS LOCKED
```

GI2E2 may implement the approved same-row boolean under the single-production-actor invariant. If production multi-actor support enters scope, the explicit stop condition applies before implementation or release.

## 11. Exact files modified

```text
android/docs/group_invitations/GI2E1_Independent_Review.md
```

No production source, Amendment or contract was modified or locked by this review.

## Findings summary

### GI2E1-R3.2-N01 — Production actor ownership

**Severity:** NOTE  
**Disposition:** The one-production-actor-per-database invariant is explicit and matches all discovered production call paths.

### GI2E1-R3.2-N02 — Persistence minimality

**Severity:** NOTE  
**Disposition:** The same-row boolean remains the smallest valid persistence seam under the approved invariant.

### GI2E1-R3.2-N03 — Future ownership expansion

**Severity:** NOTE  
**Disposition:** Multi-actor production is protected by an explicit architecture stop and viewer-keyed re-review requirement.

### GI2E1-R3.2-N04 — Delta regression

**Severity:** NOTE  
**Disposition:** No protocol, crypto, Nearby Room ownership or BLE behavior changes in the Revision 3.2 delta.
