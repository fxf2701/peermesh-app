# Codex VSCode Debug Kit v4 (BLE Mesh - Tiếng Việt)

## 🎯 Mục tiêu

Bộ debug kit này giúp debug BLE Mesh Android/Kotlin theo hướng có hệ
thống, giảm đoán mò và giảm vòng lặp patch sai.

------------------------------------------------------------------------

## ⚙️ Workflow chuẩn

1.  Pull logs
2.  Select token
3.  GOOD token → dựng flow chuẩn
4.  BAD token → tìm divergence
5.  So sánh
6.  Root cause
7.  Patch nhỏ nhất
8.  Test lại

------------------------------------------------------------------------

## 🧠 Debug mindset

Không hỏi: "Fix bug giúp tôi"

Hãy hỏi: "Message chết ở bước nào?"

------------------------------------------------------------------------

## 🔑 Token

GOOD = flow đầy đủ\
BAD = flow lỗi

------------------------------------------------------------------------

## 🧠 Prompt điều tra (KHÔNG PATCH)

Chỉ sử dụng bundle bên dưới làm nguồn dữ liệu DUY NHẤT.

1.  Dựng runtime flow
2.  Tìm divergence
3.  So sánh device
4.  Top 3 root cause
5.  KHÔNG patch

------------------------------------------------------------------------

## 🧪 Prompt GOOD vs BAD

So sánh GOOD và BAD:

1.  Flow chuẩn
2.  Flow lỗi thiếu gì
3.  Device diverge
4.  Layer lỗi

------------------------------------------------------------------------

## 🔍 Prompt điều tra sâu

1.  Tìm code path chunk / MTU
2.  Giải thích 79 vs 5 chunks
3.  Điều kiện degrade
4.  File/function liên quan

------------------------------------------------------------------------

## 🧩 Prompt PATCH

Đề xuất patch nhỏ nhất:

-   Không refactor
-   Không phá retry/dedup
-   Giải thích rõ
-   Output diff

------------------------------------------------------------------------

## 🧪 Prompt VERIFY

1.  Còn chunk 79 không?
2.  Flow có đúng chưa?
3.  Có side effect không?

------------------------------------------------------------------------

## 🧯 Prompt RESET

Stop patching.

1.  Sai ở đâu?
2.  Layer nào?
3.  Thiếu log gì?

------------------------------------------------------------------------

## 🎯 Nguyên tắc vàng

-   Không patch khi chưa rõ root cause
-   Không fix nhiều thứ cùng lúc
-   Luôn dựa vào log

------------------------------------------------------------------------

## 🚀 Kết luận

Token → Flow → Divergence → Root cause → Patch