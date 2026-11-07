# Developer Guide (Mobile, EN)

This guide helps new developers quickly understand the mobile-relevant repo layout, where to place code in Phase 2, and which technical docs to follow.

## 1) Repo layout (overview)

- `android/`: Android client skeleton (Kotlin).
- `ios/`: iOS client skeleton (Swift).
- `shared/`: shared schemas/docs (event model, invariants).
- `docs/`: technical specs, task lists, system notes.
- `server/`: relay server (Phase 1), used for online sync.
- `scripts/`: repo utilities.
  - Backlogs: `android/docs/backlogs/map_ui_crash_risks.md` (risk/crash analysis) and
    `android/docs/backlogs/improvement_ideas.md` (improvement ideas). Backlog items are **not tasks**
    until reviewed; they can be promoted to the task list or dropped.

## 2) Mobile clients (android/ & ios/)

### Current status
- Both `android/` and `ios/` are skeletons.
- Phase 2 targets mobile in general, but the task list focuses on Android as the baseline.
- Phase 2.1 will implement iOS based on the Android baseline.
- Key Phase 2 docs:
  - `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
  - `docs/milestones/Phase_2_Task_List.md`
  - `docs/specs/Project_Overview_Technical_Spec.md`
  - `docs/specs/Architecture_Diagram.md`

### Android
- README: `android/README.md`
- Docs: `android/docs/README.md`

### iOS
- README: `ios/README.md`
- Docs: `ios/docs/README.md`

## 3) Suggested code structure (when scaffolding)

> No app code yet. When creating the project, align with the layers in the spec.

### Suggested layers
- **UI Layer**: chat/map/group UI.
- **Application Logic Layer**: event factory, reducers, TTL/cleanup.
- **Security & Group Layer**: KDF, encrypt/decrypt, MAC verify.
- **Storage Layer**: SQLite + migrations + materialized views.
- **Transport Layer**: BLE mesh (Android: custom BLE transport) + relay sync.

## 4) File placement rules (guidelines)

- **Event model / reducers**: domain/core module (Android/iOS).
- **Storage / migrations**: data/storage module.
- **BLE transport**: transport/ble module.
- **Relay sync**: transport/relay module.
- **Crypto / group**: security/group module.
- **UI**: ui/chat, ui/map, ui/group modules.
- **Background jobs**: workers/schedulers module.
- **Tests**: unit tests for crypto/reducer, integration for BLE/relay.

## 5) Phase 2 quick map by function

### Event model & reducers
- Primary spec: `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
- Invariants: `docs/specs/Cross-platform_Event_Schema_Reducer_Invariants.md`

### BLE mesh transport
- Primary spec: `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
- Architecture: `docs/specs/Architecture_Diagram.md`

### Relay sync (online)
- Relay spec: `docs/specs/PHASE_1_TECHNICAL_SPEC.md`
- Architecture: `docs/specs/Architecture_Diagram.md`

### Implementation task list
- `docs/milestones/Phase_2_Task_List.md`

## 6) Quick links

- Project overview: `docs/specs/Project_Overview_Technical_Spec.md`
- Phase 2 spec: `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
- System architecture: `docs/specs/Architecture_Diagram.md`
- Phase 2 task list: `docs/milestones/Phase_2_Task_List.md`
- Phase 2 Android UI checklist: `docs/mobile/phase2_android_ui_checklist.md`
- Threat model (EN): `docs/private/THREAT_MODEL.md`

## 7) Android compatibility policy

- Primary target: Android 11 and above. Stability and correctness for 11+ are the default priority.
- Android below 11 is optional and best-effort only.
- If a change could reduce stability or correctness on Android 11+, call it out explicitly and wait for confirmation before proceeding.
- If a change primarily improves Android below 11 but adds risk to 11+, do not proceed unless explicitly approved.

## 8.1) Android device debug + install (USB and Wireless)

### Prerequisites
- Android SDK installed (via Android Studio).
- SDK location set in `android/local.properties`:
  ```
  sdk.dir=/home/USER/Android/Sdk
  ```
- Use SDK adb (newer than distro adb):
  ```
  export ANDROID_SDK_ROOT=/home/USER/Android/Sdk
  export PATH=$ANDROID_SDK_ROOT/platform-tools:$PATH
  ```

### Enable Developer options + USB debugging (Samsung example)
1) Settings -> About phone -> Software information
2) Tap Build number 7 times (enter PIN if asked)
3) Settings -> Developer options -> enable USB debugging

### USB debugging (wired)
1) Plug in USB cable and allow USB debugging on the phone.
2) Verify:
  ```
  adb devices
  ```
3) Install app:
  ```
  cd android
  ./gradlew :app:installOfficialDebug
  ```

### Wireless debugging (ADB over Wi-Fi)
1) Developer options -> Wireless debugging -> ON
2) Pair device with pairing code:
  ```
  adb pair <PHONE_IP>:<PAIR_PORT>
  ```
3) Connect using the non-pairing port shown in Wireless debugging:
  ```
  adb connect <PHONE_IP>:<CONNECT_PORT>
  adb devices
  ```
4) Install app:
  ```
  cd android
  ./gradlew :app:installOfficialDebug
  ```
5) Safety: turn off Wireless debugging after use.

### Notes
- `./gradlew test` runs unit tests only and does not install the app.
- Use `:app:installEmergencyDebug` to install the emergency flavor.

## 8.2) Android emulator (CLI)

### Prerequisites
- Android SDK Command-line Tools installed.
- System image installed (API 34, Google APIs x86_64).
- Optional: add SDK paths and alias to `~/.bashrc`:
```
echo 'export ANDROID_SDK_ROOT=/home/USER/Android/Sdk' >> ~/.bashrc
echo 'export PATH=$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH' >> ~/.bashrc
echo 'alias emupixel=\"$ANDROID_SDK_ROOT/emulator/emulator -avd Pixel34\"' >> ~/.bashrc
source ~/.bashrc
```

### Install system image (CLI)
```
export ANDROID_SDK_ROOT=/home/USER/Android/Sdk
export PATH=$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH
sdkmanager "system-images;android-34;google_apis;x86_64"
```

### Create AVD
```
avdmanager create avd -n Pixel34 -k "system-images;android-34;google_apis;x86_64"
```

### Run emulator
```
$ANDROID_SDK_ROOT/emulator/emulator -avd Pixel34
```

### Remove AVD
```
avdmanager delete avd -n Pixel34
```

### Troubleshooting
- Emulator fails to start: re-install system image and recreate AVD.
- Missing kernel / broken image: delete the system image folder and reinstall with `sdkmanager`.
- Slow or black screen: try another image (Google APIs) or disable hardware acceleration.
- "Not in GZIP format" during install (corrupt download):
  ```
  rm -rf /home/USER/Android/Sdk/.temp
  rm -rf /home/USER/Android/Sdk/system-images/android-34/google_apis/x86_64
  sdkmanager "system-images;android-34;google_apis;x86_64"
  ```
- "Can't find 'Linux version ' string in kernel image" (broken system image):
  ```
  rm -rf /home/USER/Android/Sdk/system-images/android-34/google_apis/x86_64
  sdkmanager "system-images;android-34;google_apis;x86_64"
  avdmanager delete avd -n Pixel34
  avdmanager create avd -n Pixel34 -k "system-images;android-34;google_apis;x86_64"
  ```
- "Connection refused" when `adb connect`:
  - Use the IP:PORT shown in Wireless debugging (not the pairing port).
  - Re-run:
  ```
  adb connect <PHONE_IP>:<CONNECT_PORT>
  adb devices
  ```
- "SDK path not found":
  - Set `android/local.properties`:
  ```
  sdk.dir=/home/USER/Android/Sdk
  ```

## 9) Phase 2 implementation checklist (by module)

> For quick onboarding and task splitting. Prioritize core before UI.  
> Phase 2 focuses on Android; iOS can reuse this checklist as a baseline later.

### 9.1 Core data model & crypto
- [ ] Define event schema + envelope (event_id == mid).
- [ ] JSON canonicalization for MAC.
- [ ] KDF (scrypt/Argon2id) + store salt.
- [ ] HMAC verify + tie-breaker rules.
- [ ] Anti-replay cache per `(gid, event_id)` TTL 24h.
Definition of done: event + MAC end-to-end works; unit tests cover canonicalization/KDF/MAC.

### 9.2 Storage layer (SQLite)
- [ ] Events table (append-only, unique `(gid, event_id)`).
- [ ] Indexes (gid/event_id/created_t/type).
- [ ] Materialized tables: chat + map.
- [ ] Transaction wrapper: insert + reducer.
- [ ] Deduplication enforcement.
Definition of done: insert one event -> reducer runs in a transaction -> chat/map state reads from materialized tables.

### 9.3 Reducers
- [ ] Chat reducer (MSG/EDIT/DEL, LWW + tie-breaker).
- [ ] Map reducer (MARKER/AREA, LWW + tombstone).
- [ ] TTL precedence (meta.ttl_s).
- [ ] Clock skew handling (tie-breaker rule).
Definition of done: same set of events yields same state regardless of order.

### 9.4 Transport: BLE mesh (Android: custom BLE transport)
- [ ] Broadcast envelope + receive.
- [ ] Chunking/reassembly.
- [ ] Flood control (jitter/backoff).
- [ ] Dedupe chunk/envelope + cache TTL.
- [ ] Payload size limit handling.
Definition of done: two devices exchange payload > MTU; no duplicated events after re-receive.

### 9.5 Transport: Relay sync (online)
- [ ] Push new events (opaque).
- [ ] Pull by cursor + persistence per relay.
- [ ] Retry/backoff + jitter.
- [ ] Clock skew handling in reducers.
Definition of done: after reconnect, client syncs correct delta without reapplying duplicates.

### 9.6 UI
- [ ] Chat UI (thread list + messages).
- [ ] Map UI (markers/areas CRUD).
- [ ] Group join/create UI (passphrase + gid).
- [ ] Status indicators (BLE/relay).
Definition of done: create group, send chat, add marker, see sync status update via BLE/relay.

### 9.7 Background jobs
- [ ] Cleanup job (expired + old events).
- [ ] Sync worker (foreground + periodic).
- [ ] Backpressure / rate limiting BLE ingress.
Definition of done: jobs run on schedule, cleanup does not remove valid TTL data, BLE ingress does not lag app.

### 7.8 Testing
- [ ] Unit tests: crypto (KDF, MAC canonicalization).
- [ ] Unit tests: reducers (LWW, tombstones).
- [ ] Integration: BLE ingest pipeline.
- [ ] Integration: relay sync push/pull.
Definition of done: CI green; minimal coverage for crypto/reducers.

---

Suggestion: update this doc once scaffolding starts so other devs can find the right modules quickly.
