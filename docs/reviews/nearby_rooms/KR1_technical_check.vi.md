# KR1 Technical Check

Packet: `KR1` / `NEARBY_ROOM_KEY_REQUEST_V1`

## Contract kỳ vọng

- Joiner phát `KR1` khi cần room key.
- Send path là broadcast.
- Receiver có room key có thể reply `KB1`.
- `KR1` được phép xử lý dù receiver chưa ở active-room session.

## Hiện trạng code

- `KR1` bypass active-room gate:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:26`
- `KR1` receive -> `shouldReplyToKeyRequest()` -> `sendKeyBootstrap(...)`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:48`
- `KR1` send là broadcast:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:98`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:110`
- Self-reply bị chặn, reply có cooldown theo `(roomId, requesterDeviceId)`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:418`

## Đánh giá

Trạng thái: `Đạt một phần`

Điểm đạt:

- Đúng broadcast flow.
- Đúng bypass gate.
- Có anti-spam ở phía responder.

Khoảng trống:

- Không có cooldown transport-layer ở phía sender.
- Nhánh `M2` thiếu key có thể phát `KR1` lặp lại nhiều lần:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:224`
- UI room screen có cooldown 30s, nhưng chỉ bao phủ flow màn hình chat:
  - `android/app/src/main/java/app/peermesh/NearbyRoomActivity.kt:415`

## Kết luận

`KR1` đúng contract cốt lõi, nhưng dedupe phía sender còn yếu.
