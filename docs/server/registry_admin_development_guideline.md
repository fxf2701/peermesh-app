# Registry Admin UI Development Guideline

This document defines implementation conventions for Registry Admin UI.

The goal is to keep Registry Admin UI consistent with Relay Admin UI while maintaining clear separation of responsibilities.

---

# 1) Scope

Registry Admin UI manages:

* Relay Directory
* Trust Management
* Certificates
* Revocations
* Policy Feeds
* Audit Logs
* Registry Metrics

Registry Admin UI does not manage:

* Federation
* Relay Sync
* Event Storage
* Peer Connectivity

Those belong to Relay Admin UI.

---

# 2) UI Structure

Recommended structure:

```text
public/admin-registry/
├── index.html
├── relays.html
├── relay_detail.html
├── certificates.html
├── trust.html
├── revocations.html
├── policy.html
├── audit.html
├── metrics.html
├── settings.html
└── assets/
    ├── registry_admin.js
    ├── registry_admin.css
    └── components/
```

---

# 3) Navigation

Recommended sidebar order:

1. Dashboard
2. Relay Directory
3. Certificates
4. Trust Management
5. Revocations
6. Policy Feed
7. Audit Log
8. Metrics
9. Settings

---

# 4) API Conventions

Use JSON APIs only.

Response format:

```json
{
  "ok": true,
  "data": {}
}
```

Error format:

```json
{
  "ok": false,
  "error": {
    "code": "invalid_request",
    "message": "..."
  }
}
```

---

# 5) Tables

All large datasets should support:

* Search
* Sort
* Pagination

Required for:

* Relay Directory
* Certificates
* Audit Logs

---

# 6) Forms

Forms should:

* Validate client-side
* Validate server-side
* Display actionable errors

Never rely solely on browser validation.

---

# 7) Dangerous Actions

The following actions require confirmation:

* Ban Relay
* Revoke Relay
* Revoke Certificate
* Publish Policy

Recommended:

* Confirmation dialog
* Reason field

---

# 8) Audit Requirements

Every governance action must create an audit entry.

Examples:

* Verify Relay
* Promote Trust
* Suspend Relay
* Ban Relay
* Issue Certificate
* Publish Policy

---

# 9) Trust Visualization

Use consistent badges:

* community
* verified
* trusted

Status badges:

* active
* suspended
* revoked
* banned

Trust and Status must be displayed separately.

---

# 10) Certificate Visualization

Display:

* Certificate Status
* Issued At
* Expires At

Warnings:

* Expiring Soon
* Expired
* Revoked

---

# 11) Metrics Visualization

Dashboard cards:

* Active Relays
* Verified Relays
* Trusted Relays
* Revoked Relays

Charts:

* Heartbeat Rate
* Discovery Traffic
* Certificate Events
* Policy Updates

---

# 12) Security

Requirements:

* HTTPS only
* Session authentication
* HttpOnly cookies
* CSRF protection
* Audit logging

Recommended:

* IP allowlist
* MFA
* Session timeout

---

# 13) Accessibility

All pages should support:

* Keyboard navigation
* Screen readers
* Responsive layouts

---

# 14) Future Extensions

Possible future modules:

* RBAC
* Multi-admin approval
* Certificate transparency log
* Automated relay review workflow
* External identity providers

Registry Admin UI should remain focused on governance and discovery rather than federation or transport functionality.
