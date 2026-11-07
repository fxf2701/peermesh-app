# Registry Admin UI Technical Guide

## 1. Purpose

This document defines the Registry Admin UI used to operate and govern the PeerMesh Registry service.

The Registry Admin UI is separate from Relay Admin UI.

Registry responsibilities:

* Relay discovery
* Relay trust management
* Relay identity verification
* Certificate lifecycle
* Client feedback review
* Federation session coordination
* Federation topology governance
* Revocation management
* Policy distribution
* Registry monitoring

Registry is not:

* A relay node
* A federation peer
* A message transport service
* An event payload proxy

---

## 2. Architecture

```text
Registry Admin UI
        |
        v
Registry Admin API
        |
        v
Registry Service
        |
        +--> Relay Directory
        +--> Certificates
        +--> Trust Model
        +--> Client Feedback
        +--> Federation Sessions
        +--> Federation Topology
        +--> Policy Feed
        +--> Revocation Feed
        +--> Audit Log
```

---

## 3. Admin UI Modules

### Dashboard

Purpose:

Provide registry health and governance visibility.

Displays:

* Active Relays
* Community Relays
* Verified Relays
* Trusted Relays
* Suspended Relays
* Revoked Relays
* Banned Relays
* Heartbeats/minute
* Discovery Requests/minute
* Active Federation Sessions
* Federation Session Requests/minute
* Federation Push Authorizations/minute

Recommended additions:

* Last Policy Publish
* Last Revocation Feed Publish
* Last Federation Session Issue
* Registry Health Status
* Heartbeat Failure Count
* Session Denial Count

Dashboard should answer:

"Is the registry healthy and governing relays correctly?"

---

### Relay Directory

Purpose:

Manage relay governance and relay trust state.

The directory should provide access to a dedicated Relay Details page.

Displays:

* Relay ID
* Endpoint
* Region
* Trust Level
* Status
* Last Seen
* Federation Enabled
* Active Federation Sessions

Actions:

* View Details
* Promote Trust
* Demote Trust
* Suspend Relay
* Revoke Relay
* Ban Relay
* View Federation Sessions

Recommended UI:

* Display current trust level.
* Allow promotion and demotion through a trust workflow.
* Avoid exposing separate Verify and Trust buttons.
* Do not expose manual peer-connect controls in managed federation mode.

---

### Relay Details

Displays:

* Relay ID
* Endpoint
* Region
* First Seen
* Last Seen

### Trust

* Trust Level
* Status

### Certificate

* Certificate Status
* Issued At
* Expires At

### Capabilities

* store_forward
* capsule_push
* capsule_pull
* federation

### Heartbeat History

* Last heartbeat
* Historical heartbeat activity

### Federation

* Active Sessions
* Assigned Peers
* Allowed GIDs
* Session Expiry
* Push/Reconciliation Permissions

### Audit History

* Trust changes
* Status changes
* Certificate actions
* Federation session actions

Actions:

* Promote Trust
* Demote Trust
* Suspend
* Reinstate
* Revoke
* Ban

---

### Federation Sessions

Purpose:

Operate registry-issued relay federation sessions.

Displays:

* Session ID
* Source Relay
* Assigned Peers
* Allowed GIDs
* Permissions
* Issued At
* Expires At
* Policy Version
* Status
* Denial Reason

Actions:

* View Session
* Revoke Session
* Force Renew
* Export Session Grant

Session statuses:

* active
* expiring
* expired
* revoked
* denied

UI Recommendations:

* Show sessions as temporary authorization, not permanent peer relationships.
* Surface expiry prominently.
* Show why a session request was denied.
* Link sessions back to Relay Details and Audit Log.

---

### Federation Topology

Purpose:

Visualize and control the registry-assigned federation graph.

Displays:

* Relay node
* Assigned peer count
* Trust level
* Status
* Region
* Capacity
* Session count
* Last session issue

Actions:

* Rebalance Topology
* Drain Relay
* Disable Federation
* Preview Policy Impact

Recommended UI:

* Show directed session edges between relays.
* Distinguish push permission from pull reconciliation permission.
* Highlight relays with no eligible peers.
* Highlight relays near capacity.

---

### Client Feedback

Purpose:

Provide a lightweight operator inbox for product feedback submitted from the Android beta app.

Displays:

* Feedback ID
* Category
* Message preview
* Source
* Device metadata
* Received time

The module should provide a dedicated Feedback Details page.

Feedback Details should show:

* Full feedback message
* App version
* Build number
* Android version
* Device model
* Local device ID
* Audit history for intake and duplicate-collapse events

Operator notes or moderation workflow actions are not required for the initial version.
* Avoid manual "add peer" UX for managed federation.

---

### Certificates

Displays:

* Relay ID
* Certificate Status
* Issued At
* Expires At

Actions:

* Issue Certificate
* Renew Certificate
* Revoke Certificate

---

### Trust Management

Trust Levels:

* community
* verified
* trusted

Recommended progression:

```text
community
    ↓
verified
    ↓
trusted
```

Definitions:

- community
  - self-hosted or self-registered relay
  - default onboarding state
- verified
  - relay identity reviewed
  - ownership confirmed
  - may possess a Relay Identity Certificate
- trusted
  - approved for participation in official federation programs
  - subject to registry trust policies

Actions:

- Promote
- Demote
- Review History

UI Recommendations:

- Trust Level should be displayed as a dedicated badge.
- Trust Level and Status must be displayed separately.
- Promote/Demote actions are preferred over separate Verify/Trust buttons.
- Operators should be able to view trust transition history.

### Relay Onboarding Bundle

Dashboard should expose a dedicated onboarding export panel for relay operator handoff.

Requirements:

* operator enters relay ID or label
* operator selects which registry-managed sections to include
* operator must provide a reason
* UI returns a one-time `.env` snippet
* UI supports copy and download
* export action is audited

The panel should export only registry-owned values.

It should not render or invent:

* relay-local binding secrets
* federation mTLS private keys
* federation mTLS certificates
* federation CA material

---

### Status Management

Statuses:

* active
* suspended
* revoked
* banned

Actions:

* Suspend
* Reinstate
* Revoke
* Ban

---

### Revocation Feed

Displays:

* Revoked Relays
* Generated At

Actions:

* Export Feed
* Regenerate Feed

---

### Policy Feed

Displays:

* Discovery Policies
* Trust Policies
* Federation Policies
* Federation Session TTL
* Max Peers Per Session
* Minimum Trust Level For Federation
* Allowed Federation Statuses
* Allowed Federation Permissions

Actions:

* Edit Policy
* Publish Policy

---

### Audit Log

Displays:

* Timestamp
* Actor
* Action
* Resource
* Result
* Reason

Examples:

* relay_verified
* relay_trusted
* relay_suspended
* relay_revoked
* certificate_issued
* certificate_revoked
* policy_updated
* federation_session_issued
* federation_session_denied
* federation_session_revoked
* federation_topology_rebalanced

All governance actions must be recorded.

---

### Metrics

Displays:

* Registry Heartbeats/sec
* Discovery Requests/sec
* Certificate Issues
* Certificate Revocations
* Policy Updates
* Audit Events
* Federation Session Issues
* Federation Session Denials
* Active Federation Sessions
* Expired Federation Sessions
* Revoked Federation Sessions

---

## 4. Admin API

### Authentication

* Session-based
* HttpOnly cookie
* Optional IP allowlist

---

### Registry APIs

Examples:

* GET /admin/api/dashboard
* GET /admin/api/relays
* GET /admin/api/relays/:id
* PATCH /admin/api/relays/:id/trust
* PATCH /admin/api/relays/:id/status
* GET /admin/api/federation/sessions
* GET /admin/api/federation/sessions/:id
* POST /admin/api/federation/sessions/:id/revoke
* POST /admin/api/federation/topology/rebalance

---

### Certificate APIs

* POST /admin/api/certificates
* PATCH /admin/api/certificates/:id/renew
* PATCH /admin/api/certificates/:id/revoke

---

### Policy APIs

* GET /admin/api/policy
* PATCH /admin/api/policy

---

### Revocation APIs

* GET /admin/api/revocations
* POST /admin/api/revocations/rebuild

---

## 5. Security

Registry Admin UI should:

* require authentication
* support IP allowlists
* audit all changes
* use HTTPS
* protect certificate operations
* protect federation session operations
* require explicit reason for session revocation and topology changes

Production deployment guidance:

* do not expose the Registry Admin UI directly on a raw public registry port
* publish `/admin` only through an HTTPS reverse proxy or ingress
* keep the registry service itself on a private/container-only network where possible
* add outer access controls before login:
  * VPN/private network access
  * IP allowlists
  * optional mTLS at the edge
* rate-limit login attempts and keep admin access logs separate from public discovery traffic

Recommended posture:

* app authentication is necessary but not sufficient
* the Registry Admin UI should be treated as a privileged control-plane surface
* production access should require both:
  * admin authentication inside the app
  * network/edge restrictions outside the app

---

## 6. Future Enhancements

Possible future additions:

* RBAC
* Multi-admin approval
* External identity provider
* Certificate transparency log
* Automated relay review workflow
* Automated federation topology optimizer
* Session anomaly detection

---

## 7. UI Design Notes

Current Registry Admin UI is functional but should evolve toward a governance console rather than a relay operations console.

Recommended priorities:

### P0

* Relay Details page
* Certificates page
* Federation Sessions page

### P1

* Registry Health widgets
* Trust workflow visualization
* Federation Topology page
* Session denial visibility

### P2

* Policy editor
* Revocation feed visualization
* Federation policy simulator

### P3

* Dashboard polish

Guidelines:

* Trust Level and Status must be displayed separately.
* Federation capability should be informational only.
* Registry should govern trust, certificates, federation sessions, revocations and policies.
* Relay operational controls should remain in Relay Admin UI.
* Managed federation should not expose manual add-peer workflows.
* Session-based federation controls should emphasize temporary authorization and expiry.
