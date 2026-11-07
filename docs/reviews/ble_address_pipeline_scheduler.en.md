# BLE Address Pipeline Scheduler (Pre-Implementation Design)

## 1. Objective

Design a scheduler that removes BLE queue race conditions (especially `ConcurrentModificationException`) when multiple threads/callbacks touch the same `address` queue concurrently.

Core idea:
- Treat each `address` as one "product".
- Use one global "main conveyor" for round-robin scheduling.
- Process work by labels; after one label is handled, put the address back on the main conveyor for the next step.

---

## 2. Processing Labels

Labels in scope:
- `ENQUEUE`
- `WRITE_NEXT`
- `CONNECT`
- `CLEANUP`
- `RETRY`

An address may hold multiple pending labels at once, but only one label is executed per turn by priority.

---

## 3. Main Components

### 3.1 Main Conveyor

A global queue of addresses waiting for processing (round-robin).

### 3.2 Address State

Per-address state:
- `pendingLabels` (set of pending labels with dedupe semantics; no duplicate labels of the same type)
- `inMainQueue` (already present in main conveyor or not)
- `busy` (currently being processed or not)
- `connect_state`:
  - `0 = DISCONNECTED`
  - `1 = CONNECTING`
  - `2 = CONNECTED`
- `lastFailureReason`, `failureCount`, `nextConnectAllowedAt` (for backoff/diagnostics)
- existing data queue (`pendingByDevice[address]`)

### 3.3 Single Scheduler Thread

All scheduler state and queue mutations run on one serial thread/handler.

Critical rule:
- BLE/Nordic callbacks must not mutate queue state directly.
- Callbacks only call `postLabel(address, label)`.

### 3.4 Two-Stage Queue (UserOutbox -> TransportQueue)

To isolate UI/input from transport contention, use two queue layers:
- `UserOutbox` (producer-facing):
  - Written by user actions (chat send, map send, trail send).
  - Must be DB-backed (durable).
  - Must not mutate `pendingByDevice`.
- `TransportQueue` (`pendingByDevice[address]`, consumer-facing):
  - Owned only by scheduler thread.
  - Filled by scheduler via `ENQUEUE` from `UserOutbox` in bounded batches.

Rules:
1. UI thread only appends to `UserOutbox` and returns immediately.
2. Scheduler periodically drains `UserOutbox` (`N` items/batch, cooldown window) into `TransportQueue`.
3. ACK traffic may be staged separately but must follow the same ownership rule: no direct UI/callback write to `pendingByDevice`.
4. Delivery state machine remains unchanged (`pending -> sent -> delivered/seen`), but source of `ENQUEUE` becomes `UserOutbox` instead of direct UI push.

---

## 4. Required Invariants

1. One address must never have two workers at the same time.
2. Every access to `pendingByDevice[address]` must go through the serial scheduler path.
3. Address duplication in main conveyor must be bounded/controlled (`inMainQueue` guard).
4. Use small quanta (one label per turn) to guarantee fairness.
5. `UserOutbox` and `TransportQueue` are strictly separated; no cross-thread direct mutation of `TransportQueue`.

---

## 5. Label Priority

Recommended priority:
1. `CONNECT`
2. `WRITE_NEXT`
3. `ENQUEUE`
4. `RETRY`
5. `CLEANUP`

Rationale:
- `CONNECT` opens the transport path.
- `WRITE_NEXT` keeps queued data moving.
- `ENQUEUE`/`RETRY` feed work.
- `CLEANUP` is maintenance and should be lower priority.

---

## 5.1 connect_state Rules

Per-address connection lifecycle is tracked by one field:
- `connect_state=0` (DISCONNECTED)
- `connect_state=1` (CONNECTING)
- `connect_state=2` (CONNECTED)

State transition rules:
1. Start connect:
   - `0 -> 1`
2. Services discovered successfully:
   - `1 -> 2`
3. Connect timeout / status=133 / disconnect:
   - `1|2 -> 0`
4. Write failure:
   - Recoverable write error (link still alive): keep `2`, add `RETRY`
   - Link error (gatt failure/disconnect): `2 -> 0`, add `CONNECT`
5. `WRITE_NEXT` is only valid when `connect_state=2`; otherwise scheduler should prefer `CONNECT`.

### Detailed behavior when `connect_state=1` (CONNECTING)

While `CONNECTING`, the address is considered to own a connect slot:
1. Do not start another connect for the same address (no overlapping connect attempts).
2. `ENQUEUE` is still allowed: new packets are appended to queue.
3. `WRITE_NEXT` is not executed immediately: keep it pending until state becomes `CONNECTED`.
4. `CLEANUP` runs in timeout-check mode:
   - If still `1` after `CONNECT_TIMEOUT_MS`:
     - set `connect_state: 1 -> 0`
     - persist reason `connect_timeout`
     - schedule `RETRY/CONNECT` with backoff.
5. On success callback (`services discovered`):
   - `1 -> 2`, then prioritize `WRITE_NEXT`.
6. On failure callback (`status=133`, disconnect, hard failure):
   - `1 -> 0`, apply backoff, requeue `CONNECT`.
7. Backoff gate for `CONNECT`:
   - If `now < nextConnectAllowedAt`, do not execute `CONNECT` immediately.
   - Requeue after remaining delay to avoid connect storms.

---

## 6. One-Cycle Processing Flow

1. System receives an event (`postLabel(address, label)`).
2. Add label to `pendingLabels[address]`.
3. If `inMainQueue=false` and `busy=false`, enqueue address to main conveyor tail.
   - If main conveyor was empty before enqueue, trigger `drainLoop` immediately.
4. Worker pops one address from main conveyor head.
5. Pick highest-priority label from `pendingLabels[address]`.
6. Execute only that label.
7. After execution:
   - update next label/state if needed,
   - requeue address to main conveyor tail if there is remaining work,
   - otherwise set `inMainQueue=false`.

---

## 7. ASCII Block Diagram

```text
                   +----------------------------------+
                   |  Event Sources / Callbacks       |
                   |  - BLE callbacks                 |
                   |  - Retry timers                  |
                   |  - Cleanup tick                  |
                   +----------------+-----------------+
                                    |
                                    | postLabel(address, LABEL)
                                    v
                    +---------------+----------------+
                    |  Scheduler Input (serial)      |
                    |  - update pendingLabels         |
                    |  - enqueue address if needed    |
                    +---------------+----------------+
                                    |
                                    v
                        +-----------+-----------+
                        |   Main Conveyor       |
                        |  [addrA, addrB, ...]  |
                        +-----------+-----------+
                                    |
                                    v
                        +-----------+-----------+
                        |  Pick one address     |
                        |  busy=true            |
                        +-----------+-----------+
                                    |
                                    v
                    +---------------+----------------+
                    | Label Selector (priority)      |
                    | CONNECT > WRITE_NEXT > ...     |
                    +---------------+----------------+
                                    |
                                    v
                    +---------------+----------------+
                    | Execute exactly 1 label        |
                    | (atomic on scheduler thread)   |
                    +---------------+----------------+
                                    |
                      +-------------+-------------+
                      |                           |
                      v                           v
        +-------------+-------------+   +---------+----------------+
        | still has pending labels? |   | no pending labels        |
        +-------------+-------------+   +---------+----------------+
                      | yes                         | no
                      v                             v
        +-------------+-------------+   +---------+----------------+
        | requeue address at tail   |   | inMainQueue=false        |
        | busy=false                |   | busy=false               |
        +---------------------------+   +--------------------------+
```

### 7.1 Two-Stage Queue Diagram

```text
UI/Input Thread
   |
   | append event/message
   v
[UserOutbox (DB-backed)]
   |
   | scheduler drain (bounded batch, cooldown)
   v
[TransportQueue: pendingByDevice[address]]
   |
   | label-driven pipeline (CONNECT/WRITE_NEXT/RETRY/CLEANUP)
   v
BLE link
```

---

## 8. Per-Address State Machine

```text
         +---------+
         |  IDLE   |
         +----+----+
              |
              | postLabel
              v
      +-------+--------+
      |  QUEUED_MAIN   |
      +-------+--------+
              |
              | picked by scheduler
              v
      +-------+--------+
      |    BUSY        |
      +---+--------+---+
          |        |
          | more   | no more
          | work   | work
          v        v
   +------+----+  +------+
   | QUEUED_MAIN| | IDLE |
   +-----------+  +------+
```

Dedicated connection state machine:

```text
DISCONNECTED(0) --start CONNECT--> CONNECTING(1) --services OK--> CONNECTED(2)
CONNECTING(1) --timeout/133--> DISCONNECTED(0)
CONNECTED(2) --disconnect/link fail--> DISCONNECTED(0)
CONNECTED(2) --recoverable write fail--> CONNECTED(2) + LABEL(RETRY)
```

---

## 9. Pseudocode

```text
postLabel(address, label):
  state[address].pendingLabels.add(label)
  if !state[address].inMainQueue && !state[address].busy:
    wasEmpty = mainQueue.isEmpty()
    mainQueue.addLast(address)
    state[address].inMainQueue = true
    if wasEmpty:
      scheduleDrainLoop()

drainLoop():
  while mainQueue not empty:
    address = mainQueue.removeFirst()
    st = state[address]
    st.inMainQueue = false
    if st.busy: continue
    st.busy = true

    label = pickHighestPriority(st.pendingLabels)
    if label != null:
      if label == CONNECT && now() < st.nextConnectAllowedAt:
        mainQueue.addLast(address)
        st.inMainQueue = true
        st.busy = false
        continue
      st.pendingLabels.remove(label)
      execute(address, label)   // queue ops only here

    st.busy = false
    if st.pendingLabels not empty:
      mainQueue.addLast(address)
      st.inMainQueue = true
```

---

## 10. Benefits and Trade-offs

### Benefits
- Eliminates queue race conditions on `ArrayDeque`.
- More deterministic behavior.
- Better fairness across multiple addresses.

### Trade-offs
- Higher scheduler-state complexity.
- Strict discipline is required: no direct queue mutation outside scheduler path.

---

## 11. Pre-Merge Acceptance Criteria

1. No `ConcurrentModificationException` in stress runs.
2. No direct `pendingByDevice` mutations outside scheduler path.
3. No unbounded duplicate address entries in main conveyor.
4. TC7 retest is more stable (no crash, acceptable throughput).
5. Scheduler markers are available to trace:
   - label enqueue
   - label execution
   - requeue
   - drain idle
