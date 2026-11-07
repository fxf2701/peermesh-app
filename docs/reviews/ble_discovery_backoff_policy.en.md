# BLE Discovery Backoff Policy (EN)

## Scope
This document defines the BLE discovery + backoff policy for Phase 3 transport stabilization.
It targets unstable one-way delivery observed in TC7/TC10.

## Problem Statement
Current behavior may fail with `no nearby or send failed` even when users still see nearby peers.
Main causes:
- strict address-level backoff can temporarily remove all send targets
- BLE address churn can invalidate previous target choice
- retries can over-focus on failed peers while fresh peers are available

## Policy Goals
- Preserve delivery progress under unstable RF conditions.
- Avoid connect spam loops and battery drain.
- Prioritize message path over ACK noise.
- Keep behavior deterministic and observable in logs.

## Target Pools
Two pools are maintained per group runtime:

1. MTL (Main Targets List)
- Active send candidates.
- Capacity-limited (default: 10 device IDs).
- Ordered by score (freshness, RSSI, recent success).

2. PTL (Potential Targets List)
- Overflow + temporarily penalized targets.
- Bounded (default: 64 device IDs; not unbounded).
- Ordered by:
  1) never-penalized fresh targets
  2) penalized targets by earliest penalty expiry

## Identity Key
Backoff/penalty must be keyed by `deviceId` (not BLE address), because BLE addresses may rotate.
Address is treated as the latest observed locator for a stable device ID.

## Discovery Ingestion Rules
On each scan hit:
- Validate transport signature/manufacturer payload.
- Extract `deviceId`.
- Update `deviceId -> latestAddress` and `lastSeen` timestamps.
- If `deviceId` is in MTL, update score in-place.
- Else insert into PTL (or refresh existing PTL node).
- If MTL has a free slot, promote the best PTL candidate to MTL only when that candidate is not currently penalized.

## Backoff Policy
### Normal mode (`nearby >= 2`)
- On connect fail for `deviceId`, apply penalty window (default: 5s, exponential up to cap).
- Demote failed target from MTL to PTL.
- Promote the best PTL candidate to MTL only after its penalty has expired.
- Continue round-robin over MTL.

### Single-target probe mode (`nearby == 1`)
- Do not fully disable safety, but bypass heavy backoff.
- Retry allowed with short probe interval (default: 1-2s) for the only visible peer.
- Cap probe retries per window to avoid hard spam.

### No-target fallback
If all MTL entries are penalized:
- allow one probe candidate (earliest penalty expiry or freshest seen) instead of returning hard send-fail.

## ASCII Flow
```text
[Scan hit]
   |
   v
[Validate payload + extract deviceId]
   |
   +-- invalid --> [Drop]
   |
   v
[Update latestAddress + lastSeen + score]
   |
   +-- deviceId in MTL? -- yes --> [Refresh MTL node] ----+
   |                                                      |
   no                                                     v
   |                                              [Send scheduler tick]
   v                                                      |
[Insert/refresh PTL node]                                 v
   |                                              [Pick from MTL round-robin]
   +-- MTL has free slot? -- no -------------------------+
   |
  yes
   |
   v
[Best PTL candidate penalty expired?]
   |
   +-- no --> [Keep in PTL]
   |
  yes
   |
   v
[Promote PTL -> MTL]

==================== SEND / FAILURE LOOP ====================
[Try connect/send target]
   |
   +-- success --> [Keep in MTL, reset failure counters]
   |
   +-- fail --> [Apply backoff by deviceId]
                 |
                 v
              [Demote MTL -> PTL]
                 |
                 v
      [Promote best PTL candidate with expired penalty]
                 |
                 v
              [Continue round-robin]

==================== SPECIAL CASE ====================
[nearby == 1]
   |
   v
[Single-target probe mode]
   |
   v
[Short retry interval with capped attempts]

## Field Notes (2026-03-08)
- To avoid sticking to stale addresses, only use `lastSeenAddrByDeviceId` when `seenAt <= DEVICE_TTL_MS`.
- Quiet window after `status=133` uses a fixed 20s (no escalation), which performed best in the latest 5 test runs.

[All MTL penalized]
   |
   v
[Select 1 probe candidate]
```

## Scheduling / Rotation
- Round-robin selection runs on MTL.
- After each fail, immediate rotation to next eligible target.
- Fairness rule: do not retry the same failed target in the same short cycle unless single-target mode.

## ACK Handling
- ACK remains in BLE mesh service, but message events have priority.
- ACK batching/piggyback should not consume all queue slots needed by message traffic.

## Defaults (Recommended)
- `MTL_CAP = 10`
- `PTL_CAP = 64`
- `BASE_PENALTY_MS = 5000`
- `PENALTY_MAX_MS = 30000`
- `SINGLE_TARGET_PROBE_MS = 1500`
- `DEVICE_FRESH_TTL_MS = 30000`

## Adaptive PTL Capacity (Heap Headroom)
PTL capacity should scale by app heap headroom, not total device RAM.

Recommended runtime tiers:
- `< 24 MB` headroom: `PTL_CAP = 24`
- `24 - 48 MB` headroom: `PTL_CAP = 48`
- `48 - 96 MB` headroom: `PTL_CAP = 96`
- `> 96 MB` headroom: `PTL_CAP = 160` (hard max)

Operational rules:
- Keep `MTL_CAP` fixed (`10`), only PTL is adaptive.
- Enforce `PTL_CAP_MAX = 160`.
- On memory pressure (`onTrimMemory`), downgrade to a lower tier and prune PTL immediately.

## Telemetry Markers (must log)
- `Discovery pool update`: deviceId, pool, score inputs
- `MTL promote` / `MTL demote`: reason
- `Backoff apply`: deviceId, penaltyMs, failReason
- `Backoff bypass single-target`: deviceId
- `All targets penalized -> probe`: chosen deviceId
- `Target selected`: deviceId, latestAddress, pool

## Rollout Plan
1. Phase A (low risk)
- Keep current transport, add deviceId-keyed penalties + single-target probe + telemetry.
2. Phase B
- Introduce explicit MTL/PTL structures and promotion/demotion.
3. Phase C
- Tune parameters using TC7/TC10 retest data.

## Exit Criteria
TC7 retest pass when:
- both directions deliver 10/10 messages in same session
- no persistent `no nearby or send failed` burst for one side

TC10 retest pass when:
- after screen-off/background transitions, both directions recover within acceptable delay window
- no long one-way blackout caused by target starvation
