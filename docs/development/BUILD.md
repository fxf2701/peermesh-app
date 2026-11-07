# Build & Install (Offline-first Mesh Communication App)

> This document explains how to build the mobile clients from source.\
> The project is client-only and does not require a backend service to run basic functionality.

---

## 0. Requirements

### Android

- Android Studio (latest stable)
- JDK 17 (or the version required by your Gradle setup)
- Android SDK (installed via Android Studio)
- A physical Android device for testing (recommended)

### iOS

- macOS with Xcode (latest stable)
- Apple Developer account (for device install; simulator supported for limited testing)
- A physical iOS device for Bluetooth testing (recommended)

---

### 1. Repository Structure (example)

Adjust paths to match your repo layout.

```
/android/            # Android client (Kotlin)
/ios/                # iOS client (Swift)
/shared/             # Shared schemas/specs (optional)
/docs/               # Documentation
```

---

## 2. Android Build (Kotlin)

### 2.1 Open the project

- Launch **Android Studio**
- Open the folder: `android/`

### 2.2 Configure SDK & Gradle

- Ensure **Android SDK** is installed (Android Studio > SDK Manager)
- Let Gradle sync complete
- If prompted, install missing build tools

### 2.3 Build Debug APK

From Android Studio:

- Build → Build Bundle(s) / APK(s) → Build APK(s)

Or from terminal:
```bash
cd android
./gradlew assembleDebug
```

Output:
```
android/app/build/outputs/apk/debug/app-debug.apk
```

### 2.4 Build Release APK (Unsigned / Signed)

Unsigned release APK:

```bash
cd android
./gradlew assembleRelease
```

Signed release APK (recommended for redistribution):

- 1. Create a keystore:
```bash
keytool -genkeypair -v -keystore release.keystore -alias app -keyalg RSA -keysize 2048 -validity 10000
```
- 2. Configure signing in `android/app/build.gradle` (do not commit secrets)
- 3. Build:
```bash
./gradlew assembleRelease
```
> **Important:** Do not commit keystore files or signing passwords.

### 2.5 Install (Sideload) APK

Using ADB:
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## 3. iOS Build (Swift)

### 3.1 Open the project

- Open `ios/App.xcodeproj` or `ios/App.xcworkspace` in Xcode

### 3.2 Select Signing

- Go to Project Settings → Signing & Capabilities
- Choose your team
- Ensure a valid Bundle Identifier is set

### 3.3 Build & Run (Device)

- Connect iPhone via USB
- Select your device in the top toolbar
- Click **Run**

### 3.4 Build Archive (for distribution)

- Product → Archive
- Use Organizer to export:
  - Development build (for internal testing)
  - Ad Hoc (if you manage device UDIDs)
  - TestFlight / App Store (if you choose official distribution later)
> Bluetooth features are best tested on a physical device.

---

## 4. Offline / Low-connectivity Build Notes

This project is designed to be built and used without internet access once dependencies are present.

### 4.1 Android: Gradle dependency caching

To build offline after a successful online sync:
- Run one build online first to populate the Gradle cache
- Later builds can use:
```bash
./gradlew assembleDebug --offline
```

### 4.2 iOS: Swift Package / CocoaPods caching

- Ensure dependencies are resolved once online
- For offline builds, keep:
  - Xcode DerivedData
  - SPM caches (or Pods directory if using CocoaPods)

---

## 5. Configuration

### 5.1 Build-time configuration

Some settings may be controlled via:
- `config/` files
- build flavors
- environment flags

Common examples:
- Default TTL values
- Feature flags (e.g., offline communication UI)
- Logging level
> Keep any security-sensitive values out of source control.

---

## 6. Testing Notes (Bluetooth)

Bluetooth testing recommendations:
- Prefer physical devices
- Test in real environments:
  - open space vs crowded areas
  - screen on/off
  - app foreground/background

Platform differences:
- iOS has stricter background limits
- Android can run foreground services for more persistent operation

---

## 7. Troubleshooting

### Android

- **Gradle sync fails:** check JDK version and Android SDK components
- **Bluetooth permission issues:** verify runtime permissions (Android 12+ requires explicit BLE permissions)
- **Install blocked:** enable “Install unknown apps” for your file manager/ADB

### iOS

- **Signing errors:** confirm Team selected and bundle ID unique
- **Bluetooth not working in simulator:** use a physical device
- **App won’t run on device:** trust developer certificate in Settings

---

## 8. Security Reminder

Building from source does not guarantee safety.
- Review `../SECURITY.md` for limitations and threat model
- Do not assume anonymity or protection against powerful adversaries
- Keep device-level security (passcode, OS updates) enabled

---

## 9. License & Redistribution

- Follow the project `LICENSE`
- If redistributing binaries, include:
  - `README.md`
  - `../SECURITY.md`
  - `../DISCLAIMER.md`

---

*This document is intentionally neutral and focuses on software build mechanics only.*