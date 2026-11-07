# Contributing to PeerMesh

PeerMesh is an **experimental, source-only research project**, not a certified emergency communications system. Contributions are welcome, but no issue, feature request, or pull request is guaranteed to be accepted or addressed on a particular schedule.

Before contributing, read the [README](README.md), [research limitations](docs/research/KNOWN_LIMITATIONS.md), [known security limitations](docs/security/KNOWN_SECURITY_LIMITATIONS.md), [security reporting policy](SECURITY.md), and [code of conduct](docs/code_of_conduct.md).

## Ways to contribute

- Reproducible bug reports, including Android device model, OS version, app variant, permissions, BLE conditions, logs with personal data removed, and exact steps.
- Documentation corrections that distinguish implemented behavior, design requirements, historical notes, and unverified assumptions.
- Tests and repeatable experiments, particularly multi-device BLE/DTN, relay/registry integration, and security boundary tests.
- Focused code changes with a clear threat model, compatibility impact, and verification evidence.

## Before opening an issue

Search existing issues and documentation first. For ordinary defects, describe the expected and observed behavior, reproduction steps, environment, and relevant logs. **Never include real group keys, passphrases, invitation secrets, access tokens, credentials, private locations, or other people's personal data.**

**Do not post a suspected exploitable security vulnerability as a public issue.** Follow [SECURITY.md](SECURITY.md). The project already documents some weaknesses; check the known-limitations document, but report newly discovered attack paths privately when possible.

## Local setup and validation

Use [Android README](android/README.md) and the [build guide](docs/development/BUILD.md). Android is the principal buildable client in this baseline; the [iOS directory](ios/README.md) is not a buildable Xcode app.

The documented Android starting point is:

```bash
cd android
./gradlew :app:assembleOfficialDebug
./gradlew :app:testOfficialDebugUnitTest
```

The test suite is **not currently fully green** according to the 2026-11-07 audit; consult [research limitations](docs/research/KNOWN_LIMITATIONS.md) before interpreting failures. A successful local build does not prove compatibility with a clean machine or a real multi-device BLE environment. Server tests may require PostgreSQL, Redis, or native Node dependencies.

Run the checks appropriate to your change, and include the exact commands, results, failures, and environment in your pull request. Do not silently remove failing tests, weaken security assertions, or claim verification you have not performed.

## Proposing changes

1. Open an issue or discussion for substantial protocol, cryptographic, schema, or compatibility changes before implementing them, when the relevant GitHub facilities are available.
2. Keep changes focused; separate documentation, refactoring, and behavioral changes where practical.
3. Explain the current behavior, proposed behavior, threat or failure scenario, and any migration or backward-compatibility impact.
4. Add or update tests and documentation where relevant. Clearly mark unverified claims.
5. Submit a pull request against the project's default branch and include a concise validation summary.

A pull request should not claim to fix owner authentication, membership revocation, replay protection, or encrypted-relay admission unless the relevant receiver-side enforcement and adversarial cases have been tested.

## Licensing and third-party materials

By intentionally submitting a contribution for inclusion, you agree that it is submitted under the terms of **Section 5 of the Apache License 2.0**, unless you explicitly state otherwise or a separate agreement applies. You must have the right to submit the material. Do not include copied code, icons, datasets, maps, or documentation with incompatible or uncertain rights.

The root [LICENSE](LICENSE) states the intended project license; [license metadata and attribution review](docs/legal/LICENSE_AND_ATTRIBUTION_REVIEW.md) records unresolved publication checks. Dependency and third-party licenses remain applicable.

## Scope and expectations

This repository is for research and peer review. No official APK or iOS binary is distributed, and the project does not promise production support, emergency reliability, confidentiality against malicious group members, or timely remediation. Please be respectful and follow the [code of conduct](docs/code_of_conduct.md).
