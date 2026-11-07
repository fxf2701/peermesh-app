# BLE Mesh Hardening Backlog

Scope: Harden current BLE mesh implementation (no Nordic integration).

1. Serialize BLE transport state (reduce race conditions) (BL-SERIALIZE-STATE)
File: `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
Problem: shared collections are mutated from scan callbacks, GATT callbacks, and cleanup loop concurrently, risking race conditions and corrupted queues.
Goal: eliminate data races across scan callbacks, GATT callbacks, and cleanup loop.
Proposed patch: move all state mutations to a single `HandlerThread` or serialized executor. Every callback should `post { ... }` into that handler before touching shared state.
Acceptance: no direct mutation of `pendingByDevice`, `lastSeen*`, `rrDeviceQueue/Set`, `gattByDevice`, `connecting*` outside the handler; soak test does not crash or lose state under heavy scan+send.

2. Prune stale devices and queues (BL-PRUNE-STALE)
File: `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
Problem: `lastSeen*` and `pendingByDevice` never shrink; stale peers can keep memory and backpressure high indefinitely.
Goal: prevent memory growth and stuck backpressure from stale peers.
Proposed patch: add `pruneStaleDevices(nowMs)` in `cleanupStalledConnections()` that removes `lastSeen*` entries older than `DEVICE_TTL_MS` and drops empty `pendingByDevice[address]` when device is stale.
Acceptance: in a long run with devices coming and going, total `lastSeen*` size stabilizes; backpressure clears after peers leave.


3. Replace ACK/resend raw threads with bounded executor (BL-ACK-EXEC)
File: `android/app/src/main/java/app/peermesh/ble/MeshAckManager.kt`
Problem: every resend spawns a new raw thread; under load this can create many threads and GC pressure.
Goal: avoid thread bursts and reduce GC/CPU churn on retries.
Proposed patch: replace `Thread {}` with `ExecutorService` (fixed size) or `HandlerThread`. Centralize send/retry work on that executor.
Acceptance: retry storm does not create unbounded threads; ACK/resend still succeeds under load.

4. Prevent GATT connect storms under high backlog (BL-GATT-STORM)
File: `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
Problem: `connectIfNeeded` can be called frequently when many chunks are queued, causing repeated connect attempts and BLE stack churn.
Goal: avoid reconnect thrash when backlog is high.
Proposed patch: gate `connectIfNeeded` when `totalPendingBytes()` exceeds high watermark; only attempt connects from the cleanup tick (single cadence).
Acceptance: under backlog > watermark, connect attempts are rate-limited; CPU/BT stack utilization drops without stalling recovery.

5. Backpressure duration guard (BL-BACKPRESSURE-GUARD)
File: `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
Problem: backpressure can stay active for a long time with no visibility, masking throughput issues and delaying recovery.
Goal: detect pathological cases where backpressure never clears.
Proposed patch: track `backpressureSinceMs`; log warning if active > N minutes; optionally force short low-latency window after backlog drops.
Acceptance: logs show clear visibility of long backpressure periods; backpressure state flips off promptly after backlog reduces.

6. Cleanup visibility (diagnostics summary) (BL-CLEANUP-SUMMARY)
File: `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
Problem: current logs are event-level; there is no periodic health snapshot for field triage.
Goal: make field debugging faster with periodic health snapshots.
Proposed patch: emit a summary log every X seconds from cleanup loop: active GATT, connecting GATT, pending device count, total pending bytes, nearby count.
Acceptance: one-line summary present in logs at steady cadence without spamming.

7. MTU/chunk size logging per device (BL-MTU-LOG)
File: `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
Problem: it is hard to verify actual MTU/chunk size in the field; mismatched MTU assumptions can hide throughput issues.
Goal: confirm negotiated MTU and payload size per peer in the field.
Proposed patch: log MTU + derived chunk size on success and expose in diagnostics listener.
Acceptance: log shows `mtu`, `chunkBytes`, `address` for each negotiated device.

8. Round‑robin fairness guard (BL-RR-FAIR)
File: `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
Problem: RR queue can starve devices when queues are long or RSSI filters kick in, leading to uneven delivery.
Goal: prevent starvation of devices that are consistently deprioritized.
Proposed patch: track `lastServedAtMs` per device; bias `maybeConnectNext` toward the longest-idle device when queues are non-empty.
Acceptance: under many peers, each active peer receives sends within a bounded window.

9. Scan filter logging rate‑limit (BL-SCAN-RL)
File: `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
Problem: when `scanFilterEnabled=false`, scan callbacks and logs can spike, increasing CPU and log noise.
Goal: avoid log and CPU churn when `scanFilterEnabled=false`.
Proposed patch: add rate-limit for scan logs and optionally drop verbose logs after N per window.
Acceptance: log volume stays stable during high scan traffic; no performance regression.

10. Tests for dedupe and prune
Files: `android/transport/src/test/java/...`
Problem: core dedupe and pruning logic lacks regression coverage; subtle changes can break flood stability.
Goal: protect against regressions in chunk reassembly, dedupe, and stale pruning.
Proposed patch: unit tests for `Chunking.Reassembler`, `ChunkDeduper`, and a new `pruneStaleDevices` path.
Acceptance: tests cover out-of-order chunks, CRC fail, duplicate chunk drops, and stale removal logic.

10.1. Chunking/TTL tuning (BL-CHUNK-TTL-TUNE)
Files: `android/transport/src/main/java/app/peermesh/transport/ble/BleConfig.kt`, `android/transport/src/main/java/app/peermesh/transport/ble/Chunking.kt`
Problem: current payload/TTL defaults may be too strict or too loose for field conditions.
Goal: tune payload size limit and chunk cache TTL for reliability vs resource use.
Proposed patch: set payload limit to 2–4 KB and chunk TTL to 10–20s; consider adaptive TTL that increases slightly when reassembly failures spike.
Acceptance: fewer reassembly timeouts in field tests without noticeable memory growth.

11. Anonymous group tag (gtag) for routing — Priority: first (BL-GTAG)
Files: `android/data/src/main/java/app/peermesh/data/transport/EnvelopeSender.kt`, `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt`, `android/core/src/main/java/app/peermesh/core/model/EventModels.kt`, `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
Problem: gid is visible in the envelope, making it easier to target or track a group at the transport layer.
Goal: hide gid while still allowing devices to quickly recognize their own group and reduce blind forwarding.
Proposed patch:
- Add `gtag` to `EventEnvelope` (8-byte tag).
- Compute `gtag = SHA-256(gid)` and take the first 8 bytes (hex or base64url).
- Sender includes `gtag` in the envelope; gid stays encrypted inside payload.
- Receiver computes local `gtag` from its gid and compares:
  - If match: proceed to decrypt/verify and render.
  - If no match: treat as non-local; apply forward policy (strict verify or opaque with guardrails).
Acceptance: devices can match their group without exposing gid on the wire; no change to payload encryption; cross-platform hash definition documented and consistent.

12. Split header (routing-safe metadata vs encrypted payload) — Priority: first (BL-SPLIT-HDR)
Files: `android/core/src/main/java/app/peermesh/core/model/EventModels.kt`, `android/data/src/main/java/app/peermesh/data/transport/EnvelopeSender.kt`, `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt`, `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
Problem: gid is currently visible in the envelope; routing needs minimal metadata while payload should remain confidential.
Goal: separate public routing fields from encrypted meta/body to reduce metadata exposure while keeping routing efficiency.
Proposed patch:
- Public header contains: `gtag`, `mid`, `ts`, `ttl` (and optionally `prio`).
- Encrypted payload contains: `gid`, `meta`, `body`.
- Receiver compares `gtag` first; only decrypts payload when local `gtag` matches.
- Non-matching `gtag` uses forward policy (strict verify or opaque with guardrails).
Acceptance: routing works using header-only data; payload stays encrypted; gid never appears in cleartext on the wire.

13. Rate-limit opaque forwarding per source — Priority: first (BL-OPAQUE-RL)
File: `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
Problem: opaque forwarding can be abused by a single source to flood the mesh.
Goal: throttle opaque forwarding per source while keeping strict-verified traffic fast.
Proposed patch:
- Track per-source token bucket keyed by `device_id` (preferred) or BLE `address`.
- Default: `max 20 envelopes / 60s`, burst 5 (configurable).
- Apply only when `forwardOpaqueMode` is active and `accepted == false`.
- Drop or delay when bucket is empty; log counters.
Acceptance: a single noisy source cannot exceed the per-window cap; strict-verified traffic is unaffected.

14. Per-device queue cap: drop by message boundary (not per-chunk) (BL-QUEUE-BOUNDARY)
File: `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
Problem: current queue cap drops the oldest chunk, which can corrupt a multi-chunk message and waste reassembly work.
Goal: reduce wasted work and preserve message integrity under queue pressure.
Proposed patch:
- Track `chunk_id` for each queued chunk (wrap with metadata).
- When queue cap is hit, drop all chunks belonging to the oldest `chunk_id`.
- Log drop count per message for diagnostics.
Acceptance: queue pressure drops whole messages rather than fragmenting them; fewer CRC/reassembly failures under load.

15. Prefer gtag+mid dedupe, fallback to EnvelopeDeduper — Priority: first (BL-GTAG-DEDUPE)
Files: `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`, `android/transport/src/main/java/app/peermesh/transport/ble/EnvelopeDeduper.kt`
Problem: EnvelopeDeduper hashes full payload; with gtag/split header we can dedupe cheaper and more accurately via header IDs.
Goal: reduce CPU cost and improve dedupe accuracy using routing header.
Proposed patch:
- If `gtag` and `mid` present, dedupe on `(gtag, mid)` before hashing payload.
- Only use `EnvelopeDeduper` when header is missing or invalid.
- Keep current TTLs but track stats for both paths.
Acceptance: duplicate payloads with same gtag+mid are dropped without hashing; fallback works for header-missing frames.

16. BLE forward window vs relay push — Priority: first (BL-FWD-WINDOW)
Files: `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`, `android/app/src/main/java/app/peermesh/relay/RelaySync*`
Problem: forwarding over BLE indefinitely wastes power; relay push should still happen when internet becomes available.
Goal: time-box BLE forwarding in memory while keeping relay push unbounded and durable.
Proposed patch:
- Track per-event BLE forward window (e.g., 2–5 minutes) in memory only.
- Forward BLE only while window is active and `nearby > 0`.
- Relay push remains persistent (DB), no time-box; always push when online.
- Optional: extend BLE window briefly if relay push fails.
Acceptance: events still reach relay after long offline gaps; BLE forwarding stops after window to save power.

---

## Spec alignment tasks

11. Update transport implementation description (Nordic vs custom) — Resolved
File: `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
Problem: spec claims Nordic BLE Library, but code uses custom BLE transport.
Goal: ensure spec matches actual transport implementation.
Proposed patch: replace Nordic mention with current custom `BleMeshTransport` details or note that Nordic is not in use.
Acceptance: spec transport section no longer implies Nordic is the active implementation.

12. Clarify “opaque blob” handling — Resolved
File: `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
Problem: spec says transport handles opaque blobs only, but code parses envelopes for dedupe/TTL/forward.
Goal: document actual behavior and boundaries of parsing.
Proposed patch: add a note that transport parses envelope headers for dedupe/forwarding while payload remains encrypted.
Acceptance: spec matches code behavior without implying plaintext access.

13. Fix chunk size and MTU negotiation details — Resolved
File: `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
Problem: spec says default chunk size ~10 bytes and MTU negotiation not added; code uses 20 bytes and MTU negotiation.
Goal: align chunking details with current implementation.
Proposed patch: update chunk size defaults and note MTU negotiation + `maxChunkBytesCap`.
Acceptance: chunking section reflects actual defaults and logic.

14. Replace “stateless flooding” with target-based scheduling — Resolved
File: `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
Problem: spec claims stateless flooding, but code uses target selection, backpressure, and RR scheduling.
Goal: document real send strategy.
Proposed patch: describe target selection and throttling; reserve “flooding” for higher-level gossip semantics.
Acceptance: transport rules match code behavior.

15. Align chunk dedupe description — Resolved
File: `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
Problem: spec says dedupe by `(chunk_id, index)`; code dedupes by chunk bytes.
Goal: document real dedupe logic.
Proposed patch: update dedupe rule to “chunk payload hash/bytes within TTL.”
Acceptance: dedupe description consistent with code.

Note for follow-up: payload-byte dedupe can false-positive if two different chunks share identical payload bytes within the TTL window.

16. Clarify “no auto-discovery peers” — Resolved
File: `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
Problem: spec says no auto-discovery peers in Phase 2, but BLE scan/advertise is auto-discovery at the device level.
Goal: clarify that “auto-discovery” refers to group/identity discovery, not BLE device discovery.
Proposed patch: reword non-goal or add clarification note.
Acceptance: no contradiction between spec and BLE mesh behavior.

Note: Auto-relay (Option B) was moved to Phase 2.1 and its code has been removed from Phase 2.
