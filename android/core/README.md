# Core Module Boundaries (Android)

Purpose: shared, platform-agnostic helpers used by data/reducer/transport layers.

Include:
- Event models (EventEnvelope/EventCore and related types)
- Canonical JSON + base64url helpers
- Event ID factory
- Clock/tie-break helpers (LWW comparison)

Exclude:
- Android framework types
- Storage/Room
- Transport (BLE/relay)
- UI
- Crypto implementations tied to platform keystore

Module dependents:
- `data` (reducers, ingest)
- `security` (MAC canonicalization inputs)
- `transport` (envelope handling)
