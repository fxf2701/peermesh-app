# BLE Usable Route State Machine

## 1. Purpose

This document separates peer discovery from outbound-route usability.

## 2. State model

```text
ABSENT
  no fresh advertisement

DISCOVERED
  fresh advertisement exists
  Nearby may be > 0
  no connection implied

IDENTIFIED
  stable peer ID resolved
  address registered as a candidate

SELECTED
  target selection chose an address
  cooldown/quiet/penalty gates may still block it

CONNECT_QUEUED
  CONNECT pipeline label exists

CONNECTING
  connectGatt returned a connection
  address state = CONNECTING
  connectStartedAt exists

LINK_CONNECTED
  callback reports STATE_CONNECTED
  Android link exists
  route is still not usable

SERVICES_READY
  service discovery succeeded
  expected characteristic exists
  address state = CONNECTED

MTU_PENDING_OR_DEFAULT
  default payload is usable for small traffic
  large first DATA may remain held

WRITE_READY
  characteristic owned by current connection
  pending/staged/held work can enter write pump

FIRST_WRITE_IN_FLIGHT
  activePendingPacketByAddress is set
  writeCharacteristic returned true

TX_CONFIRMED
  characteristic-write callback succeeded

RX_CONFIRMED
  first valid inbound chunk/envelope observed

USABLE_ROUTE
  minimum outbound definition:
    SERVICES_READY + successful write path
  bidirectional definition:
    TX_CONFIRMED + RX_CONFIRMED within the current connection/session

DEGRADED
  peer still discovered but route blocked by:
    quiet/cooldown/backoff
    stale connecting state
    service/characteristic failure
    status 133
    stalled write
    scan-stale gate
    GATT-cap gate

CLEANING
  disconnect/close in progress
  pending work should remain recoverable

RESTARTING
  transport stop/clear/start
  in-memory queues cleared
  durable work must be re-dispatched
```

## 3. Critical distinction

```text
Nearby > 0  !=  CONNECTED
CONNECTED   !=  SERVICES_READY
SERVICES_READY != successful write
successful write != durable peer receipt
```

## 4. Transition table

| From | Event | To | Usable? |
|---|---|---|---|
| ABSENT | advertisement | DISCOVERED | No |
| DISCOVERED | stable ID mapped | IDENTIFIED | No |
| IDENTIFIED | target selected | SELECTED | No |
| SELECTED | CONNECT label | CONNECT_QUEUED | No |
| CONNECT_QUEUED | `connectGatt` returns connection | CONNECTING | No |
| CONNECTING | callback `STATE_CONNECTED` | LINK_CONNECTED | No |
| LINK_CONNECTED | services + characteristic found | SERVICES_READY | Not yet proven |
| SERVICES_READY | write accepted | FIRST_WRITE_IN_FLIGHT | No |
| FIRST_WRITE_IN_FLIGHT | write callback success | TX_CONFIRMED | Outbound path proven |
| any connected state | valid receive | RX_CONFIRMED | Inbound path proven |
| TX_CONFIRMED + RX_CONFIRMED | current session correlated | USABLE_ROUTE | Yes |
| CONNECTING | timeout/status 133 | DEGRADED/CLEANING | No |
| SERVICES_READY | stalled write | CLEANING | No |
| DEGRADED | watchdog hard restart | RESTARTING | No |
| RESTARTING | new successful route | USABLE_ROUTE | Yes |

## 5. Required invariants for BR2 diagnostics

1. One current connection owner per `(address, generation)`.
2. A callback may mutate state only when it belongs to the current generation.
3. Cleanup must name the exact connection generation it closes.
4. `CONNECTED` must not be published before service/characteristic readiness.
5. `Nearby` must remain discovery-only unless UI labels it otherwise.
6. Durable work identity must survive every CLEANING and RESTARTING transition.
