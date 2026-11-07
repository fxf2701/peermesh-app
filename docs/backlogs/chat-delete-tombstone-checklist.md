# Chat Delete With Tombstone Redaction Checklist

## GroupChatActivity
File: [android/app/src/main/java/app/peermesh/GroupChatActivity.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/GroupChatActivity.kt:43)

- [x] Thêm action `Delete` cho message của chính user.
- [x] Không hiện action `Delete` cho message của người khác.
- [x] Thêm confirm dialog trước khi delete.
- [x] Khi confirm:
- [x] tạo `CHAT.DEL` bằng `EventFactory.chatDelete(...)`
- [x] insert local event delete
- [x] apply local reducer delete
- [x] enqueue outbox để gửi đi
- [x] Sau delete thành công, refresh list/snapshot đúng cách.
- [x] Nếu message đang ở reply compose target thì clear reply state phù hợp.

Acceptance criteria:
- [x] User chỉ có thể delete message của chính mình.
- [x] Delete tạo event `CHAT.DEL` local và gửi đi như chat event khác.
- [x] Source device xử lý delete giống receiver.
- [x] Message vừa xóa không còn hiển thị nội dung cũ trên source device.

## ChatMessageAdapter
File: [android/app/src/main/java/app/peermesh/ChatMessageAdapter.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/ChatMessageAdapter.kt:22)

- [x] Thêm callback delete, ví dụ:
- [x] `onDeleteRequested: (ChatMessageEntity) -> Unit`
- [x] Chỉ enable delete action cho own message và `status != deleted`
- [x] Nếu `message.status == deleted`:
- [x] body text hiển thị `"Deleted"`
- [x] body dùng màu xám mờ
- [x] body in nghiêng
- [x] disable reply long-press cho message deleted
- [x] quoted preview nếu trỏ tới parent deleted:
- [x] hiển thị `"Deleted"`
- [x] màu xám mờ
- [x] in nghiêng

Acceptance criteria:
- [x] Message deleted không còn hiển thị text gốc.
- [x] Deleted message có style khác rõ ràng: xám mờ + italic.
- [x] Reply preview tới deleted parent không lộ text cũ.
- [x] Không thể long-press reply trên deleted message nếu policy chặn.

## item_chat_message.xml
File: [android/app/src/main/res/layout/item_chat_message.xml](/home/truong/projects/disaster-app/android/app/src/main/res/layout/item_chat_message.xml:1)

- [ ] Đảm bảo `message_body` hỗ trợ style italic cho deleted state.
- [ ] Nếu cần, tách style hoặc state handling để body đổi màu khi deleted.
- [ ] Nếu cần, quoted preview text cũng hỗ trợ italic khi parent deleted.

Acceptance criteria:
- [ ] Deleted bubble hiển thị `"Deleted"` với đúng style UI.
- [ ] Không cần thay layout lớn, chỉ cần state render đúng.

## strings.xml
File: [android/app/src/main/res/values/strings.xml](/home/truong/projects/disaster-app/android/app/src/main/res/values/strings.xml:1)

- [x] Thêm string:
- [x] `chat_message_deleted = "Deleted"`
- [x] `chat_delete_action`
- [x] `chat_delete_confirm_title`
- [x] `chat_delete_confirm_body`
- [x] `chat_delete_confirm_cta`
- [x] Nếu cần, thêm string cho lỗi delete unauthorized / delete failed

Acceptance criteria:
- [x] Không hardcode `"Deleted"` hoặc text dialog trong Kotlin.
- [x] Copy delete nhất quán với UI app.

## EventValidator
File: [android/core/src/main/java/app/peermesh/core/validation/EventValidator.kt](/home/truong/projects/disaster-app/android/core/src/main/java/app/peermesh/core/validation/EventValidator.kt:28)

- [x] Siết rule `CHAT.DEL`
- [x] `target_id` hoặc `ref.target` phải tồn tại và non-blank
- [x] Tùy chọn: reject nếu target trỏ vào chính event delete
- [x] Không làm ảnh hưởng `CHAT_MSG`, `CHAT_REPLY`, `CHAT_EDIT`

Acceptance criteria:
- [x] `CHAT.DEL` malformed bị reject sớm.
- [x] `CHAT.DEL` hợp lệ vẫn pass.

## EventFactory
File: [android/core/src/main/java/app/peermesh/core/factory/EventFactory.kt](/home/truong/projects/disaster-app/android/core/src/main/java/app/peermesh/core/factory/EventFactory.kt:66)

- [x] Review `chatDelete(...)` để xác nhận payload hiện tại đủ dùng:
- [x] `type = CHAT.DEL`
- [x] `data.target_id`
- [x] `ref.target`
- [x] Không cần sửa nếu đã đúng

Acceptance criteria:
- [x] Factory tạo `CHAT.DEL` đúng contract reducer/validator cần.

## EventFactoryTest
File: [android/core/src/test/java/app/peermesh/core/factory/EventFactoryTest.kt](/home/truong/projects/disaster-app/android/core/src/test/java/app/peermesh/core/factory/EventFactoryTest.kt:1)

- [x] Thêm test cho `chatDelete(...)`
- [x] assert `type = CHAT.DEL`
- [x] assert `data.target_id`
- [x] assert `ref.target`

Acceptance criteria:
- [x] `CHAT.DEL` factory behavior được khóa bằng test.

## EventValidatorTest
File: [android/core/src/test/java/app/peermesh/core/validation/EventValidatorTest.kt](/home/truong/projects/disaster-app/android/core/src/test/java/app/peermesh/core/validation/EventValidatorTest.kt:1)

- [x] Thêm test `validate_acceptsChatDeleteWithTarget`
- [x] Thêm test `validate_rejectsChatDeleteMissingTarget`
- [x] Nếu có self-target rule, thêm test reject

Acceptance criteria:
- [x] Validator test cover đủ các nhánh chính của `CHAT.DEL`.

## DeletedEventEntity
File mới: `android/data/src/main/java/app/peermesh/data/db/entity/DeletedEventEntity.kt`

- [x] Tạo entity `deleted_events`
- [x] Field tối thiểu:
- [x] `gid`
- [x] `target_event_id`
- [x] `delete_event_id`
- [x] `actor_did`
- [x] `deleted_at`
- [x] `expires_at`
- [x] `target_created_t`
- [x] `delete_created_t`
- [x] `last_event_t`
- [x] `last_event_lt`
- [x] `last_event_actor`
- [x] Thêm index phù hợp:
- [x] `(gid, expires_at)`
- [x] `(gid, delete_event_id)`

Acceptance criteria:
- [x] Schema đủ để chặn resurrect event và hỗ trợ cleanup.

## DeletedEventDao
File mới: `android/data/src/main/java/app/peermesh/data/db/dao/DeletedEventDao.kt`

- [x] Thêm methods:
- [x] `upsert`
- [x] `find`
- [x] `findActive`
- [x] `deleteExpired`
- [x] `deleteByGid`

Acceptance criteria:
- [x] DAO đủ cho reducer, ingest gate, cleanup worker.

## EventsDao
File: `android/data/src/main/java/app/peermesh/data/db/dao/EventsDao.kt`

- [x] Thêm method xóa event gốc plaintext:
- [x] `deleteByEventId(gid, eventId)`
- [x] Review nơi nào gọi method này để tránh xóa nhầm `CHAT.DEL`

Acceptance criteria:
- [x] Event gốc của message bị delete có thể bị xóa khỏi local event store.

## AppDatabase
File: [android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt](/home/truong/projects/disaster-app/android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt:1)

- [x] Thêm `DeletedEventEntity` vào danh sách entities
- [x] Tăng `version`
- [x] Thêm `abstract fun deletedEventDao(): DeletedEventDao`

Acceptance criteria:
- [x] DB compile được với bảng tombstone mới.

## DatabaseMigrations
File: `android/data/src/main/java/app/peermesh/data/db/DatabaseMigrations.kt`

- [x] Thêm migration tạo bảng `deleted_events`
- [x] Thêm indexes tương ứng
- [x] Nếu tăng DB version, wiring migration phải đúng

Acceptance criteria:
- [x] Nâng version DB không làm vỡ app cũ.
- [x] Bảng mới được tạo đầy đủ.

## ChatReducer
File: [android/data/src/main/java/app/peermesh/data/reducer/ChatReducer.kt](/home/truong/projects/disaster-app/android/data/src/main/java/app/peermesh/data/reducer/ChatReducer.kt:18)

- [x] Mở rộng reducer để có access tới:
- [x] `ChatDao`
- [x] `DeletedEventDao`
- [x] `EventsDao`
- [x] Sửa `applyDelete()`:
- [x] resolve `targetId`
- [x] load target message
- [x] check ownership: chỉ actor gốc mới được delete
- [x] check LWW
- [x] tính `tombstone expires_at`
- [x] upsert `deleted_events`
- [x] update `chat_messages.status = deleted`
- [x] update `chat_messages.text = "Deleted"`
- [x] update `last_event_*`
- [x] xóa event gốc trong `events`
- [x] Nếu target chưa có trong `chat_messages`:
- [x] vẫn lưu tombstone tối thiểu vào `deleted_events`
- [x] không crash, không bỏ qua hoàn toàn
- [x] Với `CHAT.EDIT`, cân nhắc reject nếu target đã có tombstone active

Acceptance criteria:
- [x] Delete own message sẽ redact text local thành `"Deleted"`.
- [x] Delete message người khác bị reject.
- [x] Event plaintext gốc bị xóa khỏi local `events`.
- [x] Tombstone vẫn tồn tại để chặn resurrect.

## Ingest Gate
File dự kiến: nơi parse/validate/insert/reduce event chat trong data ingest pipeline

- [x] Trước khi reduce `CHAT.MSG` và `CHAT.REPLY`, check `deleted_events.findActive(gid, event.id, now)`
- [x] Nếu có tombstone active:
- [x] reject event
- [x] không recreate `chat_messages`
- [x] không reinsert plaintext event
- [x] Với `CHAT.EDIT`, nếu target message đã deleted thì reject edit
- [x] Không áp rule này cho `CHAT.DEL` chính nó

Acceptance criteria:
- [x] Event cũ tới muộn từ BLE/cloud không làm message sống lại.
- [x] Edit không thể sửa message đã deleted.

## Cleanup Policy
File dự kiến: worker/job/service cleanup hiện có

- [x] Thêm cleanup `deleted_events` theo `expires_at`
- [x] Không cleanup tombstone quá sớm
- [x] Công thức expiry:
- [x] `max(target_event.expires_at + 6h, now + 24h)` hoặc helper tương đương
- [x] Review cleanup `events` để không giữ plaintext gốc của message đã delete
- [x] Review cleanup `chat_messages` deleted để vẫn giữ row redact an toàn

Acceptance criteria:
- [x] Tombstone sống đủ lâu để chống resurrect event.
- [x] Cleanup không làm message cũ quay lại.

## Chat Delete Tests
File mới hoặc file test phù hợp trong `android/data/src/test/...`

- [ ] Test delete own message:
- [x] `chat_messages.text = "Deleted"`
- [x] `status = deleted`
- [x] tombstone được lưu
- [x] event gốc bị xóa
- [x] Test delete other user message bị reject
- [x] Test delete target chưa có local message vẫn lưu tombstone
- [x] Test event cũ đến lại sau delete bị ingest gate reject
- [x] Test edit event vào target đã deleted bị ingest gate reject
- [ ] Test reply preview/source deleted render logic nếu có test được ở JVM layer

Acceptance criteria:
- [x] Reducer + tombstone + ingest gate có coverage đủ để refactor an toàn.

## UI/Manual QA
- [ ] Delete message của chính mình từ UI.
- [ ] Không thể delete message của người khác.
- [ ] Sau delete, source device chỉ thấy `"Deleted"` chứ không thấy nội dung cũ.
- [ ] Receiver sau sync cũng chỉ thấy `"Deleted"`.
- [ ] Export/trích xuất bảng `chat_messages` không còn plaintext của message đã delete.
- [ ] Export/trích xuất bảng `events` không còn event plaintext gốc của message đã delete.
- [ ] Event cũ đến lại qua BLE/cloud không làm message sống lại.
- [ ] Reply preview trỏ vào deleted parent hiển thị `"Deleted"` xám mờ, italic.
- [ ] Deleted message hiển thị xám mờ, italic trên cả dark/light theme.

## Definition of Done
- [ ] UI có delete action cho own message.
- [ ] `CHAT.DEL` được tạo và gửi đi đúng flow event hiện tại.
- [ ] Source và receiver apply cùng một delete policy.
- [ ] `chat_messages` không còn giữ nội dung cũ sau delete.
- [ ] `events` xóa plaintext event gốc của message bị delete.
- [ ] `deleted_events` ngăn event cũ bị resurrect.
- [ ] Deleted message hiển thị `"Deleted"` xám mờ, in nghiêng.
- [x] Có test cho validator, factory, reducer, ingest gate.
- [ ] Manual QA pass cho source, receiver, replay, và export safety.
