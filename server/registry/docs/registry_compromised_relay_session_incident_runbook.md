# Registry Incident Response Runbook for Compromised Relay Session

This runbook defines how Registry operators should respond when a managed federation relay session is suspected to be compromised or abused.

Examples:

* leaked or replayed federation session token
* relay using a session outside expected behavior
* relay compromise causing malicious federation traffic
* incident reports pointing to a relay/session that should no longer remain active

---

## 1. Scope

This runbook applies to:

* Registry-governed managed federation
* registry-issued federation sessions
* relay/operator response through Registry Admin and registry APIs

This runbook does not cover:

* client access-grant compromise
* relay request-signature secret compromise
* full relay host forensic response

---

## 2. Primary Goals

During incident response, prioritize:

1. stop active misuse quickly
2. limit further propagation through managed federation
3. preserve enough audit context for follow-up review
4. restore safe topology with minimal unnecessary disruption

---

## 3. Indicators of a Compromised Relay Session

Possible indicators:

* unexpected federation push traffic
* repeated session validation failures at peers
* relay-side logs indicating invalid or out-of-scope behavior
* registry audit spikes in:
  * `federation_session_denied`
  * repeated revoke/reissue cycles
* multiple client or operator abuse reports tied to the same relay
* operator evidence that a relay host or secret has been exposed

Treat the following as especially high-risk:

* confirmed relay credential exposure
* confirmed host compromise
* confirmed unauthorized use of a still-active session

---

## 4. Immediate Containment Options

Current registry/runtime controls support:

* revoke one active federation session
* revoke multiple active sessions
* suspend a relay
* revoke a relay
* ban a relay
* rebuild the relay revocation feed
* rebalance managed federation topology

Recommended containment order depends on confidence level:

### Low confidence / active investigation

* revoke the affected federation session first
* monitor whether a fresh session request repeats suspicious behavior

### Medium confidence / repeated suspicious behavior

* revoke active sessions for the relay
* suspend the relay
* rebalance topology away from the relay

### High confidence / confirmed compromise

* revoke all active sessions for the relay
* revoke or ban the relay
* rebuild revocation feed if relay status must be published to consuming systems
* rotate related secrets if compromise scope is broader than one session

---

## 5. Pre-Containment Checklist

Before acting, if time permits:

1. Record incident start time.
2. Identify:
   * `relay_id`
   * affected `session_id` values
   * incident source
   * confidence level
3. Export audit history for the affected resource if useful:
   * `/admin/api/audit/export?resource_type=federation_session&resource_id=<session_id>&format=jsonl`
   * `/admin/api/audit/export?resource_type=relay&resource_id=<relay_id>&format=jsonl`
4. Confirm Registry Admin access works.

If active abuse is ongoing, containment takes priority over evidence collection.

---

## 6. Session-Level Containment

Use this when one specific federation session is suspected to be compromised.

### Step 1: Revoke the Session

Registry Admin:

* open Federation Sessions
* locate the affected session
* revoke it with an explicit incident reason

API path:

* `POST /admin/api/federation/sessions/:id/revoke`

Expected result:

* session status becomes `revoked`
* audit event `federation_session_revoked` is recorded

### Step 2: Verify Revocation Propagation

Check:

* session appears in `GET /v1/registry/federation/revocations`
* assigned peers reject late traffic for that session
* relay fleet refreshes revocation cache as expected

### Step 3: Watch for Re-Issue Attempts

If the relay immediately requests a fresh session and behavior still looks bad:

* escalate from session-level containment to relay-level containment

---

## 7. Relay-Level Containment

Use this when the relay itself is likely unsafe, not just one session.

### Step 1: List Active Sessions for the Relay

Check:

* `GET /admin/api/federation/sessions?relay_id=<relay_id>`

### Step 2: Revoke Active Sessions

For each active or expiring session:

* revoke the session

### Step 3: Change Relay Status

Recommended status by confidence:

* `suspended` for temporary containment during investigation
* `revoked` when relay participation should be invalidated
* `banned` for malicious or repeated abuse

Operator path:

* `PATCH /admin/api/relays/:id/status`

### Step 4: Rebalance Topology

After containment:

* trigger federation topology rebalance

Purpose:

* remove dependency on the affected relay
* redistribute sessions to eligible peers

### Step 5: Rebuild Revocation Feed if Needed

If relay status changes should be reflected in the published relay revocation snapshot:

* `POST /admin/api/revocations/rebuild`

Important:

* relay status changes do not automatically republish the persisted revocation feed snapshot
* rebuild is an explicit operator action

---

## 8. Verification After Containment

Verify:

1. affected session ids are no longer active
2. affected relay no longer appears eligible for new federation sessions
3. remaining healthy relays still receive valid topology assignments
4. `federation_session_denied` does not spike unexpectedly for healthy relays
5. Registry health and admin APIs remain normal

Recommended checks:

* `GET /admin/api/federation/sessions`
* `GET /admin/api/relays/:id`
* `GET /v1/registry/federation/revocations`
* relevant audit exports

---

## 9. Escalation Rules

Escalate beyond session revoke when any of the following is true:

* the same relay repeatedly receives revoked sessions
* compromise evidence points to host or secret exposure
* multiple peers report invalid behavior from the same relay
* suspicious behavior resumes immediately after renewal/reissue

Escalation options:

* suspend relay
* revoke relay
* ban relay
* rotate federation session signing secret if broader compromise is suspected
* rotate heartbeat or request-signature secrets if compromise scope includes those credentials

---

## 10. Rollback / Recovery

If investigation finds the relay was incorrectly contained:

1. return relay status to `active` if appropriate
2. allow new managed federation sessions to be issued
3. rebalance topology again if needed
4. document the false positive and root cause

Do not:

* un-revoke a compromised session and reuse it

Preferred recovery:

* issue fresh sessions after containment is cleared

---

## 11. Post-Incident Follow-Up

After containment:

* export audit history for the incident window
* document:
  * timeline
  * affected relay ids
  * affected session ids
  * status actions taken
  * whether revocation feed was rebuilt
  * whether topology rebalance was triggered
* review whether:
  * relay status should remain suspended/revoked/banned
  * certificate action is required
  * secret rotation is required
  * client/relay abuse correlation thresholds need adjustment

---

## 12. Current Runtime Limitations

Current implementation limitations to keep in mind:

* no bulk incident-response endpoint for mass session revocation by relay id
* revocation feed rebuild is explicit, not automatic
* no dual-key support for federation session signing secret rotation
* some broader response steps still rely on operator coordination rather than one-click incident workflows

These limitations should be treated as operational constraints during incident handling.
