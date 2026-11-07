# Location Prompts for Map & Trail

## Summary
Add non-blocking and blocking location prompts to improve UX, and run background trail recording via **Foreground Service (FGS)** with a persistent notification (no `ACCESS_BACKGROUND_LOCATION` or background location permission flow):

- **Map (Group/Room):** show a one-time optional dialog on open when Location is OFF. User can skip with **Not now** or open Location settings.
- **Trail screen:** show a one-time optional dialog on open when Location is OFF.
- **Trail recording start (manual):** if Location is OFF, show a blocking dialog. Only start recording after Location is enabled. If user taps **Not now**, do not start.
- **Auto-trail in background:** use FGS + persistent notification to keep recording in background. Do not request background location permission or show “Allow all the time” guidance.

## Behavior Details

### Map (GroupMapActivity)
- Trigger: Activity open.
- Condition: Location disabled.
- Dialog:
  - Title: “Enable location for current position”
  - Body: “Turn on Location to show your current position on the map. This is optional.”
  - Buttons: **Open Location Settings**, **Not now**
- Persistence: do not re-show on configuration change (saved state flag).

### Trail (TrailActivity)
- Trigger: Activity open.
- Condition: Location disabled.
- Dialog:
  - Title: “Enable location for trail”
  - Body: “Turn on Location if you want to record a trail. This is optional.”
  - Buttons: **Open Location Settings**, **Not now**
- Persistence: do not re-show on configuration change (saved state flag).

### Trail Start (Manual)
- Trigger: user taps **Start** in manual mode.
- Condition: Location disabled.
- Dialog:
  - Title: “Location required to record”
  - Body: “Turn on Location to start trail recording.”
  - Buttons: **Open Location Settings**, **Not now**
- Behavior:
  - **Open Location Settings** → after Location ON, start recording.
  - **Not now** → do not start recording.

### Auto-trail in background (no ACCESS_BACKGROUND_LOCATION)
- Background recording **does not request** `ACCESS_BACKGROUND_LOCATION` and **does not** route users to background location settings.
- When user enables auto-trail, show a one-time info dialog explaining a **persistent notification** will stay active.
- If Location permission is missing, request **foreground** location permission (fine/coarse).

## References
- `docs/reviews/BLE_Mesh_Relax_Location_Requirement_SPlus.md`
