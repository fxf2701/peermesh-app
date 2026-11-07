# DTN disconnect cleanup and restart semantics

`BleMeshTransport.cleanupConnection` invokes `DtnSyncEngine.onPeerDisconnected(peerId, ...)`. That method removes matching:

* session task state,
* outstanding local wants,
* dependency-root reservations,
* deferred manifest offers,
* `outboundInventoryStates` (including active HAVE window and its in-memory cursor).

It does not save an uncommitted active cursor. `InMemoryDtnRepairSuppressionStore` is process-local and is not cleared by ordinary disconnect, but is unavailable after app restart. Room peer state/cursors survive disconnect only if already persisted.

On next HELLO, `buildNextHaveForPeer` creates a new session state and loads only the durable cursor. For the observed A→B NORMAL tier, that record is absent, so it restarts at `(insertedAt=0,eventId="")`; it cannot resume the old 160-ID active window.

