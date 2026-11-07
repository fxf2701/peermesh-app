# Codex Prompt — Update Disaster App Transport KPI/SLO Specification

## Objective

Update the project specifications so that BLE/DTN transport quality is evaluated using **bounded delivery latency, delivery success, eventual convergence, and stability**, rather than an unrealistic universal real-time target.

This is a **documentation/specification task only**.

Do not modify production code.

---

## Project principle

The project prioritizes:

```text
Stability > latency optimization
Correctness > performance
Durability > aggressive retry shortcuts
Isolation > cross-layer coupling
Evidence-driven decisions > speculative fixes
```

The specification must make this principle explicit for future ChatGPT/Codex implementation and review work.

---

## Required KPI/SLO model

Add the following transport quality model to the appropriate project specification documents.

### Normal operating conditions

Define normal operating conditions as a state where:

- both devices have Bluetooth enabled;
- both devices are within practical BLE range;
- both apps are running;
- both devices are members of the same active group;
- there is no intentional network partition;
- no device is force-stopped;
- no OS-level Bluetooth failure prevents communication;
- temporary address rotation, reconnect, or normal GATT recovery may still occur.

### Partitioned or disrupted conditions

Define partitioned/disrupted conditions separately:

- one device is offline;
- Bluetooth is disabled;
- the app process is stopped;
- devices are out of range;
- the BLE route is temporarily unavailable;
- the event depends on a missing canonical/root event;
- delivery requires later DTN repair or peer re-encounter.

In disrupted conditions, immediate delivery is not required, but durable eventual convergence remains required.

---

## Core KPI targets

Add this table or an equivalent normative section:

| Metric | Target |
|---|---|
| Chat delivery success | 100% eventual delivery |
| Map event delivery success | 100% eventual delivery |
| Eventual convergence | 100% when a valid route eventually becomes available |
| Chat latency under normal conditions | At least 95% of events delivered within 60 seconds |
| Map canonical event latency under normal conditions | At least 95% delivered within 60 seconds |
| Map action latency under normal conditions | At least 95% delivered within 60 seconds once required canonical/root data is available |
| Maximum unexplained normal-condition delay | No event should remain unexplained beyond 5 minutes |
| Permanent data loss | 0 accepted |
| Protocol deadlock | 0 accepted |
| Unbounded queue growth | 0 accepted |
| Silent protocol-progress failure | 0 accepted |
| Crash/ANR caused by transport work | 0 accepted |
| Durable retry after temporary failure | Required |
| Recovery after disconnect/restart | Required |
| Final materialized state correctness | 100% |

---

## Latency interpretation

Document the following interpretation.

### Chat

```text
<10 seconds   = very good
10–30 seconds = good
30–60 seconds = acceptable
1–5 minutes   = degraded; acceptable only with clear disruption/recovery evidence
>5 minutes    = investigation required
```

### Marker/Area canonical events

```text
<60 seconds under normal conditions = acceptable
>5 minutes under normal conditions  = investigation required
```

### Dependent Map actions

Examples:

```text
MAP.ITEM.CONFIRM
MAP.ITEM.SITUATION.UPDATE
MAP.ITEM.SITUATION.STATUS
MAP.ITEM.ACKNOWLEDGE
```

Their latency must be measured from the point at which:

```text
the action is created
and
its required canonical/root event is available or becomes recoverable
```

If the canonical/root event is missing, report separately:

```text
action delivery latency
dependency repair latency
final convergence latency
```

Do not hide dependency-repair delay inside one undifferentiated number.

---

## Bounded latency requirement

Make clear that the key reliability property is not merely low average latency.

The required property is:

```text
bounded progress
```

The system must avoid this pattern:

```text
most events arrive quickly
but a minority stall for 15–25 minutes without clear reason
```

An implementation should not be considered stable merely because:

- average latency is low;
- later messages are fast;
- the event eventually arrives after an unexplained long delay.

A healthy implementation must provide:

```text
predictable progress
observable retry
bounded queue wait
observable protocol outcome
```

---

## Eventual consistency and DTN semantics

State explicitly:

- PeerMesh is not required to behave like an internet-backed instant messenger.
- BLE/DTN may require reconnect, re-encounter, inventory exchange, repair, replay, or retry.
- Eventual delivery is acceptable under disrupted conditions.
- Repeated advertisement is acceptable only when conservative correctness requires it and protocol progress remains observable.
- A transport flow must never silently stop after receiving a valid control packet.
- Every control lifecycle must produce either:
  - a valid next protocol response;
  - an explicit terminal outcome;
  - or an observable failure diagnostic.

---

## Stability-first decision rule

Add a normative implementation rule:

> Do not introduce a latency optimization when it increases coupling, queue contention, lifecycle races, data-loss risk, or protocol ambiguity unless evidence shows that the benefit clearly outweighs the stability risk.

Examples:

- Do not send a false empty WANT merely to advance a cursor.
- Do not commit inventory cursor state before protocol completion.
- Do not bypass durable defer/retry to make UI updates appear faster.
- Do not patch Map, Chat, or another feature separately when the defect is in a shared transport layer.
- Do not trade eventual correctness for near-real-time behavior.

---

## Required verification evidence

For each BLE/DTN milestone or transport fix, require evidence for:

### Automated verification

```text
unit tests
compile checks
focused transport tests
data persistence tests
app integration tests
whitespace/diff checks
```

### Real-device verification

At minimum record:

```text
event ID
batch ID
sender timestamp
receiver timestamp
render timestamp
delivery latency
retry count
session ID
peer stable ID
whether address rotation occurred
whether transport restart occurred
final state
```

### Protocol lifecycle evidence

For inventory/control flows:

```text
HELLO
INV_HAVE
INV_WANT
DATA
ACK
cursor commit
disconnect cleanup
```

Every stage must be observable or explicitly marked not reached.

---

## Acceptance classification

Add the following outcome vocabulary:

```text
PASS
PASS WITH ACCEPTED LIMITATION
DEGRADED
INCONCLUSIVE
FAIL
BLOCKED
```

Definitions:

- `PASS`: correctness, durability, bounded latency, and required evidence all satisfy the spec.
- `PASS WITH ACCEPTED LIMITATION`: correctness and convergence pass; a documented limitation is explicitly accepted and does not violate safety/stability.
- `DEGRADED`: delivery succeeds but normal-condition latency or retry behavior exceeds target.
- `INCONCLUSIVE`: test setup or logs do not prove the required lifecycle.
- `FAIL`: correctness, durability, bounded progress, or final state is wrong.
- `BLOCKED`: a shared transport defect prevents meaningful validation of the feature milestone.

---

## Recommended document locations

Inspect the repository and update the most authoritative existing documents rather than creating duplicate specifications.

Search for likely targets such as:

```text
docs/
docs/architecture/
docs/specs/
docs/transport/
docs/map_refactor/
docs/milestones/
README files related to BLE/DTN
project handoff documents
transport architecture specifications
testing/verification specifications
```

Prefer:

1. One authoritative transport KPI/SLO specification.
2. References from milestone roadmaps and handoff documents.
3. A concise decision record explaining the stability-first policy.

If no suitable document exists, create:

```text
docs/specs/ble_dtn_transport_kpi_slo.md
```

and link to it from the relevant transport roadmap/handoff documents.

---

## Required deliverables

Return:

```text
1. Exact files modified or created
2. Summary of the new KPI/SLO contract
3. Any existing specification statements that conflicted with the new standard
4. How those conflicts were resolved
5. Confirmation that no production code was changed
```

Also produce or update:

```text
docs/specs/ble_dtn_transport_kpi_slo.md
```

or the repository's existing authoritative equivalent.

---

## Constraints

Do not:

- modify Kotlin/Java production code;
- modify Room schema;
- modify BLE scheduler behavior;
- change retry policy;
- change cursor semantics;
- change suppression behavior;
- change tests unrelated to documentation validation;
- invent a hard real-time guarantee;
- declare `<2 seconds` as a universal requirement.

This task is complete only when future implementation/review work can clearly answer:

```text
Is delivery correct?
Is it durable?
Does it eventually converge?
Is latency bounded under normal conditions?
Is failure observable?
Does the implementation preserve stability?
```
