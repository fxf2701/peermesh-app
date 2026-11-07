# NORMAL inventory cursor commit path

## Durable key

`DtnSyncEngine.tierCursorKey(gid, tier)` stores cursor with key:

```
peer_id = remote stable device ID
gid = "$groupId#normal"
```

The cursor is not keyed by BLE MAC address or session ID.

## Only commit paths

| Path | Function chain | Required condition | Durable effect |
|---|---|---|---|
| Empty comparison acknowledgement | `onHave` creates empty WANT → remote `onWant` → `commitActiveWindow` | receiver returns empty WANT for a tiered HAVE | saves cursor after offered window |
| Requested data acknowledged | `onWant` loads DATA → receiver `onData` ACKs → sender `onAck` → `maybeCommitActiveWindowFromAck` | ACK contains every ID in `pendingAckIds` | saves cursor after offered window |
| Local exhaustion | `buildNextHaveForPeer` gets no offered IDs yet has advanced cursor | a tier contains no remaining unsuppressed item | saves scanned cursor |

For the known session, the first two paths are unavailable because B did not complete `onHave`/emit WANT. Local exhaustion does not apply because the window contains 160 offered NORMAL IDs. A has no durable NORMAL cursor record for B.

