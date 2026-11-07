# MS1 Technical Check

Packet: `MS1` / `NEARBY_ROOM_METADATA_RESPONSE_V1`

## Contract kỳ vọng

- `MS1` trả metadata đầy đủ cho requester.
- Send path là directed.
- Receiver merge metadata, chuyển room sang `RESOLVED`, và có thể phát `JR1` nếu đã sẵn key.

## Hiện trạng code

- `MS1` send là directed response:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:141`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:158`
- Payload mang `room_name`, `participant_count`, owner info, timing:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:145`
- Receive path:
  - bỏ qua nếu thiếu `room_name`
  - `markRoomMetadataResolved(...)`
  - `onMetadataResolved(...)`
  - nếu chat access ready thì phát `JR1`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:111`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:120`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:130`

## Đánh giá

Trạng thái: `Đạt một phần`

Điểm đạt:

- Đúng directed lifecycle.
- Đúng side effect chuyển room sang `RESOLVED`.
- Đúng hook phát `JR1` sau khi đủ key + metadata.

Khoảng trống:

- Discovery list đang phụ thuộc tuyệt đối vào `MS1` thành công:
  - `android/data/src/main/java/app/peermesh/data/db/dao/NearbyRoomDao.kt:24`
- `participant_count` trong `MS1` lấy từ room row hiện tại; nếu room row sai thì metadata cũng lan truyền giá trị sai.

## Kết luận

`MS1` là packet quyết định room có hiện trên discovery hay không; implementation hợp đồng nhưng ảnh hưởng UX rất lớn khi timeout hoặc metadata nguồn không ổn.
