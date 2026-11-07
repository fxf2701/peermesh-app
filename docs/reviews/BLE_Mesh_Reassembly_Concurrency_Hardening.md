# BLE Mesh -- Reassembly Concurrency Hardening

## Technical Specification & Patch Proposal

**Project:** Peer Mesh\
**Module:** BLE Mesh Transport -- Chunking (Reassembler)\
**Priority:** HIGH (Rare Data Corruption Risk)\
**Status:** Proposed for Implementation

------------------------------------------------------------------------

# 1. Executive Summary

Sau khi hoàn tất:

-   Namespace Chunk Dedupe theo sender
-   Namespace Reassembly theo sender

Hệ thống đã loại bỏ hoàn toàn collision cross-sender.

Tuy nhiên, Reassembler hiện tại vẫn có một rủi ro tiềm ẩn về
**concurrency**:

> Mutation của một `Entry` có thể xảy ra song song nếu nhiều chunk của
> cùng một `(senderKey, chunkId)` đến gần như đồng thời.

Điều này có thể dẫn đến:

-   Race condition trong `parts[index]`
-   Double increment `receivedCount`
-   Complete-check xảy ra trước khi data fully written
-   Payload ghép sai trong trường hợp cực hiếm

Đây là bug loại: - Rare - Khó reproduce - Cực khó debug trong production

Giải pháp: **Atomic mutation per Entry bằng synchronized(entry)**

------------------------------------------------------------------------

# 2. Phân tích vấn đề hiện tại

## 2.1 Kiến trúc hiện tại

-   `entries` là `ConcurrentHashMap`
-   Nhưng `Entry.parts` là `Array<ByteArray?>`
-   `Entry.receivedCount` là Int thường

Trong `add()`:

``` kotlin
entry.parts[index] = payload
entry.receivedCount++

if (entry.receivedCount == entry.total) {
    ...
}
```

Không có lock per-entry.

## 2.2 Kịch bản race condition

Giả sử:

-   2 chunk khác nhau (index 0 và 1)
-   Đến gần như cùng lúc
-   Callback BLE xử lý song song (stack có thể multi-thread)

Thread A: - ghi index 0 - tăng receivedCount = 1

Thread B: - ghi index 1 - tăng receivedCount = 2

Nếu race xảy ra giữa read/write của receivedCount hoặc parts, có thể:

-   Complete check chạy khi array chưa fully consistent
-   Hoặc double increment
-   Hoặc missing write

Xác suất thấp nhưng không bằng 0.

------------------------------------------------------------------------

# 3. Mục tiêu của Task

-   Đảm bảo mutation của mỗi `Entry` là atomic
-   Không lock global
-   Không ảnh hưởng performance mesh
-   Không thay đổi protocol/network layer

------------------------------------------------------------------------

# 4. Giải pháp đề xuất

## 4.1 Lock per Entry (Recommended)

Trong `add()`:

``` kotlin
synchronized(entry) {

    if (entry.parts[index] == null) {
        entry.parts[index] = payload
        entry.receivedCount++
    }

    if (entry.receivedCount == entry.total) {
        val combined = combine(entry.parts)
        entries.remove(key)
        return Reassembled.Complete(combined)
    }
}
```

### Ưu điểm

-   Lock scope nhỏ (per message)
-   Không ảnh hưởng peer khác
-   Không ảnh hưởng chunk khác
-   Không lock global map

### Overhead

Không đáng kể vì: - Lock rất ngắn - Chỉ khi nhiều chunk cùng message đến
gần nhau

------------------------------------------------------------------------

## 4.2 Không khuyến nghị

### ❌ Lock toàn bộ entries map

Gây bottleneck khi nhiều peer

### ❌ Dùng global mutex

Không cần thiết

### ❌ AtomicInteger cho receivedCount nhưng không lock array

Không giải quyết race array write

------------------------------------------------------------------------

# 5. Patch Proposal (Find & Replace)

## File: Chunking.kt

### BEFORE

``` kotlin
if (entry.parts[index] == null) {
    entry.parts[index] = payload
    entry.receivedCount++
}

if (entry.receivedCount == entry.total) {
    ...
}
```

### AFTER

``` kotlin
synchronized(entry) {

    if (entry.parts[index] == null) {
        entry.parts[index] = payload
        entry.receivedCount++
    }

    if (entry.receivedCount == entry.total) {
        val combined = combine(entry.parts)
        entries.remove(key)
        return Reassembled.Complete(combined)
    }
}
```

------------------------------------------------------------------------

# 6. Performance Impact

  Metric        Impact
  ------------- ----------------------------
  CPU           Không đáng kể
  Memory        Không thay đổi
  Throughput    Không ảnh hưởng
  Latency       Không đáng kể
  Scalability   An toàn hơn khi nhiều peer

------------------------------------------------------------------------

# 7. Validation Strategy

Sau khi implement:

1.  Stress test với:
    -   5--10 peer giả lập
    -   Broadcast cùng lúc nhiều message multi-chunk
2.  Log count:
    -   Complete events
    -   CRC mismatch
    -   Drop events

Expected result: - Không có corruption - Không có random drop

## 7.1 Test/Stress Note (Implementation)

Mục tiêu: tái hiện điều kiện nhiều chunk của cùng `(senderKey, chunkId)` đến gần như đồng thời và xác nhận reassembly luôn nhất quán.

Thiết lập:
- 5-10 thiết bị/peer (hoặc giả lập) ở khoảng cách gần, bật BLE mesh cùng group.
- 2 thiết bị gửi burst liên tục, còn lại chỉ nhận để tăng cạnh tranh callback.
- Bật Activity Log và giữ session log đủ dài (>= 10 phút).

Kịch bản test:
1. Burst A->B: gửi liên tục 50-100 tin nhắn, mỗi tin đủ lớn để thành >= 3 chunks.
2. Burst B->A: gửi đồng thời 50-100 tin nhắn tương tự (chồng thời gian với A->B).
3. Mixed burst: A gửi tin lớn, C/D gửi tin nhỏ xen kẽ.
4. Repeat 3 vòng, mỗi vòng cách nhau 30-60s để gây overlap.

Theo dõi log:
- `GATT reassembly` với `complete=true` phải tăng tương ứng số message gửi.
- Không xuất hiện `crc=false` hoặc `crc_header_mismatch` tăng đột biến.
- Không có pattern `complete=true` nhưng `payload` bị drop ở ingest.

Tiêu chí pass:
- 0 corruption (CRC mismatch = 0 trong các phiên stress).
- 0 random drop không có nguyên nhân rõ ràng (không thấy “Reassembly CRC mismatch; drop payload”).
- Tỷ lệ complete/recv ổn định giữa các vòng (> 95% nếu RSSI tốt).

------------------------------------------------------------------------

# 8. Conclusion

Reassembly Concurrency Hardening là bước tự nhiên sau khi fix namespace.

Không implement sẽ:

-   Không crash
-   Nhưng tồn tại rare corruption bug
-   Khó debug trong production

Implement sớm giúp:

-   Mesh ổn định lâu dài
-   Scale tốt khi peer tăng
-   Sẵn sàng cho production/disaster environment

------------------------------------------------------------------------

# 9. Sequence Diagram -- Race Condition Minh Họa

## 9.1 Bối cảnh

-   1 message được chunk thành 2 phần: `index=0` và `index=1`
-   Cùng thuộc `(senderKey, chunkId)`
-   BLE stack/dispatcher có thể gọi `onCharacteristicWriteRequest` song
    song (multi-thread)
-   `Reassembler.add()` được gọi song song cho cùng một entry

## 9.2 Diagram (Mermaid)

``` mermaid
sequenceDiagram
    autonumber
    participant Peer as Peer Device
    participant GATT as BleMeshTransport (GATT Server)
    participant R as Reassembler.add(key)
    participant E as Entry(parts[], receivedCount)

    Note over Peer,GATT: Peer gửi 2 chunks gần như đồng thời
    par Chunk #0 (index=0)
        Peer->>GATT: Write chunkId=X idx=0 total=2 payload=A
        GATT->>R: add(senderKey, chunk0)
        R->>E: read parts[0]==null?
        R->>E: write parts[0]=A
        R->>E: receivedCount++ (0->1)
        R->>E: if receivedCount==2? (false)
    and Chunk #1 (index=1)
        Peer->>GATT: Write chunkId=X idx=1 total=2 payload=B
        GATT->>R: add(senderKey, chunk1)
        R->>E: read parts[1]==null?
        R->>E: write parts[1]=B
        R->>E: receivedCount++ (1->2)
        R->>E: if receivedCount==2? (true)
        R->>R: combine(parts)
        R-->>GATT: Complete(payload=A+B)
    end

    Note over R,E: Nếu không có lock per-entry, thứ tự read/write có thể bị interleave
```

## 9.3 Các interleaving gây lỗi (minh họa)

Một số interleaving cực hiếm nhưng có thể xảy ra: - **Double
increment:** 2 thread cùng đọc receivedCount=0 rồi cùng ghi -\> 1 hoặc 2
(lost update / inconsistent) - **Complete trước khi write xong:** thread
B thấy receivedCount==total (do race) và combine khi parts\[0\] chưa
được publish đúng cách - **Missing part:** combine ra payload thiếu một
phần, dẫn đến CRC fail hoặc payload sai

> `synchronized(entry)` đảm bảo toàn bộ: check-null → write → increment
> → complete-check là một transaction atomic.

------------------------------------------------------------------------

# 10. Test Plan Chi Tiết -- Stress Simulation Scenario

## 10.1 Mục tiêu test

1.  Tái tạo (hoặc tăng xác suất) race condition trong
    `Reassembler.add()`
2.  Xác nhận sau patch:
    -   Không corrupted payload
    -   Không complete sai
    -   Không CRC mismatch do race
    -   Không leak entries (TTL cleanup ok)

## 10.2 Phạm vi test

-   Unit / JVM test (deterministic-ish) cho Reassembler
-   Instrumentation test (Android) để stress concurrency
-   Multi-device field test (real BLE stack)

## 10.3 Metrics cần thu thập

-   `complete_count`
-   `crc_mismatch_count`
-   `dropped_count` (invalid index/total/size)
-   `entries_peak_size`
-   `entries_leaked_size` (sau TTL, entries còn lại)
-   p50/p95 time-to-complete

Khuyến nghị log thêm trong diagnostics (chỉ trong debug build): -
`reasm_add_calls` - `reasm_entry_lock_contention` (count lock contention
nếu muốn)

------------------------------------------------------------------------

## 10.4 Unit Test (JVM) -- Concurrency Harness

### Setup

-   Instantiate `Reassembler(ttlSeconds=10, maxEntries=...)`
-   Fixed `senderKey="A"`, `chunkId=123`
-   Create 2 chunk byte arrays hợp lệ (header+payload) với `index=0` và
    `index=1`, `total=2`, cùng CRC đúng

### Scenario UT-1: Many interleavings via thread pool

-   Dùng `ExecutorService` (8--16 threads)
-   Lặp 50,000 vòng:
    1.  reset reassembler (hoặc dùng chunkId mới mỗi vòng)
    2.  submit 2 tasks đồng thời gọi `add(senderKey, chunkX)`
    3.  await futures
    4.  assert:
        -   exactly 1 completion returned (Complete)
        -   payload đúng (A+B theo thứ tự index)
        -   không CRC mismatch

### Scenario UT-2: Duplicate chunk replays under concurrency

-   Gửi chunk0, chunk1 đồng thời
-   Đồng thời replay chunk0 thêm 3 lần (simulate BLE retransmit)
-   Assert completion đúng 1 lần, không corrupted

### Scenario UT-3: Out-of-order arrival

-   submit chunk1 trước chunk0 nhiều lần
-   completion vẫn combine đúng theo index

> Lưu ý: Unit test không guarantee reproduce race 100%, nhưng volume
> lớn + nhiều threads sẽ tăng xác suất.

------------------------------------------------------------------------

## 10.5 Instrumentation Test (Android) -- Stress BLE Ingestion Path

### Mục tiêu

Test gần thực tế hơn: callback threading + message pump +
WorkManager/service conditions.

### Setup

-   Build debug variant bật diagnostics
-   Inject a "test mode" API (nếu có) để call trực tiếp
    `reassembler.add()` từ multiple threads (không cần BLE thật), hoặc:
-   Tạo local test sender trong app gửi các chunk vào transport handler
    (mock device address)

### Scenario IT-1: Synthetic concurrent ingestion

-   Trong app, tạo 1 test activity/command:
    -   generate 10,000 messages, mỗi message 3--6 chunks
    -   mỗi message dispatch chunks từ 4 threads xen kẽ
-   Assert:
    -   complete_count == message_count
    -   crc_mismatch_count == 0
    -   dropped_count == 0
    -   entries_leaked_size == 0 sau TTL+1s

### Scenario IT-2: Service + background constraints

-   Start `BleForegroundService`
-   While screen off / Doze-ish (adb commands):
    -   keep service alive
    -   run same ingestion test
-   Verify không tăng CRC mismatch hoặc leak

------------------------------------------------------------------------

## 10.6 Multi-Device Field Test -- Real BLE Mesh

### Setup tối thiểu

-   3--6 Android devices (khác hãng càng tốt: Samsung/Xiaomi/Pixel)
-   1 device làm broadcaster gửi message chunked liên tục
-   2--5 device còn lại nhận cùng lúc

### Scenario FT-1: Broadcast storm

-   Sender gửi:
    -   1 message/100ms trong 3 phút
    -   message size đủ lớn để chunk 4--8 chunks
-   Receivers:
    -   log complete_count, crc_mismatch_count
-   Pass criteria:
    -   crc_mismatch_count \~ 0 (chấp nhận 0 tuyệt đối trong lab)
    -   dropped_count không spike bất thường
    -   complete_count tăng tuyến tính

### Scenario FT-2: Mobility + intermittent connectivity

-   Receivers di chuyển vào/ra range (RSSI thay đổi)
-   Sender vẫn gửi đều
-   Expect:
    -   packet loss tăng (bình thường), nhưng **không có corrupted
        payload** và **không có complete sai**

### Scenario FT-3: Mixed senders

-   2 sender cùng lúc gửi vào cùng group
-   Verify:
    -   no cross-sender contamination
    -   completion đúng từng sender

------------------------------------------------------------------------

## 10.7 Pass/Fail Criteria

### PASS (bắt buộc)

-   0 corrupted payload (payload mismatch)
-   0 completion "sai format" (missing part)
-   entries không leak sau TTL cleanup

### PASS (khuyến nghị)

-   crc_mismatch_count == 0 trong synthetic tests
-   crc_mismatch_count cực thấp trong field tests (nếu có, phải trace
    được nguyên nhân khác race)

### FAIL

-   bất kỳ case combine ra payload thiếu/đảo part do race
-   leak entries tăng theo thời gian
-   complete xảy ra 2 lần cho cùng (senderKey, chunkId)

------------------------------------------------------------------------

## 10.8 Gợi ý hỗ trợ debug

Nếu team muốn dễ trace: - Thêm log debug trong `Reassembler.add()`
khi: - receivedCount changes - complete happens - entry removed - Gắn
`messageSeq` vào payload ở test mode để verify order/uniqueness.

------------------------------------------------------------------------

**Appendix added: Sequence Diagram & Test Plan**
