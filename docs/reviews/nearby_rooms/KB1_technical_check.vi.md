# KB1 Technical Check

Packet: `KB1` / `NEARBY_ROOM_KEY_BOOTSTRAP_V1`

## Contract kỳ vọng

- Peer có room key trả `KB1` cho requester.
- Send path là directed.
- Receiver lưu room key và trigger post-join metadata refresh.

## Hiện trạng code

- `KB1` send dùng `broadcastEnvelopeToDeviceIds(..., setOf(targetDeviceId))`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:65`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:87`
- `KB1` bypass active-room gate:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:26`
- Receive path:
  - store key: `maybeStoreBootstrapKey(...)`
  - sync authoritative `created_at_ms`
  - enqueue post-join metadata refresh
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:65`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:72`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:87`

## Đánh giá

Trạng thái: `Đạt một phần`

Điểm đạt:

- Đúng directed reply.
- Đúng side effect lưu key và kéo metadata tiếp.

Khoảng trống:

- `maybeStoreBootstrapKey()` chỉ kiểm tra room tồn tại và key decode được; chưa thấy xác thực “source peer có thẩm quyền” ngoài logic mạng hiện tại:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:365`
- Về mặt blueprint, đây là vùng cần test kỹ khi có relay peer cùng giữ key.

## Kết luận

`KB1` bám đúng lifecycle chính, nhưng trust model của nguồn bootstrap key vẫn khá mở.
