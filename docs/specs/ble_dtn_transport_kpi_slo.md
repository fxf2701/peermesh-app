# BLE/DTN Transport KPI and SLO Specification

Status: Normative

Applies to: Android BLE/DTN transport, Chat delivery, Map canonical events,
dependent Map actions, and transport milestone/review evidence

Authority: This document is the source of truth for transport quality and
acceptance. Feature documents may define stricter scenario-specific targets,
but must not weaken this contract or reinterpret it as a universal real-time
guarantee.

## 1. Purpose and engineering priority

PeerMesh transport quality is evaluated by delivery correctness, durability,
eventual convergence, bounded progress, and stability. A low average latency
alone is not sufficient.

The required priority order is:

```text
Stability > latency optimization
Correctness > performance
Durability > aggressive retry shortcuts
Isolation > cross-layer coupling
Evidence-driven decisions > speculative fixes
```

PeerMesh is a store-carry-forward system, not an internet-backed instant
messenger. BLE/DTN delivery may require reconnect, peer re-encounter,
inventory exchange, repair, replay, or retry.

## 2. Operating-condition classes

### 2.1 Normal operating conditions

A test is in normal operating conditions only while all of these are true:

- Bluetooth is enabled on both devices.
- The devices are within practical BLE range.
- Both apps are running.
- Both devices belong to the same active group.
- There is no intentional network partition.
- Neither app is force-stopped.
- No OS-level Bluetooth failure prevents communication.

Temporary address rotation, reconnect, and ordinary GATT recovery may occur
without changing the classification. Record those events in the evidence.

### 2.2 Partitioned or disrupted conditions

A test interval is partitioned or disrupted when any of these applies:

- A device is offline, Bluetooth is disabled, an app process is stopped, or
  devices are out of range.
- The BLE route is temporarily unavailable.
- An event depends on a missing canonical/root event.
- Delivery requires later DTN repair or a peer re-encounter.

Immediate delivery is not required during such an interval. Durable retry and
eventual convergence remain required when a valid route eventually becomes
available. Reports must identify the disruption interval rather than charging
it silently to normal-condition latency.

## 3. Normative KPI/SLO targets

| Metric | Target |
| --- | --- |
| Chat delivery success | 100% eventual delivery |
| Map event delivery success | 100% eventual delivery |
| Eventual convergence | 100% when a valid route eventually becomes available |
| Chat latency under normal conditions | At least 95% of events delivered within 60 seconds |
| Map canonical event latency under normal conditions | At least 95% delivered within 60 seconds |
| Map action latency under normal conditions | At least 95% delivered within 60 seconds once required canonical/root data is available |
| Maximum unexplained normal-condition delay | No event remains unexplained beyond 5 minutes |
| Permanent data loss | 0 accepted |
| Protocol deadlock | 0 accepted |
| Unbounded queue growth | 0 accepted |
| Silent protocol-progress failure | 0 accepted |
| Crash/ANR caused by transport work | 0 accepted |
| Durable retry after temporary failure | Required |
| Recovery after disconnect/restart | Required |
| Final materialized state correctness | 100% |

Percentile targets are evaluated over a declared test population and interval.
The zero-tolerance rows are correctness and stability invariants, not
percentile budgets.

### 3.1 Delivery-eligible durable records

Transport TTL, hop limits, and bounded forwarding caches may stop a particular
copy from being forwarded. They do not authorize deleting the last durable,
repairable copy of an accepted Chat or Map event before required peers can
converge. Eviction is valid only when another authoritative durable source
remains available or an explicit product retention policy has made the event
ineligible before the test population is declared.

Test reports must declare any retention/expiry policy up front. A transport
implementation must not relabel an unexpected eviction, queue drop, or retry
abandonment as intentional expiry to avoid the permanent-loss invariant.

## 4. Latency measurement and interpretation

Delivery latency is measured from the sender event timestamp to durable
receipt on the receiver. Render latency is measured separately from the same
sender timestamp to the receiver render timestamp. Clock synchronization or
the measured clock offset must be recorded for multi-device results.

### 4.1 Chat

```text
<10 seconds   = very good
10-30 seconds = good
30-60 seconds = acceptable
1-5 minutes   = degraded; acceptable only with clear disruption/recovery evidence
>5 minutes    = investigation required
```

### 4.2 Marker and Area canonical events

```text
<60 seconds under normal conditions = acceptable
>5 minutes under normal conditions  = investigation required
```

### 4.3 Dependent Map actions

This rule applies to actions including:

```text
MAP.ITEM.CONFIRM
MAP.ITEM.SITUATION.UPDATE
MAP.ITEM.SITUATION.STATUS
MAP.ITEM.ACKNOWLEDGE
```

Measure action delivery from the later of:

1. the time the action is created; and
2. the time its required canonical/root event is locally available or becomes
   recoverable through a valid route.

If the canonical/root event is missing, report these as distinct values:

- action delivery latency;
- dependency repair latency; and
- final convergence latency.

Dependency-repair delay must not be hidden inside one undifferentiated
latency value. The final state is not correct until the canonical item and its
eligible dependent actions have materialized consistently.

## 5. Bounded progress

The central reliability property is **bounded progress**, not merely a low
mean or median. An implementation is not stable when most events arrive
quickly but a minority stall for 15-25 minutes without an identified cause.
Fast later messages and eventual arrival do not excuse an unexplained stall.

A healthy implementation provides:

- predictable protocol progress;
- observable retry;
- bounded queue wait; and
- an observable protocol outcome.

Any normal-condition event not delivered within five minutes requires an
investigation that identifies its queue, session, retry, dependency, and
terminal state. Until that evidence exists, the run cannot be classified
`PASS`.

## 6. DTN lifecycle and observability contract

Eventual delivery is acceptable under disrupted conditions. Repeated
advertisement is acceptable only when conservative correctness requires it
and progress remains observable.

A transport flow must never silently stop after a valid control packet is
received. Every control lifecycle must produce one of:

- a valid next protocol response;
- an explicit terminal outcome; or
- an observable failure diagnostic.

For inventory/control flows, evidence must account for every applicable stage:

```text
HELLO
INV_HAVE
INV_WANT
DATA
ACK
cursor commit
disconnect cleanup
```

Each stage must be observable or explicitly marked `not reached`, with the
reason. Cursor commit is a protocol-completion outcome, not a substitute for
it.

## 7. Stability-first decision record

Decision: Do not introduce a latency optimization when it increases coupling,
queue contention, lifecycle races, data-loss risk, or protocol ambiguity
unless evidence shows that the benefit clearly outweighs the stability risk.

Consequences:

- Do not send a false empty `WANT` merely to advance a cursor.
- Do not commit inventory cursor state before protocol completion.
- Do not bypass durable defer/retry to make UI updates appear faster.
- Do not patch Map, Chat, or another feature separately when the defect is in
  the shared transport layer.
- Do not trade eventual correctness for near-real-time behavior.

This decision deliberately rejects a universal `<2 seconds` target. A feature
may set a stricter target for a controlled scenario, but failure to meet that
feature target must not motivate a shortcut that violates this contract.

## 8. Required verification evidence

Every BLE/DTN milestone or transport fix must include the relevant automated
verification:

- unit tests;
- compile checks;
- focused transport tests;
- data persistence tests;
- app integration tests; and
- whitespace/diff checks.

Real-device evidence must record at least:

| Field | Required value |
| --- | --- |
| Event identity | event ID and batch ID |
| Timing | sender, receiver, and render timestamps; delivery latency |
| Retry | retry count |
| Session/peer | session ID and peer stable ID |
| Churn | whether address rotation or transport restart occurred |
| Outcome | durable receipt, render/materialization, and final state |

Also record build/commit, devices and OS versions, group/test scenario,
operating-condition classification, relevant configuration, and clock offset.
Logs must show the lifecycle from Section 6 when an inventory/control flow is
under test.

## 9. Acceptance classification

Use exactly one primary outcome:

| Outcome | Definition |
| --- | --- |
| `PASS` | Correctness, durability, bounded latency, and required evidence satisfy this specification. |
| `PASS WITH ACCEPTED LIMITATION` | Correctness and convergence pass; a documented limitation is explicitly accepted and does not violate safety or stability. |
| `DEGRADED` | Delivery succeeds, but normal-condition latency or retry behavior exceeds the target. |
| `INCONCLUSIVE` | The setup or evidence does not prove the required lifecycle. |
| `FAIL` | Correctness, durability, bounded progress, or final state is wrong. |
| `BLOCKED` | A shared transport defect prevents meaningful validation of the feature milestone. |

`PASS WITH ACCEPTED LIMITATION` cannot be used for permanent loss, deadlock,
silent progress failure, unbounded growth, transport crash/ANR, or incorrect
final state. A disrupted run that eventually converges may pass only when the
disruption and recovery lifecycle are fully evidenced; otherwise it is
`INCONCLUSIVE` or `DEGRADED` as applicable.

## 10. Review questions

Implementation and review work is complete only when the evidence answers:

```text
Is delivery correct?
Is it durable?
Does it eventually converge?
Is latency bounded under normal conditions?
Is failure observable?
Does the implementation preserve stability?
```

## 11. Precedence and resolved legacy interpretations

This specification refines older DTN wording as follows:

- “Stored until TTL expired, max hops exceeded, or memory limit reached”
  governs forwarding copies and eligibility, not deletion of the last durable
  repair source for an accepted delivery-eligible Chat or Map event.
- “No global consistency guarantee” means no strong or instantaneous global
  consistency while routes are absent. It does not waive required eventual
  convergence after a valid route becomes available.
- Scenario-specific fast-path or real-time goals are additional measurements,
  not universal transport acceptance gates and not permission to weaken
  durability or stability.
