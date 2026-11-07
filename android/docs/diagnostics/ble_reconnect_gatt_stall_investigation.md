# BR1 — BLE Reconnect / GATT Stall Investigation

**Milestone:** BLE Runtime Reliability / BR1  
**Mode:** Investigation only  
**Production changes:** None  
**Baseline reviewed:** `android(32).zip`  
**Primary verdict:** `INCONCLUSIVE`  
**Confidence:** HIGH that the current evidence is insufficient for a final causal verdict; MEDIUM for the source-level failure model below.

## 1. Executive finding

The baseline source proves that `Nearby = 1` is a discovery-presence signal, not a route-readiness signal. A peer can therefore remain visible while every usable-route prerequisite is absent.

The strongest source-supported failure model is:

```text
new advertisement for stable peer
→ address ownership rotates
→ old address lane may intentionally remain alive while considered drainable
→ new address is scheduled for CONNECT
→ candidate selection may still resolve to an address whose address-keyed state is CONNECTING/CONNECTED
→ CONNECT is skipped as state_busy_connecting_or_connected
→ old attempt remains until callback or 18 s timeout cleanup
→ callback/cleanup post RETRY and CLEANUP independently
→ status 133 adds device quiet and exponential backoff
→ foreground watchdog restarts the transport after a 45 s progress threshold + 6 s confirmation
```

This is consistent with a **mixed stale-lane / address-keyed lifecycle / recovery-composition risk**. However, BR1 cannot lock one of the allowed final verdicts without the raw real-device logs, attempt identity, GATT object identity and pending-work snapshots required by the BR1 specification.

## 2. Evidence boundary

Reviewed:

- all four normative/closure documents supplied for BR1;
- complete baseline Android source;
- `BleMeshTransport`;
- `BleMeshRuntime`;
- `BleForegroundService`;
- BLE client abstraction and callbacks;
- queue, cleanup, retry, rotation and watchdog paths;
- existing unit tests in the baseline.

Not supplied in this investigation package:

- raw final M3E12 Activity Logs;
- earlier chat-only and Map-repair logs;
- device database snapshots;
- per-attempt BluetoothGatt object identity;
- exact timestamps correlating advertisement, selection, connect, cleanup and restart.

Therefore conclusions below distinguish:

- **PROVEN FROM SOURCE**
- **SUPPORTED BY CLOSURE SUMMARY**
- **UNPROVEN / REQUIRES RAW EVIDENCE**

## 3. Answers to BR1 questions

### 3.1 Why can Nearby = 1 while no usable route exists?

**Conclusion: PROVEN FROM SOURCE — HIGH confidence.**

`nearbyDeviceCount()` is used by the service as a presence input. The service stall detector separately requires Nearby > 0, pending data and no recent progress. This means the architecture explicitly permits Nearby to be positive before GATT readiness.

The actual outbound route is not usable until:

```text
connectGatt callback STATE_CONNECTED
→ service discovery succeeds
→ target characteristic exists
→ connect state changes to CONNECTED
→ write pump starts
→ writeCharacteristic returns success
```

MTU success improves readiness but is not universally mandatory for small packets; large first data may be held until MTU is known.

Therefore the UI signal is a **false route-readiness proxy**, although it is not false as a discovery signal.

### 3.2 Is stale address state possible?

**Conclusion: PROVEN AS A REPRESENTABLE STATE — HIGH confidence.**

Address rotation updates the stable peer’s latest/target address, but deliberately preserves the previous lane when `hasActiveDrainPathForAddress(previousAddress)` is true. Queued work is migrated only when the old lane is not considered busy.

This creates a valid transition window with:

```text
stable peer → new latest address
old address → still owns active packet/write/connect state
new address → selected for future work
```

Whether the observed stalls were caused by this window is not proven without raw logs.

### 3.3 Is stale GATT connection state possible?

**Conclusion: SOURCE RISK — MEDIUM confidence.**

Connection state and ownership maps are keyed by address:

- `addressStateByAddress`
- `clientByDevice`
- `connectingClient`
- `connecting`
- `connectStartedAtMs`
- `characteristicByDevice`
- `activePendingPacketByAddress`

Callbacks receive a `BleClientConnection`, but do not first verify that this exact object is still the current owner in `clientByDevice[address]` or `connectingClient[address]`. A late callback from an older connection for a reused address can therefore mutate address-keyed state, enqueue retry/cleanup, or replace/remove current ownership.

No attempt generation or connection-generation token is present in the reviewed callback path.

This is a race **possibility**, not a proven occurrence.

### 3.4 How long can `state_busy_connecting_or_connected` retain the wrong state?

**Conclusion: BOUNDED IN THE COMMON CONNECTING PATH, NOT STRICTLY BOUNDED FOR ALL CALLBACK RACES — MEDIUM confidence.**

For a normal connecting attempt:

- timeout: 18 s;
- cleanup loop cadence: 5 s;
- effective timeout detection: roughly 18–23 s after connect start;
- then retry backoff and quiet/cooldown may apply.

For `CONNECTED` state, cleanup depends on service readiness, write progress, disconnect callback, stalled-write detection or hard restart. A late callback can also re-mutate address-keyed state after cleanup because no generation guard is visible.

Thus the common path is timer-bounded, but the baseline does not prove a strict upper bound for every stale-callback ordering.

### 3.5 How is status 133 recovered?

**Conclusion: PROVEN FROM SOURCE — HIGH confidence.**

On connection callback status 133:

1. record disconnect diagnostic;
2. apply 20 s device quiet;
3. set address state DISCONNECTED;
4. suppress duplicate 133 retry within 1.5 s when applicable;
5. otherwise mark item backoff;
6. enqueue RETRY;
7. enqueue FORCE CLEANUP;
8. `recordConnectFailure()` applies exponential delay:
   - base 2 s;
   - doubles by failure count;
   - capped at 30 s;
   - up to 0.5 s jitter;
   - after three failures at least 30 s;
9. failure handling may rotate to another known address;
10. the service-level transport watchdog can hard-restart after a persistent stall.

The design treats 133 as recoverable and conservative, not ignorable.

### 3.6 Which GATT instance does hard reset close? Is there a race?

**Conclusion: INSTANCE SET PROVEN; EXACT OWNERSHIP NOT PROVEN — MEDIUM confidence.**

Transport-level `maybeHardReset()` calls `closeAllGatt()`.

`closeAllGatt()` closes:

- every `BleClientConnection` currently present in `clientByDevice`;
- every connection currently present in `connectingClient`.

It does not track a unique attempt generation. Callback events already queued by Android may arrive after disconnect/close and are still processed by address. Therefore a late-callback race is structurally possible.

The source does not prove whether every historical/orphaned Android `BluetoothGatt` object is still represented in those two maps at reset time.

### 3.7 Are queue, active envelope or pending packet lost on restart?

**Conclusion: DURABLE EVENT LOSS NOT INDICATED; IN-MEMORY PACKETS ARE CLEARED — MEDIUM confidence.**

Normal cleanup uses `closeQueue = false` and preserves pending/staged/held queues. Interrupted active packets are failed through the send-state path before connection removal.

A hard restart performs:

```text
stop()
→ failAllInFlightPackets(transport_stop)
→ close all GATT
→ clearRuntimeState(skipInFlightFailure = true)
→ clear pending/staged/held and address state
→ start()
```

Therefore transport in-memory packets are intentionally discarded during hard restart. Safety depends on the durable dispatcher/retry source reissuing unfinished work. Closure evidence says pending durable events eventually converged, but this package does not contain database rows proving exact-once rebind or proving every active envelope was rehydrated.

No packet should be considered delivered merely because it was written: delivery completion must be tied to the existing send/ACK/durable-dispatch contract. A raw pending-work trace is still required.

### 3.8 What composes the recovery upper bound?

**Conclusion: MULTI-LAYER COMPOSITION — HIGH confidence.**

It is not only:

```text
timeout + cooldown + backoff + restart
```

The source adds:

- cleanup-loop quantization;
- 5 s disconnect quiet;
- 20 s post-133 quiet;
- 12 s old-address rotation cooldown;
- 4 s device rotation cooldown;
- selection cooldown/dwell/debounce;
- 8 s item backoff;
- exponential connect backoff up to 30 s;
- scan-stale gate;
- GATT-cap gate;
- single-target penalty probe window;
- old-lane drain preservation;
- 45 s service warmup;
- 45 s no-progress threshold;
- 6 s restart confirmation;
- 30 s service recovery cooldown.

A representative first-attempt timeout path is approximately:

```text
18–23 s timeout detection
+ 2–2.5 s first retry backoff
```

A status-133 path can be approximately:

```text
20 s quiet
+ 2–30.5 s backoff
```

A service hard restart becomes eligible no earlier than roughly:

```text
45 s transport warmup/progress age
+ 6 s confirmation
≈ 51 s
```

Repeated failures, recovery cooldown and fresh progress timestamps can extend the observed interval. A strict end-to-end upper bound is not derivable without execution traces.

## 4. Root-cause assessment

### Final BR1 verdict

```text
INCONCLUSIVE
Confidence: HIGH
```

Reason: the baseline supports multiple interacting mechanisms, but the required attempt-level evidence is absent. Selecting `CONNECT/CLEANUP RACE`, `STALE CONNECTION STATE`, or `MIXED CAUSE` as a final verdict would overstate the evidence.

### Most likely source-level hypothesis

```text
MIXED CAUSE candidate
Confidence: MEDIUM
```

Components:

1. **FALSE NEARBY / ROUTE-READINESS SIGNAL** — proven architectural mismatch.
2. **STALE ADDRESS LANE** — representable and intentionally preserved during rotation.
3. **CONNECT/CLEANUP CALLBACK OWNERSHIP RISK** — no connection generation check.
4. **COOLDOWN/BACKOFF COMPOSITION** — proven to extend recovery.
5. **STATUS_133 platform failure** — observed by closure evidence and explicitly handled.

## 5. Risk

### Reliability risk — HIGH

A visible peer with durable pending work can remain unable to establish a usable route long enough to violate the 60 s target and approach/exceed the five-minute investigation threshold under repeated failures.

### Data-loss risk — LOW to MEDIUM, not closed

Durable convergence was observed in M3E12, and normal cleanup preserves queues. Hard restart clears in-memory transport queues, so safety depends on durable re-dispatch. Raw database/log evidence is needed to reduce this to LOW conclusively.

### Race risk — MEDIUM

Late callbacks are address-scoped rather than attempt-scoped. An old connection can theoretically mutate state for a newer connection reusing the same address.

## 6. Smallest safe fix candidate — DOCUMENT ONLY

Do not implement in BR1.

The smallest stability-first correctness change to evaluate in BR2 is:

```text
Introduce a monotonically increasing connection-attempt generation per address.
Bind:
- connecting connection;
- connected connection;
- connectStartedAt;
- callback handling;
- cleanup request;
- retry request;
- characteristic ownership
to (address, generation).

Ignore or close callbacks whose connection/generation is no longer the current owner.
```

This is narrower than changing retry, timeout, cooldown or scheduler policy. It targets ownership correctness without increasing retry rate or weakening durability.

A separate UI semantics change may later distinguish:

```text
discovered peer
connecting peer
usable route
```

That should not be combined with the lifecycle fix unless evidence requires it.

## 7. Evidence required to close BR1

Capture at least one failing and one healthy run containing:

- stable peer ID;
- old/new address;
- attempt generation or BluetoothGatt identity hash;
- selected address;
- current owner connection identity;
- connect state;
- callback connection identity;
- cleanup target identity;
- pending/staged/held counts;
- active packet and envelope IDs;
- restart boundary;
- durable dispatcher state before and after restart;
- first write, first receive and usable-route timestamps.

Until that exists, the next action is:

```text
BR2 diagnostic refinement
```

This is investigation instrumentation only; no reconnect behavior change should be made yet.
