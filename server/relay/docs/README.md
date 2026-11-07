# Relay Operator Docs

This folder is the production entrypoint for Relay operators.

If you are operating a live Relay service, start in this folder instead of the root `docs/` directory.

Read in this order:

1. `relay_admin_operator_guide.md`
2. `relay_admin_ui_guide.md`
3. `relay_production_guide.md`
4. `relay_deploy_rollback_runbook.md`
5. `relay_backup_restore_runbook.md`
6. `relay_incident_response_runbook.md`

Additional production references:

* `relay_tls_edge_guide.md`
* `relay_edge_waf_guidance.md`
* `relay_grafana_dashboards.md`
* `relay_retention_policy.md`
* `relay_data_export_guide.md`
* `relay_disaster_recovery_plan.md`
* `relay_security_hardening_checklist.md`

Production runtime files:

* compose: `server/relay/docker-compose.yml`
* env: `server/relay/.env`
* start: `server/relay/start.sh`
* stop: `server/relay/stop.sh`

Use this folder for:

* daily Relay operations
* Relay Admin UI usage
* deploy and rollback
* backup, retention, and incident response

Developer/local simulation is documented separately and should use:

* `server/docker-compose.dev.yml`
* `server/.env.dev`
* `server/start.sh`
* `server/stop.sh`
