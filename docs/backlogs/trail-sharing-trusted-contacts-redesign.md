# PeerMesh – Trail Sharing & Trusted Contacts UX Redesign

## Overview

Current implementation mixes together two very different concepts:

1. Manual trail sharing
2. Automatic safety tracking

This causes UX confusion because:
- manual sharing is session-based and contextual
- auto-trail is persistent safety automation

The system should separate these into distinct concepts and flows.

---

# Core UX Direction

## Manual Trail
User-driven temporary sharing.

Mental model:

> “I am going somewhere and want to share this trip.”

Characteristics:
- temporary
- optional
- contextual
- audience selected per session

---

## Auto Trail
Safety-oriented automatic tracking.

Mental model:

> “If something happens to me, trusted people can automatically see my movement.”

Characteristics:
- persistent
- safety-oriented
- background automation
- preconfigured recipients
- automatic visibility

---

# Problem With Current Group Settings Screen

Current screen:
- named "Group Settings"
- contains only live trail sharing options

Problems:
- title suggests broad group configuration
- content is too narrow
- feels empty and misplaced
- exposes configuration before user even starts a trail
- mixes manual sharing with safety automation

---

# Recommended Architecture

# 1. Remove Current "Group Settings" Screen

Do not keep the current screen structure.

The feature has evolved beyond simple sharing settings.

---

# 2. Move Manual Sharing Into Trail Flow

Manual trail sharing should happen only when user starts a trail.

## New Manual Flow

User:
- opens Trails screen
- presses Start

Then:
- choose audience

Options:
- Entire group
- Selected members

If selected members:
- open member picker

Then:
- start live trail sharing session

---

# 3. Create Separate Trusted Contacts System

Introduce a separate safety-oriented subsystem.

Recommended names:
- Trusted Contacts
- Safety Sharing
- Emergency Contacts

Preferred:
# Trusted Contacts

Reason:
- consumer-friendly
- emotionally understandable
- aligned with safety apps like Life360

---

# Trusted Contacts Concept

Trusted contacts are:
- always-authorized viewers
- safety recipients
- automatic live trail viewers

They are NOT:
- temporary sharing targets

---

# Auto Trail Behavior

When:
- automatic trail recording starts

Then:
- trusted contacts automatically gain live visibility
- no additional confirmation required

Visibility duration:
- from trail start
- until trail ends

This matches:
- emergency use cases
- kidnapping/lost-person scenarios
- hiking safety
- family safety
- lone worker protection

---

# Important UX Principle

## Persistent visibility must always be explicit

If app can:
- auto-share location
- background-share movement
- auto-start visibility

Then user must clearly understand this behavior.

Never hide this functionality silently.

---

# Recommended UX Copy

## Auto Trail Section

### Automatic Trail Recording

When automatic recording starts, your trusted contacts can view your live location and movement trail in real time.

[ Manage Trusted Contacts ]

---

# First-Time Auto Trail Onboarding

When enabling Auto Trail for the first time:

## Automatic Trail Sharing

When automatic trail recording starts, your trusted contacts will automatically receive your live trail and real-time location until the trail ends.

[ Continue ]

---

# Trail Screen Recommendations

## Current Problem

Current screen:
- mixes recording settings and sharing logic
- unclear distinction between manual and automatic behavior

---

# Suggested Structure

## Trail Recording

### Recording Mode
- Manual
- Automatic

---

## Manual Mode

Behavior:
- user presses Start
- audience selected at runtime

---

## Automatic Mode

Behavior:
- trail starts automatically
- trusted contacts automatically receive live visibility

Indicator example:

🟢 Auto-sharing enabled for 3 trusted contacts

---

# Trusted Contacts Screen Example

## Trusted Contacts

People who can automatically receive your live trail during emergency or auto-tracking.

---

### Auto-sharing
(toggle)

### Share auto-trails with:
- Entire group
- Selected trusted contacts

### Trusted contacts
- Alice
- Bob
- Carol

---

# Product Direction Implications

This architecture transforms the feature from:
- social sharing

into:
- passive safety network

The app becomes capable of:
- automatic movement visibility
- emergency tracking
- real-time safety awareness

without requiring user interaction during crisis situations.

---

# Future Expansion Opportunities

Trusted Contacts system can later support:
- SOS alerts
- dead-man switch
- offline disappearance alerts
- no-movement detection
- overdue arrival detection
- battery-low emergency alerts
- safe-zone monitoring
- emergency escalation rules

---

# Final Recommendations

## Manual Trail
- audience selected when starting trail
- temporary sharing session

## Auto Trail
- uses preconfigured trusted contacts
- automatically visible immediately after trail starts
- remains visible until trail ends

## Trusted Contacts
- separate subsystem
- safety-oriented
- persistent configuration

## Remove Current Group Settings Screen
Current implementation no longer matches the product direction.

The feature has evolved into a safety system rather than a simple sharing option.