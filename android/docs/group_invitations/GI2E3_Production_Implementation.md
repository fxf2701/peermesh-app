# GI2E3 — Public Invitation Send Production Implementation

Status: IMPLEMENTED — AWAITING VERIFICATION

## Scope

GI2E3 implements only the public Group Invitation send pipeline. Private invitation orchestration and GI2D encryption remain assigned to GI2E4.

## Production flow

```text
NearbyPublicGroupInvitationSendRequest
→ validate caller-owned presentation content
→ NearbyRoomRepository.prepareGroupInvitationContext(Public)
→ bind payload.cardId = preparation.messageId
→ GroupInvitationProtocol.encodePublic
→ NearbyRoomRepository.insertPreparedGroupInvitation(
      finalizedOuterBody = canonical public body,
      localRenderSourceBody = null,
  )
→ submit only the persisted NearbyRoomMessageEntity
→ existing Nearby Room transport / sent marking / relay / owner retry
```

## Authority preservation

- The caller supplies only semantic invitation fields.
- The caller cannot supply card ID/message ID, sender identity, creation time, expiry, encoded body, or persistence state.
- Public invitation `cardId` is always the repository-issued preparation `messageId`; no parallel card identity exists.
- `NearbyRoomRepository` remains the only preparation and insertion authority.
- Public encoding is performed only by `GroupInvitationProtocol.encodePublic`.
- The prepared capability is submitted once; the pipeline does not retry insertion.
- Transport is invoked only after `PreparedGroupInvitationInsertResult.Inserted`.
- The transport receives the exact persisted entity returned by the repository.
- Public invitations always persist `local_render_source_body = null`.

## Existing Nearby behavior preserved

The production submitter mirrors the established Nearby Room message flow:

- room owners use the bounded nearby-peer target snapshot;
- non-owners use the existing broadcast path;
- successful transport marks the persisted row sent and notifies relay recovery;
- failed owner transport enqueues `NearbyRoomOwnerMessageRetryCoordinator` by room ID and message ID;
- retry reloads the persisted body and does not rebuild the invitation.

No invitation-specific transport envelope, retry queue, table, repository, or schema field was added.

## Files added

Production:

- `app/src/main/java/app/peermesh/nearby/NearbyPublicGroupInvitationSendPipeline.kt`

Tests:

- `app/src/test/java/app/peermesh/nearby/NearbyPublicGroupInvitationSendPipelineGI2E3Test.kt`

Documentation:

- `docs/group_invitations/GI2E3_Production_Implementation.md`

## Focused test coverage

The focused JVM suite verifies:

- public preparation audience;
- canonical GI1 public body;
- null local render source;
- exact persisted-entity transport handoff;
- persistence-retaining transport failure result;
- no insertion/transport when preparation is unavailable;
- invalid semantic content rejected before preparation consumption;
- exact mapping of every non-insert repository result;
- `cardId == preparation.messageId` in the canonical encoded body;
- absence of caller-controlled card/message, sender, and expiry fields.

## Independent review correction

The initial implementation incorrectly exposed `cardId` as caller-owned input. Independent review rejected that authority leak. The corrected implementation removes `cardId` from the request and derives it exclusively from `preparation.messageId`.

## Verification status

The initial focused and related regression suites passed before this correction. The corrected focused suite must be rerun before independent re-review.
