# Phase 2 Android UI Checklist (Threat-Aware)

Tài liệu này chuyển Threat Classes thành checklist UI cụ thể (wireframes + component list) cho Phase 2 Android.

---

## 1) Groups Home (Group List)

### Wireframe (text)
```
┌───────────────────────────┐
│ Peer Mesh      ⓘ        │
├───────────────────────────┤
│ Groups                    │
├───────────────────────────┤
│ Group: Area A             │
│ gid: abcd1234…            │
├───────────────────────────┤
│ Group: Volunteers         │
│ gid: efgh5678…            │
├───────────────────────────┤
│ Group: Family             │
│ gid: ijkl9012…            │
├───────────────────────────┤
│ [Create/Join on Test]     │
└───────────────────────────┘
```

### Component list
- Top bar: app title + “ⓘ limitations”
- Group list item: name + gid preview
- Empty state: “No groups yet. Create on Test page.”

### Checklist (Threat-aware)
- [ ] Không hiển thị “verified/official” trong group list.
- [ ] Empty state trung tính, không “urgent”.

---

## 2) Group Chat (Messages)

### Wireframe (text)
```
┌───────────────────────────┐
│ Group: Area A   ⓘ        │
├───────────────────────────┤
│ (Report) “Observed ...”   │
│ 2 sources • 5m ago • TTL  │
│ [Details]                 │
├───────────────────────────┤
│ (Report) “Unverified ...” │
│ 1 source • 2m ago         │
├───────────────────────────┤
│ [Composer: 200 chars]     │
└───────────────────────────┘
```

### Component list
- Group header: name + limitations icon
- Message card: text, source count, timestamp, TTL badge
- Detail sheet: list of sources (anonymized), raw timestamps
- Composer: 200‑char counter + neutral prompt

### Checklist
- [ ] Hiển thị “reported/observed/unverified” tag.
- [ ] TTL/freshness hiển thị rõ trong mỗi message.
- [ ] Không gợi ý “official” hay “urgent”.
- [ ] Character limit 200 enforced at UI.
- [ ] Progressive disclosure under load là tuỳ chọn; ưu tiên áp dụng cho Map UI (Phase 2).

---

## 3) Map View

### Wireframe (text)
```
┌───────────────────────────┐
│ Map   Filters: Danger...  │
├───────────────────────────┤
│   (markers w/ badges)     │
│   [•] 2 sources • 4m      │
│   [•] 1 source • 12m      │
├───────────────────────────┤
│ Marker detail sheet       │
│ “Reported: blocked road”  │
│ 2 sources • TTL 20m       │
│ [View sources]            │
└───────────────────────────┘
```

### Component list
- Map canvas + filter chips
- Marker badge: source count + age
- Detail bottom sheet: text, TTL, source count
- “View sources” modal (anonymized)

### Checklist
- [ ] Không dùng label “safe/official”; dùng “reported/observed”.
- [ ] Marker luôn hiển thị freshness + TTL.
- [ ] Mặc định ẩn marker cũ (TTL expired).
- [ ] Khuyến nghị “verify independently” ở detail sheet.
- [ ] Progressive disclosure under load: group/cluster markers tương tự để tránh spam khi load cao.

---

## 4) Group Join/Create

### Wireframe (text)
```
┌───────────────────────────┐
│ Join/Create Group         │
├───────────────────────────┤
│ Passphrase [...........]  │
│ [Join]  [Create]          │
│ Notice: no authority      │
└───────────────────────────┘
```

### Component list
- Passphrase input
- Join/Create buttons
- Notice text (no authority/verified)

### Checklist
- [ ] Hiển thị “no authority/verification” notice.
- [ ] Không hiển thị member rank/role.

---

## 5) Alerts (Optional SYS.ALERT)

### Wireframe (text)
```
┌───────────────────────────┐
│ Alert (Unverified)        │
├───────────────────────────┤
│ “Reported: ...”           │
│ 1 source • 3m ago         │
│ [Dismiss] [Details]       │
└───────────────────────────┘
```

### Component list
- Alert banner/card
- Dismiss button
- Details modal (sources + TTL)

### Checklist
- [ ] Không dùng màu cảnh báo quá mạnh; tránh gây hoảng loạn.
- [ ] Luôn hiển thị “unverified” + timestamp.

---

## 6) Limitations & Safety

### Wireframe (text)
```
┌───────────────────────────┐
│ Limitations               │
├───────────────────────────┤
│ • Reports may be wrong    │
│ • No verification badges  │
│ • Always verify yourself  │
└───────────────────────────┘
```

### Component list
- Static info screen
- Link from top bar “ⓘ”

### Checklist
- [ ] Dễ truy cập từ mọi screen chính.
- [ ] Ngắn gọn, không mập mờ.
- [ ] Bắt buộc cho Emergency flavor; Commercial có thể bỏ.

---

## 7) System Status

### Wireframe (text)
```
┌───────────────────────────┐
│ Mesh: ON  Relay: OFF      │
│ Nearby nodes: ~3          │
└───────────────────────────┘
```

### Component list
- Status bar widget
- Optional nearby count (SYS.PRESENCE)

### Checklist
- [ ] Trạng thái rõ ràng, tránh hiểu nhầm “official coverage”.
- [ ] Không suy diễn số node thành “độ tin cậy”.

---

## 8) Copywriting Guidelines (UI strings)

- Dùng “reported”, “observed”, “unverified”.
- Tránh “official”, “safe”, “urgent”.
- Nhắc “Please verify independently” ở context quan trọng.

---

## 9) QA Scenarios (UX Guardrails)

**App**
- [ ] Không có group → hiển thị empty state rõ ràng, không gây hiểu nhầm.
- [ ] Tạo 2-3 group → list hiển thị đúng, click vào mở đúng group chat.
- [ ] 20 messages trong 1 group → list messages vẫn mượt, không crash.
- [ ] Join group sai passphrase → báo lỗi rõ ràng, không tạo group mới.
- [ ] Xoá group trong local DB rồi mở lại chat screen → quay về list, không crash.
- [ ] Nhập gid không hợp lệ → báo lỗi, không lưu.
- [ ] Đổi theme khi đang ở GroupChatActivity → UI cập nhật đúng, không crash.
- [ ] User tắt quyền BLE → app báo rõ ràng, không crash.
- [ ] Bị OS kill khi chạy nền → mở lại app phục hồi state đúng.
- [ ] Thiếu dung lượng lưu trữ → DB write fail, UI báo lỗi.
- [ ] Clock lệch lớn giữa thiết bị → LWW xử lý đúng, không đảo thứ tự bất thường.
- [ ] Message hết TTL nhưng còn DB → UI ẩn đúng, cleanup chạy sau.
- [ ] Duplicate events (flood) → dedupe hoạt động, state không nhân bản.
- [ ] Marker cũ quá TTL → tự ẩn khỏi map.
- [ ] Nội dung gắn “official” → UI gợi ý sửa/không cho tag đó.
- [ ] Alert spam → không làm rung/flash liên tục.
- [ ] BLE mesh: nhiều thiết bị gần nhau → không crash, không bị treo gửi.
- [ ] BLE mesh: thiết bị rời khỏi vùng → hệ thống bỏ kết nối, không kẹt queue.
- [ ] BLE mesh: flood traffic → backpressure hoạt động, app vẫn responsive.
- [ ] BLE mesh: foreground service bị kill → healthcheck restart đúng nếu đủ điều kiện.
- [ ] BLE mesh: MTU negotiation thành công → chunk size tăng; nếu fail → fallback an toàn.
- [ ] BLE mesh: RSSI ưu tiên thiết bị gần → gửi/nhận ưu tiên device gần trước.
- [ ] BLE mesh: không vượt quá 4 kết nối GATT đồng thời (exceed → queue/round‑robin).
- [ ] BLE mesh: reassembly sai CRC/hash → drop, không forward tiếp.
- [ ] BLE mesh: envelope MAC/validate fail → không forward, không crash.
- [ ] BLE mesh: TTL update không làm hỏng JSON (no regex corruption).
- [ ] BLE mesh: per-device queue vượt ngưỡng → drop/defers đúng, không leak memory.
- [ ] BLE mesh: idle vs traffic → scan/advertise duty cycle thay đổi hợp lý.
- [ ] BLE mesh ACK (received): sender retry 5x @ 5s và dừng khi received_group_ack_count>=1 & received_ack_count>=4.
- [ ] BLE mesh ACK (received): receiver offline → sender retry đủ 5 lần rồi dừng.
- [ ] BLE mesh ACK (seen): chat mở và message render → sender hiển thị 2 avatar mới nhất + “+x others”.
- [ ] BLE mesh ACK (seen): ordering avatar theo thời điểm seen mới nhất.

**Relay**
- [ ] Xem checklist riêng: `docs/relay/relay_qa_checklist.md`.

**Network**
- [ ] Mạng chập chờn khi relay sync đang chạy → không crash, trạng thái hiển thị hợp lý.
- [ ] Đổi SIM / mất quyền mạng → relay sync dừng đúng, tự phục hồi khi có mạng.
- [ ] Mạng chập chờn khi push/pull → cursor không nhảy sai.
