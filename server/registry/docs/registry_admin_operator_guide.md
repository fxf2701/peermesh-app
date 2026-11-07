# Registry Admin Operator Guide

## 1. Purpose

This guide helps Registry operators manage:

* Relay discovery
* Trust levels
* Relay verification
* Certificates
* Revocation feeds
* Policy feeds

Registry operators govern relay participation.

They do not operate relay message transport.

This guide is for production Registry operations only.

If this is the first file you opened in this folder, read `README.md` in the same folder first.

Production runtime/layout:

* compose: `server/registry/docker-compose.yml`
* env: `server/registry/.env`
* start: `server/registry/start.sh`
* stop: `server/registry/stop.sh`

Developer/local simulation is intentionally documented separately and should use:

* `server/docker-compose.dev.yml`
* `server/.env.dev`
* `server/start.sh`
* `server/stop.sh`

---

## 1.1 Service Lifecycle

Typical production lifecycle commands:

```bash
cd /path/to/repo
server/registry/start.sh --build
server/registry/stop.sh
```

Notes:

* keep `server/registry/.env` on the VPS and treat it as operator-managed state
* keep `server/registry/config/` and `server/registry/data/` persistent on the VPS
* do not use the root `server/start.sh` launcher for production registry service management

---

## 2. Daily Checklist

Review:

* Active relay count
* Suspended relays
* Revoked relays
* Heartbeat failures
* Discovery traffic
* Policy changes
* New client feedback submissions

---

## 2.1 Admin Credentials

Recommended configuration:

* `REGISTRY_ADMIN_USERNAME`
* `REGISTRY_ADMIN_PASSWORD_HASH`

Alternative file-backed configuration:

* `REGISTRY_ADMIN_CREDENTIALS_FILE`

Credential file JSON format:

```json
{
  "username": "admin",
  "password_hash": "scrypt$16384$8$1$<salt_hex>$<derived_key_hex>"
}
```

Plaintext password configuration should not be used in normal deployments.

Compatibility escape hatch:

* `REGISTRY_ADMIN_ALLOW_PLAINTEXT_PASSWORD=1`
* `REGISTRY_ADMIN_PASSWORD`

Generate a password hash with:

```bash
cd server/registry
npm run admin:hash-password -- 'your-password'
```

### Recommended Env Example

Baseline registry admin env variables:

```dotenv
REGISTRY_ADMIN_USERNAME=admin
REGISTRY_ADMIN_PASSWORD_HASH=scrypt$16384$8$1$<salt_hex>$<derived_key_hex>
REGISTRY_ADMIN_CREDENTIALS_FILE=
REGISTRY_ADMIN_SESSION_TTL_SECONDS=28800
REGISTRY_ADMIN_ALLOW_PLAINTEXT_PASSWORD=0
REGISTRY_ADMIN_PASSWORD=
```

Notes:

* `REGISTRY_ADMIN_SESSION_TTL_SECONDS=28800` means 8 hours.
* Prefer either `REGISTRY_ADMIN_CREDENTIALS_FILE` or `REGISTRY_ADMIN_PASSWORD_HASH`.
* Leave `REGISTRY_ADMIN_PASSWORD` empty unless you are intentionally using the dev/test plaintext compatibility mode.
* If `REGISTRY_ADMIN_CREDENTIALS_FILE` is set, its contents override `REGISTRY_ADMIN_USERNAME` and `REGISTRY_ADMIN_PASSWORD_HASH`.

### Credential Rotation

Recommended approach:

* use `REGISTRY_ADMIN_CREDENTIALS_FILE`
* update the file atomically
* restart the registry service

Rotation steps for file-backed credentials:

1. Generate a new password hash:

```bash
cd server/registry
npm run admin:hash-password -- 'new-strong-password'
```

2. Update the credentials file:

```json
{
  "username": "admin",
  "password_hash": "scrypt$16384$8$1$<salt_hex>$<derived_key_hex>"
}
```

3. Keep the previous credentials file content in a secure temporary rollback location.
4. Restart the registry service.
5. Verify:
   * login succeeds with the new password
   * login fails with the old password
   * admin pages still load normally
6. Delete the temporary rollback copy after verification.

Rotation steps for hash-in-env deployments:

1. Generate a new password hash.
2. Update `REGISTRY_ADMIN_PASSWORD_HASH`.
3. Restart the registry service.
4. Verify the new password works and the old password no longer works.

Rollback:

* restore the previous credentials file or previous password hash
* restart the registry service
* verify admin login is restored

Operational notes:

* existing admin sessions may remain valid until session TTL expiry or process restart
* schedule rotation during a controlled maintenance window
* avoid `REGISTRY_ADMIN_ALLOW_PLAINTEXT_PASSWORD=1` in normal operations
* audit the rotation as an operator change event

### Production Access Posture

Registry Admin should not be exposed as an open public web application.

Recommended production posture:

* terminate HTTPS at a trusted reverse proxy or ingress
* keep the registry process itself bound to an internal interface, container network, or private subnet
* expose `/admin` only through the HTTPS entrypoint
* restrict access with one or more outer controls before app-level login:
  * VPN / private overlay network
  * corporate/private subnet only
  * IP allowlist
  * mTLS at the proxy layer if your environment supports it

Avoid:

* exposing `http://<public-ip>:8090/admin` directly to the internet
* relying on registry admin username/password as the only outer control
* enabling plaintext password compatibility mode in production

Minimum production checklist:

1. Put Registry Admin behind HTTPS.
2. Restrict source networks with allowlists or private access.
3. Keep direct registry container/service ports off the public internet.
4. Use hashed or file-backed admin credentials.
5. Set an intentional `REGISTRY_ADMIN_SESSION_TTL_SECONDS` appropriate for operator workflow.
6. Review audit log exports and dangerous actions regularly.

Recommended reverse-proxy behavior:

* pass only the admin path you intend to expose
* preserve `Host` and real client IP headers correctly
* rate-limit login attempts
* log admin access separately from public discovery traffic
* deny direct access to internal-only endpoints if they should not be internet-visible

### Recommended Tailscale Setup For Registry Admin

One practical production pattern is:

* keep public Registry API on `https://registry.peermesh.app/*`
* allow `https://registry.peermesh.app/admin*` only from Tailscale

This keeps Registry discovery/API public while forcing Admin UI access through a private overlay network.

#### 1. Install Tailscale on the VPS

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

The command prints a login URL. Open it in a browser, authenticate, and approve the VPS in your tailnet.

You can also confirm the device and its assigned Tailscale IP in the Tailscale admin console web after login/approval.

Check the Tailscale IPv4 address:

```bash
tailscale ip -4
```

Example:

```text
100.x.y.z
```

#### 2. Install Tailscale on the operator machine

Linux example:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

macOS/Windows:

* install the official Tailscale app
* sign in to the same tailnet as the VPS
* approve the device in the Tailscale admin console web if required by your tailnet policy

You can view the operator device and its assigned Tailscale IP in the Tailscale admin console web after login.

Check the operator machine Tailscale IPv4 address:

```bash
tailscale ip -4
```

Example:

```text
100.a.b.c
```

Verify the operator machine can reach the VPS over Tailscale:

```bash
tailscale ping 100.x.y.z
```

#### 3. Keep Registry local on the VPS

Recommended Registry runtime values:

```dotenv
PORT=8080
REGISTRY_HOST_PORT=8090
REGISTRY_BIND_IP=127.0.0.1
```

Meaning:

* the Registry process listens on container port `8080`
* the VPS exposes local host port `127.0.0.1:8090`
* the Registry service is not directly exposed to the public internet

#### 4. Run Caddy directly on the VPS host

Recommended production posture:

* run Registry in Docker
* run Caddy on the VPS host via `systemd`
* keep Registry bound to `127.0.0.1:8090` on the VPS
* let host-level Caddy proxy to `127.0.0.1:8090`

This keeps the reverse proxy simple and allows `remote_ip` access control to see the real Tailscale source IP of the operator machine.

Install Caddy on the VPS host:

```bash
sudo apt update
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update
sudo apt install -y caddy
```

Ensure Caddy can write its log directory:

```bash
sudo mkdir -p /var/log/caddy
sudo chown caddy:caddy /var/log/caddy
sudo chmod 755 /var/log/caddy
```

#### 5. Example Caddyfile policy

Example policy:

* `https://registry.peermesh.app/*` remains public for app/relay API traffic
* `https://registry.peermesh.app/admin*` is allowed only from the operator's Tailscale IP

```caddy
registry.peermesh.app {
    encode zstd gzip

    header {
        Strict-Transport-Security "max-age=31536000; includeSubDomains; preload"
        X-Content-Type-Options "nosniff"
        X-Frame-Options "DENY"
        Referrer-Policy "no-referrer"
        Permissions-Policy "geolocation=(), microphone=(), camera=()"
        -Server
    }

    @admin path /admin /admin/*
    @tailscale remote_ip 100.a.b.c/32

    handle @admin {
        handle @tailscale {
            reverse_proxy 127.0.0.1:8090
        }

        respond "Forbidden" 403
    }

    handle {
        reverse_proxy 127.0.0.1:8090
    }

    log {
        output file /var/log/caddy/registry.peermesh.app.log
        format json
    }
}
```

Notes:

* replace `100.a.b.c/32` with the operator machine's actual Tailscale IP
* if multiple operator devices are allowed, add multiple `remote_ip` entries or widen to the tailnet range if intentionally desired
* this example assumes Caddy runs on the VPS host, not in Docker
* with host-level Caddy, `remote_ip` can correctly match the operator's Tailscale IP
* avoid containerized Caddy for this specific Tailscale IP allowlist use case unless you fully understand the source-IP forwarding implications

#### 6. Reload Caddy

Validate and restart the host-level Caddy service:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl restart caddy
sudo systemctl enable --now caddy
sudo systemctl status caddy --no-pager
```

#### 7. Access Registry Admin through Tailscale

Important:

* opening `https://registry.peermesh.app/admin` through normal public DNS may still hit the VPS over the public internet path
* in that case, Caddy sees the public source IP, not the Tailscale IP, and correctly returns `403`
* for admin access, the operator machine must reach the VPS through its Tailscale IP

For command-line testing, force the domain to resolve to the VPS Tailscale IP:

```bash
curl -i --resolve registry.peermesh.app:443:100.x.y.z https://registry.peermesh.app/admin
```

If the Tailscale restriction is working, expected behavior is:

* from the operator machine via Tailscale: login page or `200`
* from non-allowed sources: `403`

For browser access, use one of these approaches:

* add a temporary hosts-file entry mapping `registry.peermesh.app` to the VPS Tailscale IP, for example:

```text
100.x.y.z registry.peermesh.app
```

* configure split DNS in Tailscale so the domain resolves to the VPS Tailscale IP for operator devices
* publish a dedicated tailnet-only admin hostname instead of reusing the public API hostname

#### 8. Operational checks

On the VPS:

```bash
tailscale status
tailscale ip -4
curl -i http://127.0.0.1:8090/healthz
```

On the operator machine:

```bash
tailscale status
tailscale ip -4
tailscale ping 100.x.y.z
curl -i --resolve registry.peermesh.app:443:100.x.y.z https://registry.peermesh.app/admin
```

Expected result:

* Registry API stays available publicly
* Registry Admin is reachable only over Tailscale
* direct public internet access to `/admin` returns `403`

Related runbooks:

* `registry_federation_session_signing_key_rotation_runbook.md`
* `registry_compromised_relay_session_incident_runbook.md`
* `registry_policy_rollback_runbook.md`

---

## 3. Relay Lifecycle

Registry onboarding follows the lifecycle defined in:

- server/REGISTRY_PROTOCOL.md

Typical progression:

community
→ verified
→ trusted

Trust level and operational status are independent.

Examples:

- community + active
- verified + active
- trusted + active
- verified + suspended
- trusted + revoked

### Relay Onboarding Bundle Export

Registry Admin can generate a one-time relay onboarding bundle from the Dashboard.

Purpose:

* let a registry operator hand off registry-owned relay secrets without opening the registry `.env`
* keep bundle export behind authenticated admin workflow
* require an explicit reason and record the export in audit history

What the bundle may include:

* `REGISTRY_SHARED_SECRET`
* `REGISTRY_CLIENT_ACCESS_GRANT_SIGNING_SECRET`
* `REGISTRY_FEDERATION_SESSION_SIGNING_SECRET`
* matching feature toggles and registry URL

What the bundle does not include:

* relay-local secrets such as `CLIENT_RELAY_SESSION_BINDING_SECRET`
* mTLS private keys or certificates
* CA files for federation mTLS

Those remain operator-managed on the relay side.

Recommended handoff process:

1. Open `/admin`.
2. In **Relay Onboarding Bundle**, enter the relay ID or operator label.
3. Select only the sections the target relay actually needs.
4. Enter the reason for export.
5. Generate the bundle, then copy or download it once.
6. Deliver it to the relay operator over a secure out-of-band channel.
7. Ask the relay operator to generate any relay-local secrets on their own host before first start.

Audit expectations:

* action: `relay_onboarding_bundle_exported`
* resource type: `relay`
* resource id: relay ID or `pending`
* reason: required
* result: exported section list

### Community Relay

Default state.

Characteristics:

* self-hosted
* self-registered
* discoverable
* not automatically trusted

---

### Verified Relay

Requirements:

* relay identity reviewed
* endpoint reachable
* ownership confirmed

Verified relays typically receive a Relay Identity Certificate.

Certificates are optional governance artifacts and are not required for a relay to function.

Operator action:

* Issue Relay Identity Certificate

---

### Trusted Relay

Requirements:

* verified relay
* federation approved
* operationally reliable

Operator action:

* Promote trust level to trusted

---

## 4. Approving a Relay

Steps:

1. Open Relay Directory.
2. Review endpoint.
3. Review heartbeat history.
4. Review ownership evidence.
5. Issue certificate.
6. Promote to verified.

---

## 5. Promoting to Trusted

Steps:

1. Confirm relay is verified.
2. Review federation history.
3. Review abuse history.
4. Promote to trusted.

Trusted relays may participate in official federation programs.

---

## 6. Suspending a Relay

Reasons:

* suspicious activity
* instability
* investigation

Effect:

* hidden from discovery
* federation restrictions may apply

---

## 7. Revoking a Relay

Reasons:

* compromised credentials
* ownership dispute
* certificate invalidation

Effect:

* certificate invalid
* removed from trusted lists

---

## 8. Banning a Relay

Reasons:

* malicious behavior
* repeated abuse
* protocol violations

Effect:

* excluded from registry ecosystem

---

## 9. Certificate Operations

### Issue

Used when:

* relay passes review

### Renew

Used when:

* certificate nearing expiration

### Revoke

Used when:

* certificate no longer valid

---

## 10. Policy Management

Review:

* minimum_trust_level_for_auto_sync
* allowed_statuses_for_discovery
* deny_relays

Changes should be audited.

---

## 11. Revocation Feed

Review:

* generated_at
* revoked_relays

Ensure revoked relays appear correctly.

---

## 12. Audit Review

Review regularly:

* trust changes
* certificate actions
* policy edits
* relay bans

Unexpected actions should be investigated.

---

## 13. Security Recommendations

* Enable HTTPS.
* Restrict admin access.
* Use IP allowlists.
* Audit all governance actions.
* Rotate admin credentials.
* Backup registry database.

---

## 14. Registry SQLite Backup and Restore

Registry backup procedures are defined in:

* `registry_sqlite_backup_restore_runbook.md`

Operational baseline:

* the registry database file is configured by `REGISTRY_DB_PATH`
* the usual local/container default is `/app/data/registry.sqlite`
* keep at least one fresh pre-deploy backup before any release that may change schema or policy state
* prefer a short maintenance window over unvalidated hot-copy assumptions
* use the maintenance-window checklist in `registry_sqlite_backup_restore_runbook.md` before deploy, rollback, policy repair, or signing/admin credential rotation work that may require DB restore

Minimum operator verification after restore:

* `/healthz`
* `/readyz`
* admin login
* relay directory contents
* policy feed state
* revocation feed state
* federation sessions view

---

## 15. Deployment Rollback Checks for Schema Auto-Migration

Registry startup performs SQLite schema auto-migration.

Because these migrations are forward-moving schema changes, operators should verify rollback safety before restoring an older registry build.

Pre-deploy checks:

* confirm a fresh backup of the registry SQLite DB exists
* confirm the target release has passed:
  * `cd server/registry && npm run test:schema-migration`
* confirm release notes mention any new registry tables or columns

Post-deploy checks:

* verify registry starts successfully
* verify `/healthz` and `/readyz` return success
* verify admin login still works
* verify relay directory still shows existing relays
* verify policy feed and revocation feed still load
* verify audit history is still readable

Rollback decision checks:

* if the new release only added backward-tolerant columns/tables and the older binary ignores them safely, application rollback may be acceptable
* if the new release changed required schema semantics, do not assume the older binary can read the migrated DB safely
* if rollback compatibility is not explicitly verified, prefer restoring:
  * previous application version
  * previous registry SQLite backup

Safe rollback procedure:

1. Stop the registry service.
2. Restore the previous registry SQLite DB backup.
3. Restore the previous registry application build/config.
4. Start the registry service.
5. Verify:
   * `/healthz`
   * admin login
   * relay directory
   * policy feed
   * revocation feed

Do not:

* manually delete new columns from a live SQLite DB
* assume SQLite schema downgrade is safe without a tested rollback path
* roll back application code alone after an unreviewed schema change

Operational note:

* The current project now has an automated old-fixture migration regression test, but that only proves forward startup compatibility of the new binary.
* It does not guarantee reverse compatibility of older binaries against newly migrated DB files.

---

## 16. Incident Response

If a relay is compromised:

1. Suspend relay.
2. Revoke certificate.
3. Publish revocation feed update.
4. Review federation impact.
5. Notify affected operators.

---

## 17. Registry vs Relay

Registry:

* discovery
* trust
* certificates
* governance

Relay:

* message transport
* federation sync
* event storage

These services should be operated independently.
