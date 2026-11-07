# Project Overview & Technical Specification
## Offline-first Local Communication App (Mesh-based)

---

## 1. Project Vision

### 1.1 Problem Statement
Internet and cellular connectivity may be limited, intermittent, or unavailable during:

- Outdoor activities such as hiking, camping, and trekking
- Travel in wilderness or remote areas with little or no network coverage
- Natural disasters such as floods, storms, earthquakes, and wildfires
- Power outages or temporary disruptions to communication infrastructure
- Large events where local networks are congested

In these conditions, people may have difficulty:

- Keeping in contact with nearby group members
- Coordinating local activities and meeting points
- Sharing observations about nearby conditions, obstacles, and available resources

This project aims to provide a **resilient, offline-first communication tool** that works **without internet**, allowing users to:
- Chat locally
- Share map-based information
- Propagate data via nearby devices

---

### 1.2 Core Philosophy
- **Offline-first**
- **Client as autonomous node**
- **Event-based architecture**
- **No blockchain, no consensus**
- **Minimal infrastructure dependency**

---

## 2. High-level Features

### 2.1 Core Features (Phase 2)
- Group-based private communication (passphrase-based)
- Mesh chat (text)
- Group join via manual input (GID + salt + passphrase) or invitation link
- Offline map sharing:
  - Markers (danger, safe, help, resources)
  - Areas (zones, routes)
- Automatic propagation via Bluetooth mesh
- TTL-based data expiry

### 2.2 Future Features (Phase 3+)
- Private relay mode with server-side MAC verification
- Key provisioning / rotation for trusted operators
- Web dashboard / analytics (optional)

---

## 3. System Architecture Overview

```
+------------------------------------------------+
|                  Mobile Client                 |
|                                                |
|  +---------------- UI ----------------------+  |
|  | Chat UI | Map UI | Group Management     |  |
|  +----------------▲------------------------+  |
|                   |                           |
|  +----------------|------------------------+  |
|  |  Application / Domain Layer              |  |
|  |  - Event Factory                          |  |
|  |  - Reducers (Chat / Map)                  |  |
|  |  - TTL / Cleanup                           |  |
|  +----------------▲------------------------+  |
|                   |                           |
|  +----------------|------------------------+  |
|  |  Security & Group Layer                   |  |
|  |  - Passphrase → Group Key                 |  |
|  |  - Encryption / MAC                       |  |
|  +----------------▲------------------------+  |
|                   |                           |
|  +----------------|------------------------+  |
|  |  SQLite (Client-only)                     |  |
|  |  - Event Log                              |  |
|  |  - Materialized Views                     |  |
|  +----------------▲------------------------+  |
|                   |                           |
|  +----------------|------------------------+  |
|  |  Transport Layer                          |  |
|  |  - BLE mesh (Android: custom BLE transport) |  |
|  +-------------------------------------------+  |
```

---

## 4. Data Model

### 4.1 Event-based Model
- All shared data is represented as **events**
- Events are append-only
- State is derived from events via reducers

### 4.2 Event Categories
- `CHAT.*` — messaging
- `MAP.*` — map markers & areas
- `SYS.*` — presence, alerts, metadata (e.g., `SYS.GROUP.UPD`, `SYS.USER.UPD`)

---

## 5. Transport Layer

### 5.1 Technology
- **Android: custom BLE transport** (BLE primitives; app implements target selection/TTL/dedupe)
- **iOS: Berkanan SDK** (if used)
- Bluetooth Low Energy (BLE) broadcast mesh
- No pairing, no session

### 5.2 Transport Characteristics
- Stateless
- Best-effort delivery
- Flooding with TTL
- No ordering guarantees

---

## 6. Storage Layer

### 6.1 SQLite (Client-side)
- Event log (append-only)
- Materialized tables for chat & map
- WAL mode enabled
- Crash-safe transactions

### 6.2 Why SQLite?
- Works offline
- Mobile-friendly
- Deterministic rebuild from log

---

## 7. Reducer & State Management

### 7.1 Reducer Principles
- Deterministic
- Idempotent
- Order-independent
- Last-Write-Wins (LWW)
- Tombstone-based deletion
- TTL-based expiration

### 7.2 Chat Reducer
- Group-only messages (no threads)
- Reply, edit, delete (optional)
- TTL auto-expire

### 7.3 Map Reducer
- Marker / Area entities
- Add / update / delete
- LWW per field (or entity-level for MVP)
- Spatial filtering

---

## 8. Group & Security Model

### 8.1 Group Identity
- Group created via passphrase + salt
- `gid = hash(passphrase + salt)`
- No central authority
- Join methods:
  - Manual input (GID + salt + passphrase)
  - Invitation link (encodes GID + salt)

### 8.2 Security
- End-to-end encryption (group key)
- Message authentication (MAC)
- Server (Phase 2) cannot read content
- Metadata events (`SYS.GROUP.UPD`, `SYS.USER.UPD`) are also encrypted in transit

---

## 9. Frameworks & SDKs

### 9.1 Mobile Platforms
- **Android**
  - Language: Kotlin
  - DB: SQLite (Room / SQLDelight)
  - Crypto: Android Keystore, Tink or libsodium
- **iOS**
  - Language: Swift
  - DB: SQLite (GRDB / SQLite.swift)
  - Crypto: CryptoKit or libsodium

**Development note (Ubuntu):**
- Android Emulator runs on Ubuntu via Android Studio.
- iOS Simulator requires macOS; on Ubuntu use a physical iPhone or a cloud device farm.

### 9.2 Cross-cutting
- JSON serialization (stable canonical form)
- UUIDv4 for IDs

---

## 10. Phase Breakdown

### Phase 1 — Opaque Relay Server
- Internet relay (opaque store/forward)
- Validation, rate limits, TTL cleanup
- Server-to-server federation support (minimal)

### Phase 2 — Mobile Apps
- Android / iOS clients
- Offline-first mesh communication
- Chat + map sharing
- Client-side verify MAC / decrypt payloads
- Mesh forwarding should drop duplicate/expired envelopes to reduce BLE/CPU churn.
- Forward opaque mode (when enabled) still enforces TTL + dedupe + drop duplicate/expired + rate‑limit.
- Relay sync (opaque) with power modes:
  - **Normal**: sync only when network is available.
  - **Battery Saver**: sync only when network is available, WorkManager periodic (15 minutes).
- Relay discovery (client):
  - **Phase 2**: user-provided **seed relays list** (manual input).
  - **Phase 3**: discovery/auto-selection (server + mobile).

### Phase 3 — Private Relay Mode
- Optional server-side MAC verification
- Key provisioning & rotation for trusted operators

---

## 11. Non-goals & Constraints

- No blockchain
- No global consensus
- No real-time guarantees
- Not a replacement for cellular networks

---

## 12. Target Use Cases

- Group communication during hiking, camping, trekking, and other outdoor activities
- Local communication in wilderness and remote areas with limited network coverage
- Sharing local observations and coordinating community assistance during natural disasters
- Keeping nearby groups connected during power outages, infrastructure disruptions, or network congestion
- Temporary group communication and map sharing during travel, events, and on-site activities

---

## 13. Summary

This project delivers a **resilient, decentralized, offline-capable communication platform** for nearby groups in outdoor, remote, and everyday settings, including situations where natural disasters or infrastructure disruptions limit connectivity.

By prioritizing simplicity, determinism, and offline operation, it avoids unnecessary complexity while remaining extensible for future online synchronization.

---

*This document is intended for engineers and AI systems to quickly understand the system’s goals, architecture, and technical foundations.*
