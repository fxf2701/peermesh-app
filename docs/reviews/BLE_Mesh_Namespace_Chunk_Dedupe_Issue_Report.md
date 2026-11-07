# BLE Mesh -- Issue Report & Patch Proposal

## Namespace Chunk Dedupe theo Sender

**Project:** Peer Mesh\
**Module:** BLE Mesh Transport\
**Scope:** ChunkDeduper & Reassembler logic\
**Priority:** HIGH (Data Integrity & Reliability Risk)\
**Author:** Internal Review

------------------------------------------------------------------------

## 1. Executive Summary

Trong môi trường BLE Mesh nhiều peer, hệ thống hiện tại đang thực hiện
**chunk dedupe** và **reassembly** chỉ dựa trên:

-   `chunkId`
-   `index`

Điều này dẫn đến nguy cơ **collision cross-sender**, nơi hai thiết bị
khác nhau có thể vô tình sử dụng cùng `chunkId` trong cùng TTL window.

Hệ quả:

-   Chunk hợp lệ bị drop sai (false positive dedupe)
-   Reassembly trộn chunk từ hai sender khác nhau
-   CRC mismatch không ổn định
-   Packet loss "ngẫu nhiên" rất khó debug

Giải pháp đề xuất: **Namespace chunk dedupe và reassembly theo
senderKey**.

------------------------------------------------------------------------

## 2. Phân tích vấn đề hiện tại

### 2.1 ChunkDeduper hiện tại

``` kotlin
private data class Key(val chunkId: Int, val index: Int)
```

Vấn đề: - Không phân biệt sender - Nếu sender A và sender B cùng tạo
`chunkId=1234` và `index=0` - Trong TTL window → chunk của B sẽ bị drop

### 2.2 Reassembler hiện tại

``` kotlin
private val entries = ConcurrentHashMap<Int, Entry>()
```

Key = `chunkId`

Nếu collision: - Chunk của 2 sender có thể bị merge chung entry - Hoặc
bị reject do CRC mismatch

### 2.3 Xác suất collision

Hiện tại:

``` kotlin
val chunkId = (System.nanoTime() and 0xFFFFFFFFL).toInt()
```

-   32-bit space
-   Mesh nhiều peer broadcast đồng thời
-   Collision không phải 0%
-   Càng nhiều thiết bị → rủi ro càng tăng

------------------------------------------------------------------------

## 3. Impact Assessment

  Risk                 Severity   Description
  -------------------- ---------- --------------------------------
  False drop           HIGH       Chunk hợp lệ bị drop
  Payload corruption   HIGH       Reassembly trộn sender
  Debug difficulty     HIGH       Lỗi xảy ra ngẫu nhiên
  Scalability          HIGH       Càng nhiều peer càng dễ xảy ra

------------------------------------------------------------------------

## 4. Giải pháp đề xuất

### 4.1 Nguyên tắc

Mọi cấu trúc dedupe và reassembly phải được namespace theo:

    (senderKey, chunkId)

senderKey có thể là:

-   `device.address` (BluetoothDevice address)
-   hoặc `advertisedDeviceId`
-   hoặc peerKey stable identifier

------------------------------------------------------------------------

## 5. Patch Proposal -- ChunkDeduper

### BEFORE

``` kotlin
private data class Key(val chunkId: Int, val index: Int)
```

### AFTER

``` kotlin
private data class Key(
    val senderKey: String,
    val chunkId: Int,
    val index: Int
)
```

### Update seen()

``` kotlin
fun seen(senderKey: String, chunk: ByteArray, nowSeconds: Long): Boolean {
    if (chunk.size < minHeaderSize) return false

    val buffer = ByteBuffer.wrap(chunk)
    val chunkId = buffer.int
    val index = buffer.get().toInt() and 0xFF

    val key = Key(senderKey, chunkId, index)
    val existing = entries[key]

    if (existing == null || existing < nowSeconds) {
        entries[key] = nowSeconds + ttlSeconds
        return false
    }

    return true
}
```

### Update call site (BleMeshTransport)

``` kotlin
val senderKey = device.address
if (chunkDeduper.seen(senderKey, value, nowSeconds)) {
    return
}
```

------------------------------------------------------------------------

## 6. Patch Proposal -- Reassembler

### BEFORE

``` kotlin
private val entries = ConcurrentHashMap<Int, Entry>()
```

### AFTER

``` kotlin
private data class ReassemblyKey(
    val senderKey: String,
    val chunkId: Int
)

private val entries = ConcurrentHashMap<ReassemblyKey, Entry>()
```

### Update add()

``` kotlin
fun add(senderKey: String, chunk: ByteArray): ByteArray? {
    val buffer = ByteBuffer.wrap(chunk)

    val chunkId = buffer.int
    val index = buffer.get().toInt() and 0xFF
    val total = buffer.get().toInt() and 0xFF
    val crc32 = buffer.int

    val key = ReassemblyKey(senderKey, chunkId)

    val entry = entries.computeIfAbsent(key) {
        Entry(total, crc32)
    }

    ...
}
```

### Update call site

``` kotlin
val senderKey = device.address
val complete = reassembler.add(senderKey, value)
```

------------------------------------------------------------------------

## 7. Optional Enhancement (Recommended)

Để tăng an toàn hơn nữa:

-   Thay chunkId generation bằng:
    -   Atomic counter + session salt
    -   hoặc SecureRandom.nextInt()

Ví dụ:

``` kotlin
private val chunkCounter = AtomicInteger()

val chunkId = chunkCounter.incrementAndGet()
```

------------------------------------------------------------------------

## 8. Migration Impact

  Area               Impact
  ------------------ ---------------------------
  Network Protocol   Không thay đổi
  Header Format      Không thay đổi
  Compatibility      Backward compatible
  Performance        Không đáng kể
  Memory             Tăng nhẹ do namespace key

------------------------------------------------------------------------

## 9. Expected Outcome

Sau patch:

-   Không còn cross-sender dedupe collision
-   Reassembly hoàn toàn tách biệt theo peer
-   Mesh ổn định khi số lượng peer tăng
-   Giảm packet loss khó debug

------------------------------------------------------------------------

## 10. Conclusion

Namespace chunk dedupe theo sender là bắt buộc trong môi trường mesh
multi-peer.

Không sửa sẽ dẫn đến: - Packet loss ngẫu nhiên - CRC mismatch khó tái
hiện - Hành vi không ổn định khi scale

Đề xuất implement ngay trong sprint gần nhất.

------------------------------------------------------------------------

**End of Document**
