# MO1 Technical Check

Packet: `MO1` / `NEARBY_ROOM_MESSAGE_OFFER_V1`

## Contract kỳ vọng

- Source peer báo cho target rằng nó có message compact id để target kéo nếu cần.
- Send path là directed.
- Receiver chỉ tạo want-list item nếu chưa có message và chưa có want active.

## Hiện trạng code

- `MO1` send là directed:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:242`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:260`
- Receive path đi vào `NearbyRoomRelayRecoveryManager.onMessageOfferReceived(...)`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:182`
- Manager chỉ tạo want khi:
  - room active
  - local chưa có message compact id đó
  - want hiện tại không ở `PENDING/REQUESTING/RESOLVED`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomRelayRecoveryManager.kt:41`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomRelayRecoveryManager.kt:48`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomRelayRecoveryManager.kt:52`

## Đánh giá

Trạng thái: `Đạt`

Điểm đạt:

- Directed đúng contract.
- Có chặn duplicate want tương đối tốt.

## Kết luận

`MO1` đang là flow recovery sạch và hợp lý.
