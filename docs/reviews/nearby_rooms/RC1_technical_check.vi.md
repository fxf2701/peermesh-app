# RC1 Technical Check

Packet: `RC1` / `NEARBY_ROOM_CLOSED_V1`

## Contract kỳ vọng

- Owner đóng room thì phát `RC1`.
- Receiver active-room sẽ mark room closed, relay một lần, purge local data, và ghi UI notice.

## Hiện trạng code

- Owner close -> `repository.closeRoom(roomId)` rồi `transportManager.sendRoomClosed(roomId)`:
  - `android/app/src/main/java/app/peermesh/NearbyRoomActivity.kt:330`
- `RC1` send là broadcast:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:324`
- Receive path:
  - `closeRoomFromTransport(...)`
  - `relayRoomClosed(...)`
  - `purgeRoomLocalData(...)`
  - `recordOwnerClosedNotice(...)`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:157`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:169`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:175`
- Dedupe relay tồn tại:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:406`

## Đánh giá

Trạng thái: `Đạt`

Điểm đạt:

- Lifecycle phù hợp blueprint.
- Có relay dedupe.
- Có terminal cleanup và UI notice.

Lưu ý:

- Relay `RC1` đang preserve nguyên envelope, không rewrite `sender_device_id`. Với `RC1` hiện tại điều này giúp dedupe ổn định hơn, nhưng cần giữ nhất quán nếu sau này contract thay đổi.

## Kết luận

`RC1` hiện là flow hoàn chỉnh và khá sạch.
