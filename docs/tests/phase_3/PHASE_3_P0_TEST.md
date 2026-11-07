# Phase 3 - P0 Manual Test Plan & Results

Ngày cập nhật: 2026-02-22
Phạm vi: [PH3-DTN][BLP-F][P0-RX-1] (Critical Transport Stabilization)

## Ngữ cảnh test
- Thiết bị A: Samsung Galaxy M30s (Android 11)
- Thiết bị B: Samsung Galaxy A04s (Android 14)
- Lưu ý: toàn bộ TC trong file này (TC1 -> TC11) đều được thực hiện trên cùng 2 thiết bị trên.

## 0) Summary
| TC | Status | Key risk |
| --- | --- | --- |
| TC1 | PASS | Latency spike, status=133, GATT stalled/hard reset. |
| TC2 | PASS | Reminder toast chưa có marker bền trong log; còn duplicate nhẹ. |
| TC3 | PASS | Recover được sau BT OFF/ON nhưng delay còn cao, có start stuck. |
| TC4 | PASS | Recover được sau Location OFF/ON nhưng delay có thể rất cao; có timeout/133/reset. |
| TC5 | PASS (cảnh báo) | Duy trì 2 chiều sau kill/reopen nhưng còn duplicate dày, hard reset/start stuck, status=133/timeout lẻ tẻ. |
| TC6 | PASS (cảnh báo) | Store-carry-forward hoạt động nhưng duplicate/replay dày, có stalled/hard reset gây lag ngắn hạn. |
| TC7 | FAILED | Burst 10+10 tái hiện retry storm theo cùng `eventId`, stalled/hard reset/133 làm phiên mất ổn định và không chứng minh được continuity 2 chiều trong cùng window. |
| TC8 | PENDING | Chưa benchmark trong môi trường BLE nhiễu liên tục. |
| TC9 | PASS (cảnh báo) | Retest đạt 2 chiều + ACK seen hai phía, nhưng duplicate/replay còn dày. |
| TC10 | FAILED (stability) | Có nhận 2 chiều ở mốc cuối (#5/#6) nhưng stalled/hard reset/133 và retry lặp dài làm session dài không ổn định. |
| TC11 | FAILED | ACK hardening/inferred delivery chưa đạt; vẫn kẹt `sent` và còn one-way ở cuối phiên. |

## 1) Tiêu chí chung PASS/FAIL
- `PASS`: cả 2 chiều đều nhận được message trong cùng phiên test.
- `PASS`: trạng thái gửi lên `delivered`/`seen`, không kẹt `sent` bất thường.
- `PASS`: không crash/freeze UI trong lúc test.
- Luôn ghi nhận độ trễ ước tính cho 2 chiều `A -> B` và `B -> A`.
- Nếu fail: lưu ngay `logs/device-A.txt` và `logs/device-B.txt`.

## 2) Test Cases

### TC1 - Happy path 2 chiều (baseline)
Mục tiêu: xác nhận nhắn tin 2 chiều hoạt động khi đủ điều kiện.

Test steps:
1. Bật Bluetooth + Location trên cả A/B.
2. Mở app cả 2 máy, vào cùng group chat.
3. A gửi 1 tin cho B, đợi B nhận.
4. B gửi 1 tin cho A, đợi A nhận.
5. Kiểm tra trạng thái tin nhắn gửi lên delivered/seen.

Kỳ vọng:
- Cả 2 chiều đều nhận được tin.
- ACK cập nhật trạng thái delivered/seen.

Kết quả thực tế:
- `PASS` (đã nhận 2 chiều, ACK lên delivered/seen).

Backlog ghi nhận từ TC1:
- Độ trễ cao và lệch chiều, có lúc A -> B ~60s.
- Có `GATT stalled`/`GATT hard reset` trong phiên.
- Có `status=133` lẻ tẻ, cần giảm thêm.

### TC2 - Start app khi thiếu điều kiện
Mục tiêu: xác nhận cơ chế nhắc điều kiện và khả năng recover sau khi bật đủ BT/Location.

Test steps:
1. Tắt app cả 2 máy.
2. Tắt Bluetooth và Location trên cả 2.
3. Mở app lại, vào màn hình chat.
4. Bật chỉ Bluetooth (chưa bật Location), xác nhận có reminder bật Location.
5. Bật thêm Location, đợi mesh recover, gửi thử 2 chiều.

Kỳ vọng:
- Có reminder khi thiếu điều kiện.
- Sau khi đủ điều kiện, mesh recover và gửi/nhận lại 2 chiều.

Kết quả thực tế:
- `PASS` (gửi/nhận 2 chiều sau recover, ACK chạy đúng).

Backlog ghi nhận từ TC2:
- Reminder toast xác nhận bằng UI, chưa có marker rõ trong activity log để đối chiếu sau test.
- Có hiện tượng nhận lặp do retransmit, cần theo dõi thêm ở các test stress.

### TC3 - Runtime toggle Bluetooth OFF -> ON
Mục tiêu: xác nhận service tự recover khi Bluetooth bị tắt/bật trong runtime.

Test steps:
1. Đang chạy ổn định 2 chiều.
2. Trên B: tắt Bluetooth 10-15s rồi bật lại.
3. Gửi tin A -> B và B -> A ngay sau khi bật lại.

Kỳ vọng:
- Service tự recover, không cần force stop app.
- Vẫn nhắn tin được 2 chiều.

Kết quả thực tế:
- `PASS` (retest mới nhất đạt 2 chiều).

Backlog ghi nhận từ TC3:
- Độ trễ vẫn cao (thường 20-30s, có spike cao hơn).
- Có `start stuck` và hard reset trước khi ổn định trở lại.
- Chưa đạt mức latency ổn định cho trải nghiệm người dùng.

### TC4 - Runtime toggle Location OFF -> ON (2 pha)
Mục tiêu: xác nhận recover khi Location tắt/bật trên từng máy và kiểm tra 2 chiều cho cả 2 pha.

Test steps:
1. Tắt Location trên A 15s rồi bật lại.
2. A gửi `TC4: A -> B`; B gửi `TC4: B -> A`.
3. Tắt Location trên B 15s rồi bật lại.
4. B gửi `TC4 B -> A (2)`; A gửi `TC4: A -> B (2)`.

Kỳ vọng:
- Mỗi pha đều truyền 2 chiều thành công sau recover.
- ACK cập nhật delivered/seen.

Kết quả thực tế:
- `PASS` trên phiên capture 5 phút (đủ bằng chứng cho cả 4 message mốc).

Backlog ghi nhận từ TC4:
- Có phiên trước fail do window capture ngắn (2 phút), dẫn tới bỏ sót message đến muộn.
- Latency có spike rất cao (có thể > 50s, từng quan sát thực tế ~3 phút).
- Có `status=133`, `connect timeout`, `start stuck/hard reset` trong cùng phiên.
- Message trùng lặp nhiều lần do retry/retransmit.

### TC5 - Kill app / reopen khi điều kiện đủ
Mục tiêu: xác nhận khi swipe-kill app, mesh vẫn duy trì/khôi phục đúng.

Test steps:
1. Giữ Bluetooth + Location ON.
2. Swipe-kill app trên A (recent apps), mở lại app.
3. Gửi B -> A rồi A -> B.

Kỳ vọng:
- BLE mesh background/service vẫn quay lại và nhận/gửi bình thường.

Kết quả thực tế:
- `PASS (cảnh báo)` (đã xác nhận các luồng B->A, A->B, B->A (2), A->B (2), B->A (3), A->B (3) đều tới đích trong phiên TC5).

Backlog ghi nhận:
- Duplicate receive/reassembly còn dày (cùng event xuất hiện nhiều lần).
- Vẫn có `GATT hard reset` do `start stuck` ở đầu phiên.
- Trên B còn ghi nhận `status=133` và `connect timeout` rải rác, cần tiếp tục giảm.

### TC6 - Mất kết nối tạm thời do khoảng cách
Mục tiêu: xác nhận store-carry-forward khi mất contact tạm thời.

Test steps:
1. Đưa B ra xa/tách phòng 1-2 phút (mất contact).
2. A gửi 2-3 tin trong lúc mất contact.
3. Đưa B lại gần.

Kỳ vọng:
- B nhận dần các tin đã queue.

Kết quả thực tế:
- `PASS (cảnh báo)` (A gửi `test` khi gần, sau đó gửi thêm 3 tin khi tách xa; khi quay lại contact, B nhận lại đủ 3 tin `TC6: A -> B`, `TC6: A -> B (2)`, `TC6: A -> B (3)`).

Backlog ghi nhận:
- Duplicate/replay cao: cùng `eventId` được nhận nhiều lần sau khi reconnect.
- Vẫn có `GATT stalled`/`GATT hard reset` trong phiên, có thể gây lag UI ngắn hạn.
- Cần tối ưu dedupe sớm ở ingest/UI để giảm burst xử lý khi contact quay lại.

### TC7 - Burst nhỏ (stress nhẹ)
Mục tiêu: đo độ ổn định khi gửi liên tiếp.

Test steps:
1. A gửi liên tiếp 10 tin (mỗi 1-2 giây).
2. B gửi liên tiếp 10 tin.
3. Quan sát trễ, thứ tự hiển thị, tỷ lệ delivered.

Kỳ vọng:
- Không treo app, không mất hàng loạt tin.

Kết quả thực tế (retest mới nhất):
- `FAILED`.
- Pha burst tái hiện retry/requeue dày cho cùng `eventId` trên cả 2 máy, nhưng không có đủ bằng chứng receive ổn định cho batch test trong cùng window.
- Có `GATT stalled` -> `GATT hard reset` và `GATT connect failed` (`status=133`), làm nghẽn phiên và gây mất ổn định continuity 2 chiều.
- Hybrid dedupe marker mới chỉ thấy `Hybrid dedupe mark upsert`; chưa quan sát được `Hybrid dedupe memory hit/db hit` trong phiên này.

Backlog ghi nhận:
- Ưu tiên transport pressure control: `P0-RX-4` (in-flight window + queue cap), `P0-RX-5` (adaptive backoff), `P0-RX-6` (target health/quarantine).
- ACK lane cần tối ưu theo batch/fairness dưới tải (`P0-ACK-2`) để không cộng hưởng nghẽn khi burst.
- Hoàn thiện marker dedupe hit (`memory/db`) trong test profile (`P0-DUP-2`) để phân tách rõ lỗi dedupe vs lỗi transport retry.
- Tiếp tục theo dõi lag UI trên B (Samsung Galaxy A04s, Android 14) sau khi giảm retry storm.

### TC8 - Nhiễu môi trường BLE thực tế
Mục tiêu: xác nhận độ bền trong môi trường nhiều thiết bị BLE lân cận.

Test steps:
1. Giữ loa/chuột/bàn phím BLE xung quanh hoạt động bình thường.
2. Lặp lại TC1 + TC7.

Kỳ vọng:
- Duy trì nhận 2 chiều; có thể tăng delay nhưng không one-way kéo dài.

Kết quả thực tế:
- `PENDING`

Backlog ghi nhận:
- Chưa có.

### TC9 - ACK/Delivered consistency
Mục tiêu: xác nhận hai chiều đều cập nhật trạng thái gửi nhất quán.

Test steps:
1. A gửi 1 tin cho B, B xác nhận đã thấy tin.
2. Kiểm tra A chuyển `sent -> delivered/seen`.
3. B gửi ngược lại 1 tin cho A, kiểm tra chiều ngược tương tự.

Kỳ vọng:
- Không có tình huống chỉ một chiều lên delivered kéo dài.

Kết quả thực tế:
- `PASS (cảnh báo)` trong phiên retest mới nhất.
- Chiều `A -> B` đạt: B nhận/render `TC9: A -> B`.
- Chiều `B -> A` đạt: A nhận/render `TC9: B -> A`.
- ACK consistency cải thiện: cả hai phía đều có `ACK applied seen` cho message chiều ngược lại trong cùng window log.

Backlog ghi nhận:
- Duplicate/replay vẫn cao: cùng event được receive nhiều lần trên cả hai thiết bị.
- Cần tiếp tục xử lý `P0-DUP-1` để giảm burst duplicate và nhiễu UI.
- Tiếp tục theo dõi ACK consistency ở phiên dài (TC10/TC11), chưa thể coi là ổn định hoàn toàn chỉ với 1 retest TC9.

### TC10 - Session ổn định dài hơn
Mục tiêu: kiểm tra độ ổn định dài phiên khi app foreground/background luân phiên.

Test steps:
1. Duy trì app mở 10-15 phút, mỗi 2-3 phút gửi qua lại 1 tin.
2. Xen kẽ bật/tắt màn hình, chuyển app nền/foreground.

Kỳ vọng:
- Không tụt về one-way, không crash/hang.

Kết quả thực tế (retest mới nhất):
- `FAILED (stability)`.
- Có bằng chứng 2 chiều ở mốc cuối: `B -> A #6` và `A -> B #6` đều gửi/nhận thành công.
- Tuy nhiên toàn phiên dài chưa ổn định: lặp `GATT stalled/hard reset/connect failed (133/timeout)` và retry cùng `eventId` theo chu kỳ dài.
- Không đạt tiêu chí continuity sau các vòng sleep/background; session vẫn có giai đoạn drift/churn cao dù không one-way tuyệt đối ở mốc cuối.

Backlog ghi nhận:
- Chưa đạt ổn định phiên dài do connection churn (`stalled/reset/133/timeout`) và retry storm.
- Cần triển khai cụm mới đã mở trong P0: `P0-RX-4`, `P0-RX-5`, `P0-RX-6`, `P0-ACK-2`, `P0-DUP-2`.
- Retest gate TC10 sau fix: phải giữ continuity qua đủ baseline + 4 vòng + kill-app #6, không cần reopen chat để hồi trạng thái.

### TC11 - ACK hardening + inferred delivery
Mục tiêu: xác nhận sender không kẹt sent khi ACK event bị rớt, và state vẫn đúng theo bằng chứng thực tế.

Test steps:
1. A gửi 3-5 tin cho B trong điều kiện link chập chờn (di chuyển xa/gần hoặc toggle nhẹ để tăng xác suất rớt ACK).
2. Trên B mở chat và xác nhận đã thấy đủ tin (UI render đầy đủ).
3. Đối chiếu trên A: message state phải lên delivered (có thể là inferred) dù không bắt buộc nhận đủ ACK event cho từng tin.
4. Xác nhận seen chỉ lên khi có bằng chứng đọc trực tiếp (ACK seen/receipt tương đương), không tự suy diễn từ delivered.
5. Kiểm tra log có marker evidence (inventory/replay/peer-have) dùng để nâng trạng thái inferred-delivered.

Kỳ vọng:
- Không còn tình trạng kẹt sent kéo dài khi B đã nhận và hiển thị tin.
- delivered có thể đến từ inferred evidence hợp lệ, không phụ thuộc cứng vào 1 ACK event.
- seen vẫn bảo thủ, chỉ cập nhật khi có read evidence trực tiếp.

Kết quả thực tế:
- `FAILED` trong phiên log hiện tại.
- Chiều `A -> B` đạt đủ trong window test (B nhận/render các message mốc).
- Chiều `B -> A` không ổn định ở cuối window: A nhận được các message đầu, nhưng thiếu message cuối từ B.
- ACK objective chưa đạt: có `SYS.ACK` được tạo/rời rạc nhưng chưa có bằng chứng ổn định cho chuỗi `ACK received -> ACK applied delivered/seen` trên toàn bộ message.
- Chưa có bằng chứng policy inferred-delivered đang vận hành ổn định để kéo state khỏi `sent` khi ACK event bị rớt.

Backlog ghi nhận:
- Cần hoàn tất `P0-ACK-1`: ACK hardening theo chuỗi `generated -> sent -> received -> applied` với marker rõ ràng, đối chiếu theo `messageId -> ackId`.
- Bổ sung inferred-delivered policy có guardrail (chỉ nâng `delivered`, không tự nâng `seen`) từ evidence hợp lệ.
- Vẫn cần giảm lỗi transport nền (`GATT stalled/hard reset/133/timeout`) vì đây là nguyên nhân gốc làm rớt ACK và tái hiện one-way.

## 3) Backlog tổng hợp sau TC1-TC11
- Giảm latency recovery sau biến cố BT/Location OFF->ON, đặc biệt các spike > 30s.
- Giảm `status=133`/`connect timeout` và tần suất `hard reset`.
- Hạn chế duplicate delivery do retry/retransmit.
- ACK delivered/seen chưa ổn định sau burst 10+10.
- ACK consistency hai chiều chưa đạt (TC9 failed).
- Phiên dài (TC10) vẫn one-way và mất message chiều B->A.
- Triển khai `P0-OBS-1`: persisted Activity Log session bằng foreground capture service để không mất log khi app/process bị kill.






