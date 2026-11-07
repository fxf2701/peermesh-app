# BLE Mesh App Batch Envelope + App-Signature

## 1. Mục tiêu

- Gom nhiều `EventEnvelope` vào 1 gói app-level để giảm overhead BLE.
- Ký app-level bằng HMAC để lọc các gói không thuộc hệ sinh thái app.
- Giới hạn payload để tránh crash `Chunking` khi MTU thấp.

## 2. Phạm vi triển khai

- Bên gửi đóng gói `EventEnvelope` thành `AppBatchEnvelope`.
- Bên nhận verify HMAC, sau đó giải mã từng item và ingest như thường lệ.
- Không thay đổi schema `EventEnvelope`/`EventCore`.

## 3. Định dạng dữ liệu

`AppBatchEnvelope` (JSON canonical):
- `v`: version (hiện tại `1`)
- `kind`: fixed `"APP_BATCH"`
- `ts`: timestamp (ms)
- `items`: list các `EventEnvelope` đã được serialize và base64url
- `app_sig`: HMAC (SHA-256), trên canonical JSON không có `app_sig`

## 4. Ký & xác thực

- `AppBatchCrypto.sign(...)` tạo `app_sig` bằng HMAC-SHA256.
- `EnvelopeReceiverImpl`:
  - Nếu `kind == APP_BATCH` thì verify HMAC.
  - Verify pass -> decode `items` -> ingest từng item.
  - Verify fail -> drop.
- Non-batch envelope:
  - Nếu `REQUIRE_APP_SIGNATURE=true` thì drop mọi envelope không đến từ `RELAY`.

## 5. Window batch theo loại

- `MESSAGE`: 3s
- `ACK`: 1.5s
- `MAP`: 3s
- `TRAIL`: 8s
- `OTHER`: 5s

## 6. Giới hạn payload

- `MAX_BATCH_BYTES = 4KB`
- `HARD_MAX_PAYLOAD_BYTES = 2.4KB`
- Nếu batch vượt hard cap sau khi ký/encode -> drop batch (log cảnh báo).

## 7. Hạn chế hiện tại

- Batcher chỉ giữ pending trong RAM (không persistence). Nếu app/service bị kill trước khi flush thì batch mất.
- Không có resend ở tầng batcher (broadcast 1 lần).
- Chưa có cơ chế event_batch + sent_device tracking (backlog).

## 8. Backlog đề xuất

- Event batch persistence + per-device sent tracking + resend loop (TTL 5 phút).
- Nếu 1 item vượt hard cap -> nghiên cứu item-fragment + reassemble trước khi render.

