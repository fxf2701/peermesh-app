# Relay Incident Response Runbook

This runbook covers common incidents for relay operations:
- DB outage
- Peer abuse
- Traffic spikes

Use it together with monitoring/alerting and the backup/restore runbook.

## 1) General Checklist

- Acknowledge alert and start an incident log (time, impact, actions).
- Confirm scope: single node or all relays.
- Capture current metrics and recent logs.
- Communicate status to operators/peers.
- Apply mitigations and verify recovery.

## 2) DB Outage

### Symptoms
- `/readyz` returns 503
- `health_db_check_failures_total` or timeouts spike
- `db_pool_waiting` and `db_pool_wait_ms_avg` increase

### Immediate Actions

- Verify DB process health and disk space.
- Check DB connections and pool saturation.
- If the DB is down, restart the DB service.
- If pool is saturated, reduce ingress (WAF/edge rate limits).

### Recovery Steps

- Ensure migrations are not stuck or half-applied.
- Restart relay after DB is stable.
- Run a quick fetch/ingest smoke test.

### If Recovery Fails

- Failover to a standby DB if available.
- Restore latest backup to a clean instance.
- Point relay to the restored DB and re-verify.

## 3) Peer Abuse

### Symptoms

- Unexpected traffic from a peer
- Excessive ingestion requests
- Spike in error rate or rate limits

### Immediate Actions

- Identify the abusive peer (IP/peer ID).
- Add the peer to banlist (DB-backed) or IP denylist.
- Reduce exposure at the edge (WAF/IP rules).

### Recovery Steps

- Monitor `rate_limited_total` and `errors_total`.
- Review audit logs for admin actions.
- Remove ban if false positive after verification.

## 4) Traffic Spikes

### Symptoms

- Sustained increases in `requests_total`
- Rate limiting activates frequently
- Increased latency in `http_request_duration_ms`

### Immediate Actions

- Enable stricter edge rate limits.
- If available, scale relay instances horizontally.
- Ensure Redis is enabled for shared rate limits.

### Recovery Steps

- Monitor error budget burn and DB saturation.
- Adjust rate limit thresholds after incident.
- Update alerts to catch earlier signals.

## 5) Post-Incident Review

- Document timeline and root cause.
- Update runbooks, configs, and alerts.
- Schedule restore drill if data loss occurred.
