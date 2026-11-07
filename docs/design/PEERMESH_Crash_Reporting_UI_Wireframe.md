# Peer Mesh – Crash Reporting UI Wireframe

Version: 1.0  
Platform: Android  
Purpose: UX layout and interaction design for custom crash logger

---

# Overview

This document defines the layout structure, content hierarchy, and user interaction behavior for the crash reporting flow in Peer Mesh.

Design goals:

- Calm and professional
- No alarming language
- No automatic data transmission
- User-controlled reporting
- Consistent with privacy-first positioning

---

# 1. Crash Detected Dialog (After App Relaunch)

## Type

Modal dialog displayed when the app is reopened after an unexpected crash.

---

## Layout Structure

Centered dialog containing:

- Neutral info icon
- Title
- Body text
- Two action buttons

---

## Content

**Title:**

Peer Mesh closed unexpectedly

**Body:**

Peer Mesh encountered an unexpected issue previously.  
Would you like to review a crash report to help improve the app?

**Buttons:**

- Not Now (secondary)
- Review Report (primary)

---

## Behavior

- Appears only once after crash.
- If user selects "Not Now", dialog will not reappear immediately.
- If user selects "Review Report", navigate to Crash Report Review screen.
- No automatic sending of data.
- No urgent or warning language.

---

# 2. Crash Report Review Screen

## Type

Full-screen scrollable page.

---

## Layout Structure

Top App Bar:
- Back button
- Title: Crash Report

Body Content:

1. Intro message
2. Crash summary section
3. Expandable technical details
4. Privacy notice
5. Action buttons at bottom

---

## Section Details

### Intro Text

Unexpected issue detected.

Short neutral statement. No blame or alarm.

---

### Crash Summary Section

Displayed as grouped information fields:

- Date
- App version
- Android version
- Device model

This section is always visible.

---

### Technical Details (Expandable)

Collapsed by default.

Header:

▶ Technical Details

When expanded:
- Show full stack trace
- Scrollable area
- Monospace formatting

User must manually expand to view raw error data.

---

### Privacy Notice

Displayed before action buttons:

This report does not include chat messages or personal data.  
Only technical information about the issue will be shared.

Purpose:
- Reinforce transparency
- Reduce user hesitation

---

## Action Buttons

At bottom of screen:

- Delete (secondary)
- Send Report (primary)

---

## Behavior

- "Send Report" opens email intent with pre-filled subject and body.
- "Delete" removes stored crash log (with confirmation prompt).
- "Back" keeps crash log stored.
- After sending, show confirmation message (snackbar or short confirmation screen).

---

# 3. Confirmation Behavior

After report is sent:

Show message:

Report sent. Thank you for helping improve Peer Mesh.

No additional prompts.

---

# 4. Settings – Diagnostics Section

Accessible via Settings screen.

---

## Layout

Section title:

Diagnostics

Options:

☑ Ask before sending crash reports

Supporting text:

Peer Mesh stores crash logs locally.  
You can review them before sending.

---

## Behavior

- If toggle disabled:
  - Crash logs still stored locally.
  - No prompt shown automatically.
- User can re-enable anytime.

---

# 5. Multiple Crash Handling (Future-Ready)

If multiple crash logs exist:

- Show list screen with entries sorted by date.
- Each entry opens Review screen.
- User may delete individually or all.

---

# 6. UX Tone Guidelines

Use calm language such as:

- Unexpected issue
- Unexpected closure
- Technical details

Avoid:

- Fatal error
- Critical failure
- Severe malfunction

---

# 7. Privacy Principles

- No automatic upload.
- No background transmission.
- No hidden analytics.
- User must explicitly choose to send report.
- Crash logs stored locally only.

---

# 8. User Flow Summary

Crash occurs  
→ Crash logged locally  
→ User reopens app  
→ Dialog appears  
→ User chooses:
   - Review Report
   - Not Now  

If Review:
→ View summary  
→ Expand details (optional)  
→ Send or Delete  

If Send:
→ Email intent opens  
→ User manually sends  

---

# Conclusion

This crash reporting UX:

- Preserves user trust
- Maintains privacy positioning
- Avoids alarming design
- Supports professional commercial standards
- Aligns with Peer Mesh brand identity
