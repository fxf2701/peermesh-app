# Bản đồ cho Nhóm — Đặc tả Kỹ thuật & UX
## Ưu tiên offline • Ưu tiên quyền riêng tư • Trung lập theo thiết kế

**Trạng thái:** Bản nháp  
**Áp dụng cho:** Ứng dụng di động (Android / iOS)  
**Hồ sơ ngữ cảnh:** `urban`, `outdoor`  
**Phạm vi:** Chỉ bản đồ theo nhóm (không có bản đồ toàn cục)

---

## 1. Mục đích & Mục tiêu Thiết kế

Tính năng **Bản đồ cho Nhóm** cung cấp một bề mặt nhận thức tình huống chung cho các nhóm nhỏ hoạt động trong môi trường kết nối hạn chế.

Tính năng này được thiết kế để:
- Hỗ trợ **phối hợp mà không chỉ huy**
- Chia sẻ **quan sát, không phải chỉ dẫn**
- Giảm rủi ro **thao túng, ảo giác thẩm quyền, và hiệu ứng bầy đàn**
- Hoạt động **ưu tiên offline** với đồng bộ trễ
- Duy trì **trung lập và tập trung vào quan sát theo thiết kế**

Bản đồ này **không**:
- Là hệ thống điều hướng hay định tuyến
- Là nguồn thông tin đã được xác minh
- Là bản đồ tình huống toàn cục hay công khai

---

## 2. Nguyên tắc Cốt lõi

1. **Phạm vi chỉ theo nhóm**
   - Mỗi bản đồ bị ràng buộc chặt với một nhóm (`gid`)
   - Không có bản đồ toàn cục hoặc xuyên nhóm

2. **Quan sát thay vì chỉ dẫn**
   - Marker mô tả điều kiện quan sát được
   - Không có lệnh chỉ hướng hay ngữ nghĩa “vùng an toàn”

3. **Mặc định là tạm thời**
   - Tất cả dữ liệu chia sẻ đều có TTL
   - Thông tin mờ dần theo thời gian để tránh thẩm quyền lỗi thời

4. **Dữ liệu cá nhân là tùy chọn**
   - Vị trí và lộ trình chỉ lưu cục bộ trừ khi người dùng chủ động chia sẻ

5. **Ngôn ngữ & biểu tượng trung lập**
   - Tên marker mô tả quan sát thực tế, tránh ngôn ngữ cổ súy hoặc định hướng chỉ huy

---

## 3. Hồ sơ Ngữ cảnh Bản đồ

Hệ thống bản đồ giống nhau nhưng thích ứng hành vi theo hồ sơ ngữ cảnh.
Ngữ cảnh ảnh hưởng **UI, mặc định, và chính sách**, không ảnh hưởng kiến trúc lõi.

### 3.1 Hồ sơ `urban`
Dành cho môi trường đông đúc, phức tạp.

**Chính sách**
- TTL ngắn cho marker
- Không vẽ tuyến đường hoặc điều hướng
- Chia sẻ hiện diện rất hạn chế
- Nhấn mạnh mạnh vào tính mới & độ không chắc chắn

**Trường hợp sử dụng**

- Giao tiếp trong không gian gần như quán cà phê hoặc khu sinh hoạt chung: mọi người có thể tạo hoặc tham gia nearby room để trò chuyện, bắt chuyện và làm quen khi còn ngại mở lời trực tiếp.
- Giao tiếp tại những nơi thiếu internet hoặc có kết nối không ổn định: hỗ trợ các thành viên ở gần nhau trao đổi tin nhắn và chia sẻ thông tin cục bộ.
- Hỗ trợ cứu trợ thiên tai tại khu dân cư: các nhóm trao đổi thông tin và chia sẻ ghi nhận về nhu cầu hỗ trợ, vật tư và lối đi khi kết nối internet bị gián đoạn.

---

### 3.2 Hồ sơ `outdoor`
Dành cho môi trường tự nhiên và nhóm nhỏ.

**Chính sách**
- TTL dài hơn cho marker
- Tùy chọn lịch sử dấu vết / lộ trình
- Chỉ báo hiện diện rõ ràng hơn
- Marker chú ý kiểu SOS (chỉ trong nhóm)

**Trường hợp sử dụng**

- Hiking, trekking, dã ngoại, cắm trại và các hoạt động ngoài trời: hỗ trợ liên lạc giữa các thành viên ở gần nhau, chia sẻ điểm hẹn và ghi nhận về lộ trình.
- Hoạt động tại vùng hoang dã, vùng xa và những nơi thiếu internet hoặc có kết nối không ổn định: hỗ trợ nhóm trao đổi thông tin qua kết nối cục bộ giữa các thiết bị ở gần nhau.
- Hỗ trợ cứu trợ thiên tai tại vùng xa hoặc nơi hạ tầng liên lạc bị ảnh hưởng: các nhóm chia sẻ quan sát thực địa, nhu cầu hỗ trợ và vị trí tập kết vật tư.

---

## 4. Hệ thống Marker (Thống nhất & Trung lập)

Marker dựa trên **hiện tượng**, không dựa trên **tác nhân**.

### 4.1 Các Loại Marker Chuẩn

| Loại | Biểu tượng | Nhãn | Mô tả |
|----|----|----|----|
| obstacle | 🚧 | Lối đi bị cản | Đường bị chặn hoặc có vật cản |
| crowded | 👥 | Khu vực đông người | Mật độ hoặc mức độ hoạt động cao |
| risk | ⚠️ | Rủi ro tiềm ẩn | Có thể nguy hiểm hoặc có rủi ro |
| incident | 🚨 | Xáo trộn đang diễn ra | Xáo trộn đang diễn ra |
| medical | 🚑 | Cần hỗ trợ y tế | Cần sơ cứu hoặc hỗ trợ y tế |
| resource | 💧 | Điểm tiện ích | Nước, nghỉ, hoặc tiện ích |
| route_note | 🧭 | Quan sát lộ trình | Quan sát về tuyến đường hoặc lối mòn |
| environment | 🌧 | Điều kiện môi trường | Thời tiết hoặc điều kiện môi trường |
| info | 📝 | Ghi chú quan sát | Quan sát tự do |

---

### 4.1.1 Quy tắc ngôn ngữ UI (Trung lập, tập trung vào quan sát)

Sử dụng nhãn ngắn, mô tả quan sát. Tránh ngôn ngữ thể hiện thẩm quyền, cổ súy, hoặc mệnh lệnh.

**Ưu tiên**
- quan sát, báo cáo, ghi nhận
- có thể, tiềm ẩn, không chắc chắn
- khu vực, điểm, điều kiện

**Tránh**
- vùng an toàn, đã xác minh, chính thức
- lãnh đạo, chỉ huy, trạm kiểm soát
- an toàn, đáng tin, đã được làm sạch

---

### 4.2 Siêu dữ liệu Marker (Bắt buộc)

Mỗi marker phải bao gồm:
- Dấu thời gian
- TTL (thời gian sống)
- Chỉ báo độ mới
- Mức độ tin cậy:
  - “Quan sát trực tiếp”
  - “Không chắc chắn / được báo cáo”
- Tác giả ẩn danh (trong nhóm)

Marker tự mờ dần khi TTL sắp hết.

---

### 4.2.1 Loại Area (Giới hạn)

Area nên là **vùng**, không phải điểm. Cần giới hạn kích thước tối thiểu để tránh nhầm với thực thể đơn lẻ:
- Diện tích tối thiểu **10.000 m²**.
- Góc của polygon không được quá nhọn: **góc trong ≥ 45°**.

Loại area đề xuất (ít loại, trung lập):
| Loại | Mô tả |
| ---- | ----- |
| crowded_area | Khu vực đông người |
| restricted_area | Khu vực bị hạn chế / khó tiếp cận |
| hazard_area | Khu vực rủi ro |
| blocked_area | Khu vực bị chặn |
| incident_area | Khu vực có sự cố |
| flooded_area | Khu vực ngập |
| fire_area | Khu vực cháy / khói dày |
| medical_need_area | Khu vực cần hỗ trợ y tế |
| search_area | Khu vực cần tìm kiếm |

---

### 4.3 Hiển thị theo Đồng thuận (Giảm rối)

**Tạm hoãn** tính năng gộp đồng thuận (consensus merge). Hiện ưu tiên:
- **Cooldown theo loại + vị trí**
- **Chặn theo người dùng**
- **Vote/Note** trên item có sẵn

Sẽ xem xét bật lại consensus merge sau khi ổn định guardrails.

Hiển thị đồng thuận hiện tại:
- Mỗi marker/area hiển thị **Reported N / Observed M** (từ `map_item_votes` + confidence ban đầu của owner).
- Observed có trọng số cao hơn Reported.
- Sắp xếp danh sách theo trọng số: `weight = observed*2 + reported`, hoà thì theo `updated_t`.

Preview contribution (list + popup):
- Item trong list chỉ hiển thị **1 contribution mới nhất** (không hiển thị số lượng). Nếu chưa có vote:
  - Owner: hiện “No vote yet…” để nhắc cần thêm vote.
  - Non‑owner: hiện “Be the first person to vote…”.
- Popup hiển thị mục **Contributions**: 10 vote mới nhất + note, link “See more” để tải thêm 10.
- Lazy load theo **item đang thấy**; khi scroll thì **tạm dừng refresh**; refresh sau **0.5s** khi idle (throttle 5s).

---

### 4.4 Guardrails chống spam / chống “hùa”

Để giảm thao túng và thiên kiến đám đông:
- **Giới hạn tần suất theo người dùng** (vd: tối đa N marker / 10 phút).
- **Cooldown theo loại + vị trí** (tránh spam cùng loại quanh một điểm).
- **Chặn theo người dùng**: cùng một người không thể tạo 2 marker cùng loại quá gần nhau.
- **Chặn theo người dùng (marker có radius)**:
  - Nếu marker **có radius**: bán kính “gần” là `max(200m, radius * 0.5)` và **trần 3km**.
  - Nếu **không có radius**: giữ mức **200m**.
- **Chặn theo người dùng (area)**:
  - Bán kính “gần” là **động** theo kích thước: `max(200m, size * ratio)` và **trần 3km**.
  - `size` = max(chiều ngang, chiều dọc) của bbox area (mét).
  - `ratio = 0.15` nếu size < 3km, `ratio = 0.10` nếu size ≥ 3km.
  - **Cho phép refine/escalate**: nếu area mới **chứa** hoặc **bị chứa** bởi area cùng loại hiện có thì **cho phép**.
  - **Ngược lại** nếu bbox **chồng nhau** hoặc khoảng cách tâm ≤ bán kính động → chặn tạo và gợi ý Report/Observe.
- **Dedup theo khoảng cách**: nếu đã có marker cùng loại gần đó, ưu tiên “Report/Observe” thay vì tạo mới.
- **Cooldown theo loại + bán kính (chống spam)**:
  - Nếu có marker/area **cùng loại** ở gần và được tạo/cập nhật trong **10 phút**, chặn tạo mới.
  - Hiển thị gợi ý **Report/Observe** thay vì tạo thêm item.
  - Bán kính “gần” dùng cùng quy tắc **động** như proximity guard.
- **Trọng số độ tin cậy**: Observed nặng hơn Reported.
- **Giới hạn TTL theo loại** (min/max) để tránh đặt TTL cực đoan.
- **Một trạng thái mỗi người**: mỗi người chỉ được Report *hoặc* Observe cho cùng marker/area. Nếu đổi ý thì chuyển trạng thái, không cộng chồng.
- **Giới hạn đổi ý 1 lần**: mỗi người chỉ được đổi trạng thái tối đa **1 lần** cho cùng marker/area.
- **Quy tắc marker đầu tiên**: khi tạo marker/area mới, người tạo **chỉ được Reported** và **không thể đổi sang Observe** cho chính item đó. Observe chỉ áp dụng với item đã tồn tại bởi người khác.

Không chặn báo cáo khẩn cấp, nhưng khuyến khích người dùng xác nhận trên marker có sẵn.

---

## 5. Chia sẻ Vị trí & Dấu vết

### 5.1 Hành vi Mặc định
- Vị trí và dấu vết **chỉ lưu cục bộ**
- Không theo dõi nền
- Không tự động phát

---

### 5.2 Tùy chọn Chia sẻ (Chủ động)

#### A. Chia sẻ với Nhóm
- Bật/tắt do người dùng chủ động
- TTL bắt buộc (vd: 10p / 30p / 2h)
- Hiển thị:
  - Vị trí hiện tại
  - Thời điểm thấy lần cuối
- Mặc định không có lịch sử di chuyển

#### B. Chia sẻ với Người được chọn
- Chia sẻ theo năng lực
- Token ngắn hạn (QR / mã)
- Thuộc tính token:
  - TTL
  - Giới hạn sử dụng (một người hoặc nhóm nhỏ)

Có thể dừng chia sẻ bất cứ lúc nào.

---

### 5.3 Chia sẻ Dấu vết / Lộ trình
- Dấu vết được ghi cục bộ
- Tùy chọn chia sẻ:
  - Theo cửa sổ (N phút gần nhất)
  - Điểm mốc thay vì polyline đầy đủ
- Không có lộ trình đề xuất hay gợi ý

### 5.4 Ghi dấu vết (Chỉ cục bộ)
- Chế độ Manual: người dùng nhấn Start / Stop.
- Chế độ Auto: bắt đầu sau khi vượt ngưỡng di chuyển, kết thúc khi không vượt ngưỡng trong khoảng thời gian chờ.
- Ngưỡng di chuyển tự điều chỉnh theo motion mode (đi bộ / xe máy / ô tô) suy ra từ tốc độ.
- Dừng ngắn (vd: đèn đỏ) không reset motion mode.
- Trong một trail đang chạy, motion mode chỉ nâng cấp (đi bộ → xe máy → ô tô), không hạ xuống cho đến khi bắt đầu trail mới.
- Dữ liệu trail lưu cục bộ (DB) trừ khi người dùng chủ động chia sẻ.
- Khi hiển thị trên bản đồ, **chỉ tải điểm trail khi bật Show**; danh sách chỉ dùng metadata (tiết kiệm RAM).  
  Cache trail được clear khi: tắt Show, chuyển sang trail khác, `onPause`, và khi switch actor.

**Ngưỡng (mặc định hiện tại)**
- Đi bộ: ngưỡng 20m.
- Xe máy: ngưỡng 30m.
- Ô tô: ngưỡng 50m.
- Auto-stop: 8 phút không vượt ngưỡng.
- Dừng ngắn (đèn đỏ): 30–120s giữ nguyên motion mode.
- Lọc accuracy: bỏ điểm nếu accuracy > 30m.
- Lọc tốc độ bất thường: bỏ điểm nếu tốc độ suy ra > 120 km/h.
- Lọc nhảy: bỏ điểm nếu nhảy ≥ 80m trong ≤ 3s.
- Dead‑zone theo accuracy: chỉ cộng quãng vượt khỏi sai số để tránh drift.
- Lọc drift đi bộ: nếu speed < 1 m/s và quãng vượt (accuracy × 2) trong 10s thì coi là jump.
 Lọc quán tính heading (moving policy):
  - Chỉ áp dụng khi tốc độ >= 1 m/s; dưới 1 m/s ưu tiên compass và bỏ suy luận 3/4 điểm.
  - Trong vùng tốc độ >= 1 m/s, giật lùi bị flag nếu hướng đảo >= 120° và quãng lùi > 20% quãng kỳ vọng.
  - Đứng yên gắt chỉ bị flag khi tốc độ >= 5 m/s, nếu quãng tiến < 20% quãng kỳ vọng.
  - Ở tốc độ đi bộ (~1–2 m/s), dừng đột ngột được phép; giật lùi ở ~1 m/s cũng được phép.
- Quy tắc điểm giả định (xử lý jump):
  - Điểm jump không bao giờ lưu vào DB hay hiển thị trên bản đồ.
  - Khi phát hiện jump, tạo điểm giả định để hiển thị; nếu jump lặp liên tiếp thì commit điểm giả định trước đó vào DB.
  - Quãng dịch chuyển giả định bị giới hạn (vd tối đa 30m mỗi interval) để tránh nhảy vô lý.

**Vì sao dùng các ngưỡng này**
- Ngưỡng di chuyển (20/30/50m) giúp giảm false start/stop do jitter GPS, nhưng vẫn phản hồi với di chuyển thực tế ở bối cảnh đi bộ/xe máy/ô tô.
- Cửa sổ idle 8 phút tránh dừng khi chỉ tạm dừng ngắn; cửa sổ dừng ngắn 30–120s giữ nguyên motion mode cho tình huống dừng đèn đỏ.
- Accuracy > 30m và lọc nhảy lớn giúp loại bỏ điểm nhiễu (nhất là trong nhà hoặc đô thị nhiều nhà cao tầng) mà không quá gắt khi ở ngoài trời.
- Dead‑zone theo accuracy giúp tránh “đứng yên mà quãng đường vẫn tăng” do sai số GPS.
- Lọc drift đi bộ giúp bắt các spike trong nhà không đủ lớn để bị lọc theo ngưỡng nhảy chung.
- Ngưỡng > 120 km/h loại bỏ outlier cực lớn, ít gặp trong các bối cảnh nhóm.
- Lọc quán tính heading giúp tránh heading bị flip do jitter, nhưng vẫn cho phép dừng ngắn và thao tác ở tốc độ thấp.

**Pipeline lọc điểm & tạo điểm giả định**
1) **Rule 3 điểm (quán tính – ưu tiên)**
   - Dùng 3 điểm gần nhất.
   - Nếu speed >= 1 m/s:
     - Nếu hướng đảo >= 120° và lùi > 20% quãng kỳ vọng -> jump.
     - Nếu speed >= 5 m/s và tiến < 20% quãng kỳ vọng -> jump.
   - Nếu speed < 1 m/s: bỏ qua rule quán tính/trục (cho phép dừng đột ngột / lùi nhẹ).
2) **Rule 4 điểm (trục chính – fallback)**
   - Dùng 4 điểm gần nhất.
   - Loại điểm khiến 3 điểm còn lại lệch ít nhất.
   - Trục chính = đoạn dài nhất trong 3 điểm còn lại.
3) **Kiểm tra điểm thô**
   - Bỏ nếu accuracy > 30m.
   - Bỏ nếu tốc độ suy ra > 120 km/h.
   - Với nhảy xa (>= 80m trong <= 3s): chỉ bỏ nếu segment **không aligned** với trục (rule 3/4 điểm). Nếu aligned thì chấp nhận.
4) **Điểm giả định**
   - Điểm jump không lưu/không hiển thị.
   - Tạo điểm giả định để hiển thị.
   - Nếu speed < 1 m/s -> giữ nguyên vị trí trước (đứng yên).
   - Ngược lại: chiếu theo trục (nếu đủ điểm), fallback theo bearing hợp lệ trước đó.
   - Giới hạn quãng giả định tối đa 30m mỗi interval.
   - Chỉ lưu điểm giả định nếu jump xảy ra liên tiếp.

5) **Persist & Export UX**
   - Persist chạy định kỳ và khi stop; hiển thị trạng thái “Saving…” trong lúc persist.
   - Export tạm bị vô hiệu trong lúc saving để tránh export rỗng.
   - Dùng foreground “saving” để giữ process đến khi DB persist xong.
   - Tính quãng đường có áp dụng dead‑zone theo accuracy để giảm drift.
   - Auto‑trail watch hiển thị notification “Auto‑trail ready” khi chưa recording.
   - Hành vi khi GPS tắt:
     - Nếu GPS tắt và chưa có trail hoạt động: auto‑trail bị disable và ép về Manual.
     - Manual Start sẽ prompt bật GPS; nếu user từ chối và có mạng thì vẫn record bằng Network (gắn cờ unstable).
     - Nếu GPS tắt khi auto‑trail đang chạy thì chuyển sang Manual và tiếp tục đến khi user Stop.
     - Trail có gián đoạn GPS sẽ bị đánh dấu unstable + note lý do.

**Speed status vs Motion mode**
- **Speed status** phản ánh tốc độ tức thời (idle/walk/moto/car/high) và dùng như cờ trạng thái cho UI/logic.
- **Motion mode** đại diện loại phương tiện của trail để chọn rule/threshold; trong 1 trail chỉ upgrade (không downgrade).

**Các hạn chế tiềm ẩn (review sau)**
- Distance vẫn có thể bị phình ở moto/car nếu GPS jitter tích lũy giữa các điểm được accept (dead‑zone chỉ dùng accuracy của điểm hiện tại).
- Nếu khoảng cách thời gian giữa các điểm quá dài thì có thể vượt qua jump filter dạng cửa sổ ngắn (dt > jump window).
- Khi `accuracy` không có, dead‑zone = 0 nên dễ bị cộng dư distance.
- Điểm đầu trail có thể lấy từ vị trí cũ; đoạn đầu có thể bị lớn bất thường nếu fix trước đó ở xa.
- Backfill distance (`dist_m <= 0`) đang cộng khoảng cách thô giữa các điểm, không áp dụng dead‑zone/jump filter.
- Tốc độ cao vẫn có thể bị cộng dư nếu thiết bị báo nhiều lệch nhỏ quanh trục.

---

## 6. Mô hình Hiện diện

Hiện diện là:
- Tùy chọn
- Có giới hạn thời gian
- Theo phạm vi nhóm

### Quy tắc Hiển thị
- Hiển thị chấm + thời gian thấy lần cuối
- Không có tốc độ, hướng, hoặc dự đoán
- Hồ sơ urban: rất hạn chế
- Hồ sơ outdoor: mở rộng (pin, lần đồng bộ gần nhất)

---

## 7. Rào chắn UI & Copy

### Tuyên bố miễn trừ luôn hiển thị
> “Bản đồ này phản ánh quan sát của nhóm, không phải thông tin được xác nhận.”

### Các khái niệm UI bị cấm
- “Vùng an toàn”
- “Vị trí đã xác minh”
- “Điểm lãnh đạo”
- “Lộ trình khuyến nghị”

### Hướng dẫn ngôn ngữ
- Dùng: quan sát, báo cáo, ghi nhận
- Tránh: xác nhận, đáng tin, chính thức

---

## 8. Hành vi UI Bản đồ (Android Phase 2)

- Tuyên bố miễn trừ nổi trên bản đồ khi mở (hiển thị ~5s), sau đó chuyển sang “Tap to place a marker…”.
- Hiển thị vị trí hiện tại của người dùng bằng chấm.
- Hiển thị mũi tên hướng:
  - Ưu tiên hướng theo chuyển động (GPS bearing) khi đang di chuyển.
  - Fallback sang hướng la bàn khi đứng yên.
  - Ẩn nếu không có hướng.
- Tự điều chỉnh chu kỳ cập nhật vị trí theo tốc độ.
- Viewport bản đồ có **3 mức** qua handle ở đáy (click hoặc swipe): expanded, collapsed, và **max** (ẩn danh sách).
- Nút “Center on current location”.
- Chế độ follow khi di chuyển: nếu motion mode là xe máy/ô tô thì vị trí người dùng được đẩy xuống dưới để thấy “đường phía trước”; đi bộ/đứng yên ở giữa trừ khi người dùng kéo bản đồ.
  - Follow chạy theo chu kỳ 15s; nếu user kéo/zoom thì tạm dừng follow 60s sau khi dừng thao tác (không áp dụng khi đi bộ/đứng yên).
- Khi motion mode nâng cấp từ đi bộ → xe máy/ô tô thì follow sẽ tự bật lại ngay (kể cả trước đó người dùng có kéo bản đồ).
- UI bật Location dùng Settings API (fallback sang Settings hệ thống).
- Hiển thị trails cục bộ bằng polyline trên bản đồ.
- Danh sách Map hiển thị **Shared Live Trails** (từ người khác) với nút “Show”.
- Hiển thị trail:
  - Có điểm bắt đầu/kết thúc (🚩 start, 🏁 end).
  - Màn hình playback trail cũng hiển thị 🚩 start / 🏁 end.
  - Line dày hơn; màu segment theo **tốc độ đoạn** (dist/dt), không dùng `spd` của từng điểm (đứng yên=đỏ, đi bộ=cam, xe máy=vàng, ô tô=xanh lá, high=tím).
  - Khi zoom xa, downsample điểm và gộp segment cùng màu để giảm tải render.
  - Chỉ render các đoạn trong vùng nhìn thấy; vẽ lại sau khi dừng scroll (~0.5s).
  - Vẽ đoạn gián đoạn (gray dashed) khi:
    - Chênh lệch thời gian giữa 2 điểm liền kề >= 15s (Normal) hoặc >= 60s (Battery Saver), hoặc
    - Đoạn đầu: nếu điểm trước không có speed/0 và segment speed > walk max thì coi là gián đoạn, hoặc
    - Gap theo số lần bỏ mẫu: dt >= 45s và (dt / expectedInterval) >= 6, expectedInterval dựa vào scan mode + segment speed, hoặc
    - Chênh lệch < 5 phút, speed > 0.5 m/s, dt >= 2s, **segment speed > walk max**, và khoảng cách thực > 2x khoảng cách kỳ vọng (speed * dt).
  - Cache segment toàn bộ trail theo (trailId + zoom bucket); khi scroll chỉ lọc theo viewport (không rebuild).
  - (Optional) Pre‑simplify (LOD) trail khi zoom xa; có thể kết hợp với cache sau.
  - (Optional) Giới hạn số segment trail render mỗi frame; nếu vượt ngưỡng thì chỉ render subset ưu tiên gần trung tâm viewport.

### Chia sẻ Live Trail (Group Map)
- Chế độ chia sẻ: **To group** hoặc **To selected members**.
- Event:
  - `LIVE_TRAIL_START`: metadata + phạm vi chia sẻ/đối tượng.
  - `LIVE_TRAIL_POINT_BATCH`: batch 10s với `start_index` + points.
  - `LIVE_TRAIL_END`: dừng/archived.
- Xác nhận phía người nhận:
  - Trail được chia sẻ sẽ ở trạng thái **Pending** và **không tải/lưu điểm** cho đến khi người nhận bấm **Accept**.
  - Pending hiển thị **Accept** và **Delete**. Delete chỉ ẩn trail ở máy người nhận (không phát event).
  - Pending sẽ **hết hạn sau 24h** nếu không được accept (ẩn khỏi danh sách; notification tự tắt).
  - “Stale” (không update 2 phút) và “Reconnected” (2 phút sau khi có lại dữ liệu) chỉ áp dụng sau khi accept.
- Backfill:
  - Ưu tiên realtime cho điểm mới nhất.
  - Sau khi trail kết thúc, client **backfill** các điểm thiếu từ events/relay (không cần ACK/resend).
- Khi stop share: phía người share vẫn ghi local; phía người nhận không nhận thêm điểm mới và trail chuyển **archived** (vẫn xem lại được).
- Trail archived phía người nhận có thể **xoá local** (không ảnh hưởng người share).
- Bật share lại sẽ tạo **live trail mới** (trail_id mới).
- Cleanup:
  - Event live trail TTL **7 ngày** (relay cleanup).
  - Local cleanup: xoá pending hết hạn, xoá trail đã kết thúc > 7 ngày, xoá trail active không update > 60 ngày.
  - Khi pending hết hạn hoặc user bấm Delete pending, xoá **LIVE_TRAIL_\*** events của trail đó khỏi bảng events local.
  - Cleanup chạy bằng **WorkManager** (định kỳ).

### Live Trail UI (Danh sách chia sẻ)
- Pending (chưa accept): chỉ hiển thị **creator + start time** (không hiển thị end/elapsed/km/pts).
- Archived chỉ áp dụng khi **accepted = true** và có **END event**.
- Refresh live trail (10s): center map vào **điểm cuối**.

---

## 8.1 Ghi chú theo người dùng (Report/Observe)

Để tránh việc chỉnh sửa làm sai lệch thông tin gốc nhưng vẫn thu thập được bối cảnh:
- **Non‑owner không được sửa** icon/label/note/TTL/geometry của marker/area hiện có.
- Mỗi người chỉ được **một ghi chú** cho mỗi marker/area khi Report/Observe.
- Ghi chú là **theo người dùng**, chỉ tác giả mới được sửa/xóa.
- Owner của marker/area **không** được sửa/xóa note của người khác.
- **Xóa note thì xóa luôn vote** (Reported/Observed) của người đó.
- Ghi chú lưu ở bảng riêng với khóa `(item_id, actor_id)` (upsert).
- UI: khi Report/Observe có ô nhập note (tuỳ chọn); nếu đã có note thì cho Edit/Delete.

Mục tiêu là giữ thông tin gốc ổn định nhưng vẫn có thêm ngữ cảnh từ cộng đồng.

---

## 8.1.1 Yêu cầu gỡ (Owner)

Owner có thể yêu cầu gỡ marker/area thay vì xoá trực tiếp:
- **Owner có thể yêu cầu gỡ** với **lý do bắt buộc**.
- Marker/area **vẫn tồn tại đến hết TTL** (không rút ngắn TTL).
- Trên bản đồ, item có yêu cầu gỡ sẽ **hiển thị trong suốt**.
- Trong popup chi tiết sẽ hiển thị: **“Requested removal by owner. Reason: …”**
- Owner có thể **huỷ yêu cầu**, **cooldown 30s** (nút Cancel bị disable trong thời gian cooldown).

---

## 8.2 Cửa sổ chỉnh sửa thông tin marker/area

Các trường thông tin gốc (icon/type/label/geometry/TTL) chỉ được phép sửa trong **một khoảng thời gian giới hạn**:
- `lock_at = clamp(0.5 * TTL, min=10 phút, max=6 giờ)`
- Sau `lock_at`, **chỉ cho phép sửa ghi chú của chính người đó**; không cho đổi vote.
- Nếu chưa có vote/note trước `lock_at` thì không được thêm mới vote/note.

---

## 9. Tính năng Chỉ cục bộ

Một số tính năng cố ý **không đồng bộ**:
- Tổng quan nhiều nhóm (cho các nhóm của người dùng)
- Bộ lọc theo bán kính
- Bộ lọc marker theo tuổi

Đây là các tiện ích chỉ ở phía client.

---

## 10. Mô hình Dữ liệu & Đồng bộ

- Tất cả sự kiện trên bản đồ đều:
  - Ràng buộc theo nhóm
  - Được mã hóa
  - Được quản lý bởi TTL
- Relay coi dữ liệu bản đồ là payload mờ
- Không tổng hợp hoặc diễn giải phía server

---

## 11. Phi mục tiêu Rõ ràng

Bản đồ cho Nhóm **không**:
- Cung cấp thẩm quyền điều hướng
- Xác minh an toàn hay độ đúng
- Ngăn chặn lừa dối hoặc xâm nhập
- Thay thế phán đoán của con người

---

## 11.2 Hạn chế / Cân nhắc cải thiện sau

- **Thời gian thao túng**: thao túng có tổ chức thường cần hàng chục phút đến vài giờ.  
  Cửa sổ chỉnh sửa hiện tại (khóa sau `clamp(0.5 * TTL, 10m, 6h)`) giúp giảm rủi ro nhưng có thể cần tinh chỉnh theo loại/ngữ cảnh.
  Có thể xem xét rút ngắn thời gian cho các loại nhạy cảm hoặc bổ sung tín hiệu chống điều phối trong tương lai.
- **Cửa sổ vote**: hiện vote được phép trong toàn bộ TTL.  
  Vote muộn có thể bị lợi dụng để thao túng đồng thuận; có thể xem xét giới hạn thời gian vote hoặc giảm trọng số vote theo thời gian.
- **Lộ danh tính tác giả**: hiển thị người tạo marker/area hoặc tác giả note có thể tạo điều kiện nhắm mục tiêu / herding.  
  Có thể cân nhắc ẩn danh hoàn toàn trong UI (chỉ hiển thị số lượng, nội dung note, thời gian).
- **Đồng thuận có trọng số**: có thể cân nhắc weight theo độ mới và nguồn (observed > reported) thay vì đếm thô để giảm thao túng.
- **Không có trọng số theo thời gian**: hiện vote không giảm theo tuổi; vote cũ có trọng số như vote mới.  
  Nếu có dấu hiệu sai lệch, cân nhắc time‑decay hoặc cửa sổ thời gian.
- **Nhất quán theo thời gian**: số Report/Observe chỉ chính xác tuyệt đối sau khi các thiết bị sync đầy đủ.  
  Trong giai đoạn chưa sync, các thiết bị có thể hiển thị số khác nhau.
- **Rủi ro multi‑actor (sybil)**: đếm theo actor nên có thể bị thổi phồng nếu tạo nhiều actor/thiết bị.  
  Có thể cân nhắc rate limit, reputation hoặc trọng số nguồn nếu cần.
- **Độ trễ UI refresh**: danh sách refresh theo timer 60s + lazy preview; số liệu/preview có thể trễ tối đa ~1 phút nếu không có refresh thủ công.

---

## 11.1 Công cụ Test / Debug (Không đưa vào Production)

Các tiện ích sau **chỉ dùng để test** và **phải tắt trong bản phát hành production**.

- **Bộ chuyển alt-user (chỉ Test):**
  - Tạo và chuyển giữa nhiều actor cục bộ trên một thiết bị.
  - Main actor là cố định (actor đầu tiên).
  - Chuyển chỉ thay đổi active actor; **không** xóa DB.

---

## 12. Tóm tắt

Tính năng Bản đồ cho Nhóm là một **lớp ghi chú tình huống dùng chung**, không phải hệ thống chỉ huy.

Nó ưu tiên:
- Ngữ cảnh cục bộ
- Tính liên quan theo thời gian
- Quyền chủ động cá nhân
- An toàn thông qua phi tập trung

Theo thiết kế, nó tránh trở thành một thẩm quyền, một luồng toàn cục, hoặc bề mặt thao túng.

---

**Kết thúc tài liệu**
