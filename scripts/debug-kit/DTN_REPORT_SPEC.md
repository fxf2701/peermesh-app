# DTN Report Spec

Purpose: upgrade `mesh_debug.sh dtn-md` so the generated markdown report reflects how we actually review DTN rounds.

## Scope

Phase 1 adds these report sections:

1. `Delivery`
2. `Carrier`
3. `Source-side Noise`
4. `Per-batch Verdict`

Later phases can add:

- `Queue Health`
- `Transport Stability`
- `Routing / Identity`

## Phase 2

Phase 2 extends the report with:

1. `Queue Health`
2. `Transport Stability`
3. `Routing / Identity`

## Delivery

Goal: show user-visible message delivery, not just transport success.

### Input markers

- `Sent message at`
- `Received message at`
- `Rendered message at`
- `APP batch extract`

### Parsed fields

- `eventId`
- `text=Test X: A #1`
- `batch_id`
- device name from log filename
- line timestamp

### Output

- summary table:
  - `A -> B`
  - `B -> A`
  - or more generally `sender_device -> receiver_device`
  - delivered/sent
  - missing
  - received_only
- event matrix:
  - event id
  - text
  - sender device
  - sent time
  - received-by devices
  - rendered-by devices
  - status: `DELIVERED | RECEIVED_ONLY | MISSING`
  - associated batch ids

## Carrier

Goal: show whether relay nodes are actually collecting and forwarding.

### Input markers

- `DTN opaque retry store`
- `DTN opaque retry queued`
- `BLE batch send complete`
- `BLE batch send failed`
- `queued_no_write_attempt_timeout`

### Output

- per-device counters:
  - opaque store
  - opaque queued
  - send complete
  - send failed
  - queued timeout

Interpretation:

- high `store/queued` with low `send_complete` means collector but weak carrier
- high `send_complete` with zero carrier failures means relay path is healthy

## Source-side Noise

Goal: detect source-origin batches that already entered the mesh successfully but still generate extra failed attempts.

### Definition

A source-noise batch is a normal app batch where:

- the origin device recorded at least one `send_complete`
- then the same origin device later recorded `send_failed` for another target

### Input markers

- `BLE batch send complete`
- `BLE batch send failed`
- `APP dispatch job completed`
- `APP dispatch job failed`
- `APP batch transport queued`
- `reason=first_peer_delivered`

### Output

- one row per noisy batch:
  - batch id
  - origin device
  - text/event sample
  - complete targets
  - failed targets after success

## Per-batch Verdict

Goal: provide a compact round-level verdict per `batch_id`.

### Verdict rules

- `RELAYED`
  - batch has successful send completion and also has opaque store evidence
- `PARTIAL`
  - batch has both success and failure evidence
- `SUCCESS`
  - batch has successful send completion only
- `COLLECTED_ONLY`
  - batch has opaque store evidence but no successful completion
- `FAIL`
  - batch has failure evidence and no success
- `QUEUED`
  - batch only reached queued stages
- `SEEN`
  - batch was observed but not enough evidence for the states above

### Output columns

- batch id
- origin
- verdict
- devices seen
- complete targets
- failed targets
- reasons

## Notes

- Phase 1 is intentionally evidence-driven and log-only.
- It does not attempt full causal graph reconstruction.
- It should stay robust when logs are incomplete or truncated.

## Queue Health

Goal: distinguish true queue blockage from noisy retries.

### Input markers

- `queued_no_write_attempt_timeout`
- `BLE batch send failed`
- `APP dispatch job failed`
- `APP batch transport skipped`
- `BROADCAST_REJECT_DECISION`
- any `chunks=0/N`

### Output

- per-device counters:
  - queue timeout
  - zero-chunk failures
  - send failed
  - dispatch failed
  - transport skipped
  - broadcast rejected
- verdict:
  - `QUEUE_HEALTHY`
  - `QUEUED_NO_PROGRESS`
  - `ZERO_CHUNK_FAIL`
  - `NOISY`

## Transport Stability

Goal: summarize BLE service/runtime cleanliness apart from delivery results.

### Input markers

- `BLE service start command`
- `BLE service stop mesh`
- `BLE advertise started`
- `BLE advertise failed`
- `GATT disconnect reason`
- `GATT connect failed`
- `GATT connect backoff`
- `write_failed_status_1`
- `write_failed_status_133`

### Output

- per-device counters:
  - service start/stop
  - advertise ok/fail
  - disconnect/fail/backoff
  - write status 1 / 133
- verdict:
  - `SERVICE_STABLE`
  - `CONNECT_CHURN`
  - `WRITE_CALLBACK_FAILURE`

## Routing / Identity

Goal: detect identity rewrite noise and anti-loop drops.

### Input markers

- `identity_alias_added`
- `identity_canonicalized`
- `queued_target_rewritten`
- `stale_identity_suppressed`
- `reason=last_hop_unknown_drop`
- `reason=no_non_origin_target`
- `CANDIDATE_POOL_SNAPSHOT`
- `Queued BLE targets`

### Output

- per-device:
  - candidate peers
  - queued peers
  - identity rewrite counters
  - anti-loop drop counters
- verdict:
  - `IDENTITY_CLEAN`
  - `IDENTITY_REWRITE_NOISE`
  - `ANTI_LOOP_DROP`
