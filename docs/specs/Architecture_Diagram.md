# Architecture Diagram (System Overview)

## 1. Mục tiêu
Tài liệu này mô tả sơ đồ kiến trúc tổng thể của hệ thống theo tinh thần offline-first, kết hợp mesh nội bộ và tùy chọn relay liên kết qua internet. Nội dung tổng hợp từ:
- `docs/specs/Project_Overview_Technical_Spec.md`
- `docs/server/FEDERATION_PROTOCOL.md`
- `docs/server/INTERNET_RELAY_OPTION.md`

Hệ thống được thiết kế sao cho **vẫn hoạt động đầy đủ khi không có internet**. Internet relay chỉ là một tùy chọn mở rộng phạm vi truyền thông tin.

---

## 2. Sơ đồ tổng quan (ASCII)

```
                    (Optional Internet)
+---------------------------------------------------------------+
|                     Federated Relay Network                   |
|                                                               |
|  +-----------+        +-----------+        +-----------+       |
|  | Relay A   | <----> | Relay B   | <----> | Relay C   |       |
|  | (opaque   |  sync  | (opaque   |  sync  | (opaque   |       |
|  |  events)  |        |  events)  |        |  events)  |       |
|  +-----------+        +-----------+        +-----------+       |
+---------------------------▲--------------------▲--------------+
                            |                    |
                            | push/pull          | push/pull
                            | (gateway mode)     | (gateway mode)
                            |                    |
                 +----------+---------+   +------+----------+
                 |  Mobile Client G1 |   | Mobile Client G2 |
                 | (Gateway capable) |   | (Gateway capable)|
                 +----------▲---------+   +------+----------+
                            |                    |
                            | BLE mesh           | BLE mesh
                            | (offline)          | (offline)
     +----------------------+--------------------+----------------------+
     |                         Local Mesh Cluster                        |
     |                                                                    |
     |  +-----------+   +-----------+   +-----------+   +-----------+     |
     |  | Client A  |<->| Client B  |<->| Client C  |<->| Client D  | ... |
     |  | (offline) |   | (offline) |   | (offline) |   | (offline) |     |
     |  +-----------+   +-----------+   +-----------+   +-----------+     |
     +--------------------------------------------------------------------+
```

---

## 3. Thành phần chính

### 3.1 Mobile Client (Offline-first node)
- Là nút chính của hệ thống.
- Lưu trữ event log trong SQLite (append-only).
- Reducers tạo ra trạng thái chat/map từ event.
- Chat theo mô hình group-only (không có threads).
- Trao đổi sự kiện qua BLE mesh (Android: custom BLE transport).
- Sử dụng passphrase để tạo `gid`, mã hóa và MAC.
- Join group bằng GID + salt + passphrase hoặc invitation link.
- Mesh forward: drop duplicate/expired envelopes; avoid forwarding corrupt blobs.

### 3.2 Gateway Mode (Client-as-Bridge)
- Bất kỳ client có internet có thể đóng vai trò gateway.
- **Push** event từ mesh lên relay.
- **Pull** event từ relay về mesh.
- Hoạt động best-effort, không ảnh hưởng UX nếu mất internet.

### 3.3 Relay Node (Federation Node)
- Server lưu/forward event blob (không giải mã nội dung).
- Deduplicate theo `eid` và enforce `exp`.
- Cung cấp API ingest/fetch, idempotent.
- Có thể đồng bộ với relay khác để đảm bảo eventual consistency.

---

## 4. Luồng dữ liệu chính

### 4.1 Offline mesh flow (mặc định)
1. Client tạo event (chat/map) -> lưu SQLite.
2. Event phát qua BLE mesh.
3. Client khác nhận event -> ghi log -> reducers cập nhật state.
4. Metadata (`SYS.GROUP.UPD`, `SYS.USER.UPD`) đi qua cùng pipeline.

### 4.2 Internet relay flow (tùy chọn)
1. Gateway client push batch event lên relay (POST ingest).
2. Relay lưu event (opaque), kiểm tra `exp`, `eid`.
3. Gateway client pull event từ relay (GET fetch, theo `gid` + cursor).
4. Event được re-inject vào mesh như sự kiện nội bộ.

---

## 5. Kỹ thuật và ràng buộc then chốt

- **Event-based, immutable**: mỗi event có `eid`, `gid`, `ts`, `exp`, `sig`.
- **Opaque payload**: relay không biết nội dung, chỉ lưu blob.
- **Idempotent**: ingest/fetch an toàn nếu retry.
- **Eventual consistency**: đồng bộ relay node không cần realtime.
- **No central registry**: client tự quản lý danh sách relay.
- **Offline-first**: internet relay không được là dependency.
- **Relay sync modes (Android)**:
  - **Normal**: sync khi có mạng.
  - **Battery Saver**: sync khi có mạng, chạy WorkManager mỗi 15 phút.
- **Relay discovery & selection (client)**:
  - **Phase 2**: chỉ có **seed relay list** do người dùng nhập.
  - **Phase 3**: discovery/auto-selection cho server + mobile.

---

## 6. Mapping với tài liệu gốc

- `Project_Overview_Technical_Spec.md`: kiến trúc client (UI, reducers, SQLite, BLE).
- `../server/INTERNET_RELAY_OPTION.md`: vai trò gateway, relay node, chính sách optional.
- `../server/FEDERATION_PROTOCOL.md`: invariants sự kiện, ingest/fetch, sync node-to-node.

---

## 7. Ghi chú triển khai

- Relay node khuyến nghị TLS + pinning.
- Rate limit và request caps để chống abuse.
- TTL/expiration là cơ chế chính cho retention.
- Không lưu user identity, không có accounts.

---

## 8. Kết luận

Sơ đồ kiến trúc nhấn mạnh 2 lớp kết nối:
- **Mesh offline** (mặc định, bắt buộc)
- **Federated relay** (tùy chọn, mở rộng tầm phủ sóng)

Hệ thống giữ tính chất đơn giản, tin cậy, và hoạt động được trong điều kiện mất kết nối internet.
