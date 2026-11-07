# Registry Operator Docs

This folder is the production entrypoint for Registry operators.

If you are operating a live Registry service, start in this folder instead of the root `docs/` directory.

Read in this order:

1. `registry_admin_operator_guide.md`
2. `registry_admin_ui_guide.md`
3. `registry_sqlite_backup_restore_runbook.md`
4. `registry_federation_session_signing_key_rotation_runbook.md`
5. `registry_compromised_relay_session_incident_runbook.md`
6. `registry_policy_rollback_runbook.md`

Production runtime files:

* compose: `server/registry/docker-compose.yml`
* env: `server/registry/.env`
* start: `server/registry/start.sh`
* stop: `server/registry/stop.sh`

Use this folder for:

* daily Registry operations
* Admin UI usage
* maintenance windows
* rollback and incident response

Developer/local simulation is documented separately and should use:

* `server/docker-compose.dev.yml`
* `server/.env.dev`
* `server/start.sh`
* `server/stop.sh`
