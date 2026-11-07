# M2 Technical Check

Packet: `M2` / `NEARBY_ROOM_MESSAGE_V2`

## Contract kỳ vọng

- Mang encrypted room message.
- Nếu receiver có room key thì decrypt và ingest.
- Nếu chưa có key thì phát `KR1`.
- Sau ingest, message có thể tham gia relay recovery flow.

## Hiện trạng code

- Send path encrypt với AAD gồm `room_id`, `message_id`, origin sender, `created_at_ms`, `expires_at_ms`, `key_version`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:23`
- Owner có thể gửi targeted set, còn mặc định là broadcast:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:58`
- Receive path:
  - nếu có key -> decrypt -> `ingestRemoteTransportMessage(...)`
  - nếu thiếu key -> `sendKeyRequest(...)`
  - ingest xong -> `onMessageAvailable(...)`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:188`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:210`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:224`

## Đánh giá

Trạng thái: `Đạt một phần`

Điểm đạt:

- Đúng encrypted-message flow.
- Đúng key-missing fallback.
- Đúng handoff sang relay recovery.

Khoảng trống:

- Khi thiếu key, `M2` có thể kéo theo `KR1` spam vì không có cooldown ở receive path.
- `ingestRemoteTransportMessage()` cập nhật participant row và `participant_count`, nên nếu origin mapping sai sẽ ảnh hưởng presence/room summary:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomRepository.kt:379`

## Kết luận

`M2` đúng contract chính, nhưng là nơi rõ nhất cho vấn đề dedupe chưa đủ chặt ở nhánh `missing_key -> KR1`.
