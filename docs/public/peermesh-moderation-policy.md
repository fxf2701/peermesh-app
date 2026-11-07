# PeerMesh Moderation and User Safety Policy

Last updated: 2026-07-04

## Summary

PeerMesh is a local-first communication app designed for private group coordination, delayed synchronization, and peer-to-peer delivery. Because users can communicate directly with each other and can create user-generated content, PeerMesh provides in-app safety controls and a moderation workflow that is compatible with privacy-preserving operation.

This document explains the minimum moderation and user-safety policy currently supported by PeerMesh for users, reviewers, and platform partners such as Google Play.

## What PeerMesh supports

PeerMesh supports the following in-app safety controls:

- local user block
- peer reporting
- message reporting
- local hide controls for sensitive content such as location or trail data where applicable
- owner-managed group governance actions such as removing a member from a group
- lightweight Nearby Room moderation such as room-scoped mute

These controls are designed to work even when the device is temporarily offline.

## Core principles

PeerMesh applies the following moderation principles:

- personal safety controls should be immediate and available in-app
- privacy should be preserved by default
- reporting should be user-initiated, not based on blanket background inspection of private chats
- offline-created reports should be queued locally and sent later when a network path is available
- group-level enforcement and personal blocking are separate actions

## Local block

PeerMesh allows a user to block another peer locally inside the app.

When a user blocks a peer:

- messages from that peer are hidden from the blocking user
- future chat content from that peer is hidden from the blocking user in supported user-facing surfaces
- the blocking device may still receive and relay transport envelopes needed for mesh delivery even when that content is hidden locally
- the action applies only to the blocking user by default
- the action does not automatically remove the blocked peer from the group for everyone else
- the action does not deny room join or group membership by itself

When a user blocks a peer, PeerMesh treats that block primarily as a local visibility control.
In practical terms, the blocking user should stop seeing new messages from the blocked peer, but other participants may still see those messages unless they also block that peer.

This design protects the user immediately without giving ordinary members the power to silence another user for the entire group.

## Reporting

PeerMesh supports both peer reporting and message reporting.

Users can report:

- a peer
- a specific message

Reports may include:

- reporter identifier
- reported peer identifier
- group identifier
- reason code
- timestamp
- limited technical metadata needed to identify the reported content

For message reports, readable message text is not included by default unless the reporting user explicitly chooses to include it.

When a registry moderation endpoint is available, submitted reports may be delivered to PeerMesh registry-side moderation infrastructure for operator review and audit handling.

PeerMesh does not guarantee that every report will result in immediate removal, restriction, or other enforcement action.
Reports are intended to create a reviewable moderation signal while preserving user-initiated and privacy-limited disclosure.

## Offline-first report handling

PeerMesh is designed to function in degraded and offline environments. Because of that:

- reports can be created and stored locally while offline
- reports are queued on the device
- queued reports are sent later when a network path becomes available

If no registry moderation endpoint is available, the report remains stored locally until delivery becomes possible or another supported support workflow is provided.

## Group owner governance

PeerMesh separates personal safety actions from group governance.

- `Block` protects one user locally.
- `Report` creates a moderation signal for registry-side review when delivery is possible.
- `Remove from group` is a governance action that should be limited to the group owner or another authorized role if such a role is introduced later.

In the current V1 implementation, member removal is intended to:

- move the removed member to read-only behavior on synced clients
- stop the removed member from sending new group activity on synced clients
- prevent normal rejoin through old invitation material after synced removal and key rotation

Owner-approved rejoin after removal is not part of the current V1 implementation and is planned separately for a future version.

This separation helps prevent abuse of moderation controls while still giving users direct safety tools.

For the current public policy on how group join requests, member removal, rejoin prevention, and key rotation are intended to work, see:

- [PeerMesh Group Membership and Member Removal Policy](./peermesh-group-membership-and-removal-policy.md)

## Nearby Room moderation

Nearby Room uses a lighter moderation model than Private Group.

This is because Nearby Room is:

- temporary
- visible to nearby participants
- designed for short-lived public coordination
- not based on durable private-group membership

For Nearby Room, PeerMesh should support:

- local block by any participant
- peer reporting
- message reporting
- room-owner mute for the current room

In Nearby Room, `Block user` is a participant-level safety control, not an owner-only privilege.
Any participant should be able to block another participant locally, which may hide that user's messages for the blocking device without turning the block into authoritative room denial.

For Nearby Room, PeerMesh should not rely on:

- authoritative kick / remove behavior equivalent to private-group member removal
- durable bans that outlive the room by default
- complex moderator roles in the MVP

Nearby Room mute is intended to be room-scoped and temporary.
In practical terms, this means the room owner may emit room-scoped mute and unmute events, devices that receive those events should enforce them locally for that room, and the mute state should expire with the room unless a broader local block is also applied.

Nearby Room mute is not implemented as a low-level transport drop rule.
Devices may still receive and forward BLE envelopes needed for mesh traffic, while app-layer message handling can suppress new message materialization from a muted participant after the mute state is known.

For Nearby Room MVP, PeerMesh should prioritize these controls in this order:

1. `Block user` locally
2. `Report user`
3. `Report message`
4. `Owner mute in room`

## Privacy posture

PeerMesh does not require blanket centralized inspection of all private conversations in order to provide basic moderation controls.

PeerMesh aims to follow this privacy posture:

- no default upload of all chat content for review
- no always-on proactive reading of all private messages by operators
- report-driven disclosure only
- minimal metadata by default
- explicit opt-in before sending plaintext excerpts where supported
- reporter network IP is not treated as report evidence; it may be used transiently for abuse prevention such as rate limiting

Where moderation reports are delivered successfully, authorized PeerMesh operators may review the submitted report record and related audit history needed to process that report.

## Acceptable use

Users may not use PeerMesh for:

- harassment or abuse
- threats of violence
- spam
- impersonation or deceptive identity abuse
- sharing content in violation of applicable law or platform policy
- misuse of location-sharing features to target, stalk, or intimidate others

PeerMesh may restrict, review, or escalate reports associated with such behavior where infrastructure and operations support that action.

## Limitations

Because PeerMesh is local-first and may operate with encrypted or delayed synchronization:

- some reports may be delivered later rather than immediately
- some reports may be reviewed only after successful delivery to the registry moderation workflow
- some enforcement actions may be limited to the local device unless a group owner or server-side moderation path is involved
- some reports may result in review with no further action if the available evidence is insufficient or the report does not indicate a policy violation
- users should not assume that private content is proactively reviewed by PeerMesh operators

## Contact and policy updates

PeerMesh may update this policy as the product, moderation systems, and platform requirements evolve.

For support or policy questions, use the official PeerMesh support or operator contact channels published by the project.
