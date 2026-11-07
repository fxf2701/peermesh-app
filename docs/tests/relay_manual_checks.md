# Relay Manual Operational Checks

This document captures checks that require real operational setup and cannot
be reliably covered by automated scripts.

## 1) Log Redaction & Rotation

Goal: ensure logs do not leak sensitive payloads and are rotated in production.

Checklist:
- Confirm request logs do not include `body`, `gid`, or full payloads.
- Confirm reverse proxy/access logs are configured to avoid sensitive query params.
- Configure log rotation (size/age caps) at the process manager or container runtime.

Verification steps (examples):
- Inspect recent logs for `gid`, `body`, or raw JSON payloads.
- Validate Docker log driver limits or systemd/journald retention settings.
- Confirm log files rotate and old logs are removed after the configured period.

Notes:
- This is a policy + ops configuration check, not a code test.
