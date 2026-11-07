# GI2E2 — Send-Limit Query and Enforcement

## Architecture Review and Corrected Implementation Plan

**Revision:** 2  
**Status:** READY FOR FOCUSED INDEPENDENT RE-REVIEW  
**GI2E1 authority:** Revision 3.2 LOCKED  
**Production source changed:** No  
**Production implementation authorized by this document:** No

---

# 1. Scope

GI2E2 implements only:

1. Repository-owned Group Invitation preparation context.
2. Atomic send-limit query and enforcement.
3. Insert-only DAO path.
4. Additive Room migration:
   - `local_render_source_body`
   - `is_hidden_locally`
5. Authoritative structured GI1 immutability guards.
6. Unit and migration tests.

GI2E2 does not implement Public Invitation UI, Private Invitation UI, renderer, Hide/Unhide UI, Join flow, GI2E3, GI2E4 or GI2E5 receive orchestration.

---

# 2. Locked ownership model

GI2E2 extends existing ownership only:

```text
NearbyRoomRepository
→ preparation authority
→ send transaction authority
→ message persistence coordination

NearbyRoomMessageEntity
→ authoritative persisted message row

NearbyRoomMessageDao
→ focused candidate reads
→ insert-only prepared write

Existing retry / relay
→ reload and transmit exact persisted wire body
```

GI2E2 must not introduce:

- a Group Invitation repository;
- a quota table;
- a second message store;
- an Invitation retry lifecycle;
- a transport kind;
- a BLE-specific Invitation path;
- a generic structured-content framework.

---

# 3. Two distinct GI1 policies

GI2E2 must not use one decoder for both ownership and quota.

## 3.1 Structured ownership classifier

```text
classifyGi1StructuredCandidate(body)
```

Authority rule:

```text
exact top-level discriminator t == "gi"
```

This classifier establishes structured GI1 ownership even when the payload is malformed, unsupported or cannot be fully decoded.

It is used for:

- local edit rejection;
- local delete rejection;
- replace/transition rejection;
- remote immutable merge protection;
- preventing conversion from structured GI1 content to normal text.

A persisted exact-GI candidate remains protected regardless of supported-payload validity.

## 3.2 Supported payload decoder

The existing locked supported GI1 decoder is used separately to obtain:

- public/private audience;
- private target participant;
- card identity;
- quota business key.

Only a supported, valid GI1 payload with a matching persisted identity may count as send-limit evidence.

Therefore:

```text
malformed or unsupported exact-GI row
→ immutable protection: YES
→ quota evidence: NO

supported valid exact-GI row
→ immutable protection: YES
→ quota evidence: YES, using its public/private key
```

GI2E2 must reuse the locked GI1 candidate/discriminator semantics. It must not invent a broader or narrower discriminator.

---

# 4. Repository-owned preparation capability

A caller must not be able to construct a valid prepared message by supplying its own message ID, sender, timestamps, expiry or body context.

## 4.1 Preparation request

The repository exposes a narrow preparation operation accepting only caller-owned intent:

```text
prepareGroupInvitationContext(
    roomId,
    audience = Public | Private(targetParticipantId)
)
```

The caller must not provide:

- message ID;
- logical sender ID;
- sender display name;
- creation time;
- effective expiry;
- opaque preparation token.

## 4.2 Frozen repository-owned values

The repository creates and freezes:

```text
preparation token
room ID
message ID
logical sender ID
sender display name
created-at time
effective expiry
audience / private target
```

The message ID is created before public encoding or GI2D encryption.

For private invitation, GI2E4 must use the repository-created message ID and frozen context for the locked GI2D cryptographic context and AAD.

## 4.3 Opaque capability

The returned preparation object is an opaque capability, not a freely constructible data class representing authority.

Required properties:

```text
unpredictable token
repository-issued
bound to every frozen field
one-shot
not durable
not a second persistence system
```

The smallest valid implementation is an in-memory, bounded repository-owned preparation registry keyed by an unpredictable token.

The registry is not quota state, message state or retry state. It only proves that the submitted finalized body was produced from a repository-issued preparation context.

The preparation object must not expose a public/internal constructor that lets arbitrary module code mint an accepted capability.

## 4.4 Submission API

Conceptual API:

```text
insertPreparedGroupInvitation(
    opaquePreparation,
    finalizedOuterBody,
    optionalLocalRenderSourceBody
)
```

The repository resolves the token and verifies that all supplied/exposed frozen fields match its own record.

A token is consumed for exactly one submission attempt. Any retry requires a new preparation, new message ID and, for private invitation, new encryption.

This prevents:

- token replay;
- alternate-body replay;
- message ID regeneration around existing ciphertext;
- body/context splicing.

## 4.5 Preparation invalidation

The preparation becomes unusable after any submission attempt, including:

- success;
- send-limit rejection;
- message-ID collision;
- stale room expiry;
- sender change;
- mute rejection;
- invalid body/source validation.

Unexpected database failure may be surfaced as failure, but the capability still remains consumed. This is the simpler and safer one-shot rule.

---

# 5. Authoritative finalized-body validation

Before the database transaction, deterministic validation must establish:

```text
preparation token exists
opaque capability matches repository record
outer body is exact-GI structured content
outer body is supported and valid for local prepared insertion
outer card ID == repository-created message ID
outer audience == frozen audience
private outer target == frozen target
public local source == null
private local source passes authoritative validation
```

No sanitized, normalized or re-encoded replacement of the finalized outer body is allowed.

The exact supplied outer body becomes the persisted wire body only if validation succeeds.

---

# 6. Sender-local source validation

GI2E4 may construct `local_render_source_body`, but GI2E2 owns authoritative insertion validation.

## 6.1 Public invitation

```text
local_render_source_body must be null
```

Any non-null source is rejected.

## 6.2 Private invitation

When a source is supplied, insertion validation must prove:

1. The preparation audience is Private.
2. The frozen logical sender is the current local sender at insertion time.
3. The source decodes as the locked supported private-inner GI1 content.
4. The source belongs to the same card/message identity.
5. Its invitation semantics agree with the finalized private outer envelope and frozen target.
6. Canonical re-encoding equals the supplied String byte-for-byte.
7. The source is inner render content, not an outer encrypted envelope.

Validation sequence:

```text
decode supplied source
→ canonical encode decoded private-inner value
→ require canonical output == supplied source
```

Semantically valid but noncanonical source is rejected. GI2E2 must not silently replace it.

## 6.3 Trust boundary

Only the local prepared-private insertion path may persist a non-null source.

Remote, relayed and received construction paths must explicitly set:

```text
local_render_source_body = null
```

Remote merge must always preserve existing local source and must never copy it from incoming data.

---

# 7. Persistence extension

Add exactly two columns to `NearbyRoomMessageEntity`:

```text
local_render_source_body TEXT NULL
is_hidden_locally INTEGER NOT NULL DEFAULT 0
```

Entity defaults:

```text
local_render_source_body = null
is_hidden_locally = false
```

No new index is authorized because GI2E2 introduces no indexed query use case for either field.

`is_hidden_locally` is installation-local state under the locked invariant:

```text
one production local actor ↔ one peer_mesh.db
```

If production multi-actor sharing enters scope, implementation must stop for viewer-keyed architecture review.

---

# 8. Migration

Baseline database version:

```text
54
```

Target:

```text
55
```

Migration:

```sql
ALTER TABLE nearby_room_messages
ADD COLUMN local_render_source_body TEXT;

ALTER TABLE nearby_room_messages
ADD COLUMN is_hidden_locally INTEGER NOT NULL DEFAULT 0;
```

Required updates:

- `AppDatabase.version = 55`;
- `MIGRATION_54_55`;
- registration in the existing migration list;
- exported Room schema `55.json`;
- migration instrumentation coverage.

The migration must not recreate the table, rewrite message bodies, alter the primary key, change indices or infer legacy Invitation state.

Existing rows receive:

```text
local_render_source_body = null
is_hidden_locally = false
```

If the implementation branch already uses version 55, migration numbering must be rebased before code changes.

---

# 9. DAO seams

## 9.1 Insert-only prepared path

Add a dedicated DAO method using:

```text
OnConflictStrategy.ABORT
```

Existing generic `REPLACE` behavior remains for legacy normal-message flows in GI2E2 to minimize blast radius.

The prepared path must never use `REPLACE`.

## 9.2 Candidate-row query

Add a focused sender-in-room candidate query using logical author compatibility:

```text
owner_device_id == logical sender
OR
(owner_device_id IS NULL AND sender_device_id == logical sender)
```

The DAO selects rows only. It must not parse JSON or decide quota semantics.

Kotlin policy performs:

```text
exact-GI ownership classification
supported payload decoding
public/private business-key comparison
```

Candidate rows must not be excluded merely because mutable legacy metadata is present. A structured row must not silently release protection or quota classification through a mutation artifact.

---

# 10. Exact send-limit keys

Public:

```text
(room ID, logical sender)
```

Private:

```text
(room ID, logical sender, target participant)
```

Message primary-key uniqueness is not send-limit uniqueness.

A public invitation and private invitations to different participants use distinct business keys according to the locked product contract.

Only supported valid GI1 payloads produce quota keys.

---

# 11. Atomic transaction

All authoritative current-state decisions and insertion occur inside one Room transaction.

## 11.1 Transaction time

`transactionNowMs` is captured exactly once as the first operational statement inside the transaction, after Room has begun transaction ownership:

```text
runInTransaction {
    transactionNowMs = nowMsProvider()
    ...
}
```

It must not be captured before entering the transaction.

The same value is used for room-expiry and mute decisions.

## 11.2 Exact expiry rule

At preparation:

```text
effectiveExpiryAtMs = current room.expires_at_ms
```

At insertion transaction:

```text
room exists
room state == ACTIVE
transactionNowMs < room.expires_at_ms
room.expires_at_ms == frozen effectiveExpiryAtMs
```

If room expiry changed after preparation:

```text
StalePreparation
```

GI2E2 must not extend, shorten or rewrite the frozen expiry, outer body or ciphertext. The caller must prepare again.

## 11.3 Transaction ordering

Exact ordering:

```text
BEGIN TRANSACTION

1. Capture transactionNowMs inside transaction.
2. Reload room.
3. Require ACTIVE and not expired at transactionNowMs.
4. Require current room expiry == frozen effective expiry.
5. Reload current local sender; require frozen sender match.
6. Recheck mute state using transactionNowMs.
7. Check existing (room_id, message_id) collision.
8. Query sender candidate rows.
9. Classify exact-GI ownership separately from supported quota decode.
10. Match exact public/private send-limit key.
11. If matching evidence exists, return SendLimitReached with no writes.
12. Insert prepared entity using ABORT.
13. Update participant bookkeeping.
14. Update room bookkeeping.

COMMIT
```

The message insert precedes bookkeeping writes.

## 11.4 Exception and rollback boundary

Constraint exceptions must not be caught inside the transaction lambda.

Correct structure:

```text
try {
    runInTransaction {
        checks
        ABORT insert
        bookkeeping writes
    }
} catch expected constraint outside transaction {
    map result
}
```

The exception must escape the lambda so Room rolls back the entire transaction before result mapping.

A primary-key precheck inside the transaction provides deterministic `MessageIdCollision` behavior. ABORT remains a defensive invariant.

An ABORT after a successful same-key precheck is treated as unexpected database failure unless the implementation can reliably prove the expected primary-key collision. Unrelated database failures must never be mapped to business collision.

---

# 12. Immutable guards

## 12.1 Local edit/delete

For any existing persisted row whose body is classified as exact-GI structured content:

```text
edit → reject
delete → reject
```

Protection does not depend on supported decode success, card validity or current reply state.

The guard executes immediately after loading the row and before any body, timestamp or deletion mutation.

Normal text behavior remains unchanged.

## 12.2 Remote replace and transition

For an existing exact-GI row, incoming data must not:

- replace body;
- convert it to normal text;
- change public/private content;
- change target;
- add or change reply;
- add edit timestamp;
- add deletion timestamp;
- change created time;
- widen or narrow expiry;
- change logical author.

Malformed and unsupported exact-GI rows receive the same transition protection.

Allowed exact-duplicate bookkeeping merge must preserve immutable fields and both local-only fields from the existing row.

## 12.3 Local field preservation

Delivery-state updates, allowed duplicate merge and display-name projection must preserve:

```text
local_render_source_body
is_hidden_locally
```

Incoming transport data never owns either field.

---

# 13. Retry, relay and reporting

Retry and relay continue to reload the persisted row and transmit exact persisted `body`.

They must never:

- use `local_render_source_body` as wire body;
- re-encrypt;
- allocate a new message ID;
- rebuild Invitation content;
- mutate quota evidence.

GI2E2 must audit existing logging/reporting paths before adding the local source column. No log, report payload, entity dump or debug export may expose:

- sender-local plaintext;
- credential;
- full private outer body;
- ciphertext, nonce or ephemeral key.

If an existing generic entity serialization would expose the new field, that leakage is a GI2E2 security blocker and must be corrected within scope.

---

# 14. Result model

The repository must distinguish at least:

```text
Inserted
InvalidPreparation
InvalidPreparedContent
RoomUnavailable
StalePreparation
SenderChanged
SenderMuted
SendLimitReached
MessageIdCollision
```

Unexpected database failures are not converted into these business outcomes.

The exact Kotlin representation may follow existing repository conventions, but quota and collision must remain distinguishable.

---

# 15. Required tests

## 15.1 Authority and capability

- Caller cannot mint an accepted preparation context.
- Unknown token rejected.
- Reused token rejected.
- Token is one-shot after every submission attempt.
- Room A capability cannot insert into room B.
- Public capability cannot submit private body.
- Private target A capability cannot submit target B body.
- Body card ID must equal repository-created message ID.
- Caller cannot override frozen sender/time/expiry.

## 15.2 Source validation

- Public source rejected.
- Corrupt private source rejected.
- Noncanonical private source rejected.
- Wrong-card source rejected.
- Outer/inner target mismatch rejected.
- Sender mismatch after preparation rejected.
- Remote path cannot persist source.

## 15.3 Migration

- 54→55 succeeds.
- Existing body and primary key unchanged.
- Existing indices unchanged.
- Existing rows receive null/false defaults.
- Exported schema validates.

## 15.4 Insert-only DAO

- First insert succeeds.
- Duplicate primary key fails.
- Existing row remains unchanged after collision.
- Local fields are not replaced.
- Legacy normal-message upsert regression remains unchanged.

## 15.5 Send-limit

Public:

- first succeeds;
- same sender/room second ID blocked;
- different room allowed;
- different sender allowed;
- private evidence does not consume public key.

Private:

- first sender→target succeeds;
- same sender→same target blocked;
- different target allowed;
- different sender allowed;
- public evidence does not consume private key.

Malformed/unsupported exact-GI:

- immutable protection applies;
- public/private quota evidence is not produced.

## 15.6 Concurrency

Two concurrent same-key public submissions with different IDs:

```text
exactly one Inserted
exactly one SendLimitReached
exactly one persisted evidence row
```

Same for private same-target submissions.

Different private targets may both succeed.

Tests must exercise Room transactions, not an application mutex as authority.

## 15.7 Transaction time and expiry

- Time is sampled after transaction begins.
- Room expiring while waiting for writer lock is rejected.
- Mute change while waiting is evaluated at transaction-local time.
- Room expiry change after preparation returns StalePreparation.
- Frozen body/expiry is never rewritten.

## 15.8 Rollback

- Constraint exception escapes transaction lambda.
- No participant update commits after failed insert.
- No room bookkeeping commits after failed insert.
- Unexpected database failure is not mapped to collision.

## 15.9 Immutable guards

- Supported public/private rows cannot edit/delete/replace.
- Malformed exact-GI rows cannot edit/delete/replace.
- Unsupported exact-GI rows cannot edit/delete/replace.
- Structured-to-normal transition rejected.
- Reply/edit/delete/created-time/expiry/author changes rejected.
- Normal text edit/delete remains unchanged.

## 15.10 Retry and local-field preservation

- Mark-sent changes only delivery bookkeeping.
- Exact wire body unchanged.
- Local source and hidden state preserved.
- Retry sends outer body, never local source.
- Retry does not invoke GI2D or allocate ID.

---

# 16. Planned implementation sequence

```text
Patch 1 — exact-GI ownership classifier and supported-decoder separation
Patch 2 — repository-owned opaque preparation capability
Patch 3 — additive entity/migration/schema
Patch 4 — focused DAO candidate query and ABORT insert
Patch 5 — atomic transaction with internal time and exact expiry rule
Patch 6 — authoritative sender-local source validation
Patch 7 — local and remote immutable guards
Patch 8 — retry/reporting/local-field regression audit
Patch 9 — focused verification and implementation report
Patch 10 — independent review before production authorization
```

No production patch may begin until this corrected plan passes focused independent re-review.

---

# 17. Stop conditions

Stop and return to architecture review if implementation requires:

- durable preparation storage;
- a quota table;
- a second repository or message store;
- a new transport or BLE path;
- Invitation-specific retry/relay state;
- protocol or GI2D AAD changes;
- automatic message-ID regeneration after finalization;
- sanitizing or re-encoding the finalized outer body;
- production multi-actor sharing of `peer_mesh.db`;
- global replacement of legacy Nearby message write semantics.

---

# 18. Verdict

```text
GI2E1 Revision 3.2: LOCKED
GI2E2 corrected architecture direction: PASS
Prior BLOCKER: RESOLVED by explicit lock action
Prior MAJOR findings: RESOLVED IN PLAN
Prior MINOR findings: RESOLVED IN PLAN
Production source changed: NO
Production implementation: NOT AUTHORIZED
Next step: FOCUSED INDEPENDENT RE-REVIEW
```
