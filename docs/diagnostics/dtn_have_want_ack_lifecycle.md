# DTN HAVE → WANT → DATA → ACK lifecycle: session `F6C01431201D-1785566879970-71adf6d9`

Scope is evidence only: Activity Logs, frozen Room snapshots and current production source. Device A is sender (`F6C01431201D82C6`); Device B is receiver (`3D3FA1C865F3B265`).

## Final verdict

**Receiver never generated WANT — HIGH confidence.**

The exact internal reason why `DtnSyncEngine.onHave` did not return is **UNKNOWN** from the available Activity Log: the capture does not contain an exception/stack trace or a post-`onHave` diagnostic. It is not valid to claim a particular Room exception without that evidence.

The failure boundary is nevertheless narrow and proven:

```
B reassembled valid INV_HAVE
→ DtnControlRouter logged "DTN inv_have recv"
→ engine.onControlPacket(packet) started
→ no "DTN inv_want sent", "DTN sync summary", or "DTN control handle result"
```

`DtnControlRouter.route` emits the receive diagnostic *before* calling `engine.onControlPacket`, and emits sync summary only after that call returns. For an inventory HAVE with non-null gid, session and tier, `onHave` must construct either a non-empty WANT or an empty WANT. Thus the observed state cannot be a successful “receiver had everything and intentionally did nothing” path.

## Lifecycle reconstruction

| Stage | Timestamp / result | Production path | Durable state | In-memory state | Completion condition | Evidence |
|---|---|---|---|---|---|---|
| HAVE build | Exact build timestamp unavailable | `DtnSyncEngine.onHello → buildNextHaveForPeer → DtnInventoryBuilder.buildHaveWindow` | Reads cursor; no write | A active NORMAL window records 160 IDs and cursor before/after | Routed/enqueued | candidate reconstruction + source |
| HAVE wire start | A 13:48:29.518 | `BleMeshTransport.writeNextPacket` | none | active DTN control item | all chunks write callback | A Activity Log |
| HAVE local packet completion | A 13:48:35.820, 45/45, 7584/7584 | `onGattWrite` packet progress | none | send state reaches completed | remote reassembly is separate | A Activity Log |
| HAVE receiver completion | B 13:48:39.133, 45/45, CRC OK | reassembler → `handleDtnControlPayload` | none | reassembled payload | decode/route | B Activity Log |
| HAVE parsed | B 13:48:39.141 | `DtnControlRouter.route` receive diagnostic | none | router begins | `engine.onControlPacket` returns | B Activity Log |
| Inventory comparison | **UNKNOWN / did not complete** | `DtnSyncEngine.onHave → DtnDiffResolver.resolveWantIds → store.existingEventIds` | peer-seen would be written before comparison | local missing/suppression candidates | produce empty or nonempty WANT | absence of post-route logs |
| WANT build/queue/wire | **not generated** | `onHave` would create empty WANT when all IDs exist, otherwise requested WANT | outstanding wants only for nonempty path | session task routing after response | sender receives WANT | no B WANT / no A WANT receive |
| DATA | not reached | `onWant → loadData` | none | pending ACK IDs | DATA accepted/ACKed | no event |
| ACK | not reached | `onData → ACK`, `onAck` | sync success plus possible cursor commit | active window tracking | all pending IDs ACKed | no event |
| Cursor commit | blocked | `commitActiveWindow` | would save `dtn_sync_cursor(peer, "$gid#normal")` | clears active window | empty WANT or all pending DATA ACKed | DB has no NORMAL cursor |
| Disconnect/reconnect | eventually destroys active state | `onPeerDisconnected` | existing durable cursor retained only | removes session/task/outbound inventory maps | new HELLO creates new SessionKey | source + later address/disconnect activity |

## HAVE transport result

The envelope was transmitted and received intact. A records 45/45 chunks at 13:48:35.820; B records reassembly `recv=45/45 complete=true crc=true` at 13:48:39.133 and decodes an `INV_HAVE` with 160 IDs/7,584 bytes. No chunk-loss, CRC failure, or disconnect-before-reassembly evidence exists.

The missing A `DTN control wire complete` diagnostic for this INV_HAVE is a telemetry inconsistency: lower-level packet completion and remote CRC-valid reassembly are stronger evidence of wire delivery. It does not show a failed HAVE.

## Receiver WANT result

Current `onHave` semantics:

* It compares remote IDs to local Room events with `DtnDiffResolver.resolveWantIds`.
* If missing IDs exist, it creates non-empty `INV_WANT` and tracks outstanding local wants.
* If no IDs are missing but `gid`, `sessionId`, and `inventoryTier` are present, it creates an **empty `INV_WANT`** specifically so the sender can commit its active cursor window.

The observed HAVE includes gid/session/tier (it is logged as NORMAL inventory). Neither flavor of WANT appears in B's log, and sender A has no matching receive. Therefore neither WANT transport nor DATA/ACK can be classified as failed: they were never reached.

## Cursor commit result

There are exactly two relevant commit paths for an active inventory window:

1. `onWant(ids.isEmpty()) → commitActiveWindow(reason="empty_want")`.
2. `onAck` after `ackIds` contains all `activeWindow.pendingAckIds` → `commitActiveWindow(reason="ack_complete")`.

Both require the receiver's response phase. In this session no WANT reached A, so neither precondition occurs. The frozen A DB corroborates this: it has an `#high` cursor for B but no `#normal` row for this group.

## Answers to mandatory questions

**Q1 — `disconnect → destroy active window → cursor progress lost`?** Yes, for an uncommitted window. `DtnSyncEngine.onPeerDisconnected` removes `outboundInventoryStates` for the matching peer/session. Only a cursor already saved by `commitActiveWindow` survives.

**Q2 — Immediate reconnect resumes or restarts?** It restarts from durable cursor. Reconnect creates a new session ID/`SessionKey`; it cannot resume an old active window. With no persisted NORMAL cursor, that means traversal starts at null and historical IDs become eligible again.

**Q3 — If receiver never sends WANT, is repeat expected or a bug?** Re-advertisement is the current conservative result, but a receiver that never produces either a non-empty or empty WANT is a **protocol-progress bug**, not intended completed behavior. The sender intentionally withholds cursor commit until that acknowledgement of comparison/transfer outcome.

