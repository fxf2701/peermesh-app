# Map UI Crash Risk Backlog

Context: app crashes after several minutes while Map UI is open during motorbike test. No ActivityLog entry captured. Below are likely crash sources to investigate and mitigate.

## High-risk (likely crash/ANR/OOM)
1) Overlay churn + bitmap creation
- `renderMapOverlays()` clears overlays and re-adds markers/polygons/labels on every refresh.
- Label/emoji markers are backed by new `BitmapDrawable` instances.
- Frequent refresh during movement + follow/heading updates can lead to GC thrash or OOM.

2) Trail rendering on UI thread
- `renderTrailOverlays()` rebuilds segments and adds polylines on UI thread.
- `rebuildTrailRenderCache()` may run frequently on zoom/pan changes.
- Long trails amplify CPU and memory pressure -> jank/ANR or crash.

3) Unbounded caches
- `labelIconCache`, `emojiIconCache`, `trailRenderCache` do not have eviction.
- Unique labels + zoom buckets accumulate in RAM.

4) Handler/runnable after Activity pause/destroy
- `scheduleTrailRender()` uses `Handler.postDelayed()`.
- If Activity is paused/destroyed, delayed runnables may still render and touch null/invalid view state.

5) MapEventsOverlay / Projection instability
- `mapView.projection` can be null or unstable while layout/zoom is changing.
- Touch handling uses projection frequently; race can cause NPE or internal osmdroid exceptions.

6) Overlay reference reuse
- `mapView.overlays.clear()` then re-add `mapEventsOverlay`, draft overlays.
- If `mapEventsOverlay` references old view or is not in overlays list, events can go to null state.

## Medium-risk (logic/state mismatch)
7) Actor switch state mismatch
- Async background threads (loadMapItems, trail render) can use stale actor ID.
- UI state (selectedTrailId/hidden sets) may carry across actor switch -> NPEs.
- Queries not filtered by actor could return empty/mismatched data.

8) Background threads + UI updates
- Multiple `Thread { ... runOnUiThread { ... } }` blocks can race if Activity not in foreground.
- Risk of `IllegalStateException` or view access after destruction.

9) Frequent invalidate calls
- `mapView.invalidate()` invoked from multiple paths (drag, heading, follow, overlay rebuild).
- High frequency can starve main thread rendering.

## Suggestions for investigation
- Capture full crash stacktrace (logcat with `AndroidRuntime` + `FATAL EXCEPTION`).
- Add sampling logs around `renderMapOverlays`, `renderTrailOverlays`, and follow tick to measure frequency.
- Add guard: skip render if `isFinishing || isDestroyed` or map not ready.
- Add LRU cache for label/emoji drawables and trail render buckets.
- Move trail cache rebuild to background + post result to UI.

## Suggested mitigations (later tasks)
- Throttle overlay refresh (coalesce updates within 300-500ms).
- Incremental overlay diff instead of full clear/add.
- Incremental list refresh: track `last_refresh_t`, query only items with `updated_t > last_refresh_t` or `expires_at <= now`, update adapter diff instead of full reload (avoid 60s full list refresh on large datasets).
- Cap number of trail segments rendered per frame.
- Evict caches on actor switch or on map pause.
