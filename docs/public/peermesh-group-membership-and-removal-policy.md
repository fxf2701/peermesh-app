# PeerMesh Group Membership and Member Removal Policy

Last updated: 2026-06-19

## Summary

PeerMesh uses a privacy-first, local-first group membership model designed to reduce unauthorized re-entry after a member is removed from a group.

This document explains the current public policy for:

- joining a group from an invitation link or QR code
- approving a join request
- removing a group member
- preventing a removed member from rejoining in the current V1 implementation
- the intended owner-approved rejoin path planned for a future version

This policy is intended for users, reviewers, and platform partners such as Google Play.

## Core principles

PeerMesh applies the following membership principles:

- an invitation is a bootstrap mechanism, not permanent proof of membership
- active access to a group depends on receiving the current group access key
- group removal and personal blocking are separate actions
- removed members must not be allowed to continue normal participation on synced clients
- privacy should be preserved by avoiding public display of raw group secrets in normal UI

## Current implementation scope

PeerMesh currently ships member removal as a V1 control.

In V1:

- the group owner can remove a member
- synced clients enforce that removed state for future group participation
- removed members become read-only for that group on synced clients
- invitation material is rotated so removed members should not regain normal access through old join material alone
- owner-approved rejoin is not yet implemented

This means the current policy distinguishes between what is implemented now and what is planned for a later version.

## Invitation links and QR codes

PeerMesh invitation links and QR codes are intended to help a user request access to a group.

They are not intended to function as unlimited permanent admission credentials.

In the current model:

- the app generates group access credentials internally
- normal UI does not require users to manually type or view the raw group passphrase
- opening an invitation link or scanning a QR code starts a join flow based on current invitation material

## Join and invitation behavior

When a user opens an invitation link or scans a QR code:

- the app uses the invitation material that is currently valid for that group
- the requester is not treated as an active member until the join succeeds

A join succeeds only if the invitation material is still valid for that user and group state.

## Group member removal

PeerMesh treats member removal as a group governance action.

Member removal is separate from personal block:

- `Block` protects one user locally on one device
- `Remove member` is a group-level action

At the current stage, member removal is intended to be limited to the group owner.

When the group owner removes a member:

- the app records a group-level removal event
- synced clients mark that peer as removed for the group
- synced clients should stop accepting new group participation from that removed peer
- the removal event should be synchronized with stronger delivery priority and longer retention than ordinary group activity where transport policy allows
- synced valid members should continue normal group participation with updated group access material

## What removal means on synced clients

Once a synced client knows that a peer has been removed from a group:

- new chat activity from that peer should be rejected for that group
- new map, trail, or similar group activity from that peer should be rejected for that group
- invite and invite-sharing actions for that removed peer should be disabled on that synced client
- moderation and message actions for that removed peer should be disabled in that group UI where supported
- the removed peer's own app should stop normal participation after it receives the removal state

This is the expected moderation and governance behavior for synced clients.

## Key rotation after removal

PeerMesh protects future group content by rotating group access keys or equivalent invitation material after a member is removed.

This means:

- old content can remain tied to older keys
- new content should use a newer group access key
- valid remaining members keep access to both old history and new content as needed
- removed members should not receive the new key

In the current V1 protocol direction:

1. the member removal event is synchronized first
2. the key-rotation event is synchronized after that
3. the new key is shared only with valid remaining members

The removal event and the key-rotation event should both receive stronger delivery priority and longer transport retention than ordinary group activity where transport policy allows.

This model helps prevent a removed member from using older credentials to continue accessing new group content.

## Rejoin prevention

PeerMesh does not treat possession of old invitation material as sufficient to regain membership after synced removal.

A removed user who tries to rejoin should be blocked in two ways on synced clients:

- policy check:
  - the requester is recognized as removed and should not regain access automatically
- access-key check:
  - the requester should not receive the latest rotated group key or current invitation material

In V1, this means a removed member is expected to remain unable to rejoin after synced removal and secret rotation.

## Future owner-approved rejoin path

PeerMesh may add a later V2 flow for deliberate owner-approved return.

That planned model is:

- the removed user sends a rejoin request
- the group owner reviews that request
- any approval must reference the specific request it is approving

In other words, owner approval is meant to be tied to a particular rejoin request, not treated as a generic override.

That future path is not part of the currently shipped V1 behavior.

Together, these controls are intended to stop normal re-entry by removed users after clients have synchronized the removal state, while leaving room for a deliberate owner-approved return path in a future version.

## Important limitations

PeerMesh is designed for local-first and delayed-sync environments, so some limitations apply:

- removal enforcement depends on clients learning the removal event
- a client that has not yet synced the removal state may temporarily behave based on older information
- deletion of local secrets on the removed user's device improves hygiene, but does not replace key rotation
- enforcement depends on synchronized client state and therefore may converge over time in degraded-network conditions

Because of these constraints, PeerMesh aims to make removed users unable to continue normal participation on synced clients, while recognizing that enforcement may converge over time rather than instantly in every degraded-network situation.

## Relay and transport

PeerMesh separates group participation from transport behavior.

A removed user should not be able to create new group activity as a member on synced clients.

However, device-level forwarding or relay behavior may still be allowed where the device is acting only as a transport node for opaque payload delivery rather than as an authorized participant creating new group content.

## User-facing expectations

Users should understand the following:

- joining a group from an invitation is a request-based process
- membership depends on current approval and current group access state
- being removed from a group is different from being blocked by another individual user
- once removal has synced, the removed user should no longer be able to participate normally in that group

## Policy intent

This membership and removal model is intended to support:

- safer group governance
- reduced risk of simple re-entry after removal
- compatibility with privacy-preserving communication
- clearer moderation and membership controls for platform review

PeerMesh may update this policy as the membership protocol, key management, and moderation systems evolve.
