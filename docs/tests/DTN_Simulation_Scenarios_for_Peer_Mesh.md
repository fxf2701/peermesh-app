# DTN Simulation Scenarios for Peer Mesh

Version 1.0 -- Field Validation Draft

------------------------------------------------------------------------

# SCENARIO 1 --- Mạng bị phân mảnh 2 cụm (Urban blackout)

## Bối cảnh

-   Khu đô thị 1km²
-   800 người trong khu
-   Internet bị cắt
-   BLE mesh chỉ nối được 2 cụm:
    -   Cụm A (400 người)
    -   Cụm B (300 người)
-   Khoảng cách giữa 2 cụm: 400m
-   Không có đường mesh liên tục

## Sự kiện

-   Cụm A phát hiện: lối thoát phía Tây an toàn
-   Thông tin cần lan sang cụm B

## Không có DTN

-   Thông tin dừng lại ở cụm A
-   Cụm B không biết

## Có DTN (Store-Carry-Forward)

1.  Người X ở cụm A nhận message
2.  Người X đi bộ 500m
3.  Gặp cụm B
4.  Thiết bị sync inventory
5.  Message được chuyển tiếp

## Điều kiện pass

-   Message không bị mất khi không có route
-   Message tự động sync khi gặp peer mới
-   Không bị duplicate flood
-   TTL không expire trước khi tới nơi

## Câu hỏi audit

-   Hệ thống hiện tại có lưu message khi không có route không?
-   Có inventory sync khi gặp peer mới không?
-   Có TTL đủ dài cho di chuyển thực tế không?

------------------------------------------------------------------------

# SCENARIO 2 --- Node di chuyển giữa 3 partition

## Bối cảnh

-   Partition A
-   Partition B
-   Partition C

Không có mesh liên tục giữa 3 partition.

Một người (Node M) di chuyển giữa A → B → C.

## Sự kiện

-   A gửi 10 message
-   B gửi 5 message
-   C gửi 3 message

Node M lần lượt gặp từng partition.

## Kỳ vọng DTN

-   Node M mang theo backlog
-   Không mất message
-   Không gửi trùng
-   Không loop vô hạn
-   Không vượt storage cap

## Điều kiện pass

-   Hop count hoạt động
-   Duplicate suppression hoạt động
-   Storage eviction không xoá message quan trọng

## Audit check

-   Có max_hops chưa?
-   Có Bloom filter hoặc inventory diff chưa?
-   Có priority scheduling chưa?

------------------------------------------------------------------------

# SCENARIO 3 --- Burst event (1000 message trong 5 phút)

## Bối cảnh

-   Tin đồn lan nhanh
-   1000 message được tạo
-   80% là low priority chat
-   20% là alert

## Kỳ vọng DTN

-   Alert được forward trước
-   Chat bị rate-limit
-   Storage không tràn
-   Không flood radio

## Điều kiện pass

-   Priority scheduler hoạt động
-   Backpressure strategy tồn tại
-   Storage cap enforce

## Audit check

-   Có priority queue chưa?
-   Có rate limit per peer chưa?
-   Có eviction theo priority chưa?

------------------------------------------------------------------------

# SCENARIO 4 --- Message TTL hết hạn giữa đường

## Bối cảnh

-   Message TTL = 30 phút
-   Node di chuyển chậm
-   Đến partition khác sau 45 phút

## Kỳ vọng DTN

-   Message bị drop
-   Không lan truyền message stale
-   Không gây nhiễu hệ thống

## Audit check

-   TTL check có chạy background không?
-   Có cleanup job không?

------------------------------------------------------------------------

# SCENARIO 5 --- Node power cycle

## Bối cảnh

-   Node đang giữ 200 message
-   Pin hết
-   Tắt máy
-   Bật lại sau 1 giờ

## Kỳ vọng DTN

-   Message store persist
-   Không mất inventory
-   Không gửi lại toàn bộ flood

## Audit check

-   Storage persistent hay memory-only?
-   Inventory index có persist không?

------------------------------------------------------------------------

# SCENARIO 6 --- Rogue node spam

## Bối cảnh

-   Node giả mạo
-   Gửi 10,000 message

## Kỳ vọng DTN

-   Signature verification reject invalid
-   Rate limit
-   Blacklist node
-   Không crash

## Audit check

-   Signature verify trước khi store chưa?
-   Có anti-spam mechanism chưa?

------------------------------------------------------------------------

# SCENARIO 7 --- Low density sparse environment

## Bối cảnh

-   20 người trong bán kính 1km
-   Không mesh liên tục
-   Di chuyển rải rác

## Kỳ vọng DTN

-   Store-Carry-Forward giúp lan truyền chậm nhưng không mất
-   Không phụ thuộc route real-time

## Nếu không có DTN

-   Mạng gần như vô dụng

------------------------------------------------------------------------

# SCENARIO 8 --- Multi-radio integration

## Bối cảnh

-   BLE local
-   Wi-Fi Direct bật khi có cluster
-   LoRa bridge giữa 2 cụm

## Kỳ vọng DTN

-   DTN layer không phụ thuộc transport
-   Không duplicate khi cùng message qua nhiều radio
-   Message ID toàn cục đảm bảo consistency

------------------------------------------------------------------------

# Checklist tổng hợp audit

  Feature                 Có   Chưa   Ghi chú
  ----------------------- ---- ------ ---------
  Message UUID                        
  TTL                                 
  Max hops                            
  Persistent store                    
  Inventory diff                      
  Bloom filter                        
  Priority queue                      
  Anti-spam                           
  Backpressure                        
  Transport abstraction               

------------------------------------------------------------------------

# Cách sử dụng tài liệu này

1.  Gửi cho dev team
2.  Yêu cầu đánh dấu từng scenario: Pass / Partial / Fail
3.  Đánh giá hệ thống đang ở mức Mesh Relay hay DTN thực thụ

------------------------------------------------------------------------

# Kết luận

Nếu hệ thống chỉ: - Relay multi-hop - Retry khi gặp peer - Không
anti-entropy - Không TTL - Không storage cap

→ Đây là Mesh Relay, chưa phải DTN hoàn chỉnh.

Nếu phần lớn scenario pass\
→ Hệ thống đã đạt mức DTN chịu phân mảnh thực sự.
