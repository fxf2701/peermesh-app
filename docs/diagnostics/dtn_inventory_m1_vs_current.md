# M1 vs current DTN inventory behavior

Comparison base: branch `refactor-map-activity-m1` versus current `M3E12-T8` source. Only generic inventory/BLE scheduling scope is included.

| Behavior | M1 | Current | Risk/effect | Evidence |
|---|---|---|---|---|
| Generic inventory limit/cursor handshake | Same 250-limit, tier cursor model and commit-after-empty-WANT/all-ACK semantics | Same | The observed NORMAL cursor gap is not introduced by M3E12 changes | `DtnSyncEngine` and `DtnInventoryBuilder` diff; no core cursor change |
| Peer suppression | Same in-memory store wiring and generic builder filter | Same | No cross-process durable peer knowledge in either | `BleMeshRuntime`, `DtnInventoryBuilder` |
| HELLO-triggered HAVE | Existing `onHello → buildNextHaveForPeer` | Same | Historical window can start on each new session where cursor absent | `DtnSyncEngine.onHello` |
| Dependency-root WANT | No proactive worker/session-context path | Added during M3E12 | Separate targeted repair work; does not explain the 160 generic NORMAL HAVE | current `BleMeshTransport.scheduleDependencyRepairWant` |
| DTN control queue | Generic queue selection | Current adds per-control cap, type policy, control active-envelope tracking | Makes control ordering explicit and preserves contiguous started control envelope | `BleMeshTransport.pollNextPacketForWrite` diff |
| Address rotation | Existing lane handling | Current can rebind queued, not-started control envelopes | Addresses queued control stranded on old address; not proof of duplicate chunks | `handleDeviceAddressRotation` diff |

Conclusion: there is no M1→current regression point that changes the generic cursor completion contract. Current changes add repair routing/control-queue handling, while the observed failure is the pre-existing (or unchanged) non-completed-window path.

