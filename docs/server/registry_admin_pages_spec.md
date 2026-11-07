# Registry Admin UI Pages Specification

This document defines the required Registry Admin UI pages and their detailed purpose, data, actions and related APIs.

The Registry Admin UI is internal-only and is used to administer the PeerMesh Registry service.

Registry responsibilities:

* Relay discovery
* Relay trust management
* Relay identity verification
* Relay certificates
* Revocation feeds
* Policy feeds
* Registry governance

Registry is not responsible for:

* Message transport
* Federation synchronization
* Event storage

Those functions belong to Relay nodes.

---

# 1) Dashboard

Purpose:

Provide high-level visibility into registry health and governance state.

Widgets:

* Active Relays
* Community Relays
* Verified Relays
* Trusted Relays
* Suspended Relays
* Revoked Relays
* Banned Relays
* Heartbeats/minute
* Discovery Requests/minute

APIs:

* GET /registry/admin/api/dashboard

---

# 2) Relay Directory

Purpose:

Browse and manage registered relays.

Columns:

* Relay ID
* Endpoint
* Region
* Trust Level
* Status
* Federation Enabled
* Last Seen

Actions:

* View Details
* Verify Relay
* Promote Trust
* Suspend Relay
* Revoke Relay
* Ban Relay

APIs:

* GET /registry/admin/api/relays
* GET /registry/admin/api/relays/:id
* PATCH /registry/admin/api/relays/:id/trust
* PATCH /registry/admin/api/relays/:id/status

---

# 3) Relay Details

Purpose:

Display complete information for a relay.

Sections:

### General

* Relay ID
* Endpoint
* Region
* Created At
* Last Seen

### Trust

* Trust Level
* Status

### Capabilities

* store_forward
* capsule_push
* capsule_pull
* federation

### Heartbeat

* Last Heartbeat
* Heartbeat History

### Certificate

* Certificate Status
* Issued At
* Expires At

Actions:

* Verify
* Trust
* Suspend
* Revoke
* Ban

---

# 4) Certificates

Purpose:

Manage Relay Identity Certificates.

Columns:

* Relay ID
* Certificate Status
* Issued At
* Expires At

Actions:

* Issue Certificate
* Renew Certificate
* Revoke Certificate

APIs:

* GET /registry/admin/api/certificates
* POST /registry/admin/api/certificates
* PATCH /registry/admin/api/certificates/:id/renew
* PATCH /registry/admin/api/certificates/:id/revoke

---

# 5) Trust Management

Purpose:

Manage relay trust levels.

Trust Levels:

* community
* verified
* trusted

Actions:

* Promote
* Demote
* Review History

APIs:

* GET /registry/admin/api/trust
* PATCH /registry/admin/api/trust/:relay_id

---

# 6) Status Management

Purpose:

Manage relay operational status.

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

APIs:

* PATCH /registry/admin/api/status/:relay_id

---

# 7) Revocation Feed

Purpose:

Review revocation data published to clients and relays.

Fields:

* generated_at
* revoked_relays

Actions:

* Rebuild Feed
* Export Feed

APIs:

* GET /registry/admin/api/revocations
* POST /registry/admin/api/revocations/rebuild

---

# 8) Policy Feed

Purpose:

Manage registry policy distribution.

Example Policies:

* minimum_trust_level
* discovery_visibility
* federation_requirements

Actions:

* Edit
* Publish

APIs:

* GET /registry/admin/api/policy
* PATCH /registry/admin/api/policy

---

# 9) Audit Log

Purpose:

Track governance actions.

Columns:

* Timestamp
* Actor
* Action
* Resource
* Result

Actions:

* Filter
* Export

APIs:

* GET /registry/admin/api/audit

---

# 10) Metrics

Purpose:

Observe registry health.

Metrics:

* Heartbeats/sec
* Discovery Requests/sec
* Certificate Issues
* Certificate Revocations
* Policy Updates
* Audit Events

APIs:

* GET /registry/admin/api/metrics

---

# 11) Settings

Purpose:

Registry configuration.

Examples:

* Heartbeat TTL
* Discovery Limits
* Certificate Lifetime
* Default Trust Policies

APIs:

* GET /registry/admin/api/settings
* PATCH /registry/admin/api/settings
