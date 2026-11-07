# Codex Investigation Prompt — Why Does DTN Re-advertise 160 Historical IDs?

## Context

We are investigating BLE/DTN runtime stability in the PeerMesh Disaster App.

A chat-only real-device test showed that Device A sent a large generic DTN inventory packet even though the user did not create or update any Map item during the test:

```text
DTN inv_have recv
gid=H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q
peer_id=F6C01431201D82C6
session_id=F6C01431201D-1785566879970-71adf6d9
ids=160
data=0
bytes=7584
chunks=45
```

The 160 IDs have now been reconstructed in:

```text
candidate_inv_have_160.csv
```

Verified breakdown from that CSV:

```text
Category:
- SYSTEM: 73
- MAP_CANONICAL: 40
- MAP_ACTION: 37
- CHAT: 10

Top event types:
- SYS.USER: 37
- SYS.GROUP: 36
- MAP.MARKER.ADD: 27
- MAP.ITEM.CONFIRM: 19
- MAP.AREA.ADD: 9
- MAP.ITEM.SITUATION.UPDATE: 8
- MAP.ITEM.SITUATION.STATUS: 8
- CHAT.MSG: 7
- CHAT.REPLY: 3
- MAP.MARKER.UPD: 2
- MAP.ITEM.ACKNOWLEDGE: 2
- MAP.MARKER.DEL: 2

Inventory metadata:
- inventory_tier=normal for all 160
- suppressed_for_peer=0 for all 160
- source=mesh: 115
- source=local: 29
- source=dtn: 16
- minimum age at build: 5,595 seconds
- median age at build: 153,597 seconds
- maximum age at build: 321,891 seconds
```

This means the packet was mostly durable history, not recent Map activity.

The current concern is not merely that one inventory packet is large. The key question is:

> Why were these same or similar historical IDs still eligible to be advertised to the same stable peer after repeated prior BLE sessions?

This task is an investigation only. Do not implement a transport fix yet.

## Project principle

Stability is the highest priority.

Do not propose or implement an optimization that risks:

- data loss,
- missed recovery,
- broken eventual consistency,
- incorrect per-peer state,
- increased coupling,
- fragile session lifecycle behavior.

First establish the actual contract and root cause with evidence.

## Inputs

Use the current Android source snapshot as the source of truth.

Required evidence file:

```text
candidate_inv_have_160.csv
```

Known group:

```text
H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q
```

Known stable peer/device ID involved in the session:

```text
F6C01431201D82C6
```

Known session:

```text
F6C01431201D-1785566879970-71adf6d9
```

Approximate packet time:

```text
2026-08-01 13:48:29–13:48:39 local device time
```

## Primary questions

Answer these questions before proposing any change:

1. Is repeatedly advertising historical IDs to the same peer intentional protocol behavior?
2. Is `INV_HAVE` designed as:
   - full-window anti-entropy,
   - cursor-based incremental inventory,
   - per-peer delta inventory,
   - or a hybrid?
3. What exactly makes an event eligible for another `INV_HAVE` after it was previously advertised?
4. What state records that the peer:
   - was merely told about an ID,
   - requested an ID,
   - received DATA,
   - ACKed DATA,
   - or is otherwise known to possess the event?
5. Does `repairSuppressionStore.filterPeerSatisfied(...)` apply only to dependency repair, or also to generic inventory?
6. Why did all 160 reconstructed rows have:

```text
suppressed_for_peer=0
```

7. Is suppression state:
   - not written,
   - written under another peer identity,
   - reset per session,
   - expired,
   - lost across process restart,
   - bypassed for normal-tier inventory,
   - or working as designed?
8. Does rotating BLE address or changing DTN session ID cause the same stable peer to be treated as a new peer?
9. Is cursor progress persisted:
   - globally,
   - per group,
   - per stable peer,
   - per session,
   - or not at all?
10. Can a reconnect/session restart cause inventory traversal to restart from the beginning?
11. Can the same 160 IDs be advertised repeatedly even after the receiver already has all of them?
12. If yes, what prevents repeated large inventory exchange from starving APP traffic such as `CHAT.MSG`?
13. Was the 160-ID packet one envelope or one window in a multi-window traversal?
14. Why did the log show a nominal `45`-chunk packet while internal progress/queue geometry continued beyond 45 in some traces?
15. Could duplicate/stale queue bookkeeping make one envelope occupy the lane longer than its encoded packet size implies?

## Source audit scope

Inspect all relevant production code and tests. At minimum:

```text
data/src/main/java/app/peermesh/data/dtn/
transport/src/main/java/app/peermesh/transport/ble/dtn/
transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt
app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt
```

Search specifically for:

```text
buildHaveWindow
listInventoryAfter
loadDtnCursorWindow
filterPeerSatisfied
markPeerSatisfied
peerSatisfied
advertised
DtnSyncCursor
DtnRepairSuppression
INV_HAVE
onHello
onHave
sessionId
peerId
stable deviceId
cursor
inventory limit
scanLimit
normal tier
sortForTransfer
```

Also inspect Room entities/DAOs/tables related to:

```text
peer state
inventory cursors
advertised IDs
received IDs
ACK state
repair suppression
session state
```

Do not infer behavior from names. Trace actual reads and writes.

## Investigation task A — Document the protocol contract

Create a precise state-machine description for generic inventory.

At minimum cover:

```text
peer discovered
→ HELLO built/sent
→ session created
→ inventory cursor selected
→ INV_HAVE built
→ peer receives INV_HAVE
→ peer compares IDs
→ INV_WANT built
→ DATA sent
→ DATA ingested
→ ACK sent/received
→ peer knowledge/suppression/cursor updated
→ reconnect/new session
```

For each transition state:

- file and function,
- input identity used,
- persisted state changed,
- in-memory state changed,
- whether it survives process restart,
- whether it is keyed by stable peer ID or session/address.

Clearly distinguish:

```text
advertised
requested
served
received
ACKed
known-to-have
suppressed
cursor advanced
```

These concepts must not be treated as equivalent unless code proves they are.

## Investigation task B — Explain why these 160 IDs were eligible

Using `candidate_inv_have_160.csv`, select representative examples from each category:

```text
SYSTEM
MAP_CANONICAL
MAP_ACTION
CHAT
```

For at least 3 rows per category, trace:

1. Why the event was included by `listInventoryAfter`.
2. Its tier classification.
3. Its transfer priority.
4. Its position after sorting.
5. Whether it matched any peer-satisfied/suppression state.
6. Whether it had previously been:
   - advertised,
   - requested,
   - delivered,
   - ACKed.
7. Why it was still eligible at 13:48.

Produce an evidence table:

```text
event_id
type
age
source
tier
priority
previously_advertised?
previously_requested?
previously_served?
previously_acked?
peer_known_to_have?
cursor_relation
reason_included
```

If historical state is not recoverable, say exactly which fields cannot be proven.

## Investigation task C — Audit stable identity correctness

Trace the identities used through the complete lifecycle:

```text
BLE address
stable deviceId
HELLO peerId
DTN packet peer_id
session_id
suppression-store peer key
cursor-store peer key
ACK peer key
```

Build an identity mapping from the real test:

```text
Device A stable ID
Device B stable ID
rotating addresses observed
HELLO peer IDs
session IDs
DB peer-state keys
```

Determine whether any state is incorrectly keyed by:

- rotating MAC address,
- local session ID,
- truncated peer ID,
- sender/receiver direction mismatch,
- a peer ID different from the stable device ID.

Pay special attention to this possibility:

```text
peer receives/ACKs data under identity X
next reconnect builds inventory for identity Y
→ previous peer knowledge is not found
→ full historical inventory is advertised again
```

## Investigation task D — Audit cursor behavior

For the known session, determine:

- initial cursor,
- cursor after the 160-ID packet,
- whether cursor was persisted,
- persistence key,
- expiry/reset conditions,
- behavior after address rotation,
- behavior after transport restart,
- behavior after process restart,
- behavior after a new HELLO.

Answer whether this flow is possible:

```text
session S1 sends first 160 IDs
→ connection rotates/restarts
→ session S2 starts cursor from null
→ same first 160 IDs sent again
```

If possible, identify the exact code path.

Also establish whether cursor progression is supposed to prevent duplicate advertisement or merely paginate within one session.

## Investigation task E — Audit suppression semantics

Inspect `repairSuppressionStore.filterPeerSatisfied(...)` and all state writes feeding it.

Answer:

1. What does “satisfied” mean?
2. Which event causes it to be marked?
3. Is it marked when:
   - INV_HAVE is sent,
   - INV_WANT is received,
   - DATA is sent,
   - ACK is received,
   - or another condition?
4. Does it apply to:
   - generic inventory,
   - dependency repair,
   - both?
5. How long does it last?
6. Does it survive restart?
7. Is it cleared by new session/address rotation?
8. Why were all 160 candidates unsuppressed?

Query Device A and Device B DBs for relevant suppression rows and include raw results.

## Investigation task F — Determine repeated-advertisement frequency

Use available Activity Logs and DB/session state to identify repeated inventories around the test.

Search for every:

```text
DTN inv_have sent
DTN inv_have recv
```

Record:

```text
timestamp
sender
receiver
stable peer ID
session ID
ID count
encoded bytes
chunk count
cursor/window if logged
```

Where full IDs are unavailable, compare packet fingerprints if possible:

- encoded payload hash,
- ordered-ID hash,
- first/last event ID,
- inventory cursor,
- count/type distribution.

If diagnostics do not currently expose fingerprints, state that. Do not modify production code in this task.

Classify repetition as:

```text
same exact ordered list
same set, different order
overlapping window
different window
unknown
```

## Investigation task G — Analyze BLE-lane impact

Using the current scheduler/order logic, determine whether generic `INV_HAVE` can delay APP batches.

Trace priority/order in `BleMeshTransport`, including:

```text
active DTN control envelope
pending DTN control
APP batch
ACK
DATA
HELLO
INV_HAVE
INV_WANT
```

For the observed 7,584-byte/45-chunk inventory:

- estimate expected healthy transmission time from actual logs;
- measure actual lane occupation time;
- identify address rotations, cooldowns, stalls or restarts during the envelope;
- determine whether APP dispatch was skipped as `queued_or_held` while inventory was active;
- establish whether the chat message missing on Device B overlapped this control work.

Do not conclude causality unless timestamps support it.

Produce a timeline combining:

```text
DTN control start/end
APP CHAT.MSG creation
APP batch claim/emit
GATT writes
address rotation
transport restart
peer receive/render
```

## Investigation task H — Compare M1 and current behavior

Compare the M1 snapshot and current source specifically for inventory behavior.

Determine whether M1 differed in:

- inventory limit,
- cursor persistence,
- peer suppression,
- HELLO-triggered inventory,
- number of inventory windows per session,
- control queue cap,
- active-envelope atomicity,
- ordering relative to APP batches,
- address-rotation handling,
- periodic/session retry behavior.

Do not report broad MapActivity refactor differences. Focus only on changes that affect generic DTN inventory or BLE lane scheduling.

Create a concise diff table:

```text
behavior
M1
current
risk/effect
evidence
```

## Required conclusions

The report must select one primary classification:

### Classification 1 — Intentional full anti-entropy

Repeated historical inventory is expected by design.

Then explain:

- why it is safe,
- why it is necessary,
- how APP starvation is prevented,
- whether current real-device behavior still satisfies that design.

### Classification 2 — Cursor/session reset defect

The same inventory window is resent because cursor progress is lost/reset.

Identify exact root cause and reproduction conditions.

### Classification 3 — Peer-identity/suppression defect

The receiver's known possession is not recognized across sessions.

Identify exact identity mismatch or missing state update.

### Classification 4 — Queue duplication/lifecycle defect

The apparent backlog is duplicated queue/chunk bookkeeping rather than legitimate new inventory.

Identify the exact enqueue/rebind/cleanup path.

### Classification 5 — Mixed cause

More than one mechanism contributes.

Rank each cause by evidence strength.

Do not force a single cause if evidence supports a mixed explanation.

## No-fix rule

Do not modify production code.

Do not:

- reduce inventory limits,
- disable `INV_HAVE`,
- change scheduler priority,
- remove atomic envelope behavior,
- change suppression TTL,
- change cursor behavior,
- add feature flags.

This phase is evidence gathering only.

At the end, provide possible fix directions only as risk-ranked options, without code.

## Deliverables

Create:

```text
docs/diagnostics/dtn_historical_inventory_investigation.md
docs/diagnostics/dtn_inventory_state_machine.md
docs/diagnostics/dtn_inventory_m1_vs_current.md
artifacts/dtn_inventory_event_trace.csv
artifacts/dtn_inventory_sessions.csv
artifacts/dtn_peer_state_dump.txt
artifacts/dtn_cursor_state_dump.txt
artifacts/dtn_suppression_state_dump.txt
```

The main report must contain:

### 1. Executive verdict

One of:

```text
INTENTIONAL BEHAVIOR
CURSOR RESET DEFECT
PEER IDENTITY/SUPPRESSION DEFECT
QUEUE LIFECYCLE DEFECT
MIXED CAUSE
INCONCLUSIVE
```

### 2. Evidence confidence

For each conclusion:

```text
HIGH
MEDIUM
LOW
```

### 3. Exact root-cause chain

Example format:

```text
new BLE session
→ cursor key misses due to ...
→ buildHaveWindow starts from ...
→ 160 historical IDs selected
→ suppression lookup returns none because ...
→ large INV_HAVE queued
→ atomic control envelope occupies lane
→ APP batch waits/skips
```

Only include steps proven by code/log/DB evidence.

### 4. Stability assessment

Answer:

- Is current behavior safe for data correctness?
- Is current behavior stable under address rotation?
- Can it create unbounded or very long APP latency?
- Is there permanent loss risk or primarily starvation?
- Is immediate remediation required before closing M3E12/T8-V2?

### 5. Recommended next step

Choose exactly one:

```text
No code change; continue T8-V2
Add diagnostic-only patch
Implement narrowly scoped correctness fix
Open separate BLE inventory reliability milestone
Rollback a specific recent behavior
```

Explain why this is the lowest-risk next action.

## Final response summary

Return a concise summary containing:

```text
primary classification
root cause
whether the 160 IDs were expected
whether they repeat across sessions
whether peer suppression/cursor is working
whether APP chat was blocked by inventory
M1-vs-current regression point
recommended next step
```
