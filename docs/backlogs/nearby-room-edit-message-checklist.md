# Nearby Room Edit Message Checklist

## Audit

- [x] Rà current Nearby Room chat code path
- [x] So sánh với Private Group edit-message implementation
- [x] Chỉ rõ khác biệt về data model / reducer / transport
- [x] Chốt hướng MVP pragmatic thay vì copy `CHAT.EDIT`

Acceptance criteria:
- [x] Có doc nêu rõ phần reuse được và phần phải làm riêng.
- [x] Scope MVP được cố định trước khi implement.

## Data Model
File: [android/data/src/main/java/app/peermesh/data/db/entity/NearbyRoomMessageEntity.kt](/home/truong/projects/disaster-app/android/data/src/main/java/app/peermesh/data/db/entity/NearbyRoomMessageEntity.kt:14)

- [x] Thêm `edited_at_ms: Long?`
- [x] Giữ `message_id` làm identity của message snapshot
- [x] Không overload `received_at_ms` làm source-of-truth cho badge

Acceptance criteria:
- [x] Badge edit có persisted source-of-truth riêng.
- [x] Existing rows vẫn upgrade được qua migration.

## Migration
File: [android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt](/home/truong/projects/disaster-app/android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt:124)
File: [android/data/src/main/java/app/peermesh/data/db/DatabaseMigrations.kt](/home/truong/projects/disaster-app/android/data/src/main/java/app/peermesh/data/db/DatabaseMigrations.kt:1552)
File: [android/data/schemas/app.peermesh.data.db.AppDatabase/45.json](/home/truong/projects/disaster-app/android/data/schemas/app.peermesh.data.db.AppDatabase/45.json:1)

- [x] Bump Room version `44 -> 45`
- [x] Add `MIGRATION_44_45`
- [x] Add column `edited_at_ms INTEGER`
- [x] Export schema `45.json`

Acceptance criteria:
- [x] App upgrade không crash vì thiếu cột edit metadata cho Nearby Room.
- [x] Schema Room khớp code hiện tại.

## Merge Hardening
File: [android/app/src/main/java/app/peermesh/nearby/NearbyRoomMessageEditReducer.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/nearby/NearbyRoomMessageEditReducer.kt:1)
File: [android/app/src/main/java/app/peermesh/nearby/NearbyRoomRepository.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/nearby/NearbyRoomRepository.kt:328)

- [x] Thêm reducer nhỏ cho Nearby Room snapshot merge
- [x] Reject actor mismatch
- [x] Reject stale edit
- [x] Reject conflicting base snapshot không có `edited_at_ms`
- [x] Reject downgrade từ edited về unedited
- [x] Thêm `editLocalMessage(...)`
- [x] Apply reducer khi ingest remote snapshot

Acceptance criteria:
- [x] Nearby Room có hardening tương đương mức MVP, dù không có event reducer đầy đủ như Private Group.
- [x] Edit của người khác không ghi đè message local row.

## Transport
File: [android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportEnvelope.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportEnvelope.kt:1)
File: [android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportCodec.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportCodec.kt:1)
File: [android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:1)
File: [android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:1)

- [x] Carry `edited_at_ms` in message envelopes
- [x] Reuse existing message snapshot transport thay vì tạo `CHAT.EDIT`
- [x] Preserve relay fetch compatibility vì `message_id` không đổi
- [x] Bổ sung log send / recv cho propagation debug

Acceptance criteria:
- [x] Peer nhận snapshot mới có thể converge sang edited content.
- [x] Relay fetch path không bị vỡ bởi edit MVP.

## UI
File: [android/app/src/main/java/app/peermesh/NearbyRoomActivity.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/NearbyRoomActivity.kt:32)
File: [android/app/src/main/java/app/peermesh/NearbyRoomMessageAdapter.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/NearbyRoomMessageAdapter.kt:1)
File: [android/app/src/main/res/layout/activity_nearby_room.xml](/home/truong/projects/disaster-app/android/app/src/main/res/layout/activity_nearby_room.xml:56)
File: [android/app/src/main/res/layout/item_nearby_room_message.xml](/home/truong/projects/disaster-app/android/app/src/main/res/layout/item_nearby_room_message.xml:29)

- [x] Thêm action `Edit` cho own message
- [x] Thêm compose edit mode
- [x] Prefill input với body hiện tại
- [x] Edit update đúng row cũ, không tạo message mới
- [x] Hiển thị badge `(edited)`

Acceptance criteria:
- [x] Chỉ own message mới mở được edit action.
- [x] Edit mode thoát an toàn khi message biến mất.
- [x] Message đã edit hiện badge ổn định.

## Tests
File: [android/app/src/test/java/app/peermesh/nearby/NearbyRoomMessageEditReducerTest.kt](/home/truong/projects/disaster-app/android/app/src/test/java/app/peermesh/nearby/NearbyRoomMessageEditReducerTest.kt:1)
File: [android/app/src/test/java/app/peermesh/nearby/NearbyRoomTransportCodecTest.kt](/home/truong/projects/disaster-app/android/app/src/test/java/app/peermesh/nearby/NearbyRoomTransportCodecTest.kt:1)

- [x] Thêm test cho reducer nhỏ:
- [x] newer edit apply
- [x] actor mismatch reject
- [x] stale edit reject
- [x] unedited downgrade reject
- [x] base content conflict reject
- [x] Thêm codec round-trip test cho `edited_at_ms`
- [x] Chạy `./gradlew :app:testOfficialDebugUnitTest :data:kaptReleaseKotlin`

Acceptance criteria:
- [x] Unit tests pass trong branch hiện tại.
- [x] Schema export pass cùng migration mới.

## Follow-up

- [ ] Device QA với 2-3 peers cho edit propagation qua BLE thực tế
- [ ] Kiểm tra late relay fetch sau khi message đã được edit
- [ ] Quyết định có cần delete/edit model riêng cho Nearby Room hay không
- [ ] Nếu cần out-of-order orphan edit handling, thiết kế path riêng thay vì cố reuse reducer của Private Group
