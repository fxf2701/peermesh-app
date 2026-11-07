# Relay Retention Policy (exp/cleanup)

This document defines retention presets and cleanup tuning for relay data.

Retention is enforced by `exp` (expiration timestamp). The relay periodically
deletes events where `exp <= now - CLEANUP_GRACE_SECONDS`.

## 1) Key Settings

- `CLEANUP_INTERVAL_SECONDS`: how often cleanup runs.
- `CLEANUP_GRACE_SECONDS`: grace window before deletion.
- `EVENT_EXP_GRACE_SECONDS`: reject events that are already expired.

## 2) Recommended Presets

### Dev
- `CLEANUP_INTERVAL_SECONDS=2`
- `CLEANUP_GRACE_SECONDS=1`
- `EVENT_EXP_GRACE_SECONDS=60`

### Staging
- `CLEANUP_INTERVAL_SECONDS=30`
- `CLEANUP_GRACE_SECONDS=60`
- `EVENT_EXP_GRACE_SECONDS=300`

### Production (default)
- `CLEANUP_INTERVAL_SECONDS=60`
- `CLEANUP_GRACE_SECONDS=300`
- `EVENT_EXP_GRACE_SECONDS=300`

## 3) Guidance

- Shorter intervals increase DB load but keep data fresher.
- Larger grace windows reduce accidental deletions during clock skew.
- Keep `EVENT_EXP_GRACE_SECONDS` >= `CLEANUP_GRACE_SECONDS`.

## 4) Verification

- Confirm expired rows are removed by cleanup.
- Ensure non-expired events remain.
