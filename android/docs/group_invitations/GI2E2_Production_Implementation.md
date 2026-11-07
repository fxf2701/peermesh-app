# GI2E2 — Production Implementation

**Status:** IMPLEMENTED — AWAITING INDEPENDENT REVIEW  
**Authority:** `GI2E1_Structured_Content_Contract_Revision_3_2_LOCKED.md`  
**Database migration:** 54 → 55

## Scope implemented

- `NearbyRoomRepository` owns opaque preparation issuance, a process-local 64-entry registry,
  five-minute expiry, deterministic eviction, atomic one-shot claim, validation, quota policy,
  and transactional insertion.
- GI1 exact structured ownership is separate from supported-valid decoding.
- Public and private outer bodies require decode, canonical re-encode, and exact String equality.
- Private local render source is mandatory and canonical; it is not decrypted, re-encrypted, or
  checked for fields absent from the GI1 inner model.
- Prepared insertion uses one Room transaction, a timestamp captured as its first business
  statement, room lookup, exact room-expiry equality before active/expired evaluation, sender and
  mute rechecks, collision precheck, canonical candidate quota evidence, `ABORT` insertion, and
  bookkeeping after insertion. A missing room remains `RoomUnavailable`; an existing room with a
  changed expiry is `StalePreparation`, including when it is currently inactive or expired.
- Quota candidates must be exact GI1, decode successfully, have a `cardId` equal to their row
  message ID, and canonically re-encode to the persisted body. Private candidates additionally
  require canonical Base64URL for `kid`, `epk`, `n`, and `ct`; noncanonical evidence cannot
  consume quota.
- The preparation registry samples claim time only while holding its synchronization lock. A
  claimant delayed behind that lock therefore expires against the time it actually claims.
- Exact-GI rows reject local edit/delete and remote immutable-field transitions, including
  malformed and unsupported exact-GI rows.
- Remote construction and merge cannot acquire sender-local source or hidden state. Retry and
  relay continue to transmit the persisted outer `body` only.
- Nearby report plaintext projection excludes exact-GI bodies, and entity diagnostic output
  redacts both outer body and local render source.

## Persistence

```sql
ALTER TABLE nearby_room_messages ADD COLUMN local_render_source_body TEXT;
ALTER TABLE nearby_room_messages ADD COLUMN is_hidden_locally INTEGER NOT NULL DEFAULT 0;
```

No table recreation, primary-key change, index change, quota table, or historical source
inference is performed.

## Focused review-fix regression coverage

`NearbyGroupInvitationPreparationTest` adds coverage for noncanonical public and private quota
candidate rows, expiry-result precedence (including matching expired and missing rooms), and the
registry-lock expiry race. The latter holds the registry lock, advances the controlled clock while
the claimant waits, and asserts `InvalidPreparation` after the lock is released.

## Verification note

`./gradlew :core:testDebugUnitTest` passed. `./gradlew :app:testEmergencyDebugUnitTest` executed
535 tests and has three unrelated map-feature failures: owner-attribution timeline summary,
detail-shell boundary, and area-label placement. Compiling the full app Android-test source set is
blocked by unrelated stale map tests (`GroupMapActivityM3D2Test` and
`OsmdroidMapRuntimeRendererM3D2Test`), so the focused preparation instrumentation test cannot be
executed through that source set. On device `192.168.2.163:41965`,
`NearbyGroupInvitationMigrationTest.migrates54To55AdditivelyAndPreservesMessageKeysAndIndices`
passed (one test, zero failures).

GI2E2 is not locked by this implementation document. Independent production review and manual
review remain required.
