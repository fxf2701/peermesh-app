# Registry Policy Rollback Runbook for Bad Topology or Session Policy Publish

This runbook defines how to recover when a bad Registry policy publish changes managed federation behavior in an unsafe or unintended way.

Examples:

* `minimum_trust_level_for_federation` set incorrectly
* `allowed_statuses_for_federation` widened or narrowed unintentionally
* `federation_session_ttl_seconds` reduced too far or raised too high
* `allowed_federation_permissions` published incorrectly
* `max_peers_per_session` or related topology policy values cause unstable assignments

---

## 1. Scope

This runbook applies to:

* Registry policy persisted through `PATCH /admin/api/policy`
* managed federation session issuance and renewal
* topology assignment behavior derived from that policy

This runbook does not cover:

* admin credential rollback
* federation signing-key rotation
* relay binary rollback

---

## 2. Key Runtime Facts

Current runtime behavior:

* policy changes persist immediately in the Registry database
* new session issuance uses the latest persisted policy
* session renewals also use the latest persisted policy
* existing active sessions do not automatically disappear just because policy changed
* topology rebalance is an explicit operator action
* revocation feed rebuild is an explicit operator action

Operational consequence:

* policy rollback requires two layers:
  1. restore a safe policy
  2. reconcile any already-issued sessions or topology state that was created under the bad policy

---

## 3. Indicators of a Bad Policy Publish

Typical symptoms:

* healthy relays suddenly stop receiving peers
* session denial rate spikes after a policy edit
* too many relays become eligible or ineligible at once
* session TTL becomes operationally too short or too long
* relays receive permissions they should not have
* topology becomes sparse, overloaded, or unexpectedly wide

Audit indicators:

* recent `policy_updated`
* follow-on spike in:
  * `federation_session_denied`
  * `federation_topology_rebalanced`
  * repeated session revoke/reissue actions

---

## 4. Immediate Containment Strategy

If the bad policy is actively disrupting federation:

1. stop making further manual topology changes until the policy state is understood
2. identify the last known-good policy values
3. decide whether:
   * policy rollback alone is enough
   * existing sessions must also be revoked/reissued

Use the least disruptive response that restores safety.

---

## 5. Pre-Rollback Checklist

Before rollback:

1. Record incident start time.
2. Export current audit history if useful:
   * `/admin/api/audit/export?resource_type=policy&format=jsonl`
   * `/admin/api/audit/export?resource_type=federation_session&format=jsonl`
3. Capture current policy:
   * `GET /admin/api/policy`
4. Capture current session inventory:
   * `GET /admin/api/federation/sessions`
5. Identify the last known-good policy values from:
   * prior change record
   * audit trail
   * operator notes
   * approved deployment artifact

If a known-good policy snapshot is not available, rebuild one carefully before applying changes.

---

## 6. Rollback Procedure

### Phase A: Restore the Policy

1. Submit a new policy update that restores the known-good values.
2. Include an explicit rollback reason.

Operator/API path:

* `PATCH /admin/api/policy`

Rollback policy should restore whichever fields were wrong, commonly:

* `minimum_trust_level_for_federation`
* `allowed_statuses_for_federation`
* `federation_session_ttl_seconds`
* `max_peers_per_session`
* `allowed_federation_permissions`
* other related governance fields changed in the bad publish

Expected result:

* Registry persists the corrected policy immediately
* subsequent session issue/renew operations use the corrected policy

### Phase B: Evaluate Existing Sessions

Decide whether active sessions issued under the bad policy are still safe to keep.

Revoke and refresh existing sessions when:

* old sessions still grant unsafe permissions
* old sessions still reflect an unsafe peer assignment
* the bad policy widened trust/status eligibility incorrectly
* topology damage is already present and should not wait for passive expiry

Leave sessions alone temporarily when:

* the bad policy mainly caused denials and did not issue unsafe sessions
* existing active sessions are still within the intended safe envelope

### Phase C: Reconcile Session State

If session state must be corrected:

1. Revoke affected sessions.
2. Allow relays to request fresh sessions under the restored policy.

Operator/API path:

* `POST /admin/api/federation/sessions/:id/revoke`

### Phase D: Rebalance Topology

If topology assignments were distorted by the bad policy:

1. Trigger topology rebalance after the safe policy is restored.

Operator/API path:

* `POST /admin/api/federation/topology/rebalance`

Purpose:

* rebuild peer assignments under the corrected policy
* reduce lingering effects of the bad publish

---

## 7. Verification After Rollback

Verify:

1. `GET /admin/api/policy` returns the expected safe values.
2. new federation sessions reflect the corrected policy.
3. renewed sessions reflect the corrected policy.
4. session denial rate returns toward baseline.
5. topology looks reasonable again.
6. no unsafe permissions remain in active session grants.

Recommended checks:

* `GET /admin/api/policy`
* `GET /admin/api/federation/sessions`
* `GET /admin/api/federation/sessions/:id`
* audit export for:
  * `policy_updated`
  * `federation_session_revoked`
  * `federation_topology_rebalanced`

---

## 8. When to Escalate Beyond Policy Rollback

Escalate if:

* rollback does not stabilize session issuance
* topology remains unhealthy after rebalance
* relay behavior suggests compromise rather than mere policy error
* the wrong policy was part of a broader bad deployment

Possible escalation:

* use the compromised relay incident runbook
* rotate federation session signing secret if trust in issued grants is affected
* perform broader registry deployment rollback if policy issue came with binary/config drift

---

## 9. Rollback Safety Rules

Do:

* restore the policy first
* then revoke/reissue or rebalance only as needed
* keep rollback reasons explicit in audit history

Do not:

* rebalance topology repeatedly before the policy is fixed
* assume active sessions automatically become safe after a policy rollback
* overwrite policy values without recording what was restored and why

---

## 10. Post-Rollback Follow-Up

After recovery:

* document:
  * bad policy fields
  * impact window
  * sessions revoked
  * whether topology rebalance was required
* review why the bad publish was approved or applied
* decide whether additional controls are needed:
  * stronger review before policy changes
  * operator checklist
  * future diff preview tooling
  * multi-operator approval for sensitive policy edits

---

## 11. Current Runtime Limitations

Current implementation limitations:

* no policy version rollback button or stored named policy-history snapshots
* rollback depends on operator knowing the last good values
* no automatic mass reissue of sessions after policy rollback
* topology correction still depends on explicit rebalance or passive session refresh

These limitations should be considered during change management for production policy edits.
