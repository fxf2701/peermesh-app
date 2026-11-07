# Registry Federation Session Signing Key Rotation Runbook

This runbook defines how to rotate `REGISTRY_FEDERATION_SESSION_SIGNING_SECRET` safely for managed federation.

This key is shared by:

* the Registry server
* relay nodes that validate registry-issued federation peer grants

It is used to sign and verify:

* federation session records
* per-peer federation grants embedded in those sessions

---

## 1. Scope

Use this runbook when:

* rotating federation session signing material on a schedule
* responding to suspected secret exposure
* replacing temporary or weak bootstrap secrets

Do not use this runbook for:

* relay request signature secrets
* registry heartbeat shared secrets
* admin credential rotation

---

## 2. Current Rotation Model

Current implementation uses a single active shared secret:

* `REGISTRY_FEDERATION_SESSION_SIGNING_SECRET`

Current limitation:

* there is no dual-key verification window
* there is no key-id based secret selection for federation session signatures

Operational consequence:

* rotation is a coordinated cutover
* sessions signed with the old secret should be considered invalid once registry/relays move to the new secret
* rotation should be treated as a controlled maintenance event for managed federation

---

## 3. Pre-Rotation Checklist

Before rotation:

1. Confirm Registry health is `ok`.
2. Confirm all managed relays are healthy and visible in Registry.
3. Confirm no active incident is already affecting federation.
4. Confirm current config and secret storage locations are backed up securely.
5. Confirm operators can restart:
   * Registry
   * all managed relays
6. Confirm Registry Admin access works.
7. Confirm audit export is available in case pre-rotation state must be preserved.

Recommended maintenance posture:

* schedule rotation in a low-traffic window
* inform operators that managed federation sessions will be reissued
* avoid rotating during an unresolved relay incident

---

## 4. Secret Generation

Generate a new high-entropy secret outside the repo.

Requirements:

* cryptographically random
* at least 32 bytes of entropy
* stored in the same secret-management system used for production deployment

Examples:

```bash
openssl rand -hex 32
```

or:

```bash
python3 - <<'PY'
import secrets
print(secrets.token_hex(32))
PY
```

Do not:

* commit the secret to git
* place the secret in operator notes or tickets
* reuse heartbeat or request-signature secrets

---

## 5. Rotation Procedure

### Phase A: Prepare

1. Record current time and maintenance window.
2. Export current federation audit history if desired:
   * `/admin/api/audit/export?resource_type=federation_session&format=csv`
3. Capture current active session inventory:
   * `GET /admin/api/federation/sessions`

### Phase B: Update Registry Secret

1. Update `REGISTRY_FEDERATION_SESSION_SIGNING_SECRET` in Registry secret storage.
2. Restart the Registry service.
3. Verify:
   * `/healthz`
   * `/readyz`
   * Registry Admin login

### Phase C: Update Relay Secrets

1. Update `REGISTRY_FEDERATION_SESSION_SIGNING_SECRET` on every managed relay.
2. Restart managed relays in a controlled wave.
3. Confirm each relay becomes healthy.

Notes:

* because there is no dual-key support, do not leave part of the fleet on the old secret for long
* complete the cutover as quickly as operationally safe

### Phase D: Refresh Session State

1. Force relays to obtain fresh registry federation sessions.
2. Revoke stale sessions if they remain listed as active:
   * `POST /admin/api/federation/sessions/:id/revoke`
3. Trigger or wait for relay session refresh.
4. Confirm newly issued sessions appear in Registry Admin.

Expected outcome:

* active federation traffic uses only sessions/grants signed with the new secret

---

## 6. Post-Rotation Verification

Verify:

1. Registry:
   * `GET /healthz`
   * `GET /readyz`
2. Relay health for all managed relays.
3. Federation session issuance:
   * new sessions can be issued
   * renewals succeed
4. Federation data plane:
   * authorized push traffic succeeds
   * authorized reconciliation succeeds
5. Revocation feed still works.
6. Registry Admin pages still load:
   * Federation
   * Relay Directory
   * Audit

Audit checks:

* review `federation_session_issued`
* review `federation_session_renewed`
* review any spike in `federation_session_denied`
* record the maintenance reason in operator change tracking

---

## 7. Failure Modes

Common failure pattern after rotation:

* some relays still use the old secret
* new grants are rejected as invalid
* session renewals or federation pushes fail

Signals to watch:

* increased `federation_session_denied`
* relay logs showing invalid registry signature / session validation failures
* federation session list not repopulating after restart

Primary causes:

* partial rollout
* wrong secret value on one or more relays
* stale deployment manifest / secret injection path
* registry restarted but one relay pool not restarted

---

## 8. Rollback

If verification fails and rapid repair is not possible:

1. Restore the previous `REGISTRY_FEDERATION_SESSION_SIGNING_SECRET` in Registry secret storage.
2. Restore the same previous secret on all managed relays.
3. Restart Registry.
4. Restart relays.
5. Reissue or refresh federation sessions.
6. Verify session issuance and data-plane traffic are healthy again.

Rollback rule:

* Registry and relays must return to the same secret value
* do not leave mixed old/new federation session signing secrets in production

---

## 9. Follow-Up Actions

After a successful rotation:

* document completion time
* document which environments were rotated
* review whether any stale sessions required manual revocation
* review whether any relay lagged behind the planned restart window

If rotation was incident-driven:

* review whether other shared secrets also need rotation
* review operator access logs
* review federation audit export and incident notes

---

## 10. Future Improvement

Operational complexity would be reduced by later adding:

* key IDs for federation session signatures
* dual-key verification window
* staged cutover support
* explicit admin tooling for mass session refresh after key rotation
