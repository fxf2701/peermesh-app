# Chat Edit Implementation Checklist

## GroupChatActivity
File: [android/app/src/main/java/app/peermesh/GroupChatActivity.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/GroupChatActivity.kt:43)

- [x] Thêm state edit:
- [x] `selectedEditMessageId: String?`
- [x] `selectedEditMessagePreview: ChatMessageEntity?`
- [x] Giữ tương thích với state reply hiện có
- [x] Thêm helper:
- [x] `startEdit(message)`
- [x] `clearEdit()`
- [x] `clearComposeMode()`
- [x] `renderComposerState()`
- [x] Khi khởi tạo `ChatMessageAdapter`, truyền callback:
- [x] `onEditRequested`
- [x] Sửa `messageSend.setOnClickListener`:
- [x] nếu đang ở edit mode thì gọi flow edit
- [x] nếu không thì giữ nguyên flow send thường / reply
- [x] Chỉ clear edit state sau khi insert local thành công
- [x] Nếu gửi fail, giữ nguyên edit state và nội dung input
- [x] Thêm `editMessageWithRetry(...)`
- [x] Thêm `editMessageOnce(...)`
- [x] Dùng `EventFactory.chatEdit(...)`
- [x] Insert local `EventEntity` rồi apply `ChatReducer`
- [x] `UserOutbox.enqueue(...)` cho `CHAT.EDIT`
- [x] Sau khi edit local thành công, update snapshot bằng `replaceSnapshotMessage(...)`
- [x] Nếu message đang edit bị delete, thoát edit mode an toàn
- [x] Khi chuyển từ edit sang reply, clear edit state hợp lý
- [x] Khi participant bị blocked, clear toàn bộ compose mode

Acceptance criteria:
- [x] Từ action menu của message own-message có thể vào edit mode.
- [x] Input được prefill đúng với text hiện tại của message.
- [x] Gửi khi đang edit không tạo message mới, mà update message cũ.
- [x] Hủy edit trả composer về trạng thái bình thường.
- [x] Reply mode và edit mode không chồng lấn nhau.
- [x] Delete message đang edit không gây crash và thoát edit mode.

## ChatMessageAdapter
File: [android/app/src/main/java/app/peermesh/ChatMessageAdapter.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/ChatMessageAdapter.kt:21)

- [x] Thêm constructor callback:
- [x] `onEditRequested: (ChatMessageEntity) -> Unit`
- [x] Mở rộng action menu primary:
- [x] message active luôn có `Reply`
- [x] own-message có thêm `Edit`
- [x] own-message có thêm `Delete`
- [x] Không expose `Edit` cho message của người khác
- [x] Thêm bind `message_edited_badge`
- [x] Badge chỉ hiện khi:
- [x] message không deleted
- [x] `message.edited_at != null`

Acceptance criteria:
- [x] Message của user khác không hiện action `Edit`.
- [x] Message của chính mình hiện action `Edit`.
- [x] Bấm `Edit` gọi đúng callback.
- [x] Badge `(edited)` hiển thị đúng trên message đã edit.
- [x] Message chưa edit không hiện badge thừa.

## item_chat_message.xml
File: [android/app/src/main/res/layout/item_chat_message.xml](/home/truong/projects/disaster-app/android/app/src/main/res/layout/item_chat_message.xml:44)

- [x] Thêm `message_edited_badge` cạnh phần time/meta
- [x] Dùng style secondary text hiện có
- [x] Mặc định `visibility="gone"`
- [x] Không phá layout hiện tại của display name / time / receipts

Acceptance criteria:
- [x] Badge `(edited)` nhìn rõ nhưng không lấn át body.
- [x] Message thường không bị thay đổi spacing bất thường.
- [x] Header của bubble vẫn ổn trên mobile width.

## activity_group_chat.xml
File: [android/app/src/main/res/layout/activity_group_chat.xml](/home/truong/projects/disaster-app/android/app/src/main/res/layout/activity_group_chat.xml:58)

- [x] Tái sử dụng composer container hiện có cho cả reply và edit
- [x] Không cần thêm layout mới riêng cho edit mode
- [x] Nút close đổi content description theo mode hiện tại

Acceptance criteria:
- [x] Composer bar dùng lại được cho reply và edit.
- [x] Edit mode hiển thị label phù hợp.
- [x] Không làm vỡ layout input hiện tại.

## strings.xml
File: [android/app/src/main/res/values/strings.xml](/home/truong/projects/disaster-app/android/app/src/main/res/values/strings.xml:423)

- [x] Thêm string:
- [x] `chat_edit_action`
- [x] `chat_edit_failed`
- [x] `chat_message_edited_badge`
- [x] `chat_editing_label`
- [x] `chat_edit_cancel`
- [x] Không hardcode text mới trong Kotlin/XML

Acceptance criteria:
- [x] UI edit mode và badge không dùng hardcoded string.
- [x] Nội dung đủ cho menu, composer và error state.

## ChatMessageEntity
File: [android/data/src/main/java/app/peermesh/data/db/entity/ChatMessageEntity.kt](/home/truong/projects/disaster-app/android/data/src/main/java/app/peermesh/data/db/entity/ChatMessageEntity.kt:14)

- [x] Thêm metadata:
- [x] `edited_at: Long?`
- [x] Không tái dùng `updated_t` để quyết định badge edit

Acceptance criteria:
- [x] Entity giữ được metadata edit tách biệt khỏi ACK / seen updates.
- [x] Badge edit có source-of-truth rõ ràng trong DB state.

## AppDatabase / Migration
File: [android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt](/home/truong/projects/disaster-app/android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt:80)
File: [android/data/src/main/java/app/peermesh/data/db/DatabaseMigrations.kt](/home/truong/projects/disaster-app/android/data/src/main/java/app/peermesh/data/db/DatabaseMigrations.kt:1549)
File: [android/data/schemas/app.peermesh.data.db.AppDatabase/44.json](/home/truong/projects/disaster-app/android/data/schemas/app.peermesh.data.db.AppDatabase/44.json:1)

- [x] Bump Room version `43 -> 44`
- [x] Thêm migration `43_44`
- [x] Add column `edited_at INTEGER`
- [x] Export schema `44.json`

Acceptance criteria:
- [x] App cũ upgrade DB không crash vì thiếu cột mới.
- [x] Schema Room khớp version mới.

## EventValidator
File: [android/core/src/main/java/app/peermesh/core/validation/EventValidator.kt](/home/truong/projects/disaster-app/android/core/src/main/java/app/peermesh/core/validation/EventValidator.kt:28)

- [x] Harden rule `CHAT.EDIT`
- [x] Bắt buộc có `text`
- [x] Bắt buộc có `target_id` hợp lệ hoặc `ref.target`
- [x] Reject nếu target blank
- [x] Reject nếu target trỏ vào chính `core.id`
- [x] Chấp nhận `CHAT.REPLY` mới chỉ dùng `ref.parent`
- [x] Chấp nhận `CHAT.EDIT` mới dùng `ref.target + data.tct`
- [x] Giữ backward compatibility cho schema cũ trong giai đoạn chuyển tiếp

Acceptance criteria:
- [x] `CHAT.EDIT` malformed bị reject.
- [x] `CHAT.EDIT` hợp lệ vẫn pass.
- [x] Không làm ảnh hưởng các chat event type khác.
- [x] `CHAT.REPLY` không còn phụ thuộc bắt buộc vào `data.reply_to`.
- [x] `CHAT.EDIT` không còn phụ thuộc bắt buộc vào `data.target_id`.

## EventValidatorTest
File: [android/core/src/test/java/app/peermesh/core/validation/EventValidatorTest.kt](/home/truong/projects/disaster-app/android/core/src/test/java/app/peermesh/core/validation/EventValidatorTest.kt:15)

- [x] Thêm test `validate_acceptsChatEditWithTarget`
- [x] Thêm test `validate_rejectsChatEditMissingTarget`

Acceptance criteria:
- [x] Test mới fail trên rule cũ và pass trên rule mới.
- [x] Test cũ vẫn pass.

## ChatReducer
File: [android/data/src/main/java/app/peermesh/data/reducer/ChatReducer.kt](/home/truong/projects/disaster-app/android/data/src/main/java/app/peermesh/data/reducer/ChatReducer.kt:22)

- [x] Harden `applyEdit(...)`
- [x] Resolve `targetId`
- [x] Tạo provisional message nếu `CHAT.EDIT` đến trước và target message chưa tồn tại
- [x] Reject nếu target đã deleted
- [x] Reject nếu actor edit khác `existing.actor_did`
- [x] Chỉ apply nếu clock mới hơn theo LWW
- [x] Update:
- [x] `text`
- [x] `updated_t`
- [x] `edited_at`
- [x] `last_event_*`
- [x] Giữ nguyên receipts / delivery state
- [x] Sau `applyMessage(...)`, replay các `CHAT.EDIT` pending theo `ref_target`
- [x] Khi delete message, giữ behavior cleanup `CHAT_EDIT` liên quan
- [x] `CHAT.REPLY` resolve reply target từ `ref.parent`
- [x] `CHAT.MSG` / `CHAT.REPLY` merge được vào provisional edited row nếu base event tới sau
- [x] Base message thắng provisional nếu actor của provisional không khớp actor gốc

Acceptance criteria:
- [x] Author gốc edit được message của mình.
- [x] Actor khác không sửa được message không thuộc về mình.
- [x] Edit đến trước base message vẫn được apply sau khi base message arrive.
- [x] Delete vẫn thắng edit khi target đã tombstoned.
- [x] Nếu base message không bao giờ tới, edited content vẫn có row để render.
- [x] Nếu base message tới sau, row không bị duplicate và thứ tự lịch sử ổn định theo `tct`.

## ChatReducer Test
File: [android/data/src/test/java/app/peermesh/data/reducer/ChatReducerTest.kt](/home/truong/projects/disaster-app/android/data/src/test/java/app/peermesh/data/reducer/ChatReducerTest.kt:20)

- [x] Update test fixtures để support `edited_at`
- [x] Thêm test `apply_rejectsEditForOtherUsersMessage`
- [x] Thêm test `apply_replaysPendingEditsWhenBaseMessageArrivesLater`
- [x] Thêm test `apply_createsProvisionalEditWhenTargetMessageMissing`
- [x] Thêm test `apply_mergesBaseMessageIntoExistingProvisionalEditedMessage`
- [x] Giữ test delete cleanup của `CHAT_EDIT`

Acceptance criteria:
- [x] Có test chứng minh reducer reject cross-author edit.
- [x] Có test chứng minh replay delayed edit hoạt động.
- [x] Có test chứng minh provisional row được tạo khi thiếu base message.
- [x] Có test chứng minh base message merge lại đúng khi tới sau.
- [x] Không regress test delete/tombstone behavior.

## Schema Alignment Phase

- [x] `CHAT.MSG` giữ `data.text`
- [x] `CHAT.REPLY` sender ngừng ghi `data.reply_to`
- [x] `CHAT.REPLY` dùng `ref.parent` làm source-of-truth
- [x] `CHAT.EDIT` sender ngừng ghi `data.target_id`
- [x] `CHAT.EDIT` thêm `data.tct`
- [x] `CHAT.EDIT` dùng `ref.target` làm source-of-truth
- [x] Update `GroupChatActivity` để gửi `targetMessage.created_t` trong `CHAT.EDIT`
- [x] Update core tests cho schema mới
- [x] Update review/spec docs cho quy ước event mới
- [ ] Thêm payload-byte budget validation cho 3 loại chat event
- [ ] Chốt chiến lược sunset hoàn toàn field legacy (`reply_to`, `target_id`, `target_created_t`)

Acceptance criteria:
- [x] Sender mới phát `CHAT.REPLY` và `CHAT.EDIT` theo schema cân bằng hơn.
- [x] Receiver mới vẫn đọc được event legacy trong giai đoạn chuyển tiếp.
- [x] `core + data` unit tests xanh sau khi đổi schema.
- [ ] Có test hoặc guard riêng cho serialized payload budget, không chỉ text length.

## EventIngestor Test Support
File: [android/data/src/test/java/app/peermesh/data/ingest/EventIngestorTest.kt](/home/truong/projects/disaster-app/android/data/src/test/java/app/peermesh/data/ingest/EventIngestorTest.kt:1)

- [x] Bổ sung stub/support cho dependency `RemovedMemberDao`
- [x] Giữ ingest tests xanh sau khi reducer/sys dependency chain thay đổi

Acceptance criteria:
- [x] Test ingest liên quan chat/delete/edit vẫn pass.
- [x] Không phát sinh null dependency do constructor chain.

## Transport Test Alignment
File: [android/data/src/test/java/app/peermesh/data/transport/EnvelopeSenderTest.kt](/home/truong/projects/disaster-app/android/data/src/test/java/app/peermesh/data/transport/EnvelopeSenderTest.kt:23)

- [x] Align test với contract batching hiện tại của `AppBatcher`
- [x] Decode `AppBatchEnvelope` trước
- [x] Base64-decode item đầu tiên để lấy `EventEnvelope`
- [x] Giữ assert không lộ plaintext chat trong outer payload
- [x] Verify priority của `SYS_ACK` trên inner envelope

Acceptance criteria:
- [x] `EnvelopeSenderTest` pass với batching behavior hiện tại.
- [x] Test không còn giả định broadcaster nhận trực tiếp `EventEnvelope`.

## Manual QA Checklist

- [ ] Edit own message ngay sau khi vừa gửi.
- [x] Edit own message sau khi peer khác đã nhận message gốc.
- [ ] Edit message khi đang có peer chậm sync, sau đó peer này sync lại.
- [ ] Edit message khi peer chỉ nhận được `CHAT.EDIT` mà không bao giờ nhận base `CHAT.MSG`.
- [ ] Base message tới sau provisional edited row:
- [ ] không duplicate row
- [ ] text cuối cùng vẫn đúng theo LWW
- [ ] reply preview vẫn đúng nếu base event là `CHAT.REPLY`
- [x] Edit rồi delete cùng message, xác nhận delete là state cuối.
- [ ] Thử edit message của người khác:
- [ ] app không expose action edit ở UI
- [ ] nếu có event lạ injected, reducer không apply sai
- [ ] Badge `(edited)` vẫn đúng sau ACK / seen update.
- [ ] Mở lại màn chat, message đã edit vẫn render đúng.
- [ ] Pagination / refresh không duplicate hoặc mất update edited content.
- [ ] Chuyển qua lại reply mode và edit mode không gây state kẹt.

## Definition of Done

- [x] User có thể edit own active message trong `private group`.
- [x] Edit tạo `CHAT.EDIT` local event đúng format.
- [x] Reducer chỉ chấp nhận edit hợp lệ từ author gốc.
- [x] UI hiển thị `(edited)` badge đúng.
- [x] DB có migration và schema cho metadata edit.
- [x] Unit tests `core + data` pass.
- [x] Schema chat event đã đổi sang hướng cân bằng payload hơn, có backward compatibility đọc schema cũ.
- [x] Reducer đã hỗ trợ provisional/orphan edited-message path.
- [x] Có bằng chứng QA thực tế trên device cho propagation và out-of-order sync.
- [ ] Có bằng chứng QA thực tế cho provisional edited-message path.
- [ ] Có guard rõ ràng cho serialized payload byte budget của `CHAT.MSG` / `CHAT.REPLY` / `CHAT.EDIT`.
- [ ] Team chốt có/không cần phase tiếp theo cho edit window, history, moderation visibility.
