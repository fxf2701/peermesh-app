# PeerMesh User Safety Guide (Draft)

Last updated: 2026-06-15

## Overview

PeerMesh gives you direct in-app safety tools for group communication while preserving a privacy-first, local-first design.

This guide explains how to use the current safety features available in the app:

- block a peer on your device
- report a peer
- report a message
- review and manage blocked peers

This is a draft user guide intended for future publication on the PeerMesh website.

## Block a peer

Blocking is the fastest way to protect yourself from a specific person inside a group.

When you block a peer:

- their messages are hidden from your chat view
- the block only affects your device
- the blocked peer is not automatically removed from the group for everyone else

### How to block a peer

1. Open the group chat.
2. Long-press a message from the peer you want to manage.
3. Choose `Block peer`.
4. Confirm the action.

After confirmation, the peer's messages will no longer appear in your chat view on that device.

## Report a peer

Use peer reporting when the issue is about the person or their overall behavior, not only a single message.

### How to report a peer

1. Open the group chat.
2. Long-press a message from the peer you want to report.
3. Choose `Report peer`.
4. Select a reason.
5. Optionally add a note.
6. Optionally choose `Also block this peer on this device`.
7. Save the report.

Reports are stored locally first and sent later when a network path is available.

## Report a message

Use message reporting when you want to report one specific message.

### How to report a message

1. Open the group chat.
2. Long-press the message you want to report.
3. Choose `Report message`.
4. Select a reason.
5. Optionally add a note.
6. Decide whether to include the message text.
7. Optionally choose `Also block this peer on this device`.
8. Save the report.

By default, PeerMesh uses a metadata-first reporting approach. Message text is only included if you explicitly choose to include it.

## Manage blocked peers

PeerMesh lets you review the peers you have blocked in the current group.

### How to view blocked peers

1. Open the current group.
2. Go to `Group Detail`.
3. Scroll to the `Blocked peers` section.

The blocked peer list shows:

- the peer's display name when available
- a short peer identifier
- what is currently hidden for that peer, such as messages, location, or trail

### How to unblock a peer

1. Open `Group Detail`.
2. Find the `Blocked peers` section.
3. Tap `Unblock` next to the peer.
4. Confirm if prompted.

After unblocking, that peer's messages can appear in your chat again.

## What happens to reports

PeerMesh is designed to work in offline and degraded conditions.

Because of that:

- reports are saved on your device first
- reports may be sent later instead of immediately
- the app does not need to upload all messages by default in order to support reporting

For message reports, readable message text is optional and user-controlled.

## Important distinction

PeerMesh separates personal safety actions from group-level governance.

- `Block` protects you locally on your device
- `Report` creates a moderation signal
- `Remove member` is a group-owner action

Blocking someone does not automatically silence them for the rest of the group.

## Current scope

At the current stage, the main safety flow is focused on group chat moderation.

Additional safety and governance features may expand over time, including more member-management tools and broader in-app moderation surfaces.
