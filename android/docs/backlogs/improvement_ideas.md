# Improvement Ideas Backlog

Purpose: capture new ideas for performance/UX/quality improvements. These are not tasks yet.
We will review, discuss, and move selected items into the task list.

## Ideas (untriaged)
- Trail speed max spikes (GPS outlier)
  - Symptom: derived max speed from trail points can spike above real driving speed.
  - Approach A (outlier filter by acc/dt): ignore segments when `dt < 2s`, or accuracy `acc > X`, or distance jump exceeds `(acc * k)`; compute max from remaining segments.
  - Approach B (percentile): report p95 (or p90) segment speed instead of absolute max; optionally show max separately in debug only.
  - Approach C (mode clamp): cap segment speed by motion‑mode bounds (walk/moto/car); use as a failsafe for extreme spikes.
- Trail distance under‑report (e.g., 71 km actual vs ~35 km shown)
  - Symptom: long trails sometimes show much shorter distance than expected.
  - Notes: current rules skip long‑gap segments and still apply dead‑zone on short dt; this does **not** address under‑report on long rides.
  - Possible causes: accuracy dead‑zone too aggressive; points filtered out by jump/accuracy rules; gaps with synthetic points not contributing to distance.
  - Options: log accept/reject counts + effectiveDist; compare DB points vs UI distance; consider relaxing dead‑zone for high speed segments or using raw sum for distance metrics.
- Incremental list refresh for map items (track `last_refresh_t`, only load updates/expirations).
- Batch-load contribution previews for visible items (single query + group in memory).
- LRU caches for label/emoji bitmap drawables and trail render buckets.
- Off-UI-thread trail cache rebuild with UI swap once ready.
- Throttle overlay rebuilds (coalesce within 300–500ms).
- Cap number of trail segments rendered per frame.
- Diff-based overlay updates instead of full clear/re-add.
