# BLE Address Rotation and State Ownership

## 1. Current ownership model

The baseline has two identity domains:

```text
stable device ID
  lastSeenAddrByDeviceId
  lastTargetByDeviceId
  device quiet/cooldown/dwell/rotation state

BLE address
  BluetoothDevice cache
  connect state
  connecting/connected connection
  characteristic
  connect timestamp
  pending/staged/held queues
  active packet/envelope
  write slot
  address cooldown
```

This split is reasonable for rotating BLE addresses, but lifecycle ownership is not atomic across the two domains.

## 2. Rotation behavior

When a new address is observed for the same stable peer:

1. previous address receives a 12 s cooldown;
2. new address receives an 8 s recovery grace;
3. latest/target mapping may switch to the new address;
4. queued control may be rebound if not already started;
5. if the old lane is considered active, it is preserved;
6. otherwise pending and deferred work migrate;
7. new address receives ENQUEUE and CONNECT labels.

This favors durability and avoids tearing down an in-flight envelope.

## 3. Ownership gap

There is no reviewed structure equivalent to:

```text
ConnectionOwner(
  stablePeerId,
  address,
  generation,
  connectionIdentity,
  phase
)
```

Instead, callbacks and cleanup resolve ownership through address-keyed maps. A delayed callback from an obsolete connection can therefore be indistinguishable from a callback belonging to the current attempt at the same address.

## 4. State-by-state ownership matrix

| State | Key | Current owner proof | Rotation behavior |
|---|---|---|---|
| discovery cache | address | latest scan result | new address added |
| stable identity | device ID | manufacturer/stable ID mapping | latest address replaced |
| connect state | address | none beyond map value | old and new coexist |
| connecting connection | address | map entry | old may be closed |
| connected connection | address | map entry | old may be preserved |
| characteristic | address | no generation binding | removed on cleanup |
| pending queue | address + packet deviceId | packet identity | migrated conditionally |
| active packet | address | map entry | old lane preserved |
| quiet/cooldown | mixed | timers | can block sibling address |
| retry/cleanup label | address | no attempt token | labels merge by address |

## 5. Race scenarios to verify

### R1 — old callback after timeout cleanup

```text
attempt A starts
→ timeout cleanup closes A
→ attempt B starts on same address
→ late callback from A arrives
→ callback mutates address state / posts cleanup
→ B is affected
```

### R2 — rotation keeps old lane alive indefinitely enough to block new lane

```text
old address has active marker
→ new address appears
→ old lane preserved
→ new CONNECT posted
→ candidate resolves to old/busy address
→ skip state_busy_connecting_or_connected
```

### R3 — cleanup label targets current map owner, not original requester

```text
callback A posts FORCE CLEANUP(address)
→ before CLEANUP executes, owner map changes to B
→ CLEANUP reads clientByDevice[address] / connectingClient[address]
→ B may be closed
```

The source supports the possibility because cleanup requests carry address/mode/reason but not connection identity.

## 6. Stability-first recommendation

Do not shorten timers or force migration. First add attempt-scoped diagnostic ownership. The narrow correctness candidate is generation validation at every callback and cleanup boundary.
