# Alerts ack API test note

Goal: Verify POST /admin/api/alerts/:id/ack writes an ack line and returns ok.

Prereqs:
- Admin session cookie available.
- ALERT_LOG_PATH points to a writable log file with at least one alert line.

Steps:
1) Fetch alerts:
   - GET /admin/api/alerts
   - Pick an alert id from items[0].id.
2) Ack the alert:
   - POST /admin/api/alerts/{id}/ack
   - Body: {"note":"operator acknowledged"}
3) Re-fetch alerts:
   - GET /admin/api/alerts
   - Verify the original alert still appears (ack does not remove it).
4) Check log side effect:
   - Tail ALERT_LOG_PATH and confirm a new line exists:
     - "<timestamp> ack <id> operator acknowledged"
5) Error handling:
   - POST /admin/api/alerts/unknown-id/ack returns 404.
   - If ALERT_LOG_PATH missing, POST returns 503 alerts_unavailable.

