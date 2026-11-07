# Build and local verification (research baseline)

PeerMesh is experimental, source-only research software. These steps build the Android client locally; they do **not** establish that the app is secure, production-ready, or suitable for emergency reliance. No official APK is distributed by the project.

## Prerequisites

- JDK 17, Android SDK (compile SDK 35), Android Studio or command-line Android tooling.
- Android Gradle wrapper in `android/`; the Gradle distribution and dependencies may require network access on a fresh checkout.
- A physical Android device for meaningful BLE testing. An emulator build is not evidence of mesh transport reliability.

## Android: build the `officialDebug` variant

From the **repository root**:

```sh
cd android
./gradlew :app:assembleOfficialDebug
```

Expected APK location relative to the Android directory:

```text
app/build/outputs/apk/official/debug/app-official-debug.apk
```

To install a locally built APK on an attached test device:

```sh
adb install -r app/build/outputs/apk/official/debug/app-official-debug.apk
```

The Android app defines `official` and `emergency` distribution flavors. For the latter, use `:app:assembleEmergencyDebug`; verify its build and runtime behavior independently. Do not assume the two variants have identical behavior or configuration.

## Local unit tests

```sh
./gradlew :core:testDebugUnitTest :security:testDebugUnitTest :app:testOfficialDebugUnitTest
```

**Audit baseline (2026-11-07):** Codex reported a successful `:app:assembleOfficialDebug` build using an existing offline dependency cache; core 83/83 tests passed, security 46/46 passed, and app 622/626 passed. Four app tests failed on source/resource assertions. This is a historical, environment-specific result, **not** a promise that a fresh checkout or the current tree will pass. See [Known limitations](../research/KNOWN_LIMITATIONS.md).

## Configuration and offline testing

The Android Gradle configuration reads optional `PEERMESH_RELAY_BASE_URL` / `RELAY_BASE_URL` and `PEERMESH_REGISTRY_BASE_URL` / `REGISTRY_BASE_URL` from Gradle properties, environment variables, or `local.properties`. Do not use live credentials for research builds. Request-signing secrets configured at build time may be recoverable from APKs and must not be treated as server-wide secrets.

Basic local/BLE testing should not require a deployed relay. The app may support optional Cloud Sync when configured; its behavior and user controls must be tested separately. A network outage is not the same as a user's explicit choice to disable Cloud Sync. Do not assume every feature works fully offline.

## iOS status

`ios/` is currently a **skeleton**, not a buildable Xcode app in this baseline. There is no verified `.xcodeproj` or `.xcworkspace` to open. Do not claim iOS build, install, or BLE support from this repository.

## Verification boundaries

A successful compilation or unit-test run does not validate BLE interoperability, background behavior, cryptographic owner authorization, key revocation, relay integration, or field reliability. For current security caveats see [Security overview](../SECURITY.md) and [Known security limitations](../security/KNOWN_SECURITY_LIMITATIONS.md).

## License

Consult the root `LICENSE` and check component/third-party notices before redistribution. Building locally does not create an official project release.
