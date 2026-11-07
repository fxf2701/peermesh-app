# Problem: Nearby Room BLE Device Quiet And Scheduler Bias

Date:
- 2026-06-23

Status:
- Open
- Active investigation

Summary:
- `Nearby room` BLE behavior is currently less reliable than `private group` in recent real-device rounds.
- The dominant pattern is not `write_failed_status_133` or `broadcast_rejected`.
- The dominant pattern is repeated `GATT connect skipped ... reason=device_quiet`, sometimes followed by `cooldown_device`, `interrupted_transport_stop`, or control-wire timeout/failure.
- Current goal is to improve `nearby room` reliability without regressing the working baseline for `private group`.

Scope:
- In scope:
  - `nearby room` discovery / join / control-wire BLE behavior
  - scheduler / lane-selection / connect gating behavior that affects `nearby room`
  - `device_quiet` and `cooldown_device` interactions on nearby-only traffic
- Out of scope:
  - re-opening broad `private group` traffic-governance refactor work
  - global BLE policy changes without a nearby-only guard
  - treating `master` as fully healthy across all BLE workloads

Observed comparison:
- `Private group` on `master` currently looks relatively workable, even though it still has older failure modes such as `queued_no_write_attempt_timeout`.
- `Nearby room` on `master` repeatedly shows a different dominant failure mode: connect starvation caused by `device_quiet`.
- This suggests a workload bias in current BLE behavior:
  - `private group` is currently favored or better tolerated by the existing scheduler/policy mix
  - `nearby room` is more easily starved by quiet/cooldown/connect gating

Recent evidence:
- `master` private-group report `logs/analysis/dtn_round_20260622_230043_918394640_29391.md`
  - No visible `write_failed_status_133`
  - No visible `broadcast_rejected`
  - Still many `queued_no_write_attempt_timeout` and `zero_chunk_failures`
- `master` nearby-room round `logs/device-A_activity_log_live_20260622_230738-04d45c97.txt`
  - `reason=device_quiet=109`
  - `reason=cooldown_device=5`
  - `reason=device_id_rotated=15`
  - `interrupted_transport_stop=2`
- `master` nearby-room round `logs/device-B_activity_log_live_20260622_230740-755dad0f.txt`
  - `reason=device_quiet=53`
  - `reason=cooldown_device=13`
  - `reason=device_id_rotated=4`
  - `interrupted_transport_stop=14`
- `master` nearby-room round `logs/device-C_activity_log_live_20260622_230733-ef6977f5.txt`
  - `reason=device_quiet=136`
  - `reason=cooldown_device=1`
  - `interrupted_transport_stop=4`
- Repeated `master` nearby-room round `logs/device-A_activity_log_live_20260622_232903-07082d05.txt`
  - `reason=device_quiet=148`
  - `queued_no_write_attempt_timeout=3`
- Repeated `master` nearby-room round `logs/device-B_activity_log_live_20260622_232905-94e8b949.txt`
  - `reason=device_quiet=111`
  - `reason=device_id_rotated=16`
- Repeated `master` nearby-room round `logs/device-C_activity_log_live_20260622_232858-cb3ac7c9.txt`
  - `reason=device_quiet=195`
  - `reason=cooldown_device=9`
  - `queued_no_write_attempt_timeout=9`
  - `interrupted_transport_stop=4`
  - `DTN control wire failed=2`

Current understanding:
- The nearby-room issue looks stable across rounds, not like one noisy outlier.
- The main bottleneck is early connect suppression, not late-stage app-batch failure.
- `device_quiet` appears too aggressive for nearby-room churn patterns.
- `cooldown_device` sometimes compounds the quiet window after failed or interrupted nearby control attempts.
- Nearby control traffic may need a different scheduler/cooldown profile than private-group traffic.

Why this should be handled separately:
- The previous BLE traffic-governance refactor introduced new regressions on other workloads.
- `Nearby room` must now be treated as its own workload with its own acceptance criteria.
- Fixing nearby reliability through global BLE changes is risky because `private group` is currently the more stable baseline and should be preserved.

Working hypothesis:
- Current BLE behavior on `master` has a practical workload bias:
  - `private group` can still make forward progress under the present policy mix
  - `nearby room` gets over-penalized by quiet / cooldown / connect gating
- This may not be an explicit product policy, but it behaves like one in live rounds.

Guardrails for future fixes:
- Nearby fixes must be nearby-only whenever possible.
- Do not change shared BLE cooldown/quiet defaults first.
- Prefer lane- or traffic-class-specific policy hooks over global behavior changes.
- Every nearby fix should be regression-checked against `private group`.

Suggested next steps:
- Identify the exact nearby-room control / discovery / join lane(s) that should bypass or reduce `device_quiet`.
- Add observability that explicitly tags scheduler decisions by workload:
  - `private_group`
  - `nearby_room`
  - traffic/control lane
- Prototype the smallest possible nearby-only policy change:
  - reduce `device_quiet` for nearby control lane
  - or avoid chaining `cooldown_device` after interrupted nearby control-wire attempts
- Validate each patch with paired rounds:
  - one `nearby room` round
  - one `private group` round

Implemented patch 1:
- Added an explicit transport hint for `NEARBY_ROOM_CONTROL`.
- Routed nearby control envelopes through the hint path:
  - key request
  - key bootstrap
  - metadata request / response
  - join ready
  - join ready relay
  - message offer / fetch
  - room closed
- Kept nearby message content paths unchanged:
  - `NEARBY_ROOM_MESSAGE_V2`
  - `NEARBY_ROOM_MESSAGE_DATA_V1`
- Current nearby-only transport behavior change:
  - allow nearby control work to bypass `device_quiet`
  - allow nearby control work to bypass `cooldown_device`
  - prioritize nearby control packets ahead of ordinary mesh batch packets in the write queue

Why patch 1 is intentionally narrow:
- It does not change global BLE defaults for all traffic.
- It does not alter `private group` batch enqueue or batch priority rules directly.
- It targets the nearby discovery / join / metadata control lane first, because live rounds show starvation before normal message delivery becomes the dominant problem.

Acceptance goal:
- `Nearby room` should materially reduce repeated `reason=device_quiet` starvation and make more consistent control-wire progress.
- `Private group` should not lose its current working stability while nearby-specific fixes are introduced.

Post-patch-1 round:
- Test round:
  - `logs/device-A_activity_log_live_20260623_112019-403624cb.txt`
  - `logs/device-B_activity_log_live_20260623_112018-ba66f82e.txt`
  - `logs/device-C_activity_log_live_20260623_112011-02376e29.txt`
- User-visible result:
  - Device `B` could see and partially join/work through room flow for device `A`.
  - Devices `A` and `C` still mostly surfaced only their own room in UI behavior, despite receiving remote nearby discovery metadata in logs.

What improved after patch 1:
- The round no longer looks dominated by `device_quiet` alone.
- Remote nearby room metadata advertisement was visible across devices:
  - `B` repeatedly decoded `A` room metadata `room_id=c8dea54cd902`
  - `C` repeatedly decoded `A` room metadata `room_id=c8dea54cd902`
  - `A` decoded remote room metadata such as `room_id=9c6efb6b2e60`
- This indicates nearby discovery advertisement is making forward progress and patch 1 did help the control lane get farther than the earlier `device_quiet`-starvation baseline.

What still failed in this round:
- Nearby control flow is still unreliable after discovery.
- `C` sent metadata fetches to both remote rooms, but both timed out:
  - fetch to `room_id=c8dea54cd902` target `10308F486AD9F054`
  - fetch to `room_id=9c6efb6b2e60` target `3D3FA1C865F3B265`
- `A` also showed `DTN control wire failed ... reason=interrupted_transport_stop`.
- `B` appears to have progressed the farthest on `A`'s room:
  - `B` received `A` key request
  - later `A` received `key bootstrap` from `B`
- This suggests the issue is no longer primarily “room not discovered”, but “control exchange does not complete reliably once discovery has succeeded”.

Observed shift in dominant failure mode:
- `device_quiet` dropped to a secondary symptom in this round:
  - `A`: `reason=device_quiet=30`
  - `B`: `reason=device_quiet=26`
  - `C`: `reason=device_quiet=37`
- `cooldown_addr` became more prominent:
  - `A`: `reason=cooldown_addr=46`
  - `B`: `reason=cooldown_addr=101`
  - `C`: `reason=cooldown_addr=143`
- `status_133` / `callback_status_133` was also severe, especially on `C`:
  - `A`: `callback_status_133=43`
  - `B`: `callback_status_133=41`
  - `C`: `callback_status_133=192`
- Interpretation:
  - patch 1 reduced the earlier `device_quiet` bottleneck
  - the nearby-only control lane now more often runs into connect/disconnect failure, then gets trapped behind address cooldown and follow-on timeout behavior

Additional blocker found outside the BLE gate:
- There are repeated nearby app-layer fetch skips with `reason=policy_gate`.
- This appears in both:
  - `A` repeatedly skipping metadata fetch for `room_id=c8dea54cd902`
  - `C` repeatedly skipping metadata fetch for local `room_id=8db1b31f9b35`
- Interpretation:
  - there are now at least two active blockers
  - transport-layer instability: `status_133`, `cooldown_addr`, `interrupted_transport_stop`
  - nearby app-layer gating: `metadata fetch skipped ... reason=policy_gate`

Updated understanding:
- Patch 1 was directionally correct and should be kept.
- The next bottleneck is no longer the original `device_quiet` / `cooldown_device` pair.
- The current nearby-only bottleneck is more likely:
  - `cooldown_addr` after failed/interrupted nearby control attempts
  - connect failure churn leading to `status_133`
  - nearby metadata fetch scheduler `policy_gate`
- This remains compatible with the guardrail that nearby should be handled separately from private group.

Suggested next step:
- Implement a nearby-only patch 2 focused on the control lane:
  - add observability when `connectIfNeeded` skips a device that currently has `NEARBY_ROOM_CONTROL` work
  - explicitly log/control-lane-tag these skip reasons:
    - `cooldown_addr`
    - `connect_attempt_cooldown`
    - `penalty_active_multi_nearby`
    - `max_gatt_reached`
  - prototype the smallest nearby-only bypass or reduction for `cooldown_addr` on `NEARBY_ROOM_CONTROL`
- In parallel, inspect the nearby metadata fetch `policy_gate` path:
  - identify why discovery-triggered fetches are repeatedly skipped after remote room metadata is already visible
  - only relax that gate if the change can stay nearby-only

Patch-2 guardrail:
- Do not broaden the bypass to all BLE traffic.
- Do not change shared global address cooldown defaults first.
- Prefer:
  - nearby-control-only cooldown hook
  - nearby-control-only logging and scheduler tagging
  - nearby metadata fetch policy adjustment scoped to nearby room flows

Patch-2 validation plan:
- Run one paired `nearby room` round and one `private group` round.
- Compare before/after for:
  - `reason=cooldown_addr`
  - `reason=device_quiet`
  - `reason=connect_attempt_cooldown`
  - `reason=penalty_active_multi_nearby`
  - `callback_status_133`
  - `interrupted_transport_stop`
  - metadata fetch send vs timeout counts
- Success criteria:
  - more nearby control exchanges complete after discovery
  - fewer nearby metadata fetch timeouts
  - no visible private-group regression

Additional trace on `policy_gate` spam:
- The repeated `Nearby room metadata fetch skipped ... reason=policy_gate` logs were traced to app-layer enqueue patterns, not just BLE failure.
- The skip is emitted in `NearbyRoomMetadataFetchCoordinator.startFetch(...)` when `NearbyRoomRepository.shouldFetchMetadata(...)` returns `false`.
- Current repository gate behavior:
  - local-owner rooms do not fetch
  - inactive / expired rooms do not fetch
  - rooms without `metadata_source_device_id` do not fetch
  - `METADATA_RESOLVED` does not fetch again
  - `METADATA_PENDING` / `METADATA_FAILED` can only retry after `METADATA_FETCH_RETRY_INTERVAL_MS = 15_000`

Important clarification from the test run:
- Manual discovery-screen refresh was used during testing, but it was used within the existing product rule:
  - `Nearby rooms` manual refresh has a 30-second cooldown
  - the tester followed that cooldown
- Conclusion:
  - the 30-second manual refresh behavior is not considered abusive or incorrect
  - it should not be treated as the main bug by itself

Discovery-screen enqueue path:
- `NearbyRoomsActivity` manual/periodic discovery refresh eventually calls:
  - `NearbyRoomBleCoordinator.flushBufferedDiscovery(...)`
  - then `NearbyRoomBleCoordinator.enqueueMetadataRefresh(activeRooms.map { it.room_id })`
- Because manual refresh is already constrained by a 30-second cooldown, this path is currently acceptable as-is unless later evidence shows it still causes regressions by itself.

Room-screen enqueue path:
- `NearbyRoomActivity.loadRoom(...)` calls `NearbyRoomBleCoordinator.enqueueMetadataRefresh(listOf(roomId))`.
- `loadRoom(...)` is driven by a 1-second timer while the room screen is open.
- This means the same room can be re-enqueued every second even when:
  - a fetch was just attempted
  - metadata is still pending
  - the repository retry gate will predictably reject the next attempt
- This is the most likely source of the dense `policy_gate` log spam seen during room-level testing.

Post-snapshot discovery behavior in current code:
- After advertisement snapshot upsert, Discovery still continues trying to fetch full metadata until the room becomes `METADATA_RESOLVED`, expires, becomes inactive, or loses a valid `metadata_source_device_id`.
- Discovery refresh sources:
  - auto refresh every `60s`
  - manual refresh at most every `30s`
- Each Discovery refresh calls `NearbyRoomBleCoordinator.enqueueMetadataRefresh(activeRooms.map { it.room_id })`.
- Full-metadata fetch policy:
  - retry gate interval: `15s`
  - response timeout per fetch: `8s`
  - max concurrent fetches: `2`
- Practical effect:
  - if a room stays unresolved, it will usually be retried on the next Discovery refresh cadence, roughly every `30-60s`
  - this is not extremely aggressive in absolute frequency
  - but it is still a blunt retry loop because it does not require meaningful new evidence before retrying

Latest round after snapshot-visible discovery patch:
- Test round:
  - `logs/device-A_activity_log_live_20260623_185329-1e401440.txt`
  - `logs/device-B_activity_log_live_20260623_185328-b63c9ec5.txt`
- User-visible result:
  - `B` could see `A`'s room on the Discovery UI.
  - Join still did not complete.
- What improved:
  - Discovery/UI behavior is better than before.
  - `B` repeatedly reached `Nearby rooms refresh db done ... rooms=1 active_rooms=1`, so advertisement snapshot visibility is now working as intended.
  - This confirms the recent patch helped the Discovery list surface rooms earlier instead of waiting for authoritative metadata.
- What still failed:
  - `B` repeatedly attempted full metadata fetch for `A`'s room and timed out.
  - `B` also repeatedly sent nearby key requests after seeing the room.
  - `A` received many of those key requests, but bootstrap still did not complete reliably.
- Dominant transport signals in this round:
  - `A`: `state_busy_connecting_or_connected=326`
  - `A`: `GATT address cooldown set source_reason=rotation_previous_addr=150`
  - `B`: repeated `metadata fetch timeout`
  - `B`: many repeated nearby key requests
- Interpretation:
  - the recent UI/discovery patch did help the front half of the flow
  - the join/control path is still failing after discovery succeeds
  - the strongest signal now is address-rotation/connect contention, not the old `device_quiet` bottleneck

Assessment of current full-metadata strategy:
- Current cadence is not obviously spammy by raw interval:
  - `60s` auto Discovery refresh
  - `30s` manual Discovery cooldown
  - `15s` metadata retry gate
  - `8s` response timeout
- However, the strategy is still too blunt under BLE failure:
  - Discovery refresh re-enqueues all active unresolved rooms
  - retries do not require a stronger/newer source signal before trying again
  - repeated failures can continue at a steady cadence even when transport conditions have not improved
- Conclusion:
  - current behavior is not "too dense" in absolute terms
  - but it is still inefficient and can create repeated nearby-only control work with low expected value

Proposed next implementation step:
- Keep the change nearby-only.
- Add a stronger retry policy for unresolved Discovery rooms:
  - retry full metadata only when there is meaningful new evidence, such as a fresher/better advertiser sighting or a changed chosen source device
  - otherwise suppress repeated full-metadata retries for already-failed unresolved rooms
- Add backoff for repeated metadata timeouts:
  - after consecutive timeouts, expand the retry interval beyond the current flat `15s` gate
  - keep this scoped to nearby-room metadata fetch only
- Preserve the current resolved-room optimization:
  - once a room is `METADATA_RESOLVED`, do not refresh snapshot/full metadata again unless there is a real state-change signal
- Why this is the right next step:
  - it reduces duplicated nearby control work without touching private-group policy
  - it matches the latest evidence that discovery visibility improved, but post-discovery control churn still wastes BLE opportunities

Refined next-step guidance:
- Do not change the 30-second discovery-screen manual refresh rule first.
- Treat the discovery-screen cooldown as valid product behavior.
- Prioritize the room-screen path first:
  - reduce or remove automatic metadata-fetch enqueue from the 1-second `NearbyRoomActivity` timer path
  - or add a room-screen-specific throttle before enqueue
- Only revisit discovery-screen enqueue scope later if room-screen throttling is not enough.

Post-room-screen-throttle round:
- Test round:
  - `logs/device-A_activity_log_live_20260623_122703-6603df6e.txt`
  - `logs/device-B_activity_log_live_20260623_122702-89a9adcb.txt`
  - `logs/device-C_activity_log_live_20260623_122658-ec3708bb.txt`
- User-visible result:
  - `A` could see `B`'s room in the UI.
  - `A` still could not complete join.
  - This round was still asymmetric:
    - only `A` showed clear UI evidence of a remote room
    - there was no comparable evidence that `B` or `C` could stably surface and join remote rooms in the UI

What improved in this round:
- The room-screen throttle patch reduced app-layer metadata-fetch spam.
- New `Nearby room metadata refresh skipped ... reason=room_screen_throttle` logs appeared on room-screen flows, confirming the new guard was active.
- `policy_gate` noise dropped substantially compared with the previous round:
  - `A`: `reason=policy_gate=8`
  - `B`: `reason=policy_gate=4`
  - `C`: `reason=policy_gate=1`
- Interpretation:
  - the room-screen 1-second enqueue path was a real source of log noise and retry churn
  - that patch should be kept

What did not improve enough:
- Nearby control exchange still failed after discovery.
- `A` repeatedly sent metadata fetches for remote room `room_id=3291a1f03ab6`, but the fetches timed out repeatedly.
- `B` and `C` also continued to show repeated metadata fetch timeout patterns for nearby rooms.
- `A` received many repeated key requests for its own room `room_id=f23ed979117c`.
- `A` did manage at least one `key bootstrap send ... sent=true`, but the requester kept retrying, and later bootstrap sends also failed.
- Interpretation:
  - nearby discovery is not the primary blocker
  - UI-level visibility improved only partially and inconsistently
  - nearby control packets can sometimes be queued and sent
  - but the control exchange still does not complete reliably enough to produce stable join progress

Current dominant failure mode in this round:
- `cooldown_addr` remains a strong blocker:
  - `A`: `reason=cooldown_addr=162`
  - `B`: `reason=cooldown_addr=34`
  - `C`: `reason=cooldown_addr=97`
- `status_133` / `callback_status_133` remains severe:
  - `A`: `callback_status_133=60`
  - `B`: `callback_status_133=21`
  - `C`: `callback_status_133=174`
- `device_quiet` is still present, but it is no longer the dominant symptom:
  - `A`: `reason=device_quiet=33`
  - `B`: `reason=device_quiet=30`
  - `C`: `reason=device_quiet=17`
- `C` still showed `DTN control wire failed ... reason=interrupted_transport_stop`.

Updated interpretation after this round:
- The room-screen throttle patch successfully reduced one app-layer source of repeated metadata fetch enqueue.
- The remaining bottleneck is now even more clearly transport-layer:
  - `status_133`
  - `cooldown_addr`
  - interrupted control-wire progress
- The system is now closer to the core nearby BLE problem:
  - rooms are visible
  - fetch/key-control work is attempted
  - joins still fail because control traffic is not surviving the BLE connection lifecycle reliably enough

Revised next-step recommendation:
- Keep the room-screen metadata refresh throttle patch.
- Patch the nearby control lane at the BLE transport layer next:
  - add nearby-control-aware observability for `connectIfNeeded(...)` skip reasons
  - especially tag:
    - `cooldown_addr`
    - `connect_attempt_cooldown`
    - `state_busy_connecting_or_connected`
    - `max_gatt_reached`
  - prototype the smallest nearby-only change for `cooldown_addr` while `NEARBY_ROOM_CONTROL` work is pending
- Do not broaden the change to all BLE traffic.
- Continue to regression-check against private group after each nearby-only transport patch.

Root-cause ordering confirmed from attempt traces:
- A deeper trace across the three logs shows `cooldown_addr` is usually not the first failure in a nearby attempt.
- The common sequence is:
  - nearby discovery / key / metadata work gets enqueued or sent
  - a BLE connect lifecycle failure happens first, usually `status_133` / `callback_status_133`
  - transport cleanup runs
  - the same address then gets blocked by `cooldown_addr`
  - the nearby attempt later ends as metadata fetch timeout, repeated key-request churn, or control-wire interruption

Per-attempt examples from this round:
- `B` metadata fetch for `A` room `room_id=f23ed979117c`:
  - fetch send succeeded
  - then transport hit `status_133`
  - then cleanup ran
  - then the nearby fetch attempt later timed out
- `B` join progression on `A` room:
  - `A` did receive repeated key requests from `B`
  - `A` managed one bootstrap send
  - but subsequent nearby control progress was repeatedly suppressed by `cooldown_addr`
  - that suppression followed earlier `status_133` cleanup, not the other way around
- `C` metadata fetch for `A` room `room_id=c8dea54cd902`:
  - fetch send succeeded
  - then `status_133`
  - then cleanup
  - then timeout
- `C` later fetches and control-wire attempts show the same pattern:
  - `status_133`
  - cleanup
  - `cooldown_addr`
  - final timeout or `interrupted_transport_stop`

Updated interpretation:
- `cooldown_addr` is a strong secondary blocker and retry amplifier.
- The earlier event that most often starts the failure chain is `status_133`.
- Nearby-specific fixes should therefore prioritize:
  - understanding which nearby control attempts are producing the first `status_133`
  - then deciding whether nearby-only policy should soften the follow-on cooldown behavior
- This means the next patch should not be framed as “fix timeout” first.
- It should be framed as:
  - reduce or isolate the first nearby `status_133` failure path
  - and only then limit how hard `cooldown_addr` penalizes nearby control retries

Post-metadata-backoff round:
- Test round:
  - `logs/device-A_activity_log_live_20260623_194356-0d2528ce.txt`
  - `logs/device-B_activity_log_live_20260623_194357-ef309107.txt`
- User-visible result:
  - `B` manually tried to join `A`'s room and initially failed.
  - `B` later remained on the Discovery side while `A` reported that someone nearby had joined.
  - The user then manually opened `A`'s room again from `B`.

What improved in this round:
- The metadata retry/backoff patch is active and working.
- `B` now shows new nearby-only skip reasons instead of repeatedly hammering the same full-metadata fetch cadence:
  - `reason=retry_backoff`
  - `reason=no_new_signal`
- Interpretation:
  - the new patch reduced duplicated full-metadata retry work
  - it did not solve the late join-state/UI completion problem

What happened in the join flow:
- `B` first did a normal discovery metadata fetch attempt for `A`'s room and hit timeout.
- `B` kept retrying nearby key requests for the same room while continuing Discovery refresh behavior.
- Later, `A` did successfully send room bootstrap to `B`:
  - `A` logged `Nearby room key bootstrap send ... sent=true`
  - `B` logged `Nearby room key bootstrap recv`
  - `B` also logged `Nearby room key bootstrap stored`
- This means `B` did eventually receive the room key for `A`'s room.

Critical app-layer gap exposed by this round:
- Immediately after `B` received and stored the bootstrap key, the expected forced follow-up refresh did not actually get queued:
  - `Nearby room metadata fetch queued ... reason=post_join ... force=true queued=false`
- At nearly the same time, an older discovery fetch for the same room was still in flight and then timed out shortly after.
- Interpretation:
  - `B` can receive the room key late, after the original manual join flow has already failed or the UI has fallen back
  - but the app currently does not reliably finalize the room/join state when that late key arrives
  - the room-key success is therefore partially stranded in app state

Updated interpretation from this round:
- This was not a pure false-positive or ghost join.
- `B` really did receive `A`'s bootstrap key.
- The first failure after that success was app-layer orchestration:
  - the post-join forced metadata refresh was dropped because it could not enter the queue while an older discovery fetch was still in flight
- Transport churn still remains significant:
  - `B`: heavy `state_busy_connecting_or_connected`
  - both `A` and `B`: continued `rotation_previous_addr` / `cooldown_addr` patterns
- But the new evidence shows that nearby reliability is now also blocked by a late-success handling problem in the product flow, not only by BLE transport.

Product-flow hypothesis consistent with the logs:
- A user can try to join room `R`, fail the initial join path, and get pushed or fall back to Discovery.
- In the background, the app can still:
  - keep resolving Discovery state
  - keep retrying nearby key requests for `R`
- If one of those later key retries succeeds, the room key for `R` can arrive while the user is no longer on the room screen.
- That explains the observed asymmetry:
  - `A` can think someone joined nearby
  - `B` still appears to be outside the room from the UI point of view

Proposed next product-flow implementation:
- Add a nearby-only `pending join intent` concept for manual room join attempts.
- Behavior rule:
  - if the user manually requested to join room `R`, and the app later receives the room key for `R` while the user is currently on Discovery, the app should auto jump-in to room `R`
  - if the user is currently inside chat room `X`, and a delayed key arrives for room `Y`, the app should stay in room `X`
- Additional safety rule:
  - only auto jump-in if the late key matches the latest still-valid user join intent
  - if the user has since tried to join a different room, the older intent must be treated as stale

Why this should be the next patch:
- It is nearby-only and does not affect private-group flow.

Implemented joiner-side wait after accepted key request:
- The latest join-flow analysis shows `B` can often enqueue `KEY_REQUEST_V1` successfully, and `A` can sometimes build `KEY_BOOTSTRAP_V1`, but the reply still dies in transport before `B` receives it.
- Under the old joiner behavior, `B` kept sending new `KEY_REQUEST_V1` attempts almost immediately, which created two bad effects:
  - more nearby-only control churn while BLE conditions had not materially improved
  - owner-side `KEY_BOOTSTRAP_V1` reply cooldown got consumed even when the earlier bootstrap reply had not actually completed the round trip
- New nearby-only joiner behavior:
  - after `sendKeyRequest(roomId)` returns `sent=true`, the joiner enters a per-room wait state for `15s`
  - while the wait is active, additional `KEY_REQUEST_V1` sends for the same room are skipped
  - if `KEY_BOOTSTRAP_V1` is later stored for that room, the wait state is cleared immediately
  - if no bootstrap arrives within `15s`, the wait expires and the next key-request attempt may proceed
- This does not claim transport delivery succeeded end-to-end.
- It only treats `sent=true` as enough evidence to stop nearby-only re-request spam for one reply window.
- Rationale:
  - the `15s` wait aligns with the current owner-side key-bootstrap reply cooldown
  - it is nearby-only
  - it reduces duplicate request pressure without touching private-group behavior or global BLE scheduler defaults
- New activity-log labels added for verification:
  - `Nearby room key request wait start`
  - `Nearby room key request wait skip`
  - `Nearby room key request wait timeout`
  - `Nearby room key request wait cleared`

Post-joiner-wait round:
- Test round:
  - `logs/device-A_activity_log_live_20260624_181113-6e76b125.txt`
  - `logs/device-B_activity_log_live_20260624_181114-799b49a4.txt`
  - `logs/device-C_activity_log_live_20260624_181111-90f11755.txt`
- User-visible result:
  - joiner still did not enter the room

What improved in this round:
- The new joiner-side wait behavior is active and behaving as intended.
- On `B`, the previous 1-second request spam pattern is gone.
- Instead, the trace now shows:
  - `Nearby room key request wait start`
  - repeated `Nearby room key request wait skip`
  - then `Nearby room key request wait timeout`
  - only then a new `KEY_REQUEST_V1` attempt
- Concrete `B` counts from this round:
  - `wait_start=15`
  - `wait_skip=203`
  - `wait_timeout=14`
  - `key_request_send=15`
- Interpretation:
  - the nearby-only throttling patch is doing real work
  - joiner-side duplicated request churn is materially lower than before

What did not improve:
- `B` still never received the room bootstrap:
  - no `Nearby room key bootstrap recv`
  - no `Nearby room key bootstrap stored`
  - no `Nearby room key request wait cleared`
- This means the joiner wait window reduced pressure but did not change the final join outcome.

What the owner-side logs show:
- `A` still repeatedly received `KEY_REQUEST_V1` for `room_id=7fbefeab71b7`.
- `A` also repeatedly attempted to send `KEY_BOOTSTRAP_V1`, with multiple `Nearby room key bootstrap send ... sent=true` events.
- But those reply attempts were still followed by transport-level suppression:
  - `GATT connect skipped ... reason=cooldown_addr`
  - `GATT connect skipped ... reason=state_busy_connecting_or_connected`
  - repeated `status_133` / `callback_status_133`
- Interpretation:
  - app-layer reply creation is no longer the main blocker
  - owner-side bootstrap reply delivery is still dying in the BLE transport scheduler / connection lifecycle before the joiner receives it

Additional observation:
- `C` also showed the new wait-state behavior and still failed to receive bootstrap.
- This means the new patch is working consistently across joiners, but the shared failure point remains the reply path, not the request cadence.

Updated conclusion after this round:
- Keep the joiner-side wait patch.
- It is nearby-only and reduces waste.
- But it is not sufficient to solve join reliability.
- The dominant remaining blocker is still owner-side `KEY_BOOTSTRAP_V1` delivery under:
  - `cooldown_addr`
  - `state_busy_connecting_or_connected`
  - `status_133`

Refined next-step recommendation:
- Focus the next patch on owner-side bootstrap reply delivery rather than further joiner throttling.
- Prefer the smallest nearby-only change possible, for example:
  - stronger nearby-only reply-path priority for `KEY_BOOTSTRAP_V1`
  - or a nearby-only relaxation/bypass for owner-side `cooldown_addr` when a bootstrap reply is pending
  - or a reply-path policy that avoids treating bootstrap reply creation as “good enough” when scheduler delivery is still blocked
- It addresses a now-proven mismatch between transport success and UI/app completion.
- It complements the metadata retry/backoff patch:
  - backoff reduced duplicated fetch work
  - the next issue is correctly consuming late join success when it does happen

Post-pending-join-intent round:
- Test round:
  - `logs/device-A_activity_log_live_20260623_223533-5e599eee.txt`
  - `logs/device-B_activity_log_live_20260623_223533-386796af.txt`
- User-visible result:
  - `B` still could not join `A`'s room.

What improved in this round:
- The new nearby-only product-flow patch is active:
  - `B` logs `Nearby room pending join intent` when opening `A`'s room from Discovery
  - the new post-join path no longer drops forced metadata refresh the old way
- The `post_join` metadata refresh now actually ran:
  - `B` logged `Nearby room metadata fetch send ... force=true fetch_reason=post_join`
- Interpretation:
  - the previous app-layer `queued=false` hole has been addressed
  - the pending-intent / auto-open design is wired up and observable

What did not happen:
- `B` did not log:
  - `Nearby room key bootstrap recv`
  - `Nearby room key bootstrap stored`
  - `Nearby room auto-open pending join`
- Interpretation:
  - the new auto-open logic never had a chance to trigger
  - the join still failed earlier, before room-key delivery reached `B`

Join timeline for this round:
- `B` discovery/full-metadata path did initially work:
  - `B` resolved `A`'s room metadata early in the round
- `B` then manually opened `A`'s room and recorded pending join intent
- `B` sent many repeated nearby key requests for the same room
- `A` received many of those key requests
- `A` attempted multiple bootstrap sends:
  - at least one early bootstrap send failed
  - later bootstrap sends did reach `sent=true`
- Despite that, `B` still never logged bootstrap receive/store for this round

Critical interpretation from this round:
- The current bottleneck is back to transport/control delivery, not the new app-flow logic.
- The room owner `A` can sometimes enqueue and send bootstrap successfully.
- But the bootstrap/control response still does not reliably arrive at `B`'s app layer.
- That means:
  - the pending-intent patch is not disproven
  - it is simply blocked by an earlier nearby control-delivery failure

Transport signals that dominate this round:
- `B` is strongly dominated by:
  - `reason=state_busy_connecting_or_connected`
  - `cooldown_addr`
  - address rotation churn such as `rotation_previous_addr` / `selection_rotation_previous_addr`
- `B` also shows:
  - `gate_not_ready:deferred_rotation_recovery_not_ready`
- Interpretation:
  - nearby key/bootstrap traffic is still contending with shared BLE transport state
  - especially connection-busy and address-rotation recovery behavior

Important implication for next patch:
- The next issue is no longer “late key arrives but UI ignores it”.
- The next issue is “owner bootstrap can be sent, but bootstrap/control delivery still fails to land on the joiner”.
- This pushes the next nearby-only patch back toward transport observability and prioritization for:
  - `KEY_BOOTSTRAP_V1`
  - and possibly `KEY_REQUEST_V1`

Suggested next implementation:
- Add tighter wire-path observability specifically for nearby bootstrap delivery:
  - bootstrap enqueue
  - selected target device/address
  - staged/pending
  - send complete
  - receive/deliver on peer
- If the traces confirm continued contention, prototype a nearby-only priority/anti-contention rule for bootstrap packets while there is a valid pending join intent.
- Keep the scope nearby-only and avoid changing private-group scheduler defaults.

Additional nearby-only idea for join flow:
- Proposed by tester:
  - track which peer(s) have already received `KEY_REQUEST_V1` for a room and when
  - avoid re-sending `KEY_REQUEST_V1` to the same peer for about `1 minute` if there are other nearby peers available
  - if nearby candidate count is too small and there is no practical alternative, allow reusing an older peer
  - expected behavior:
    - the joiner should walk through nearby candidates instead of repeatedly hammering the same peer
    - each candidate peer gets one request attempt first
    - if that path fails, the joiner fetches/uses the next nearby candidate in the queue

Refinement / recommended design:
- The idea is directionally strong because it targets the current `KEY_REQUEST_V1` spam pattern directly.
- Current rounds suggest:
  - `KEY_REQUEST_V1` often reaches the owner or another key-holder
  - `KEY_BOOTSTRAP_V1` delivery is the less stable packet
  - repeated key-request spam from the joiner may be making transport contention worse at exactly the wrong time
- Recommended nearby-only policy:
  - maintain request history keyed by `(roomId, targetDeviceId)`
  - track:
    - `last_requested_at_ms`
    - optionally `attempt_count`
    - optionally `last_result`
- Candidate selection behavior:
  - prefer a nearby peer that has not been tried recently
  - if every candidate has been tried recently, only then allow reuse of an older peer
  - prefer fresher / stronger candidates first:
    - newer `seenAtMs`
    - stronger RSSI
    - peer that recently advertised the room
- Recommended request style:
  - avoid blind repeated broadcast whenever a candidate set is already known
  - prefer a targeted `KEY_REQUEST_V1` to one peer or a very small candidate subset
  - fallback to a broader request only when the targeted walk has failed
- Cooldown suggestion:
  - the tester proposed `1 minute`
  - that is a reasonable hard cooldown when there are multiple nearby candidates
  - if needed, this could later be refined into:
    - a shorter soft cooldown for the same peer
    - and a longer hard fallback window when there are enough alternative peers

Why this idea is worth pursuing:
- It is nearby-only.
- It attacks an observed real pattern:
  - the joiner repeatedly sends `KEY_REQUEST_V1`
  - while `KEY_BOOTSTRAP_V1` still struggles to land
- It may reduce self-inflicted transport churn and create a cleaner delivery window for bootstrap/control replies.
- It is more directly aligned with the current bottleneck than additional UI/product-flow changes.

Round update - 2026-06-29 10:19 live test:
- Setup:
  - `A` owned nearby room `94be10edf264`
  - `B` and `C` both attempted nearby join
  - private-group traffic was active in the same round
- Observed user outcome:
  - `B` and `C` both failed the first nearby join attempt(s)
  - both eventually joined after later retry rounds
  - once joined, nearby room traffic became usable

What changed relative to the previous failing rounds:
- This round shows real progress:
  - nearby join is no longer "never succeeds"
  - `KEY_BOOTSTRAP_V1` can now eventually land on both joiners
- However, the delay is still large enough that the first one or two user-visible attempts can fail before a later retry succeeds.

Concrete log evidence:
- `A` received `B`'s request and attempted bootstrap reply:
  - `10:21:41.145` `Nearby room key request recv requester=SZ7qZ-mNiVwPTVPE3qkg2A`
  - `10:21:41.193` `Nearby room key bootstrap send ... owner=true sent=true`
- `B` only stored that bootstrap much later:
  - `10:22:00.758` `Nearby room key bootstrap recv`
  - `10:22:00.767` `Nearby room key bootstrap stored`
- `C` showed an even clearer multi-attempt pattern:
  - `10:21:43.199` `Nearby room key request send ... sent=false`
  - `10:21:43.423` `Nearby room key request send ... sent=false`
  - `10:21:44.446` `Nearby room key request send ... sent=true`
  - `10:22:13.443` `Nearby room key bootstrap recv`
  - `10:22:13.461` `Nearby room key bootstrap stored`

Important additional signal:
- After join success, non-owner peers started participating in bootstrap reply:
  - `C -> B`: `10:22:17.304` `Nearby room key bootstrap send ... owner=false sent=true`
  - `B -> C`: `10:23:33.196` `Nearby room key bootstrap send ... owner=false sent=true`
- This supports the design direction that once a peer has entered the room and has the key, it can help future joiners instead of relying solely on the owner lane.

Comparison with private-group traffic in the same round:
- Private-group message exchange continued to pass much earlier and much more smoothly than nearby join.
- Nearby join still spent a long time under:
  - `cooldown_addr`
  - `state_busy_connecting_or_connected`
- So the current bottleneck still looks transport-lane timing / contention related, not payload-format related.

Interpretation:
- The system now appears able to recover and eventually complete nearby join under retry pressure.
- The main remaining defect is poor time-to-join:
  - owner-side bootstrap delivery is still delayed by repeated address cooldown / busy-lane contention
  - the user can still experience one or more visible join failures before the eventual success arrives

Suggested next step:
- Keep the focus on nearby-only join transport behavior.
- Prioritize reducing bootstrap reply latency rather than changing the shared `sent=true` transport contract.
- The most relevant remaining question is:
  - why private-group traffic can usually secure a usable lane quickly
  - while nearby `KEY_BOOTSTRAP_V1` often has to wait through repeated `cooldown_addr` / busy-lane cycles before it finally lands

Resolved join-flow issue from that round:
- The earlier `NearbyRoomActivity` behavior that kept retrying `KEY_REQUEST_V1` after `bootstrap stored` has now been addressed.
- Root cause that was confirmed in logs:
  - join completion used to depend on both:
    - room key present
    - `room.metadata_status == METADATA_RESOLVED`
  - because of that coupling, `bootstrap stored` alone did not stop the room-screen retry loop
  - unresolved metadata could keep generating extra `KEY_REQUEST_V1` traffic during the 60-second join window
- Implemented nearby-only fix:
  - `NearbyRoomActivity` now tracks `isKeyReady` and `isMetadataReady` separately
  - join retry / timeout / loading overlay stop as soon as `isKeyReady == true`
  - metadata refresh continues independently after key arrival
- Purpose of the fix:
  - stop extra `KEY_REQUEST_V1` churn immediately after `KEY_BOOTSTRAP_V1` has already been stored
  - keep the change nearby-only, without touching shared BLE scheduler behavior or private-group flow
- New activity-log labels added for verification:
  - `Nearby room key access ready`
  - `Nearby room join retry stopped`
  - `Nearby room metadata pending after key ready`

Round update - 2026-06-29 11:08 live test:
- Test round:
  - `logs/device-A_activity_log_live_20260629_110816-b349b037.txt`
  - `logs/device-B_activity_log_live_20260629_110814-6ebef64c.txt`
  - `logs/device-C_activity_log_live_20260629_110812-2525a581.txt`
- High-level outcome:
  - the new key-ready UI-flow patch is working
  - nearby join still remains slow and transport-fragile
  - owner-side bootstrap delivery is still the main blocker

What is now solved:
- The old room-screen behavior that tied join completion to `METADATA_RESOLVED` is no longer the dominant source of key-request churn.
- `C` now clearly shows the intended nearby-only behavior:
  - `11:13:06.106` `Nearby room key access ready ... metadata_ready=false`
  - `11:13:06.107` pending join intent cleared with `reason=key_access_ready`
- This confirms:
  - the join UI can now stop on key arrival even when metadata is still pending
  - metadata fetch and key acquisition are now separated in the room-screen state machine

What improved in the new round:
- `B` successfully received and stored `A`'s bootstrap:
  - `11:12:09.279` `Nearby room key bootstrap recv`
  - `11:12:09.289` `Nearby room key bootstrap stored`
- The key-request cadence stayed much lower than the old spam baseline:
  - `B`: `key_request_send=3`, `wait_timeout=2`, `bootstrap_stored=1`
  - `C`: `key_request_send=7`, `wait_timeout=5`, `wait_skip=100`
- The candidate-history / targeted-rotation patch is still behaving as intended:
  - `C` alternated between owner `10308F486AD9F054` and helper candidate `3D3FA1C865F3B265`

What still failed:
- `B` still did not complete metadata after key arrival:
  - post-join metadata fetch was triggered and sent
  - but the forced post-join metadata fetch still timed out
- `A` still repeatedly received key requests and created bootstrap replies, but reply delivery remained fragile:
  - multiple `Nearby room key bootstrap send ... sent=true`
  - followed by repeated `cooldown_addr`
  - and additional busy / connect-lifecycle contention
- `C` still spent a long time waiting for bootstrap before finally reaching `key access ready`

Current dominant blocker after the UI-flow fix:
- The bottleneck is no longer primarily "UI keeps requesting key because metadata is unresolved".
- The dominant blocker is still owner-side bootstrap delivery under transport contention:
  - `cooldown_addr`
  - `state_busy_connecting_or_connected`
  - occasional `sent=false`
- Count signal from this round:
  - `A`: `cooldown_addr=128`
  - `B`: `cooldown_addr=75`, `status133=16`
  - `C`: `cooldown_addr=193`, `busy_connect=16`

Remaining edge case found in the new round:
- There is still a small race after key arrival.
- On `C`, a `Nearby room key request send ... sent=false` still appeared at `11:13:06.115`, just after:
  - `11:13:06.106` `Nearby room key access ready`
  - `11:13:06.107` pending join cleared
- Interpretation:
  - the main UI-state coupling issue is fixed
  - but one in-flight or near-simultaneous caller can still race and attempt one last request before the new key-ready state fully suppresses it

Updated next-step recommendation:
- Keep the new nearby-only key-ready UI-flow patch.
- Do not reopen the old metadata-coupling problem unless a regression appears in later rounds.
- Focus next on:
  - owner-side `KEY_BOOTSTRAP_V1` reply delivery under `cooldown_addr` / busy state
  - and a small nearby-only guard to suppress the post-key-arrival race that can still emit one last `KEY_REQUEST_V1`

Implemented nearby-only transport guard after that round:
- Added a transport-manager-level `hasRoomKey(roomId)` guard inside `sendKeyRequest(roomId)`.
- New behavior:
  - if the room key is already present, `sendKeyRequest(...)` returns immediately
  - any stale pending wait state for that room is cleared with `reason=key_ready`
  - activity log now emits `Nearby room key request wait skip ... reason=key_ready`
- Purpose:
  - suppress the last remaining post-key-arrival race even when a caller reaches transport code slightly before the UI state finishes updating
  - keep the fix nearby-only and caller-agnostic

Round update - 2026-06-29 18:50 live test:
- Test round:
  - `logs/device-A_activity_log_live_20260629_185033-14320bcd.txt`
  - `logs/device-B_activity_log_live_20260629_185032-be9af932.txt`
  - `logs/device-C_activity_log_live_20260629_185033-e253c930.txt`
- High-level outcome:
  - the new `key_ready` transport guard is working
  - joiners no longer show the previous post-key-arrival `KEY_REQUEST_V1` race pattern
  - nearby join is still slow, and post-key metadata remains unreliable

What is now solved:
- The remaining post-key-arrival request race appears fixed.
- `B` reached:
  - `18:54:38.983` `Nearby room key request wait skip ... reason=key_ready`
  - `18:54:38.986` `Nearby room key access ready ... metadata_ready=false`
  - no later `Nearby room key request send` appears after that point
- `C` reached:
  - `18:54:15.570` `Nearby room key bootstrap recv`
  - `18:54:15.586` `Nearby room key bootstrap stored`
  - `18:54:16.286` `Nearby room key access ready ... metadata_ready=false`
  - `18:54:16.287` `Nearby room key request wait skip ... reason=key_ready`
  - no extra post-key `KEY_REQUEST_V1` send followed after `key_ready`
- `A` also hit the same nearby-only guard locally:
  - `18:52:41.601` `Nearby room key request wait skip ... reason=key_ready`
- Interpretation:
  - the suppression is now enforced in transport code, not just in the room-screen state machine

What improved in this round:
- `B` and `C` both made forward progress to key-ready state.
- `C` clearly completed key acquisition:
  - `bootstrap_recv=1`
  - `bootstrap_stored=1`
  - `key_ready=1`
  - `join_retry_stopped=1`
- Request cadence remained bounded by the existing wait-state guard:
  - `B`: `key_request_send=4`, `wait_skip=52`, `wait_timeout=3`
  - `C`: `key_request_send=4`, `wait_skip=44`, `wait_timeout=3`

What still failed:
- Post-key metadata is still the biggest app-layer reliability issue.
- `B`:
  - `md_fetch_send=3`
  - `md_fetch_timeout=3`
- `C`:
  - `md_fetch_send=3`
  - `md_fetch_timeout=2`
- This means:
  - key acquisition is now materially healthier than before
  - but room metadata resolution after key arrival is still often timing out

Transport blocker status in this round:
- `cooldown_addr` remains the dominant transport symptom:
  - `A`: `cooldown_addr=83`
  - `B`: `cooldown_addr=78`
  - `C`: `cooldown_addr=104`
- Additional signs of contention remain:
  - `C`: `busy_connect=12`, `status133=8`
  - `A`: `status133=6`
  - `B`: `status133=6`
- Owner-side reply fragility is still visible:
  - `A` logged `Nearby room key reply skipped ... reason=cooldown remaining_ms=4611` for `B`

Updated conclusion after this round:
- The nearby-only `key_ready` transport guard should be kept.
- It appears to have closed the last obvious joiner-side request race after key arrival.
- The main problem has now shifted more cleanly to:
  - owner-side bootstrap reply latency / delivery under transport contention
  - and post-key metadata fetch reliability

Updated next-step recommendation:
- Keep all current nearby-only joiner patches:
  - target rotation
  - wait-state after sent request
  - key-ready UI-flow separation
  - key-ready transport guard
- Focus next on:
  - owner-side `KEY_BOOTSTRAP_V1` reply delivery under `cooldown_addr`
  - and the nearby metadata path after key arrival, because that is now the most visible remaining user-facing failure

Implemented nearby-only owner-bootstrap priority patch:
- Added a dedicated transport hint for owner/bootstrap reply traffic:
  - `MeshTrafficHint.NEARBY_ROOM_KEY_BOOTSTRAP`
- `NearbyRoomTransportManager.sendKeyBootstrap(...)` now uses that dedicated hint instead of the broader `NEARBY_ROOM_CONTROL` lane.
- Transport behavior change for this new hint:
  - still counts as nearby priority work for `device_quiet` / `cooldown_device` bypass
  - additionally bypasses `cooldown_addr`
  - is selected ahead of ordinary nearby control packets in `WRITE_NEXT`
- Intention:
  - reduce owner-side bootstrap reply latency specifically on the critical `owner -> joiner` path
  - avoid broadening the bypass to all nearby control traffic, to stay within the nearby-only guardrail and reduce risk to the private-group baseline

Why this patch is the current smallest useful next step:
- Recent rounds show the remaining join bottleneck is not mostly joiner spam anymore.
- The repeated failure pattern is:
  - owner receives `KEY_REQUEST_V1`
  - owner can often build / enqueue `KEY_BOOTSTRAP_V1`
  - reply then loses time or gets blocked under shared BLE transport contention, especially `cooldown_addr`
- A dedicated bootstrap-only priority lane is narrower than relaxing all `NEARBY_ROOM_CONTROL` traffic.
- This keeps the change focused on the packet with the highest join-critical value before touching post-key metadata or broader nearby scheduler behavior.

Round update - 2026-06-29 20:09 live test:
- Test round:
  - `logs/device-A_activity_log_live_20260629_200926-9fc86d0c.txt`
  - `logs/device-B_activity_log_live_20260629_200928-65d5bb50.txt`
  - `logs/device-C_activity_log_live_20260629_200925-d37cb193.txt`
- High-level outcome:
  - the dedicated bootstrap-priority patch helped `B`
  - owner-side bootstrap delivery to `B` completed and `B` reached `key access ready`
  - `C` still failed, and the dominant new blocker was not just transport delay

What improved:
- `A -> B` owner bootstrap now completed on-device:
  - `A` received `B`'s request at `20:11:11.649`
  - `A` sent bootstrap at `20:11:11.662`
  - `B` received bootstrap at `20:11:40.190`
  - `B` stored bootstrap at `20:11:40.201`
  - `B` reached `Nearby room key access ready` at `20:11:40.354`
- `B` then began helping as a non-owner bootstrap responder for `C`
  - repeated `Nearby room key bootstrap send ... owner=false sent=true`

What newly failed:
- `C` exposed a new app-layer failure mode:
  - `20:11:13.208` `Nearby room key bootstrap recv`
  - `20:11:13.216` `Nearby room key bootstrap store skipped ... reason=room_missing`
- This means nearby bootstrap can now reach the joiner app layer but still be discarded before key storage if the local room row does not exist yet.

Why `room_missing` happened on `C` in this round:
- At the same moment, `C` was not processing nearby discovery metadata into the DB:
  - `Nearby room discovery metadata skipped ... reason=discovery_disabled`
- `maybeStoreBootstrapKey(...)` currently hard-requires `repository.getRoom(packet.room_id) != null` before storing the key.
- `syncRoomCreatedAtFromTransport(...)` runs after bootstrap receive, but it is also a no-op when the room row does not already exist.
- So the current behavior is:
  - bootstrap arrives first
  - discovery upsert has not happened yet
  - room row is missing
  - bootstrap is dropped
  - later retries are required even though one valid bootstrap already landed

Updated conclusion after this round:
- The bootstrap-priority patch is directionally correct and should be kept.
- It improved the owner -> `B` path enough to show real join progress.
- But transport priority alone is not sufficient now.
- There is a second blocker:
  - `KEY_BOOTSTRAP_V1` can arrive before local room discovery state exists
  - and current nearby code discards that bootstrap with `reason=room_missing`

Updated next-step recommendation:
- Keep the new bootstrap-priority transport patch.
- Focus next on a nearby-only app-layer fix for bootstrap-before-room ordering:
  - either create/minimally upsert a placeholder room row from bootstrap transport metadata
  - or cache deferred bootstrap until the room row appears
- The goal is to avoid losing a valid owner/bootstrap packet just because discovery state and transport state arrive out of order.

Refined diagnosis after deeper trace:
- The first failure on `C` was not just `room_missing`.
- The deeper issue was that `C` accepted a bootstrap packet that was not intended for `C`.
- Evidence:
  - `A` built bootstrap for requester `B`
  - transport then logged target fallback away from the preferred requester lane
  - `C` later logged `Nearby room key bootstrap recv`
  - then `Nearby room key bootstrap store skipped ... reason=room_missing`
- So the effective failure chain was:
  - owner/bootstrap packet lost its preferred-target lane in transport fallback
  - a non-target peer received the packet
  - nearby receiver did not validate `target_device_id` before local bootstrap handling
  - local handling then fell into `room_missing`

Implemented nearby-only wrong-target bootstrap patch:
- `KEY_BOOTSTRAP_V1` receive path now checks `target_device_id`.
- New behavior:
  - if `target_device_id == localDeviceId`, process normally
  - if `target_device_id != localDeviceId`, do not touch local room/key state; forward the packet toward the intended target instead
  - if `target_device_id` is missing, drop the bootstrap packet as malformed
- Added nearby-only dedupe for relayed bootstrap packets to reduce loop risk.
- Added activity-log labels:
  - `Nearby room key bootstrap forwarded`
  - `Nearby room key bootstrap forward skipped`
  - `Nearby room key bootstrap dropped`

Why this patch should be kept:
- It prevents the local app from misclassifying a wrong-target bootstrap as a local `room_missing` problem.
- It preserves the packet's join value by forwarding it instead of blindly dropping it.
- It stays narrow:
  - bootstrap-only
  - nearby-only
  - no private-group scheduler changes

New phenomenon observed after the wrong-target forward patch:
- The round `2026-06-29 20:45` exposed a new bootstrap bounce pattern:
  - a device can receive a `KEY_BOOTSTRAP_V1` whose `sender_device_id` is its own local device id
  - then forward that same bootstrap again toward the original target
- Concrete examples from the round:
  - `A` logged `Nearby room key bootstrap recv ... sender=uxEvtHmTajCk9YR85KtBLA`
  - shortly after, `A` logged `Nearby room key bootstrap forwarded ... target=SZ7qZ-mNiVwPTVPE3qkg2A local=uxEvtHmTajCk9YR85KtBLA`
  - later, `A` again logged local-self bootstrap receive and forward for target `C`
- Interpretation:
  - the wrong-target forward patch did stop the earlier `room_missing` mis-handle
  - but bootstrap packets can still bounce back to their original sender through the mesh path
  - the current receiver logic may then forward that self-origin packet again

Why this matters:
- This is not yet proven to be an infinite loop, but it is clear evidence of reverse/bounce traffic on bootstrap packets.
- That bounce can:
  - add extra transport pressure
  - blur sender/forward path observability
  - potentially delay the real target if self-origin packets keep being re-forwarded

Implemented nearby-only bootstrap bounce guards:
- Patch 1: self-origin bounce suppress
  - For `KEY_BOOTSTRAP_V1`, if:
    - `sender_device_id == localDeviceId`
    - and `target_device_id != localDeviceId`
  - the receiver now treats it as a self-origin bounce and does not forward it again.
  - New activity label:
    - `Nearby room key bootstrap bounce skipped`
- Patch 2: duplicate wrong-target receive suppress
  - A peer that already saw a wrong-target `KEY_BOOTSTRAP_V1` notice will not accept that same notice again on receive.
  - The dedupe key is:
    - `room_id | sender_device_id | target_device_id | created_at_ms`
  - Practical effect:
    - if `B` already received and handled bootstrap notice `A -> D`
    - then a later reverse path like `A -> B -> C -> B` for that same notice is dropped immediately on `B`
    - `B` does not re-enter the forward path for that notice
  - New activity label:
    - `Nearby room key bootstrap receive skipped`

Why both guards are needed:
- The self-origin guard prevents the original sender from re-forwarding its own bootstrap after mesh bounce.
- The duplicate wrong-target receive guard prevents an intermediate peer from repeatedly re-accepting and re-forwarding the same bootstrap notice.
- Together they do not eliminate every possible duplicate first-hop fan-out, but they do close the clearest reverse/bounce loop patterns while staying bootstrap-only and nearby-only.

Round update - 2026-06-30 19:33 live test:
- Test round:
  - `logs/device-A_activity_log_live_20260630_193330-501e5bbd.txt`
  - `logs/device-B_activity_log_live_20260630_193331-38401580.txt`
  - `logs/device-C_activity_log_live_20260630_193333-e952366d.txt`

What the new bootstrap bounce patch achieved:
- The new guards are active in real logs.
- Evidence:
  - `A` logged `Nearby room key bootstrap bounce skipped`
  - `B` also logged `Nearby room key bootstrap bounce skipped`
  - `C` logged `Nearby room key bootstrap forwarded`
- Interpretation:
  - the recent bootstrap bounce patch is doing real work
  - this round does not show the earlier obvious reverse loop continuing indefinitely
  - no evidence in this round that an intermediate peer re-accepted the same wrong-target bootstrap notice multiple times

What still failed:
- `B` still did not complete join into `A`'s room.
- This round is important because the failure point moved later than before:
  - `B` did receive `KEY_BOOTSTRAP_V1`
  - `B` did store the room key successfully
  - `B` did trigger and send the forced `post_join` metadata fetch
  - but that `post_join` metadata fetch still timed out

Key timeline on `B`:
- `19:36:35.730` `Nearby room key bootstrap recv`
- `19:36:35.747` `Nearby room key bootstrap stored`
- `19:36:35.753` `Nearby room post-join metadata refresh trigger`
- `19:36:35.753` `Nearby room metadata fetch queued ... reason=post_join ... force=true queued=true`
- `19:36:36.351` `Nearby room metadata fetch send ... target=uxEvtHmTajCk9YR85KtBLA sent=true force=true fetch_reason=post_join`
- `19:36:44.358` `Nearby room metadata fetch timeout ... target=uxEvtHmTajCk9YR85KtBLA timeout_ms=8000 force=true fetch_reason=post_join`

What this means:
- The join failure in this round is no longer “cannot get key”.
- The join failure is now “key ready, but post-key metadata fetch still not completing”.
- This is a materially different and narrower failure point than the earlier key-bootstrap loss rounds.

Owner-side observation:
- `A` did send owner bootstrap successfully in this round:
  - `19:36:23.034` `Nearby room key bootstrap send ... sent=true`
- But after `B` sent the forced post-join metadata request, `A` does not show a matching `Nearby room metadata request recv` in the captured logs.
- Interpretation:
  - the `post_join` metadata request likely died between transport write-complete and owner receive
  - or it was lost before app-layer delivery on `A`
  - this round does not support the theory that owner app logic explicitly rejected the request after receive

Updated assessment after this round:
- The bootstrap bounce-loop work should be kept.
- It improved correctness/containment of `KEY_BOOTSTRAP_V1` forwarding and did not appear to regress private-group behavior.
- But it did not address the next dominant nearby join failure:
  - post-key `METADATA_REQUEST_V1` reliability

Refined next focus:
- The next investigation should center on `post_join` metadata fetch reliability:
  - `B` enqueue/send/write-complete path
  - relay/delivery path between `B` and `A`
  - whether owner-side `METADATA_REQUEST_V1` is missing before app receive, or received and then lost later
- Nearby-only priority remains:
  - do not broaden to global BLE policy first
  - focus specifically on post-bootstrap nearby metadata control packets

Deeper trace on `METADATA_REQUEST_V1` right after `bootstrap_stored`:
- The traced `post_join` metadata request from `B` did pass the full local write path:
  - `19:36:36.351` `Nearby room metadata fetch send ... sent=true force=true fetch_reason=post_join`
  - `19:36:36.351` pipeline staged the packet to pending for owner device `10308F486AD9F054`
  - `19:36:36.443` `BLE send progress ... chunks=1/1 bytes=128/128`
  - `19:36:36.444` `BLE send complete ... chunks=1/1 bytes=128/128`
- This means the `post_join` request did not die on `B` before write-complete.

What was not observed on the owner:
- In the same attempt window, `A` does not show:
  - `Nearby room metadata request recv`
  - `Nearby room metadata response send`
- So the strongest current reading is:
  - `B` wrote the `METADATA_REQUEST_V1` successfully
  - but `A` missed app-layer receive for that request

Transport conditions immediately after bootstrap:
- On `B`, immediately after `bootstrap_stored`, there was still transport churn:
  - repeated `GATT connect skipped ... reason=device_quiet`
  - then `GATT address cooldown set ... source_reason=rotation_previous_addr`
  - then connect-backoff gates on another nearby peer
- But those did not prevent the `post_join` request from reaching write-complete on `B`.
- On `A`, during the same window after owner bootstrap send and around the time `B` sent the forced metadata request, the owner side showed:
  - repeated `GATT connect skipped ... reason=state_busy_connecting_or_connected`
  - followed shortly by repeated `GATT connect skipped ... reason=cooldown_addr`
- Interpretation:
  - the current evidence points more strongly at owner-side receive opportunity loss than joiner-side enqueue failure
  - `state_busy_connecting_or_connected` and then `cooldown_addr` are plausible blockers on `A` during the exact post-bootstrap metadata-request window

Refined proposed solution for the next patch:
- Keep the change nearby-only.
- Treat `post_join METADATA_REQUEST_V1` as a higher-priority nearby control packet, similar in spirit to the owner bootstrap reply path.
- Candidate nearby-only options:
  - add a dedicated traffic hint for post-join metadata fetch so it can be prioritized separately from ordinary nearby control
  - allow that hint to bypass the same owner-side gating currently hurting it:
    - `state_busy_connecting_or_connected` conflict windows where possible
    - or `cooldown_addr` where a pending post-join metadata request exists
  - ensure this priority applies only to:
    - forced `post_join` metadata requests
    - and possibly the matching owner metadata response
- Why this is the best next step:
  - bootstrap delivery has already moved forward enough to expose the next bottleneck
  - the new bottleneck is narrow and nearby-specific
  - this keeps private-group baseline risk lower than a broad scheduler change
