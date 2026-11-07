# Luồng xử lý Chat Snapshot + Refresh (VN)

Tài liệu mô tả chi tiết luồng xử lý theo yêu cầu: snapshot tạm thời sau Send, insert DB có retry, refresh UI sau 0.5s, debounce Send 1s, pending buffer khi đang refresh.

---

## Biến/trạng thái chính
- `snapshot`: danh sách message đang hiển thị (từ DB + pending buffer tạm).
- `refreshing`: boolean, true khi đang refresh UI từ DB.
- `pendingBuffer`: chỉ chứa **message do user gửi** trong lúc `refreshing = true`.
- `sendDebounceMs = 1000ms`
- `refreshDelayMs = 500ms`
- `insertRetryMax = 3`, `insertRetryCooldownMs = 500ms`

---

## 1) Khi user nhấn Send
1. Disable input + Send trong `sendDebounceMs = 1s`.
2. Insert message vào DB (background).
3. Nếu insert fail:
   - Retry tối đa 3 lần, mỗi lần cách nhau 500ms.
   - Nếu vẫn fail sau 3 lần → dừng (không append snapshot, không show error).
4. Nếu insert OK:
   - Nếu `refreshing = false`
     → append message vào snapshot
     → render snapshot ngay.
   - Nếu `refreshing = true`
     → push vào `pendingBuffer` (không render snapshot).
5. Schedule refresh UI sau `refreshDelayMs = 500ms` (debounce refresh, tránh bắn nhiều lần).

---

## 2) Khi refresh UI được kích hoạt
1. `refreshing = true`
2. Query DB → lấy list messages mới nhất.
3. Replace snapshot bằng DB result.
4. Append toàn bộ `pendingBuffer` vào cuối snapshot.
5. Clear `pendingBuffer`.
6. Render snapshot.
7. `refreshing = false`

---

## 3) Debounce Send
- Sau mỗi Send, input + Send bị disable 1s để tránh spam.

---

## Sơ đồ ASCII

```
User taps Send
      |
      v
Disable input+send (1s)
      |
      v
Insert DB (bg) ------------------------------+
      |                                     |
      | success                             | fail
      v                                     v
refreshing?                                retry <= 3
 |                                         (cooldown 0.5s)
 |-- false --> append snapshot + render     |
 |-- true  --> push pendingBuffer           |
      |
      v
Schedule Refresh (delay 0.5s, debounced)
      |
      v
[Refresh UI]
refreshing = true
      |
      v
Query DB -> snapshot = DB result
      |
      v
Append pendingBuffer -> clear buffer
      |
      v
Render snapshot
      |
      v
refreshing = false
```
