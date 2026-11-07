# DTN historical inventory investigation

Date: 2026-08-01. Scope: read-only source, Activity Log and frozen Room snapshots for Device A (`251fd3e9`, stable ID `F6C01431201D82C6`) and Device B (`192.168.0.104:40991`, stable ID `3D3FA1C865F3B265`).

## Executive verdict

**MIXED CAUSE**

1. **Cursor/session reset defect — HIGH confidence.** Generic inventory is incremental per stable peer, group and tier, but an advertised window is durable only after an empty `INV_WANT` or after all requested DATA is ACKed. The observed NORMAL window received neither completion signal in the retained logs. Device A's snapshot consequently has no `H-MfE…#normal` cursor for Device B. A disconnect removes the in-memory session state, so a new HELLO reopens the traversal at the null cursor and makes the historical first window eligible again.
2. **Peer-suppression persistence gap — HIGH confidence.** `filterPeerSatisfied` is used by generic inventory as well as repair; however runtime wires `InMemoryDtnRepairSuppressionStore`. Its peer knowledge is lost on app-process restart and has no Room rows. The snapshot's `dtn_advertised` table is empty and is not read by `DtnInventoryBuilder`. This cannot provide durable cross-session protection.
3. **Queue-lifecycle duplication — LOW confidence / not proven.** The 160-ID packet is encoded as 45 chunks. The A-side `pending=77` count is the complete address queue, not packet chunk geometry. No duplicate packet fingerprint or duplicate reassembly completion was found.

This is not an intentional full anti-entropy protocol. It is a **cursor-based, per-stable-peer, per-group, per-tier inventory with a transient peer-satisfied filter**. It becomes effectively restart/reconnect-prone when a window does not finish its WANT/ACK handshake.

## Exact observed session

| Fact | Evidence |
|---|---|
| Sender / receiver | A `F6C01431201D82C6` → B `3D3FA1C865F3B265` |
| Session | `F6C01431201D-1785566879970-71adf6d9` |
| B received HAVE | 13:48:39.141 local, `ids=160`, `bytes=7584`, `chunks=45`, tier NORMAL |
| A writer progress | 13:48:29.517 already had 77 total packets pending; it still reported control writes through 13:48:43.023. These counts are queue-wide, not an encoded chunk count. |
| Completion evidence | No retained `DTN want recv`, `DTN cursor commit`, or matching `DTN ack recv` for this session on A; B has no retained `INV_WANT` for it. |
| Durable cursor after test | A has only `…#high` for B; B has only `…#high` for A. Neither has a NORMAL cursor for this group. |

## Why the 160 IDs were eligible

At packet build time, the reconstructed candidate set contained exactly 160 NORMAL eligible rows. They were all valid, all passed `filterPeerSatisfied`, and the inventory limit was 250. The window therefore exactly equals the available NORMAL candidate list; it is not a 250-item truncation. Breakdown: SYSTEM 73, MAP canonical 40, MAP action 37, CHAT 10; source mesh 115/local 29/dtn 16; age 5,595–321,891 seconds.

`RoomDtnInventoryStore.listInventoryAfter` traverses valid events by `(inserted_at,event_id)`. `DtnInventoryBuilder.buildHaveWindow` classifies each row by tier, sorts NORMAL rows using `DtnForwardPolicy.sortForTransfer`, then excludes only IDs returned by `filterPeerSatisfied(remotePeerId, ...)`. With a null NORMAL cursor and no in-memory peer-satisfied entries, history remains eligible irrespective of age or whether it arrived via mesh, local creation, or DTN.

`artifacts/dtn_inventory_event_trace.csv` records representative rows. The historical DB does **not** retain a durable per-event answer for “previously advertised/requested/served/ACKed”; the `outbound_batch_event` relation had no successful record for 159/160 candidates and cannot prove DTN-control history. Therefore those fields are explicitly `not recoverable`, not treated as “no”.

## Identity audit

| Lifecycle point | Identity/key | Result |
|---|---|---|
| BLE address | rotating MAC addresses (e.g. A writer `47:98:9A:2B:0C:CC`; B receive/reassembly address observed separately) | Ephemeral lane only |
| HELLO / packet `peer_id` | stable 16-hex device ID | Correct |
| Session | local stable-ID prefix plus timestamp/random suffix | New per contact/session, not a cursor key |
| Cursor | `(remote stable peer ID, "$gid#tier")` | Correct key; no NORMAL row persisted |
| `filterPeerSatisfied` | `(remote stable peer ID,event ID)` | Correct identity while process lives, but in-memory only |
| ACK handling | packet peer ID + gid + session ID | Correct session-scoped matching |

No evidence identifies address rotation, truncated IDs, or sender/receiver inversion as the cause. Both peer-state tables record the same counterpart stable IDs despite address rotation.

## Cursor and suppression semantics

* Sending HAVE alone does **not** move durable cursor state.
* Empty WANT commits the active window (`reason=empty_want`). A non-empty WANT records pending DATA; matching ACK of all pending IDs commits (`reason=ack_complete`).
* `onPeerDisconnected` clears `outboundInventoryStates`, active window, outstanding local wants and task state for the session. The uncommitted cursor is then only in memory and is lost.
* `markPeerSatisfied` is performed by successful opaque-retry cleanup in `BleMeshTransport`, not merely by HAVE or WANT. Generic `DtnInventoryBuilder` does consume that filter, so the name “repair suppression” understates its scope.
* The suppression TTL is 24h, but runtime uses `InMemoryDtnRepairSuppressionStore`; no persisted record survives restart. The `dtn_advertised` Room table is empty in both snapshots and is not consulted on this selection path.

## BLE-lane impact

The scheduler prefers a normal mesh batch when no DTN envelope is active. Once a DTN control item starts, the transport keeps that control item contiguous until its chunks drain. The observed inventory was received over roughly ten seconds from the first retained A progress record to B completion; its full start may be earlier. It can therefore delay APP work sharing that address lane.

However, causality for the reported B chat delay is **not proven**: B #1 was queued at 13:49:01.180, after B had completed the inventory at 13:48:39.141. There is no same-lane APP-dispatch trace tying B #1 to this envelope. The risk is real by code path; the specific chat incident needs finer packet/batch correlation diagnostics.

## Stability assessment

Data correctness is conservative: lack of completion causes re-advertisement rather than known event loss. It is not stable under interrupted sessions/address changes because failed completion restarts historical traversal. Repetition can create long APP latency while a control envelope is active; the present evidence does not establish unbounded latency or permanent loss. This warrants remediation before treating M3E12/T8-V2 as closed, but does not justify a broad scheduler change from this evidence alone.

## Recommended next step

**Add diagnostic-only patch.** Add a stable fingerprint and lifecycle record for each HAVE (`cursor before/after`, ordered-ID hash, enqueue/start/end, WANT outcome, commit or disconnect reason) plus per-lane APP wait correlation. This is the lowest-risk next step: it distinguishes lost completion from a receiver-side no-WANT path before changing durable cursor or peer-knowledge semantics.

