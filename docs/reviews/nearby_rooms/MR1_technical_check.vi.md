# MR1 Technical Check

Packet: `MR1` / `NEARBY_ROOM_METADATA_REQUEST_V1`

## Contract kỳ vọng

- Discovery flow hoặc post-join flow phát `MR1`.
- Send path là directed tới một source đã chọn.
- Receiver kiểm tra room còn active, serveable, cooldown ổn thì trả `MS1`.

## Hiện trạng code

- Discovery screen enqueue metadata fetch cho toàn bộ `activeRooms`:
  - `android/app/src/main/java/app/peermesh/NearbyRoomsActivity.kt:137`
- Post-join refresh cũng enqueue riêng:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomMetadataFetchCoordinator.kt:44`
- Worker chọn `targetDeviceIdOverride ?: room.metadata_source_device_id` rồi send `MR1` directed:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomMetadataFetchCoordinator.kt:131`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomMetadataFetchCoordinator.kt:146`
- `MR1` bypass active-room gate:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:26`
- Receive -> `shouldReplyToMetadataRequest()`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:94`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:448`

## Đánh giá

Trạng thái: `Đạt một phần`

Điểm đạt:

- Đúng directed flow.
- Có concurrency cap, timeout, retry window và cooldown reply.

Khoảng trống:

- Nếu `metadata_source_device_id` sai, trống, hoặc trỏ về local device thì fetch bị fail/skip:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomMetadataFetchCoordinator.kt:133`
- Discovery UI hiện phụ thuộc mạnh vào `MR1/MS1` vì `listVisible()` chỉ lấy room đã resolved.

## Kết luận

`MR1` flow đủ khung, nhưng UX discovery còn mong manh vì metadata resolution là nút thắt bắt buộc.
