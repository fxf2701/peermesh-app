# Block Hidden-Receive Behavior Checklist

Status:
- Draft
- Scope: audit and implementation alignment for block semantics across Private Group and Nearby Room

Related documents:
- `docs/problems/private_group_blocking_behavior_gaps.md`
- `docs/public/peermesh-moderation-policy.md`
- `docs/moderation/peermesh-android-moderation-implementation-spec.md`
- `docs/specs/NEARBY_ROOM_BLUEPRINT.md`

## Rule To Enforce

- `block => local hidden-receive / hidden-render behavior`
- `block != send suppression`
- `block != join denial / membership denial`

Practical interpretation:
- If `B` blocks `A`, device `B` should not materialize `A`'s chat messages into the local message table.
- Device `B` may still receive envelopes and continue relay/mesh behavior.
- Device `B` may still send messages in the same room/group.
- The same block relationship must not be reused as:
  - room join denial
  - group membership denial
  - discovery suppression
  - invite suppression by default

## 1. Shared Block Relationship Resolution

- [x] Define one canonical helper for `isBlockedRelationship(peerA, peerB)` or equivalent query contract.
- [ ] Confirm the identity key used for block matching is stable across Private Group and Nearby Room.
- [ ] Audit legacy scoped block rows and decide how they participate in the hidden-receive rule during migration.
- [x] Ensure the block lookup used by send gating is global, not room-scoped or group-scoped.
- [ ] Document whether hidden behavior is triggered by either direction of stored block:
- [ ] `A blocked B` => device `A` hides `B`
- [ ] `B blocked A` => device `B` hides `A`

Acceptance criteria:
- [ ] Hidden-message gating has one clear source-of-truth for block relationship evaluation.
- [ ] Private Group and Nearby Room do not implement divergent peer-matching rules.

## 2. Private Group Ingest Audit

Files to inspect:
- [ ] [ModerationRepository.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/moderation/ModerationRepository.kt:1)
- [ ] [GroupChatActivity.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/GroupChatActivity.kt:1)
- [ ] [UserOutbox.kt](../../android/app/src/main/java/app/peermesh/UserOutbox.kt)
- [ ] Any reducer / ingest path that can still materialize a locally blocked peer message

- [ ] Inventory every Private Group ingest/materialization entry point:
- [ ] base message
- [ ] reply message
- [ ] edit event
- [ ] delete tombstone if it targets a blocked peer message
- [ ] relay or bridge ingest paths
- [ ] Decide exactly which event types should skip message-table materialization versus remain transport-visible.
- [x] Gate hidden behavior at ingest/materialization rather than compose/send.
- [ ] Verify event ingest still persists relay/dedupe state while skipping message-table materialization.
- [ ] Verify blocked relationship does not remove group membership or break group open/join flows.
- [ ] Verify blocked relationship does not silently mutate members list semantics.

Acceptance criteria:
- [ ] Private Group still sends normally even when blocked peers coexist in the same room.
- [ ] Blocked-peer chat events are not materialized into the local message table.
- [ ] Membership and invite state remain unchanged unless a separate rule handles them.

## 3. Nearby Room Ingest Audit

Files to inspect:
- [ ] [NearbyRoomRepository.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/nearby/NearbyRoomRepository.kt:1)
- [ ] [NearbyRoomActivity.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/NearbyRoomActivity.kt:1)
- [ ] [NearbyRoomTransportManager.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:1)
- [ ] [NearbyRoomTransportReceiver.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:1)

- [ ] Inventory every Nearby Room ingest/materialization entry point:
- [ ] direct message snapshot ingest
- [ ] relay message data ingest
- [ ] offer/fetch/recovery paths that need hidden message availability
- [ ] Define whether owner/system control traffic remains exempt from hidden-message filtering.
- [x] Gate local hidden behavior at message materialization time instead of local send time.
- [x] Preserve relay/recovery ability for hidden messages without inserting them into `nearby_room_messages`.
- [x] Keep room discovery, room open, key bootstrap, metadata request, and join flows independent from block semantics.
- [ ] Confirm block still only hides/filters content where intended, without introducing accidental room access denial.

Acceptance criteria:
- [ ] Nearby Room still allows normal outbound send even if everyone else is blocked.
- [ ] Hidden blocked-peer messages are not inserted into `nearby_room_messages`.
- [ ] Nearby Room join/access behavior is unchanged by this rule.
- [ ] Recovery/relay paths continue to function for hidden messages.

## 4. Compose And UX Gating

- [x] Keep send affordance unchanged by block state.
- [ ] Decide whether any composer hint is needed at all, or whether block should stay fully silent on send path.
- [ ] Define whether edit/delete remain available on already-sent own messages after block relationship exists.
- [x] Ensure the UX does not imply room/group access denial if block only affects local visibility.

Acceptance criteria:
- [ ] UX does not imply a send restriction that no longer exists.

## 5. Logging And Debuggability

- [x] Add one canonical log label family for blocked-message materialization decisions.
- [x] Log `apply/skip` style reasons at the decisive ingest/materialization gate.
- [ ] Include enough metadata to debug:
- [x] actor peer id
- [ ] target peer id or actor context
- [x] surface: `private_group` or `nearby_room`
- [x] path: `ui`, `repository`, `outbox`, `transport`, or `recovery`
- [ ] reason: `blocked_peer`
- [ ] Ensure logs distinguish:
- [ ] message hidden due to local block
- [ ] message hidden by render/filter rule
- [ ] room/group join skipped for unrelated reasons

Acceptance criteria:
- [ ] A test run can tell exactly where blocked message materialization was skipped.
- [ ] Logs do not blur hidden-message behavior with join/access decisions.

## 6. Unit Tests

- [ ] Add Private Group tests:
- [ ] blocked peer chat event is stored for dedupe/relay but skipped for message-table materialization
- [ ] unblocked peer chat event still materializes normally
- [ ] join/membership behavior unaffected
- [ ] Add Nearby Room tests:
- [ ] blocked peer message is not inserted into `nearby_room_messages`
- [ ] hidden-message relay cache serves offer/fetch path
- [ ] room join/access behavior unaffected
- [ ] Add regression tests for legacy block rows if backward-compat behavior is required.

Acceptance criteria:
- [ ] Tests fail on old send-suppression assumption.
- [ ] Tests pass only when hidden-receive behavior is enforced consistently.

## 7. Manual QA

- [ ] Private Group:
- [ ] `A` blocks `B`, then device `A` no longer shows/materializes `B` chat messages`
- [ ] `A` and `B` can still coexist in the same group
- [ ] `A` can still send messages in the group
- [ ] invite/join behavior unchanged unless another feature controls it
- [ ] Nearby Room:
- [ ] `A` blocks `B`, then device `A` no longer inserts `B` room messages into local room message table
- [ ] `A` and `B` can still discover/open/join the same room
- [ ] `A` can still send messages in the room
- [ ] Existing room/system control traffic still works as expected
- [ ] Mixed-device run with logs captured for both directions

Acceptance criteria:
- [ ] Real-device behavior matches the rule text exactly.

## 8. Documentation Alignment

- [ ] Update moderation policy docs to say `block => local hidden-receive / hidden-render behavior`.
- [ ] Update implementation spec to separate:
- [ ] send behavior
- [ ] content hiding
- [ ] membership governance
- [ ] join/access control
- [ ] Update any backlog or milestone docs that still imply block should deny join.
- [ ] Keep issue doc linked as historical context, but move active work tracking to this checklist.

Acceptance criteria:
- [ ] Product, engineering, and QA docs all describe the same block semantics.
