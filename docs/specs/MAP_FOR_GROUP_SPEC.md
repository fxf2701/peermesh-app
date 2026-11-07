# Map for Group — Technical & UX Specification
## Offline-first • Privacy-first • Neutral-by-design

**Status:** Draft  
**Applies to:** Mobile App (Android / iOS)  
**Context profiles:** `urban`, `outdoor`  
**Scope:** Group-scoped map only (no global map)

---

## 1. Purpose & Design Goals

The **Map for Group** feature provides a shared situational awareness surface for small groups operating in environments with limited connectivity.

It is designed to:
- Support **coordination without command**
- Share **observations, not instructions**
- Reduce risks of **manipulation, authority illusion, and mass herding**
- Work **offline-first** with delayed sync
- Remain **neutral and observation-focused by design**

This map is **not**:
- A navigation or routing authority
- A verified source of truth
- A global or public situational map

---

## 2. Core Principles

1. **Group-only scope**
   - Every map is strictly bound to a group (`gid`)
   - No global or cross-group map exists

2. **Observation over instruction**
   - Markers describe observed conditions
   - No directional commands or “safe zone” semantics

3. **Ephemeral by default**
   - All shared data has a TTL
   - Information fades with time to prevent stale authority

4. **Personal data is opt-in**
   - Location and trail data are local-only unless explicitly shared

5. **Neutral language & icons**
   - Marker names use factual observations and avoid advocacy or command-oriented wording

---

## 3. Map Context Profiles

The same map system adapts behavior based on context profile.
Context affects **UI, defaults, and policy**, not core architecture.

### 3.1 `urban` Profile
Designed for dense, complex human environments.

**Policies**
- Short TTL for markers
- No route or navigation drawing
- Very limited presence sharing
- Strong emphasis on freshness & uncertainty

**Use cases**
- Urban crowd movement
- Connectivity disruptions
- Complex, mixed-intent environments

---

### 3.2 `outdoor` Profile
Designed for natural environments and small groups.

**Policies**
- Longer TTL for markers
- Optional trail / route history
- Clearer presence indicators
- SOS-style attention markers (group-only)

**Use cases**
- Hiking, camping, trekking
- Search & regroup
- Limited infrastructure environments

---

## 4. Map Item System (Marker & Area)

Map items are phenomenon- or coordination-based, never actor- or
command-based. A **Marker** is a point with an optional radius; an **Area** is
a drawn polygon. Both have a stable `kind` key, an emoji-backed readable label,
and one canonical category. Unknown kind keys are rejected.

| Category | Meaning | Creation source | Allowed interactions |
| --- | --- | --- | --- |
| `situation` | A reported condition that may be checked and resolved | Required: `firsthand`, `reported_by_others`, or `uncertain` | Confirm, Add Situation Update, Mark Situation No Longer Active |
| `coordination` | A neutral coordination reference or zone | Forbidden | Acknowledge Coordination, Add Coordination Note |

Situation lifecycle is `active` or `no_longer_active`. Resolution is one-way;
a resolved Situation cannot return to active. Coordination items have no
Situation lifecycle and never expose Situation actions.

### 4.1 Core Marker Types (Shared – Urban & Outdoor)

These types are **always available,** regardless of context profile.

| Type | Icon | Label | Description |
| ---- | ---- | ----- | ----------- |
| obstacle | 🚧 | Blocked path | Path blocked or obstructed |
| crowded | 👥 | Crowded area | High activity or density |
| risk | ⚠️ | Potential risk | Possible danger or hazard |
| hot_zone | 🔥 | Hot zone | Area with intense, unstable, or dangerous activity. Avoid or proceed with extreme caution. |
| incident | 🚨 | Ongoing disruption | Ongoing disturbance or disruption |
| medical | 🚑 | Medical help needed | First aid or medical help needed |
| resource | 💧 | Utility point | Water, rest, or utilities |
| route_note | 🧭 | Route observation | Route or trail observation |
| environment | 🌧 | Environmental conditions | Weather or environmental conditions |
| info | 📝 | Observation note | Free-form observation |

> **Note:**
> 🔥 *Hot zone* describes **state, not cause.**
> It does not imply a cause or attribute responsibility.

### 4.2 Urban-Focused Marker Types

Available when context profile = **urban**.

| Type | Icon | Label | Description |
| ---- | ---- | ----- | ----------- |
| checkpoint | 🛂 | Checkpoint | Controlled or inspected passage point |
| road_closed | ⛔ | Road closed | Road officially closed or restricted |
| traffic_jam | 🚗 | Traffic congestion | Heavy traffic or slow movement |
| power_outage | 🔌 | Power outage | Electricity or lighting unavailable |
| connectivity | 📶 | Connectivity point | Reliable signal or connectivity spot |

### 4.3 Outdoor-Focused Map Item Types

Available when context profile = **outdoor**.

| Type | Icon | Label | Description |
| steep | ⛰ | Steep terrain | Steep or difficult terrain |
| water_crossing | 🌊 | Water crossing | River, stream, or flooded crossing |
| wildlife | 🐾 | Wildlife presence | Signs or risk of wildlife |
| camp | 🏕 | Campsite | Camping or resting spot |
| rescue | 🆘 | Rescue needed | Emergency rescue or urgent help needed |
| waypoint | 📍	| Waypoint | Important navigation landmark |

`camp`, `waypoint` and `meeting_point` are `coordination` kinds. Every other
kind in the Shared, Urban and Outdoor tables above is a `situation` kind.

#### Coordination Marker selector by profile

| Profile | Marker kinds | Rationale |
| --- | --- | --- |
| Urban | 📌 `meeting_point`, 📍 `waypoint` | A neutral meeting or navigation reference in a dense environment |
| Outdoor | 🏕 `camp`, 📍 `waypoint` | A campsite/resting point or navigation reference |

The profile list is intentional: Urban creation must not fall back to the
Outdoor-only `camp` kind merely because the user selected Coordination.

### 4.3.1 Area compatibility (current canonical contract)

The canonical contract supports an Area only for the following existing kinds:
`obstacle`, `crowded`, `risk`, `hot_zone`, `incident`, `medical`, `resource`,
`environment`, `road_closed`, `traffic_jam`, `power_outage`, `steep`,
`water_crossing`, `wildlife`, `rescue`, `assembly_zone`, `trail_sector`,
`event_zone`, and `rescue_staging`.

The following kinds are marker-only and cannot be used to create or update an
Area: `route_note`, `info`, `checkpoint`, `connectivity`, `camp`, `waypoint`,
and `meeting_point`. The current contract accepts a drawn polygon; it does not enforce a
minimum square-metre threshold or a minimum internal angle.

### 4.3.2 Coordination-area kinds (current canonical contract)

| Key | Icon | Label | Available in Area selector |
| --- | --- | --- | --- |
| `assembly_zone` | 🧭 | Assembly zone | Urban and Outdoor |
| `trail_sector` | 🥾 | Trail sector | Outdoor |
| `event_zone` | 🎪 | Event zone | Urban and Outdoor |
| `rescue_staging` | 🚑 | Rescue staging | Outdoor |

These are `coordination` kinds. They support the Area workflow and its
Acknowledgement/Coordination Note actions; they do not show Situation
confirmation or lifecycle controls. The four kinds are protocol-compatible
with Markers too, but the current Marker creation selector intentionally does
not offer them.

### 4.3.3 Superseded Area proposal (not implemented)

The historical proposal below is retained only for design history. Its
`*_area` keys, 10,000 m² minimum and 45° angle rule are **not** canonical
keys or enforced validation rules.

Recommended area types (limited set):
| Type | Label | Description |
| ---- | ----- | ----------- |
| crowded_area | Crowded area | High density or activity |
| restricted_area | Restricted area | Access is limited or difficult |
| hazard_area | Hazard area | General risk in this zone |
| blocked_area | Blocked area | Area not passable |
| incident_area | Incident area | Ongoing disruption in the zone |
| flooded_area | Flooded area | Flooded or water‑logged zone |
| fire_area | Fire/smoke area | Fire or heavy smoke zone |
| medical_need_area | Medical need area | Area needing medical help |
| search_area | Search area | Area requiring search efforts |

### 4.4 Design notes (current)

- Situation kinds are observations, not instructions. Coordination kinds are
  neutral shared references, not command assignments.
- Icons indicate reported or perceived context, subject to uncertainty, TTL and
  local conditions. Users should evaluate risk independently.
- Map and Detail render the catalog emoji plus readable label. Area artwork
  renders the emoji at its polygon centroid in a dashed circular Area cue;
  Marker artwork renders the emoji at its point.

Historical design guidance retained below:

- Marker types are **observations,** not instructions.
- Icons indicate **reported or perceived conditions,** subject to:
  - uncertainty
  - decay over time (TTL)
  - local context
- Users should **evaluate risk independently.**

### 4.5 UI Language Rules (Neutral, Observation-Focused)

Use short, observational labels. Avoid language that signals authority, advocacy, or command.

**Prefer**
- observed, reported, noted
- possible, potential, uncertain
- area, point, condition

**Avoid**
- safe zone, verified, official
- leader, command, checkpoint
- secure, trusted, cleared

### 4.6 Metadata and mutation availability (current)

The create/edit forms expose geometry, kind, optional radius (Marker only),
optional TTL, label (maximum five characters), and optional note. Canonical
Situation creation requires a source; canonical Coordination creation forbids
one. All items are group-bound and retain the author identity in canonical
state.

Owner Edit and Delete are visible only for an active, unexpired canonical item
within its owner-edit window. They are hidden for non-owners, expired items,
resolved (`no_longer_active`) Situations, closed edit windows, invalid/missing
canonical state, and locally removed members. A confirmed Delete removes only
its target; it is not a removal-request workflow.

### 4.6.1 Superseded mandatory-field proposal (not implemented)

Every marker must include:
- Timestamp
- TTL (time-to-live)
- Freshness indicator
- Confidence level:
  - “Observed directly”
  - “Uncertain / reported”
- Anonymous author (within group)

Markers auto-fade visually as TTL approaches expiry.

---

### 4.7 Superseded consensus / contribution proposal (not normal UI)

The following vote/contribution design is historical. Normal current UI does
not expose Report/Observe, Contributions, Contribute, Request Removal or
`map_item_votes` counts. It uses the category-specific canonical actions in
section 4.1 instead.

Consensus merge (type + proximity + similar TTL) is **deferred** for now. We rely on **proximity guard + cooldown** and per‑user votes/notes instead.

Current consensus display:
- Each marker/area shows **Reported N / Observed M** (from `map_item_votes` plus owner’s initial confidence).
- Observed is higher confidence than Reported.
- List ordering uses weighted counts: `weight = observed*2 + reported`, tie‑break by `updated_t`.

Contribution preview (list + popup):
- List item shows **latest contribution text only** (no counts). If no contributions:
  - Owner: show “No vote yet…” guidance.
  - Non‑owner: show “Be the first person to vote…”.
- Popup shows **Contributions** section: latest 10 votes + note, “See more” loads 10 more.
- Lazy load previews for **visible items only**; pause list refresh while scrolling; refresh **0.5s after idle** (throttle 5s).

---

### 4.7.1 Superseded per-user Report/Observe notes proposal

To prevent manipulation of core marker/area details while still allowing context:
- **Non‑owners cannot edit** icon/label/note/TTL/geometry of an existing marker/area.
- Each user may attach **one optional note** per marker/area when they Report/Observe.
- Notes are **per‑user** and editable/deletable **only by the author**.
- Marker/area owners **cannot** edit or delete notes created by other users.
- **Deleting a note also removes the user’s vote** (Reported/Observed) for that item.
- Notes are stored in a separate table keyed by `(item_id, actor_id)` (upsert).
- UI: the Report/Observe action opens a note field (optional); if a note exists, allow Edit/Delete.

This keeps the canonical marker/area stable while still collecting distributed context.

---

### 4.7.2 Superseded owner removal-request proposal

Owners may request removal instead of deleting outright:
- **Owner can request removal** with a **required reason**.
- The marker/area **remains active until TTL**; request does **not** shorten TTL.
- Map rendering shows the item **transparent** while a removal request is active.
- Detail view shows: **“Requested removal by owner. Reason: …”**
- Owner can **cancel** the request; **cooldown 30s** (Cancel button disabled during cooldown).

---

### 4.8 Future anti-spam / anti-herding proposal (not current contract)

The controls below are product proposals, not rules enforced by the current
canonical Marker/Area validator or normal UI. In particular, current creation
does not enforce the described proximity, cooldown, vote or Report/Observe
flows.

To reduce manipulation and crowd bias:
- **Per‑user rate limit** for marker creation (e.g., max N markers per 10 minutes).
- **Per‑type cooldown** within a small radius (avoid rapid duplicates in the same spot).
- **Same‑user proximity guard**: a user cannot create two markers of the same type within a small radius.
- **Same‑user proximity guard (markers with radius)**:
  - If marker has **radius**, proximity radius is **dynamic**: `max(200m, radius * 0.5)` with **cap 3km**.
  - If no radius, fallback to **200m**.
- **Same‑user proximity guard (areas)**:
  - For **areas**, proximity radius is **dynamic** based on size: `max(200m, size * ratio)` with **cap 3km**.
  - `size` = max(width, height) of area bbox (meters).
  - `ratio = 0.15` for size < 3km, `ratio = 0.10` for size ≥ 3km.
  - **Allow refine/escalate**: if the new area **contains** or is **contained by** an existing same‑type area, allow.
  - **Otherwise** if bbox **overlaps** or centroid distance ≤ dynamic radius → block create and suggest Report/Observe.
- **Dedup within proximity**: if a marker of the same type exists nearby, offer “Report/Observe” instead of creating a new marker.
- **Cooldown by type + radius** (anti‑spam):
  - If a **same‑type** marker/area exists **nearby** and was created/updated within **10 minutes**, block new creation.
  - Show message to **Report/Observe** instead of creating a new item.
  - Proximity uses the same **dynamic radius** rules as the proximity guard.
- **Confidence weighting**: Observed counts carry more weight than Reported.
- **TTL clamps** by type (min/max TTL bounds) to prevent extreme values.
- **Single-state per user**: a user can only be Reported *or* Observed for the same marker/area. Switching state replaces the previous count (no double counting).
- **Single switch limit**: a user may switch state at most **once** for the same marker/area.
- **Per‑user single state**: each actor has exactly one active state (Report or Observe) per item; switching replaces their previous count.
- **First marker rule**: when creating a new marker/area, the creator is **Reported only** and cannot later switch to Observed for that same item. Observed is only available on existing items by other users.
- **Edit window cap**: core fields (icon/type/label/geometry/TTL) can only be edited within a limited window:
  - `lock_at = clamp(0.5 * TTL, min=10m, max=6h)`
  - After `lock_at`, only **note edits by the same actor** are allowed; vote changes are locked.
  - If the actor has no existing vote/note, they cannot add a new vote/note after `lock_at`.

These guardrails should not block urgent reporting, but should nudge users toward existing entries when possible.

---

## 5. Location & Trail Sharing

### 5.1 Default Behavior
- Location and trail data are **local-only**
- No background tracking
- No automatic broadcast

---

### 5.2 Sharing Options (Opt-in)

#### A. Share with Group
- Explicit user toggle
- Mandatory TTL (e.g. 10m / 30m / 2h)
- Displays:
  - Current position
  - Last seen time
- No movement history by default

#### B. Share with Selected People
- Capability-based sharing
- Short-lived token (QR / code)
- Token properties:
  - TTL
  - Usage limit (single or small group)

Sharing can be stopped at any time.

---

### 5.3 Trail / Route Sharing
- Trails are recorded locally
- Optional sharing:
  - Windowed (last N minutes)
  - Waypoints instead of full polyline
- No recommended or suggested routes

### 5.4 Trail Recording (Local-Only)
- Manual mode: user taps Start / Stop.
- Auto mode: trail starts after a movement threshold, ends after no movement beyond the threshold for a fixed idle window.
- Movement thresholds adapt to a simple motion mode (walk / moto / car) inferred from speed.
- Short idle (e.g., traffic light stop) does not reset motion mode.
- During an active trail, motion mode only upgrades (walk → moto → car); it does not downgrade until the next trail.
- All trail data is stored locally (DB) unless explicitly shared.
- Map display **lazy-loads trail points** only when a trail is toggled “Show”; list uses trail metadata without loading all points (memory-friendly).
  Trail render cache is cleared on: Show OFF, Show switching to a different trail, `onPause`, and actor switch.

**Thresholds (current defaults)**
- Walk: 20m movement threshold.
- Moto: 30m movement threshold.
- Car: 50m movement threshold.
- Auto-stop idle window: 8 minutes without movement beyond the threshold.
- Short-idle window (traffic light): 30–120s keeps the previous motion mode.
- Accuracy filter: drop points if accuracy > 30m.
- Speed outlier filter: drop points if inferred speed > 120 km/h.
- Jump filter: drop points if distance jump ≥ 80m within ≤ 3s.
- Accuracy dead‑zone (distance): only count movement beyond a fraction of accuracy to avoid GPS drift.
- Walk drift filter: if speed < 1 m/s and distance exceeds (accuracy × 2) within 10s, drop as jump.
Heading inertia filter (moving policy):
  - Only applied when speed >= 1 m/s; below 1 m/s prefer compass and skip 3/4-point inference.
  - Within this >= 1 m/s regime, reverse (backtrack) is flagged when heading flips >= 120° and backtrack distance > 20% of expected.
  - Sudden standstill is flagged only when speed >= 5 m/s, if forward progress < 20% of expected.
  - At walking speeds (~1–2 m/s), sudden stops are allowed; backtrack at ~1 m/s is allowed.
- Synthetic point rule (jump handling):
  - Jump points are never stored in DB or shown on the map.
  - When a jump is detected, a synthetic point is generated for display; if jumps repeat consecutively, the previous synthetic point is committed to storage.
  - Synthetic distance is capped (e.g., max 30m per interval) to avoid unrealistic jumps.

**Why these thresholds**
- Movement thresholds (20/30/50m) reduce false starts/stops from GPS jitter, while still responding to real movement across walk/moto/car contexts.
- 8-minute idle window avoids stopping during short pauses; the 30–120s short-idle window preserves the last motion mode for traffic-light stops.
- Accuracy > 30m and large jump filters remove noisy points (especially indoors or urban canyons) without being overly aggressive outdoors.
- Accuracy dead‑zone avoids “standing still but distance increases” by discounting movement within GPS error bounds.
- Walk drift filter catches indoor jitter spikes that slip under the global jump threshold.
- Speed > 120 km/h filters extreme outliers that are unlikely in most group scenarios.
- Heading inertia filter prevents spurious heading flips from jitter while still allowing realistic stopping and low-speed maneuvers.

**Trail point filtering & synthetic pipeline**
1) **3-point inertia rule (priority)**
   - Use last 3 points.
   - If speed >= 1 m/s:
     - If heading flips >= 120° and backtrack > 20% of expected -> jump.
     - If speed >= 5 m/s and forward progress < 20% of expected -> jump.
   - If speed < 1 m/s: skip inertia/axis rules (allow abrupt stop / small backtrack).
2) **4-point axis rule (fallback)**
   - Use last 4 points.
   - Drop the point whose removal yields the smallest deviation for the remaining 3 points.
   - Axis = longest segment among the remaining 3 points.
3) **Raw point validation**
   - Drop if accuracy > 30m.
   - Drop if inferred speed > 120 km/h.
   - For distance jumps (>= 80m in <= 3s): only drop if the segment is **not aligned** with the current axis (3/4-point rule). If aligned, accept.
4) **Synthetic point**
   - Jump points are never stored or shown.
   - Generate synthetic point for display.
   - If speed < 1 m/s -> keep last position (stand still).
   - Else: project along axis (if enough points) or fallback to last valid bearing.
   - Cap synthetic distance to max 30m per interval.
   - Persist synthetic point only if jumps repeat consecutively.

5) **Persist & Export UX**
   - Persist runs periodically and on stop; a short “Saving…” status is shown while persisting.
   - Export is temporarily disabled during saving to avoid empty exports.
   - A foreground “saving” mode keeps the process alive until DB persist finishes.
   - Distance accumulation uses an accuracy dead‑zone to reduce drift inflation.
   - Auto‑trail watch shows a standby notification (“Auto‑trail ready”) when not recording.
   - GPS‑off behavior:
     - If GPS is off and no active trail: auto‑trail option is disabled and mode is forced to Manual.
     - Manual Start prompts to enable GPS; if user refuses and network is available, recording continues with Network location (marked unstable).
     - If GPS turns off during an active auto‑trail, it switches to Manual and continues until user stops.
     - Trails recorded with GPS interruptions are marked unstable with a note.

**Speed status vs Motion mode**
- **Speed status** reflects instantaneous speed (idle/walk/moto/car/high) and is used as a live flag for UI/logic triggers.
- **Motion mode** represents the transport type for the trail and selects the rule set for filtering/thresholds; within a single trail it only upgrades (no downgrade).

**Potential limitations (review later)**
- Distance can still be inflated in moto/car if GPS jitter accumulates between accepted points (dead‑zone uses the current point’s accuracy only).
- Long time gaps between points can bypass the short‑window jump filter (e.g., large distance with dt > jump window).
- If `accuracy` is missing, dead‑zone = 0, which can over‑count distance.
- Trail start can inherit a stale last location; the first segment may be oversized if the previous fix was far away.
- Backfill distance (`dist_m <= 0`) uses raw point distance without dead‑zone or jump filtering.
- High‑speed movement may still over‑count if the device reports frequent small deviations around the axis.

---

## 6. Presence Model

Presence is:
- Optional
- Time-bound
- Group-scoped

### Display Rules
- Show dot + last seen
- No speed, heading, or prediction
- Urban profile: very limited
- Outdoor profile: expanded (battery, last sync)

---

## 7. UI Guardrails & Copy

### Always-visible disclaimer
> “This map reflects group observations, not verified information.”

### Prohibited UI concepts
- “Safe zone”
- “Verified location”
- “Leader point”
- “Recommended route”

### Language Guidelines
- Use: observed, reported, noted
- Avoid: confirmed, trusted, official

---

## 8. Map UI Behaviors (Android Phase 2)

- Floating disclaimer on map open (show ~5s), then show “Tap to place a marker…” hint.
- Show current user location as a point marker.
- Show heading arrow:
  - Prefer movement-based heading (GPS bearing) when moving.
  - Fallback to device compass when stationary.
  - Hide if heading is unavailable.
- Adaptive location update interval based on speed.
- Map viewport supports **3 sizes** via a bottom handle (click or swipe): expanded, collapsed, and **max** (hides list).
- “Center on current location” button.
- Driving follow mode: when motion mode is moto/car, user marker is offset toward the bottom of the screen to show the road ahead; walking/idle stays centered unless the user pans.
  - Follow runs on a 15s tick; if user pans/zooms, follow pauses for 60s after interaction ends (not in walk/idle).
- When motion mode upgrades from walk → moto/car, follow is re-enabled immediately (even if the user previously panned).
- Location enable UI uses Settings API (fallback to system Settings).
- Trails are rendered as local polylines on the map.
- Map list shows **Shared Live Trails** (from other members) with a "Show" action.
- Trail rendering:
  - Show start/end points (🚩 start, 🏁 end).
  - Trail playback map also shows 🚩 start / 🏁 end.
  - Thicker line; segment colors by **segment speed** (dist/dt), not per-point `spd` (stop=red, walk=orange, moto=yellow, car=green, high=purple).
  - Downsample points and merge same-color segments when zoomed out to reduce draw cost.
  - Viewport clipping: only render segments near the visible map area; redraw after scroll stops (~0.5s delay).
  - Gap visualization (gray dashed line) when:
    - Time gap between adjacent points >= 15s (Normal) or >= 60s (Battery Saver), or
    - First segment: if previous point has no speed/zero and segment speed > walk max, treat as a gap, or
    - Skip count gap: if dt >= 45s and (dt / expectedInterval) >= 6, where expectedInterval is based on scan mode + segment speed, or
    - Time gap < 5 minutes, speed > 0.5 m/s, dt >= 2s, **segment speed > walk max**, and actual distance > 2x expected (speed * dt).
  - Cache full-trail segments by (trailId + zoom bucket); on scroll, only filter cached segments by viewport (no rebuild).
  - (Optional) Pre‑simplify trail points (LOD) for zoomed‑out rendering; combine with cache later.
  - (Optional) Cap max trail segments rendered per frame; if segments exceed the cap, render only the most relevant (viewport‑centered) subset and defer the rest.

### Live Trail Sharing (Group Map)
- Sharing modes: **To group** or **To selected members**.
- Events:
  - `LIVE_TRAIL_START`: trail metadata + share scope/targets.
  - `LIVE_TRAIL_POINT_BATCH`: 10s batches with `start_index` + points.
  - `LIVE_TRAIL_END`: stop/archived.
- Receiver consent:
  - Shared trails arrive as **Pending** and do not pull/store points until the recipient taps **Accept**.
  - Pending list shows **Accept** and **Delete**. Delete hides the pending trail locally (no network event).
  - Pending trails expire after **24h** if not accepted (no longer visible; notification auto‑dismisses).
  - “Stale” (no updates for 2 min) and “Reconnected” (2 min after resume) apply only after accept.
- Backfill:
  - Realtime batches stay **priority** for current position.
  - After trail ends, client **backfills missing points** from stored events/relay (no ACK/resend).
- Share stop: sender keeps local recording; receivers stop receiving new points and the trail becomes **archived** (still viewable).
- Archived shared trails can be **deleted locally** by receivers (does not affect sender).
- Re‑share creates a **new live trail** (new trail_id).
- Cleanup:
  - Live-trail events use TTL **7 days** (relay cleanup).
  - Local DB cleanup: drop expired pending trails, delete ended trails older than 7 days, and delete active trails with no updates for 60 days.
  - When pending expires or user deletes pending, remove **LIVE_TRAIL_\*** events for that trail from local events table.
  - Cleanup runs via **WorkManager** (periodic).

### Live Trail UI (Shared List)
- Pending (not accepted): show **creator + start time** only (no end/elapsed/km/pts).
- Archived status only applies when **accepted = true** and an **END event** exists.
- Live trail refresh (10s): center map to **last point**.

---

## 9. Local-only Features

Certain features are intentionally **not synced**:
- Multi-group overview (for user’s own groups)
- Radius filters
- Age-based marker filters

These are client-only conveniences.

---

## 10. Data & Sync Model

- All map events are:
  - Group-bound
  - Encrypted
  - TTL-governed
- Relay treats map data as opaque payloads
- No server-side aggregation or interpretation

---

## 11. Explicit Non-Goals

The Map for Group does **not**:
- Provide navigation authority
- Validate safety or correctness
- Prevent deception or infiltration
- Replace human judgment

---

## 11.2 Future Considerations / Limitations

- **Manipulation timing**: coordinated manipulation often needs tens of minutes to hours.  
  The current edit‑window cap (lock after `clamp(0.5 * TTL, 10m, 6h)`) helps but may need refinement by type/context.
  Consider shorter caps for sensitive types or stronger anti‑coordination signals in the future.
- **Legacy vote records**: legacy vote data may still exist for compatibility,
  but canonical map interactions use Situation confirmation/update or
  Coordination acknowledgement/note rather than a voting UI.
- **Author identity leakage**: showing marker/area creator or note authors can enable targeting or herding.  
  Consider fully anonymizing authors in UI (show only counts + note text + time).
- **Weighted consensus**: consider weighting votes by freshness and source (observed > reported) instead of raw counts to reduce manipulation.
- **No time‑decay today**: vote counts do not decay by age; older votes carry the same weight as new ones.  
  If drift becomes a problem, consider time‑decay or a rolling window.
- **Eventual consistency**: canonical action counts and activity history are
  only fully accurate after all devices sync. During partial sync, devices may
  temporarily differ.
- **Alt‑actor / sybil risk**: counts are per actor; multiple actors/devices can inflate consensus.  
  Consider rate limits, reputation, or weighted sources if manipulation becomes a problem.
- **UI refresh**: successful owner Edit/Delete refreshes the affected map
  overlay and list/card immediately. A later full sync can still change remote
  data as it arrives.

---

## 11.1 Test / Debug Tools (Non‑Production)

These are **test-only** utilities and must be **disabled in production builds**.

- **Alt-user switcher (Test only):**
  - Create and switch between multiple local actors on one device.
  - Main actor is fixed (first actor).
  - Switching only changes active actor; no DB wipe.

---

## 12. Summary

The Map for Group feature is a **shared situational note layer**, not a command system.

It prioritizes:
- Local context
- Temporal relevance
- Personal agency
- Safety through decentralization

By design, it avoids becoming an authority, a global feed, or a manipulation surface.

---

**End of document**
