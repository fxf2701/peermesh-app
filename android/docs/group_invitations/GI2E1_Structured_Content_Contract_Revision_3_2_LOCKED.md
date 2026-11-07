# GI2E1 — Structured Content Contract

**Revision:** 3.2 Consolidated  
**Status:** LOCKED  
**Lock date:** 2026-08-05  
**Supersedes:** all prior GI2E1 draft, delta-only, and incomplete Revision 3/3.1/3.2 documents  
**Implementation authority:** No production implementation until this consolidated contract receives PASS and an explicit lock action.

---

# 1. Purpose

GI2E1 defines the authoritative structured-content contract for Group Invitations carried by Nearby Chat.

A Group Invitation is one immutable Nearby Room message whose `body` contains one canonical GI1 representation.

This contract freezes:

- structured ownership detection;
- canonical public and private representations;
- repository-owned preparation context;
- immutable message behavior;
- sender-local private rendering source;
- local Hide/Unhide persistence;
- send-limit evidence semantics;
- retry, relay, BLE, ordering and retention ownership;
- required implementation boundaries for GI2E2–GI2E5.

This contract does not implement production code.

---

# 2. Authority and precedence

Implementation must follow, in order:

1. `android/docs/Engineering_Constitution.md`
2. `Product_Architecture_Invariants.md`
3. `Group_Invitation_Spec_v2.md`
4. `GI2E_Product_Architecture_Decision_Invitation_Immutability_Local_Hiding.md`
5. `Group_Invitation_Spec_v2_GI2E_Amendment.md`
6. GI1R and locked GI2A–GI2D contracts
7. this consolidated GI2E1 contract
8. GI2E roadmap documents

Where an older GI2E1 draft or delta conflicts with this consolidated document, this document supersedes it after lock.

---

# 3. Core product decisions

## 3.1 One invitation is one Nearby message

An Invitation Card is a UI projection of one persisted Nearby message.

It is not:

- a second entity;
- a placeholder message;
- a transport-specific message kind;
- an Invitation-owned retry object;
- an Invitation-owned relay object;
- a separate lifecycle.

## 3.2 Immutability

After successful creation, a Group Invitation is immutable until normal Nearby retention or hard purge removes the row.

It cannot be:

- edited;
- soft-deleted;
- recalled or unsent;
- replaced with normal text;
- replaced with another structured body;
- changed by a remote edit/delete snapshot;
- assigned a reply target;
- given a different creation time;
- given a widened or narrowed effective expiry.

Delivery and local presentation bookkeeping may change only where explicitly allowed by this contract.

## 3.3 Local Hide/Unhide

Authorized viewers may locally Hide or Unhide the same invitation message.

Hide/Unhide:

- is reversible presentation state;
- does not mutate `body`;
- does not change `messageId`;
- does not change ordering position;
- does not generate a transport event;
- does not affect quota evidence;
- does not affect retry, relay, duplicate handling or retention;
- does not create another message.

---

# 4. Structured ownership and supported decoding

Two separate policies are mandatory.

## 4.1 GI1 structured ownership classifier

`classifyGi1StructuredCandidate(body)` determines only whether the exact top-level discriminator is:

```text
t == "gi"
```

This classifier is used for immutability and transition guards.

A persisted exact-GI body remains structurally owned by GI1 even when it is:

- malformed;
- unsupported;
- unknown-version;
- invalid under the current supported decoder.

Therefore an exact-GI row must not be edited, deleted, converted to normal text or replaced merely because supported decoding fails.

## 4.2 Supported GI1 decoder

The supported GI1 decoder is used for:

- public/private semantic validation;
- `cardId` validation;
- target extraction;
- canonical re-encoding;
- send-limit evidence.

Only a supported, valid, canonical payload may be newly sent.

Malformed or unsupported exact-GI persisted rows receive immutable protection but do not provide public/private send-limit keys.

---

# 5. Canonical representations

## 5.1 Outer body

Every locally created Group Invitation must pass:

```text
decode supplied outer body
→ validate supported payload
→ canonical re-encode
→ require exact String equality with supplied body
```

The repository must reject, not normalize or replace:

- reordered JSON;
- alternate whitespace;
- unknown fields;
- semantically equivalent noncanonical JSON;
- noncanonical Base64URL or other alternate primitive representation.

The exact validated String becomes the persisted and transported `body`.

## 5.2 Public invitation

A public invitation outer body must:

- be a supported canonical GI1 public payload;
- contain `cardId == repository-generated messageId`;
- agree with the frozen public preparation audience;
- have no sender-local private source.

## 5.3 Private outer envelope

A private invitation outer body must:

- be a supported canonical GI1 private encrypted envelope;
- contain `cardId == repository-generated messageId`;
- contain the frozen target participant ID;
- contain the frozen target key identity required by GI2C/GI2D;
- agree with the repository-owned preparation context;
- persist the exact encrypt-once result produced under GI2D.

GI2E must never decrypt or re-encrypt the outer envelope during insertion, retry or relay.

## 5.4 Private sender-local source

A locally sent private invitation must have a non-null `local_render_source_body`.

The source must:

- decode as the supported GI1 private-inner payload;
- canonical re-encode to the exact supplied String;
- be rejected if malformed, unsupported, augmented or noncanonical.

The current private-inner model contains:

- title;
- short description;
- invitation credential.

It does not contain `cardId` or target participant ID. GI2E1 therefore does not require nonexistent inner card/target checks.

Outer/source association is operationally bound by:

```text
one repository-owned preparation
+ one atomically claimed one-shot capability
+ one submission containing outer body and local source
+ one atomic message-row insertion
```

This is not a cryptographic proof that the local source plaintext equals the plaintext encrypted in the outer envelope. Stronger binding would require a separately reviewed GI1/GI2D change and is out of scope.

Public, received and relayed rows must have `local_render_source_body == null` unless the row is the local sender's prepared private insertion.

---

# 6. Repository-owned preparation context

## 6.1 Ownership

Only `NearbyRoomRepository` may create a valid Group Invitation preparation context.

Caller input to preparation is limited to product intent such as:

- room ID;
- public audience; or
- private audience plus selected target participant.

The caller must not supply authoritative:

- message ID;
- logical sender identity;
- sender display projection;
- creation time;
- effective expiry;
- target key identity;
- opaque capability token.

## 6.2 Frozen fields

Preparation freezes at least:

- room ID;
- repository-generated message ID;
- logical sender device ID;
- sender display projection if retained by the message model;
- creation time;
- effective room expiry;
- public/private audience;
- private target participant and pinned key identity when applicable;
- opaque preparation identity.

GI2E3/GI2E4 encode or encrypt using exactly this context.

## 6.3 Opaque capability

The preparation capability must be:

- unpredictable;
- repository-owned;
- not caller-constructible as a valid capability;
- bound to the frozen preparation record;
- one-shot.

Submission must atomically claim the preparation record before validation:

```text
atomic remove/claim(token)
→ exactly one thread receives the record
→ all other submissions return InvalidPreparation
```

The winning submission consumes the capability regardless of later success or failure.

## 6.4 Registry lifecycle

The registry is in-memory only; no preparation table or worker is authorized.

It must have:

- a fixed maximum number of pending records;
- a finite lifetime per record;
- deterministic expired/oldest eviction during prepare or claim;
- no background worker.

The implementation plan must choose explicit constants. The recommended default is:

```text
maximum pending preparations: 64
default preparation lifetime: 5 minutes
```

An expired, evicted, unknown or already claimed capability returns `InvalidPreparation`; the caller must prepare again.

Process death invalidates pending preparations. This is accepted because no message has yet been successfully created.

---

# 7. Effective expiry

At preparation:

```text
effectiveExpiryAtMs = current authoritative room expiry
```

At insertion, one `transactionNowMs` must be captured as the first business statement inside the Room transaction.

The transaction must require:

```text
room exists
room state is ACTIVE
transactionNowMs < current room expiry
current room expiry == frozen effectiveExpiryAtMs
```

If room expiry changed after preparation, return `StalePreparation`.

Do not:

- rewrite the context;
- adjust the outer body;
- widen or narrow expiry;
- reuse private ciphertext under a changed expiry.

A new preparation is required.

---

# 8. Atomic send limits

Authorized limits remain:

```text
Public:
once per logical sender per room

Private:
once per logical sender per target participant per room
```

The existing immutable Nearby message row is the evidence.

No Invitation-specific quota table, repository, entity or lifecycle is allowed.

## 8.1 Candidate query

The DAO may select a focused set of rows for the same room and logical sender using existing author compatibility semantics.

SQL must not parse GI1 JSON.

## 8.2 Evidence classification

For each candidate row:

1. exact-GI ownership may be classified for diagnostics/guards;
2. supported valid decode determines whether the row supplies a public/private quota key;
3. `cardId` must match persisted `messageId` for valid evidence.

Normal text, malformed GI1 and unsupported GI1 do not provide a quota key.

Hide state, delivery state changes and sender-local projection do not release quota.

## 8.3 Transaction authority

The authoritative operation is one serialized Room transaction:

```text
transaction begins
→ transactionNowMs captured inside transaction
→ reload room and current sender/mute state
→ verify frozen expiry and identity
→ check message-ID collision
→ query candidate rows
→ decode supported quota evidence
→ reject matching business key
→ insert exact prepared row using ABORT
→ update participant/room bookkeeping
→ commit
```

No UI mutex or preflight check replaces this transaction.

The insert must occur before bookkeeping writes where practical.

An expected insert exception must escape the transaction lambda so Room rolls back all writes. Business-result mapping occurs outside the transaction.

---

# 9. Insert-only persistence

Prepared invitations use a dedicated DAO insertion seam with:

```text
OnConflictStrategy.ABORT
```

The generic legacy `REPLACE`/upsert path may remain for existing normal-message flows in GI2E2, but it must not be used to create a prepared invitation.

A primary-key collision returns `MessageIdCollision` only when confirmed as the expected `(roomId, messageId)` conflict.

No automatic message-ID regeneration is allowed. A new ID requires a new preparation and, for private invitations, new GI2D encryption.

---

# 10. Persistence fields

GI2E2 may add exactly:

```text
NearbyRoomMessageEntity.local_render_source_body: String?
NearbyRoomMessageEntity.is_hidden_locally: Boolean = false
```

Required additive migration:

```text
54 → 55
```

Existing rows receive:

```text
local_render_source_body = null
is_hidden_locally = false
```

No index is added without a demonstrated query use case.

## 10.1 Local field isolation

Both fields are installation-local and excluded from:

- wire bodies;
- transport codecs;
- retry payload selection;
- relay payload selection;
- remote snapshot authority;
- generic reporting of confidential source text;
- logs.

Remote ingest must initialize both fields to null/false and must preserve existing local values during merge.

---

# 11. Hide/Unhide ownership

Approved persistence:

```text
NearbyRoomMessageEntity.is_hidden_locally
```

Current invariant:

```text
one production local actor
↔
one peer_mesh.db
```

`is_hidden_locally` is installation-local presentation state.

Debug/test actor switching is an accepted limitation and is outside isolation guarantees.

Stop condition:

```text
production multi-actor support sharing peer_mesh.db
→ stop implementation/release
→ architecture re-review
→ approve viewer-keyed persistence first
```

---

# 12. Immutable mutation guards

## 12.1 Local operations

For every persisted exact-GI row, authoritative application/repository guards must reject:

- edit;
- soft-delete;
- recall/unsend;
- reply assignment;
- body replacement;
- structured-to-normal transition;
- structured-to-different-structured transition.

Protection applies even when supported GI1 decoding fails.

## 12.2 Remote merge

For an existing exact-GI row, incoming snapshots must not change:

- logical author;
- message ID;
- reply target;
- exact body;
- creation time;
- effective expiry;
- edit timestamp;
- delete timestamp.

Allowed bookkeeping may include only existing generic safe projections such as delivery/received metadata or sender display projection where source policy already permits it.

Remote merge must preserve:

- `local_render_source_body` from existing local row;
- `is_hidden_locally` from existing local row.

The normal-text reducer behavior remains unchanged outside exact-GI ownership.

---

# 13. Retry, relay, BLE and ordering

Nearby Room retains ownership of:

- persistence;
- message identity;
- ordering;
- history;
- duplicate handling;
- retry;
- relay;
- retention;
- hard purge;
- transport handoff.

Retry and relay reload and transmit the exact persisted outer `body`.

They must not:

- use `local_render_source_body` as wire data;
- allocate a new message ID;
- regenerate public JSON;
- decrypt or re-encrypt private content;
- introduce an Invitation-specific retry/relay lifecycle.

GI1 remains within the existing Nearby message transport kind.

BLE may apply the generic final-envelope admission/preflight seam authorized by the roadmap. No Invitation-specific BLE message kind or routing path is allowed.

---

# 14. Audience and presentation

## Public

Every participant authorized by the existing room presentation policy may render the public card and independently Hide/Unhide it.

## Private

Only:

- the logical sender, using the validated local source; and
- the intended target, after successful GI2D decryption,

may render the private card.

A non-target peer may store/relay the opaque outer body but must not materialize plaintext, visible timeline content or actions.

Reply is prohibited for all Group Invitations.

---

# 15. Security and logging

Do not log or report:

- invitation credential;
- private-inner source;
- full outer body;
- ciphertext;
- nonce;
- ephemeral key;
- recipient public key;
- full canonical cryptographic context.

Operational logs may contain non-secret identifiers and result reasons only where consistent with existing policy.

Adding `local_render_source_body` requires an audit of generic entity logging, debug export and moderation/report builders. Any automatic full-entity serialization that could expose it is a production blocker.

---

# 16. Required result distinctions

The GI2E2 insertion boundary must distinguish at least:

- `Inserted`;
- `InvalidPreparation`;
- `InvalidPreparedContent`;
- `StalePreparation`;
- `RoomUnavailable`;
- `SenderMuted`;
- `SendLimitReached`;
- `MessageIdCollision`.

Unexpected database failures must not be mislabeled as business collisions.

---

# 17. Mandatory tests

## Preparation capability

- repository creates message ID and frozen context;
- caller cannot forge a valid preparation;
- unknown, expired, evicted and reused tokens fail;
- two concurrent submissions of one token produce exactly one claimant;
- only the claimant enters validation/transaction;
- process-death invalidation is accepted and documented;
- registry remains within configured bound.

## Canonical outer representation

For public and private outer bodies:

- canonical body accepted;
- reordered JSON rejected;
- alternate whitespace rejected;
- unknown fields rejected;
- alternate noncanonical primitive encoding rejected.

## Private local source

- private local source is mandatory;
- canonical supported inner source accepted;
- null, malformed, unsupported, noncanonical or augmented source rejected;
- public source rejected;
- remote/relayed rows cannot populate source.

## Atomic quota

- first public succeeds; same sender/room second public is rejected;
- private same sender/target is rejected; different target is allowed;
- concurrent same-key attempts with different message IDs persist exactly one row;
- malformed/unsupported exact-GI rows are immutable but do not supply quota keys;
- hidden and delivery-state changes do not release quota;
- rollback leaves no partial message, participant or room bookkeeping.

## Immutability

- supported, unsupported and malformed exact-GI rows cannot be edited/deleted/replaced;
- remote body/reply/time/expiry/edit/delete changes are rejected;
- exact safe duplicate bookkeeping preserves both local fields;
- normal text edit/delete behavior remains unchanged.

## Migration and persistence

- 54→55 succeeds;
- existing rows retain content and keys;
- local fields receive null/false defaults;
- insert-only collision does not replace a row;
- delivery update preserves body and both local fields.

## Retry/relay/security

- retry and relay use exact persisted outer body;
- no re-encryption or new message ID;
- local source never becomes transport data;
- logging/report audit proves no source leakage.

---

# 18. Phase boundaries

## GI2E2

May implement only:

- repository-owned preparation context and capability registry;
- canonical prepared-content validation foundation;
- atomic send-limit query and enforcement;
- insert-only DAO;
- two additive persistence fields and migration;
- authoritative immutable guards;
- unit/focused persistence tests.

## GI2E3

Public Invitation creation and send orchestration.

## GI2E4

Private target selection, GI2D encrypt-once orchestration, local source construction and prepared submission.

GI2E4 may construct source but GI2E2 remains the authoritative insertion validator.

## GI2E5

Receive parser/sanitizer integration, rendering, audience gating, Hide/Unhide UI, join flow, report projection and full end-to-end behavior.

---

# 19. Prohibited designs

Do not introduce:

- Invitation quota table;
- Invitation repository;
- Invitation transport type;
- Invitation retry/relay lifecycle;
- duplicate message persistence;
- placeholder message rows;
- SQL JSON parsing;
- caller-generated authoritative message IDs;
- caller-forgeable preparation context;
- re-encryption on retry;
- UI-only enforcement;
- viewer-keyed persistence without the multi-actor stop-condition review.

---

# 20. Lock criteria

This consolidated contract may be locked only after an Independent Review confirms:

- it is self-contained;
- no required rule depends on an unavailable Revision 3.1 document;
- it is consistent with higher-precedence product/spec/roadmap and GI2A–GI2D contracts;
- canonical outer/source rules match the actual GI1 models;
- preparation capability and transaction rules are implementable in the current architecture;
- no BLOCKER or MAJOR remains.

After PASS, perform one explicit lock action that records:

- exact file path;
- revision;
- SHA-256;
- review file and verdict;
- date;
- authorization scope for GI2E2 implementation.
