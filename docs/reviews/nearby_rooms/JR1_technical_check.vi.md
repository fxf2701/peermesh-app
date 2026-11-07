# JR1 Technical Check

Packet: `JR1` / `NEARBY_ROOM_JOIN_READY_V1`

## Contract kỳ vọng

- Chỉ emit khi joiner đã có:
  - room key
  - metadata resolved
- Broadcast tới active nearby peers trong room.
- Relay giữ nguyên `origin_device_id`, chỉ rewrite `sender_device_id`.
- Không echo về sender hiện tại, origin joiner, và có thể loại owner.
- Có dedupe theo `(room_id, origin_device_id, created_at_ms)`.

## Hiện trạng code

- `JR1` chỉ phát khi `isChatAccessReady(roomId)`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:169`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:399`
- `JR1` hiện được trigger sau `MS1` receive nếu owner id có sẵn và access đã ready:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:130`
- Payload dùng compact fields qua `NearbyRoomTransportCodec`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportCodec.kt:12`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportCodec.kt:170`
- Relay path loại trừ:
  - local device
  - `originDeviceId`
  - `room.owner_device_id`
  - `envelope.sender_device_id`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:217`
- Dedupe key:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:412`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:496`
- Receive path ghi participant join success và tạo system line `"Someone nearby joined."` qua repository:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:143`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomRepository.kt:429`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomRepository.kt:715`

## Đánh giá

Trạng thái: `Đạt một phần`

Điểm đạt:

- Logic origin/sender đúng ý blueprint.
- Chống phát ngược đã loại cả `sender_device_id` lẫn `origin_device_id`.
- Có dedupe relay đúng shape.

Khoảng trống:

- `sendJoinReady(roomId, targetDeviceId)` nhận tham số `targetDeviceId` nhưng thực tế không route theo tham số này; nó lấy target từ snapshot nearby peers:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:188`
- Cooldown send là theo `roomId`, không theo event key, nên các join-success liên tiếp trong cùng room có thể bị ghìm trong cửa sổ cooldown:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:173`

## Kết luận

`JR1` là packet được implement sát contract nhất, nhưng cooldown send theo room là một điểm cần theo dõi khi có nhiều joiner gần nhau.
