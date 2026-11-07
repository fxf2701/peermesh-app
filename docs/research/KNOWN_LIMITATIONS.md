# Known research and verification limitations

**Baseline:** 2026-11-07 source audit, not a frozen release. **Status:** experimental research, source-only; no official APK or iOS release. This document records observed limitations and verification gaps; it is not a security certification or a claim that all possible issues have been found.

## Security limitations

Read [Known security limitations](../security/KNOWN_SECURITY_LIMITATIONS.md) and the [security model](../SECURITY.md) before evaluating deployment. The current implementation has reported limitations in shared batch HMAC material, owner-only authorization, post-removal key distribution, registry signed-request replay, and relay plaintext acceptance. In particular, removing a member does **not** establish cryptographic exclusion from future group content against a modified client retaining earlier keys. Do not rely on PeerMesh for sensitive or adversarial communications.

## Mobile and transport

- **Android:** `officialDebug` compiled in the audit environment, but a successful build is not a field reliability result. The `emergency` flavor and release/minification configurations were not comprehensively verified in that audit.
- **BLE / DTN:** multi-device BLE/GATT interoperability, reconnection, background lifecycle, delivery reliability, and device/OS variation were not comprehensively verified. Delayed or missing control events, including member removal/rekey propagation, remain a documented concern; see [removal propagation investigation](../problems/member_removal_control_event_ble_reliability_gap.md).
- **Cloud Sync:** optional network synchronization has separate dependencies and trust boundaries. Lack of Internet access is not equivalent to manually disabling Cloud Sync. User intent to disable synchronization must remain authoritative; the audit does not prove every fallback or lifecycle path.
- **iOS:** only a skeleton is present. No verified runnable iOS app or BLE behavior.

## Server and integration

- **Registry:** signed v1 requests were observed to be accepted again on replay within the applicable time window; idempotency and endpoint-specific consequences were not comprehensively assessed. Registry integration tests were not completed in the audit environment.
- **Relay:** a validator path accepted plaintext object bodies despite a stronger documentation requirement; this does not imply that the relay decrypts properly encrypted payloads. Two selected relay scripts requiring PostgreSQL could not be verified.
- **Operations:** PostgreSQL/Redis integration, migrations, backup/restore, deployment configurations, and full dependency vulnerability scanning were not comprehensively verified.

## Test evidence (Codex audit, 2026-11-07)

| Check | Reported result | Interpretation |
| --- | --- | --- |
| Android `:app:assembleOfficialDebug` | PASS | Built using JDK 17 and pre-existing offline dependencies; fresh-checkout reproducibility not established. |
| Android core unit tests | 83 passed | Unit-test scope only. |
| Android security unit tests | 46 passed | Unit-test scope only; not a cryptographic security audit. |
| Android app unit tests | 622 passed / 4 failed (626 total) | Four failures were source/resource assertions; desired behavior still requires review. |
| Relay selected scripts | 31 passed / 1 failed / 2 not verified | One document-path failure; two blocked by unavailable PostgreSQL. |
| Relay ESLint | 230 errors | Mostly environment/global configuration and unused variables; not equivalent to 230 runtime failures. |
| Registry integration | Not verified | Native dependency/download constraints. |
| Physical BLE field testing | Not verified | No end-to-end reliability conclusion. |

The audit counted **755 executed Android unit tests: 751 pass and 4 fail** across app/core/security. These figures describe one run and may change with code, tooling, or environment.

## Documentation status and interpretation

Design blueprints, milestone records, backlogs, and historical notes may describe intended or superseded behavior. They do not override observed implementation or establish security properties by themselves. Where a document says a mechanism *must* exist but the code does not enforce it, treat that statement as a **design requirement**, not a verified guarantee.

## Intended use

Source inspection, protocol discussion, reproducible experiments, and community review are appropriate research uses. This software must not be treated as a dependable emergency service or a verified secure messaging system. No official binary is distributed by the project.
