# MF1 Technical Check

Packet: `MF1` / `NEARBY_ROOM_MESSAGE_FETCH_V1`

## Contract kỳ vọng

- Target peer chọn một source candidate rồi phát `MF1` để xin full payload.
- Send path là directed.
- Source nếu có message thì trả `MD1`.

## Hiện trạng code

- `MF1` send là directed:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:264`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:281`
- Receive path vào `NearbyRoomRelayRecoveryManager.onMessageFetchReceived(...)`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:185`
- Source lookup compact id rồi trả `MD1` nếu có:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomRelayRecoveryManager.kt:70`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomRelayRecoveryManager.kt:74`

## Đánh giá

Trạng thái: `Đạt`

Điểm đạt:

- Directed pull đúng contract.
- Worker có candidate rotation và timeout ở want queue:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomRelayRecoveryManager.kt:142`

## Kết luận

`MF1` đúng thiết kế và đã có basic retry/rotation.
