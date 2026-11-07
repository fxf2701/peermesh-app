# Chat Reply Implementation Checklist

## GroupChatActivity
File: [android/app/src/main/java/app/peermesh/GroupChatActivity.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/GroupChatActivity.kt:43)

- [x] Thêm state reply:
- [x] `selectedReplyMessageId: String?`
- [x] `selectedReplyMessagePreview: ChatMessageEntity?`
- [x] Thêm helper:
- [x] `startReply(message)`
- [x] `clearReply()`
- [x] `renderReplyComposerState()`
- [x] `scrollToMessage(messageId)` hoặc helper tương đương
- [x] Khi khởi tạo `ChatMessageAdapter`, truyền callback:
- [x] `onReplyRequested`
- [x] `onReplyPreviewClicked`
- [x] Sửa `messageSend.setOnClickListener` để gửi kèm `selectedReplyMessageId`
- [x] Chỉ `clearReply()` sau khi insert local thành công
- [x] Nếu gửi fail, giữ nguyên reply state
- [x] Sửa `sendMessageWithRetry()` nhận thêm `replyToMessageId: String?`
- [x] Sửa `sendMessageOnce()` truyền `replyTo` vào `EventFactory.chatMessage(...)`
- [x] Thêm logic click vào quoted preview để jump tới message gốc nếu đang có trong `snapshotMessages`
- [x] Nếu không tìm thấy message gốc trong list hiện tại, fallback an toàn:
- [x] không crash
- [x] có thể bỏ qua hoặc hiện toast ngắn

Acceptance criteria:
- [x] Long-press một message sẽ đưa màn chat vào trạng thái reply.
- [x] Composer hiển thị đúng actor + snippet của message đang reply.
- [x] Bấm hủy reply thì composer trở lại trạng thái bình thường.
- [x] Gửi khi đang reply sẽ tạo local event/message thành công và reset reply state.
- [x] Gửi message thường vẫn hoạt động y như cũ.
- [x] Bấm vào quoted preview trong bubble không gây crash; nếu message gốc đang load thì scroll tới đúng vị trí.

## ChatMessageAdapter
File: [android/app/src/main/java/app/peermesh/ChatMessageAdapter.kt](/home/truong/projects/disaster-app/android/app/src/main/java/app/peermesh/ChatMessageAdapter.kt:20)

- [x] Thêm constructor hoặc setter callback cho:
- [x] `onReplyRequested: (ChatMessageEntity) -> Unit`
- [x] `onReplyPreviewClicked: (ChatMessageEntity) -> Unit` hoặc callback theo `reply_to`
- [x] Thêm `messagesById` map, rebuild trong `submitList()`
- [x] Mở rộng `ViewHolder` để bind:
- [x] `message_reply_container`
- [x] `message_reply_actor`
- [x] `message_reply_text`
- [x] Trong `bind()`, nếu `message.reply_to != null`:
- [x] resolve parent từ `messagesById`
- [x] render actor + snippet nếu resolve được
- [x] render fallback nếu không resolve được
- [x] Gắn `setOnLongClickListener` cho item để gọi `onReplyRequested(message)`
- [x] Gắn click listener cho quoted preview để gọi callback jump
- [x] Giữ nguyên logic receipts hiện có

Acceptance criteria:
- [x] Adapter vẫn render đúng message thường.
- [x] Message có `reply_to` hiển thị quoted block phía trên body.
- [x] Message không có `reply_to` không chiếm extra space cho quoted block.
- [x] Long-press trên item bất kỳ gọi đúng callback reply.
- [x] Reply preview click gọi đúng callback jump.
- [x] Không có crash khi parent message không tồn tại trong current list.

## activity_group_chat.xml
File: [android/app/src/main/res/layout/activity_group_chat.xml](/home/truong/projects/disaster-app/android/app/src/main/res/layout/activity_group_chat.xml:66)

- [x] Thêm `reply composer container` phía trên `message_input_row`
- [x] Thêm view con tối thiểu:
- [x] label actor
- [x] preview text
- [x] nút close/cancel
- [x] Mặc định `visibility="gone"`
- [x] Đảm bảo layout không phá mobile width và không chèn lấn input row
- [x] Nếu cần, thêm style spacing/margin hợp lý

Acceptance criteria:
- [x] Khi chưa reply, UI chat nhìn gần như không đổi.
- [x] Khi reply, preview bar xuất hiện rõ ràng phía trên ô nhập.
- [x] Text dài trong preview không làm vỡ layout; bị ellipsize hợp lý.
- [x] Nút hủy reply dễ bấm và không che nút send.

## item_chat_message.xml
File: [android/app/src/main/res/layout/item_chat_message.xml](/home/truong/projects/disaster-app/android/app/src/main/res/layout/item_chat_message.xml:37)

- [x] Thêm `quoted reply block` phía trên `message_body`
- [x] Thêm view tối thiểu:
- [x] `message_reply_container`
- [x] `message_reply_actor`
- [x] `message_reply_text`
- [x] Dùng style phân biệt nhẹ:
- [x] nền phụ hoặc stroke/left bar
- [x] text secondary cho snippet
- [x] Đảm bảo receipts/meta vẫn nằm đúng vị trí

Acceptance criteria:
- [x] Bubble có reply hiển thị cấu trúc rõ: quoted preview trước, body sau.
- [x] Bubble thường không bị thay đổi spacing bất thường.
- [x] Quoted block nhìn phân biệt được với body nhưng không lấn át nội dung chính.

## EventValidator
File: [android/core/src/main/java/app/peermesh/core/validation/EventValidator.kt](/home/truong/projects/disaster-app/android/core/src/main/java/app/peermesh/core/validation/EventValidator.kt:28)

- [x] Tách rule `CHAT_MSG` và `CHAT_REPLY`
- [x] Với `CHAT_REPLY`, bắt buộc:
- [x] có `text`
- [x] có `reply_to` non-blank
- [x] Tùy chọn nhưng nên có:
- [x] reject nếu `reply_to == core.id`

Acceptance criteria:
- [x] `CHAT_MSG` hợp lệ không cần `reply_to`.
- [x] `CHAT_REPLY` thiếu `reply_to` bị reject.
- [x] `CHAT_REPLY` với `reply_to` hợp lệ vẫn pass.
- [x] Không làm ảnh hưởng validation các event type khác.

## EventValidatorTest
File: [android/core/src/test/java/app/peermesh/core/validation/EventValidatorTest.kt](/home/truong/projects/disaster-app/android/core/src/test/java/app/peermesh/core/validation/EventValidatorTest.kt:15)

- [x] Thêm test `validate_acceptsChatReplyWithReplyTo`
- [x] Thêm test `validate_rejectsChatReplyMissingReplyTo`
- [x] Nếu có self-reference rule, thêm test reject self-reply id

Acceptance criteria:
- [x] Test mới fail trên code cũ và pass trên code mới.
- [x] Test cũ vẫn pass.

## EventFactoryTest
File: [android/core/src/test/java/app/peermesh/core/factory/EventFactoryTest.kt](/home/truong/projects/disaster-app/android/core/src/test/java/app/peermesh/core/factory/EventFactoryTest.kt:11)

- [x] Giữ test type `CHAT_REPLY`
- [x] Bổ sung assert cho `core.ref.parent == replyTo`

Acceptance criteria:
- [x] Factory test chứng minh đủ 3 điểm:
- [x] type là `CHAT_REPLY`
- [x] `data.reply_to` đúng
- [x] `ref.parent` đúng

## ChatReducer
File: [android/data/src/main/java/app/peermesh/data/reducer/ChatReducer.kt](/home/truong/projects/disaster-app/android/data/src/main/java/app/peermesh/data/reducer/ChatReducer.kt:30)

- [x] Review logic hiện có để bảo đảm không cần sửa behavior persist `reply_to`
- [x] Chỉ sửa nếu phát hiện reducer ghi đè sai hoặc bỏ mất `reply_to`
- [x] Nếu có sửa, giữ backward compatibility cho `CHAT_MSG`

Acceptance criteria:
- [x] `CHAT_REPLY` sau reduce được lưu với `reply_to` đúng trong `chat_messages`.
- [x] `CHAT_MSG` thường vẫn không có `reply_to`.
- [x] Edit/delete/ack hiện hữu không bị regress.

## ChatReducer Test
File: `android/data/src/test/...` hoặc file test phù hợp

- [x] Thêm test reducer cho `CHAT_REPLY`
- [x] Verify `reply_to` được persist đúng vào entity
- [x] Nếu có fallback deleted parent behavior ở reducer thì test thêm, còn không cần

Acceptance criteria:
- [x] Có test chứng minh reducer xử lý `CHAT_REPLY` thật sự chứ không chỉ factory/validator.

## strings.xml
File: [android/app/src/main/res/values/strings.xml](/home/truong/projects/disaster-app/android/app/src/main/res/values/strings.xml:1)

- [x] Thêm string cho:
- [x] `Reply`
- [x] `Replying to %1$s`
- [x] `Original message unavailable`
- [x] content description cho nút hủy reply
- [x] Nếu có toast fallback khi không jump được, thêm string tương ứng

Acceptance criteria:
- [x] Không hardcode text mới trong Kotlin/XML.
- [x] String đủ cho cả composer preview lẫn message quoted preview.

## drawables
File: [android/app/src/main/res/drawable](/home/truong/projects/disaster-app/android/app/src/main/res/drawable)

- [x] Nếu cần, thêm background riêng cho:
- [x] composer reply preview
- [x] quoted reply block trong message
- [x] Tái sử dụng token/theme hiện có thay vì vẽ style inline

Acceptance criteria:
- [x] Visual của reply block nhất quán với theme hiện tại.
- [x] Không cần thêm drawable nếu style XML hiện tại đã đủ sạch.

## Manual QA Checklist

- [x] Reply một message của người khác.
- [x] Reply một message của chính mình.
- [x] Hủy reply trước khi gửi.
- [x] Gửi message thường sau khi đã từng vào reply mode.
- [x] Mở lại màn chat, message reply cũ vẫn render quoted preview đúng.
- [ ] Parent message không nằm trong `RECENT_MESSAGES_LIMIT`:
- [x] app không crash
- [x] reply bubble hiển thị fallback
- [x] Tap vào quoted preview khi parent đang có trong list sẽ scroll đúng.
- [x] Tap vào quoted preview khi parent không có trong list không crash.
- [x] ACK/delivery badge của message reply vẫn hoạt động như message thường.

## Definition of Done

- [x] User có thể kích hoạt reply từ message item.
- [x] Composer có preview reply và hủy được.
- [x] Gửi reply tạo ra `CHAT.REPLY` với `reply_to` đúng.
- [x] Local DB persist `reply_to`.
- [x] UI render được quoted snippet cho reply.
- [x] Validator siết chặt rule cho `CHAT.REPLY`.
- [x] Có test core tối thiểu cho factory + validator + reducer.
- [x] Không regress flow gửi/nhận chat thường.
