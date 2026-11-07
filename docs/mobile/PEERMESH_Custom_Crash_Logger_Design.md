# Peer Mesh – Custom Crash Logger Design

Version: 1.0  
Status: Design Specification  
Platform: Android (Kotlin)

---

# 1. Overview

This document describes the design of a lightweight, privacy-conscious crash logging system for Peer Mesh.

The goal is to:

- Capture unexpected crashes
- Store crash logs locally on the device
- Allow users to review and optionally send crash reports
- Avoid automatic data transmission
- Maintain Peer Mesh’s privacy-first positioning

This system replaces third-party crash reporting (e.g., Firebase Crashlytics) in early commercial stages.

---

# 2. Design Principles

## 2.1 Privacy First

- No automatic data transmission
- No background upload
- User must explicitly opt-in before sending crash data
- No personal data collection

## 2.2 Minimal Footprint

- No heavy SDK dependency
- No analytics tracking
- Lightweight implementation
- Simple file-based storage

## 2.3 Transparency

- User can see crash content before sending
- Clear explanation of what will be sent
- User can delete crash logs

---

# 3. Functional Requirements

## 3.1 Crash Capture

The system must:

- Catch uncaught exceptions globally
- Capture:
  - Stack trace
  - Thread name
  - Timestamp
  - App version
  - Android version
  - Device model
- Store crash information locally in a file

---

## 3.2 Local Storage

Crash reports should be stored in:

/files/crash_logs/

File naming format:

crash_YYYYMMDD_HHMMSS.log

Example:

crash_20250112_142355.log

Retention policy:

- Keep maximum 5 recent crash files
- Delete oldest automatically

---

## 3.3 On Next App Launch

If a crash log exists:

Display a dialog:

Title:
App crashed previously

Message:
Peer Mesh detected an unexpected crash.  
Would you like to review and send a crash report to help improve the app?

Buttons:
[Review Report]   [Dismiss]

---

## 3.4 Review Screen

If user taps "Review Report":

Show:

- Crash summary (short)
- Expandable full stack trace
- App version
- Android version
- Timestamp

Buttons:
[Send Report]  
[Delete Report]  
[Cancel]

---

## 3.5 Sending Crash Report

Sending method options:

Option A (Simplest - Recommended v1):
- Send via email intent
- Pre-fill subject and body
- User manually sends

Option B (Future):
- Send to HTTPS endpoint
- Requires backend API

For v1:
Use email-based sending for simplicity.

Email format:

Subject:
Peer Mesh Crash Report - v1.0.0

Body:
Include full crash log text.

---

# 4. Technical Architecture

## 4.1 Global Exception Handler

Implement a custom UncaughtExceptionHandler:

Thread.setDefaultUncaughtExceptionHandler()

Flow:

1. Intercept exception
2. Serialize crash data
3. Write to local file
4. Call original handler
5. Allow app to terminate

---

## 4.2 Crash Log Structure (JSON Recommended)

Example format:

{
  "timestamp": "2025-01-12T14:23:55",
  "app_version": "1.0.0",
  "android_version": "Android 14",
  "device_model": "Pixel 7",
  "thread": "main",
  "stacktrace": "java.lang.NullPointerException..."
}

JSON is preferred over plain text for easier future parsing.

---

## 4.3 Crash Manager Module Structure

Recommended module structure:

core-crash/
    CrashHandler.kt
    CrashLogger.kt
    CrashStorage.kt
    CrashReportFormatter.kt

Keep crash logic separate from UI.

---

# 5. User Consent Design

## 5.1 No Automatic Collection

Crash data is stored locally only.

No upload unless user explicitly taps "Send".

---

## 5.2 Settings Toggle (Optional)

In Settings screen:

Crash Reports  
☐ Share crash reports manually  

If disabled:
- App still logs crash locally
- No prompt shown automatically

---

# 6. Data Safety (Google Play Compliance)

Because:

- No automatic transmission
- No analytics
- No user identifiers
- No advertising ID

You may declare in Data Safety:

- No personal data collected
- No data shared

If sending via email:
User manually chooses to share data.

---

# 7. Security Considerations

- Do NOT log:
  - Message content
  - Peer device IDs
  - Chat history
  - Encryption keys
- Only log technical exception info

Optional improvement:
Sanitize logs before storage.

---

# 8. Future Enhancements (Phase 2)

After user base grows:

- Add crash grouping
- Add crash fingerprint hashing
- Add backend crash endpoint
- Optional Firebase Crashlytics (opt-in only)

---

# 9. UX Flow Summary

Crash occurs
    ↓
Log saved locally
    ↓
Next launch
    ↓
Prompt user
    ↓
User reviews report
    ↓
User decides:
    - Send
    - Delete
    - Ignore

---

# 10. Why Custom Crash Logger?

Advantages for Peer Mesh:

- Maintains privacy-first positioning
- Avoids early dependency on Google SDKs
- Keeps app lightweight
- Reduces perceived data tracking
- Aligns with offline-first philosophy

---

# 11. Conclusion

The custom crash logger provides:

- Professional-level crash insight
- User-controlled reporting
- Minimal privacy impact
- Low implementation complexity

It is suitable for Peer Mesh v1 commercial release.
