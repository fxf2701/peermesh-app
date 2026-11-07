# Generic DTN inventory state machine

| Transition | Production function | Identity | Durable mutation | In-memory mutation / survives restart |
|---|---|---|---|---|
| Peer discovered | `BleMeshTransport.maybeSendDtnHello` | stable `deviceId`, current BLE address | none | HELLO throttle/session context; no |
| HELLO received | `DtnSyncEngine.onHello` | packet stable `peerId`, gid, session | `markPeerSeen` in `dtn_peer_state` | session task/outbound state; no |
| Cursor selected | `buildNextHaveForPeer` → `loadTierCursor` | remote stable peer, `$gid#tier` | reads `dtn_sync_cursor` | session tier traversal; no |
| HAVE built | `DtnInventoryBuilder.buildHaveWindow` | remote stable peer for suppression | none | active window has offered IDs/cursors; no |
| HAVE received | `DtnSyncEngine.onHave` | sender packet peer ID/session | peer seen; receiving event data later | outstanding local wants/tasks; no |
| WANT received | `DtnSyncEngine.onWant` | sender packet peer ID/session | peer seen | active window records requested/pending ACK IDs; no |
| Empty WANT | `onWant` → `commitActiveWindow` | peer/gid/tier | saves `dtn_sync_cursor` | clears active window |
| DATA received | `DtnSyncEngine.onData` | sender peer/session | event insertion/dedupe | outstanding wants cleared; ACK task; no |
| ACK received | `onAck` → `maybeCommitActiveWindowFromAck` | peer/gid/session | `markSyncSuccess`; cursor only after all pending IDs ACKed | clears active window |
| Opaque retry success | `BleMeshTransport` cleanup | stable `deviceId` | opaque delivery cleanup | marks peer satisfied 24h in memory only |
| Disconnect | `DtnSyncEngine.onPeerDisconnected` | stable peer, optional gid/session | none | clears all matching session/inventory state |

Terms are deliberately distinct: **advertised** is an active HAVE window; **requested** is WANT; **served** is DATA load/send; **received** is durable event insertion; **ACKed** completes DATA; **known-to-have/suppressed** is only the transient suppression store; **cursor advanced** becomes durable only at commit.

