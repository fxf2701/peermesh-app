# Peer Mesh -- DTN Layer Specification

Version: 1.0\
Status: Draft for Engineering Review

Normative transport quality and acceptance contract:
[`docs/specs/ble_dtn_transport_kpi_slo.md`](ble_dtn_transport_kpi_slo.md).
If a latency, reliability, or outcome statement in this document is ambiguous,
the linked KPI/SLO specification takes precedence.

------------------------------------------------------------------------

# 1️⃣ Mục tiêu

Triển khai một lớp **Store-Carry-Forward (DTN)** cho phép:

-   Truyền thông tin khi không có kết nối liên tục
-   Hoạt động trong mạng phân mảnh
-   Tự động đồng bộ khi thiết bị gặp nhau
-   Không phụ thuộc Internet
-   Chịu được độ trễ cao

------------------------------------------------------------------------

# 2️⃣ Nguyên lý hoạt động

Hệ thống dựa trên:

> Opportunistic peer discovery + inventory sync + store-carry-forward

Không yêu cầu: - Route liên tục - Server trung tâm - IP routing toàn
mạng

------------------------------------------------------------------------

# 3️⃣ Kiến trúc tổng thể

    Application Layer
            ↓
    DTN Layer (Store-Carry-Forward)
            ↓
    Transport Abstraction Layer
    (BLE / WiFi Direct / LoRa / Federation)

DTN layer phải độc lập với radio.

------------------------------------------------------------------------

# 4️⃣ Message Model

## 4.1 Message Structure

``` json
{
  "id": "UUIDv7 / SHA-256 hash",
  "topic": "alert | map_marker | chat | medical | broadcast",
  "origin_node": "node_id",
  "timestamp": 1734567890,
  "ttl_seconds": 3600,
  "hop_count": 0,
  "max_hops": 20,
  "priority": 1,
  "signature": "...",
  "payload": "encrypted_blob"
}
```

## 4.2 Required Fields

  Field         Purpose
  ------------- -----------------------------
  id            Globally unique message ID
  ttl_seconds   Expiration time
  hop_count     Loop control
  max_hops      Hard limit
  origin_node   Traceability (pseudonymous)
  priority      Scheduling
  signature     Integrity

------------------------------------------------------------------------

# 5️⃣ Node Requirements

Each node must:

-   Maintain local message store
-   Maintain peer list
-   Maintain message inventory index
-   Support anti-entropy sync

------------------------------------------------------------------------

# 6️⃣ Message Lifecycle

## 6.1 Creation

-   Message created locally
-   Stored immediately
-   Broadcast to available peers

## 6.2 Storage

-   A forwarding copy remains eligible until:
    -   TTL expires
    -   Max hops is exceeded
    -   Its bounded cache policy evicts it
-   TTL, hop, and cache eviction must not delete the final durable repair
    source for an accepted delivery-eligible Chat or Map event before required
    peers converge. Durable retention follows the normative KPI/SLO contract.

## 6.3 Forwarding

When peer discovered:

1.  Exchange message inventory (IDs only)
2.  Compare missing IDs
3.  Transfer missing messages (priority-based)
4.  Update hop_count

------------------------------------------------------------------------

# 7️⃣ Anti-Entropy Protocol

## 7.1 Inventory Exchange

    Node A → Node B: Bloom filter or ID list
    Node B → Node A: Missing IDs
    Node A → Node B: Requested messages

Must support: - Bloom filter optimization - Batching - Rate limiting

------------------------------------------------------------------------

# 8️⃣ Loop Prevention

-   Increment hop_count on forward
-   Drop message if hop_count \> max_hops
-   Ignore message if ID already seen

------------------------------------------------------------------------

# 9️⃣ Expiration Policy

-   Stop forwarding a transport copy if:
    -   Current_time \> timestamp + ttl
-   Background cleanup task every X minutes
-   Cleanup must preserve an authoritative durable repair source for accepted
    delivery-eligible Chat and Map events, unless an explicit product retention
    policy has already made the event ineligible.

------------------------------------------------------------------------

# 🔟 Storage Constraints

Minimum required:

-   Configurable max storage (e.g., 50MB)
-   Priority-based forwarding-cache eviction:
    1.  Expired
    2.  Low priority
    3.  Oldest first
-   Bounded storage must not be implemented as silent permanent data loss.

------------------------------------------------------------------------

# 11️⃣ Priority Levels

  Level   Use Case
  ------- ----------------
  0       Critical alert
  1       Safety info
  2       Map markers
  3       Chat

Forwarding scheduler must prefer lower number first.

------------------------------------------------------------------------

# 12️⃣ Transport Abstraction

DTN layer must not depend on:

-   BLE specifics
-   Wi-Fi specifics
-   LoRa specifics

Interface:

``` kotlin
interface TransportAdapter {
    fun send(message: ByteArray)
    fun onReceive(callback: (ByteArray) -> Unit)
    fun getPeerId(): String
}
```

------------------------------------------------------------------------

# 13️⃣ Security Requirements

-   End-to-end encryption at application layer
-   Signature verification before store
-   Reject invalid signature
-   Prevent replay attacks (nonce or timestamp window)

------------------------------------------------------------------------

# 14️⃣ Network Partition Handling

System must:

-   Continue operating offline
-   Sync automatically upon reconnection
-   Avoid full resync flood

------------------------------------------------------------------------

# 15️⃣ Metrics for QA

Dev team must apply the normative BLE/DTN KPI/SLO contract and measure:

-   Eventual delivery and final materialized-state correctness
-   Normal-condition delivery and render latency
-   Dependency repair and final convergence latency separately
-   Retry count and complete protocol lifecycle outcomes
-   Duplicate rate and bounded queue/storage growth
-   CPU usage, battery impact, crash, and ANR behavior

Partition/disruption time must be identified separately from normal-condition
latency. A low average does not pass when individual events exhibit unexplained
unbounded delay.

------------------------------------------------------------------------

# 16️⃣ Failure Scenarios to Test

1.  Node moves between 2 partitions
2.  Node power cycle
3.  High message burst
4.  TTL expiry during transit
5.  1000+ messages backlog

------------------------------------------------------------------------

# 17️⃣ Non-Goals

-   Real-time guarantee
-   Strong ordering guarantee
-   Strong or instantaneous global consistency guarantee

DTN provides:

> Eventual consistency when a valid route eventually becomes available

------------------------------------------------------------------------

# 18️⃣ Minimal Implementation Phases

## Phase 1

-   UUID message ID
-   TTL
-   Hop count
-   Basic inventory exchange

## Phase 2

-   Bloom filter optimization
-   Priority scheduler
-   Storage cap + eviction

## Phase 3

-   Adaptive forwarding
-   Bandwidth-aware batching
-   Multi-radio integration

------------------------------------------------------------------------

# 19️⃣ Acceptance Criteria

DTN considered functional if:

-   Message can traverse disconnected partitions via mobile relay
-   No infinite loop
-   No uncontrolled storage growth
-   Battery usage acceptable (\< X% per hour test scenario)
-   Durable events eventually converge when a valid route becomes available
-   Normal-condition latency, bounded progress, observability, and final state
    satisfy `docs/specs/ble_dtn_transport_kpi_slo.md`

Reports must use the normative outcomes `PASS`,
`PASS WITH ACCEPTED LIMITATION`, `DEGRADED`, `INCONCLUSIVE`, `FAIL`, or
`BLOCKED` rather than an unqualified pass/fail result.

------------------------------------------------------------------------

# 20️⃣ Open Questions for Engineering

-   Bloom filter vs Merkle tree?
-   Max message size?
-   Persistent DB vs memory?
-   Backpressure strategy?
-   Adaptive hop limits?

------------------------------------------------------------------------

# 📌 Kết luận

DTN không phải chỉ là "retry khi fail".\
Nó là một lớp mạng độc lập, có:

-   Message identity
-   Anti-entropy sync
-   Expiration
-   Loop control
-   Storage management

Nếu triển khai đúng, Peer Mesh sẽ vượt khỏi "Bluetooth chat" và trở
thành hệ thống chịu phân mảnh thực sự.
