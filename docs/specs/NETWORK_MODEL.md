# PeerMesh Network Model

> **Principle:** BLE provides nearby communication; cloud is an optional store-and-forward layer. Each device continues to operate without Internet access.

## 1. Overview

```mermaid
flowchart LR
  subgraph Local["Area with no or limited Internet"]
    A["Participant A\nMobile client"]
    B["Participant B\nMobile client"]
    C["Forwarding device\nBLE carrier"]
    A <-->|"BLE: encrypted event\nTTL + dedupe"| C
    C <-->|"BLE: encrypted event\nTTL + dedupe"| B
  end

  G["Gateway\nInternet-connected mobile client"]
  R1["Cloud relay A\nopaque store-and-forward"]
  REG["Registry\ncontrol plane"]
  R2["Cloud relay B\nopaque store-and-forward"]
  D["Participant D\nMobile client"]

  A <-. "BLE" .-> G
  G <-->|"HTTPS: envelope opaque"| R1
  REG -. "issues sessions /\nauthorized peers" .-> R1
  REG -. "issues sessions /\nauthorized peers" .-> R2
  R1 <-->|"Federation: event opaque"| R2
  R2 <-->|"HTTPS: envelope opaque"| D
```

## 2. Roles

```mermaid
flowchart TB
  U["Participant\nCreates, receives, and reads events"] --> M["Mobile client\nEncrypts / verifies / stores in SQLite / reduces"]
  M --> BLE["BLE node\nBest-effort send, receive, and forwarding"]
  M -->|"when Internet is available"| GW["Gateway\nBridges events between BLE and cloud relay"]
  GW --> R["Cloud relay\nOpaque store-and-forward; TTL, rate limit, dedupe"]
  R <-->|"authorized peers only"| F["Relay federation\nSyncs between relays"]
  REG["Registry\nIssues federation sessions, peers, and limits"] -. "does not carry payloads" .-> F
```

| Entity | Primary role |
|---|---|
| Participant | Uses group chat and maps. |
| Mobile client | Autonomous node: creates, encrypts/verifies, and stores events locally; renders state. |
| BLE node / carrier | Discovers and forwards nearby events; delivery and ordering are not guaranteed. Android is better suited to relay; iOS is mainly an edge participant. |
| Gateway | An Internet-connected mobile client that syncs events between the local mesh and a cloud relay. |
| Cloud relay | Stores and forwards **opaque** events; never decrypts or alters payloads. |
| Registry | Federation control plane: issues relay permissions, peers, and limits; never proxies payloads. |
| Relay federation | Syncs between cloud relays authorized by the Registry. |

## 3. Core Data Flow

```mermaid
sequenceDiagram
  participant P as Participant / Mobile client
  participant B as BLE mesh
  participant G as Gateway
  participant R as Cloud relay
  participant F as Relay federation

  P->>B: Encrypted event + TTL
  B-->>G: Best effort, dedupe
  G->>R: Sync opaque envelope when Internet is available
  R->>F: Replicate to relays with authorized sessions
  F-->>P: Event reaches the destination through a relay or BLE
```

- Without Internet, BLE still works at nearby range.
- Without BLE, an Internet-connected client can sync through a cloud relay.
- Only clients in the group decrypt plaintext; relays and the Registry cannot read payloads.
