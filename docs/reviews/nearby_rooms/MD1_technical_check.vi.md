# MD1 Technical Check

Packet: `MD1` / `NEARBY_ROOM_MESSAGE_DATA_V1`

## Contract kỳ vọng

- Source trả full encrypted payload cho requester.
- Send path là directed.
- Receiver decrypt, ingest, mark want resolved, rồi có thể fan out `MO1`.

## Hiện trạng code

- `MD1` send là directed:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:285`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:320`
- Receive path:
  - decrypt bằng room key
  - `ingestRemoteTransportMessage(...)`
  - `onMessageDataResolved(...)`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:233`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:262`
- `onMessageDataResolved(...)`:
  - mark want `RESOLVED`
  - gọi lại `onMessageAvailable(...)` để tiếp tục offer outward
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomRelayRecoveryManager.kt:80`

## Đánh giá

Trạng thái: `Đạt`

Điểm đạt:

- Đúng full-response flow.
- Đúng side effect “message recovered rồi tiếp tục lan outward”.

## Kết luận

`MD1` phù hợp contract và khép kín vòng relay recovery.
