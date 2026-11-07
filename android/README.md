# PeerMesh Android client

This directory contains the Android implementation of the experimental PeerMesh research project. It is **not** an official binary distribution and is **not** validated for life-critical communication.

## Getting started

- [Build and local verification](../docs/development/BUILD.md) — JDK/SDK prerequisites, `officialDebug` build, APK path, and test commands.
- [Known research limitations](../docs/research/KNOWN_LIMITATIONS.md) — evidence boundaries and unverified runtime behavior.
- [Known security limitations](../docs/security/KNOWN_SECURITY_LIMITATIONS.md) — shared authentication material, owner authorization, rekey, and server-side constraints.

The Android app declares `official` and `emergency` flavors. The historical M3C4 skeleton notes that previously occupied this README are **not** a description of the current Android client. Refer to scoped milestone documents for historical design and implementation decisions; do not infer runtime readiness from their completion labels.

Local build/test results must be reported with the exact variant, environment, and test scope. Testing BLE/DTN behavior requires real devices and scenarios beyond unit tests.
