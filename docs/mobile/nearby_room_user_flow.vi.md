# Luồng người dùng: mở app, Room Discovery và tham gia Nearby Room

## Mục đích

Nearby Room là không gian trò chuyện tạm thời dành cho những thiết bị đang ở gần nhau. Room được tìm thấy qua Bluetooth Low Energy (BLE), không cần mật khẩu, liên kết mời hay kết nối đám mây. Room không thay thế Private Group và sẽ hết hạn hoặc được chủ phòng đóng.

Tài liệu này mô tả trải nghiệm người dùng; các bước truyền khóa, metadata và đồng bộ BLE diễn ra nền để bảo đảm người dùng chỉ vào phòng khi quyền truy cập đã sẵn sàng.

## 1. Mở ứng dụng

1. Người dùng mở PeerMesh và vào mục **Nearby Rooms** từ menu.
2. Nếu đây là lần đầu dùng Nearby Room, ứng dụng yêu cầu hoàn tất hồ sơ cục bộ:
   - nhập tên hiển thị;
   - chọn avatar.
3. Sau khi hoàn tất, trang Room Discovery bắt đầu tìm các phòng lân cận. Người dùng có thể quay lại trang này bất kỳ lúc nào để tiếp tục tìm phòng.

Không có tài khoản mạng hay danh bạ bạn bè trong luồng này. Danh tính hiển thị chỉ được dùng để người xung quanh nhận biết người tham gia trong phòng.

## 2. Trạng thái tại Room Discovery

Trang **Nearby Rooms** luôn hiển thị một trong các trạng thái sau.

| Trạng thái | Người dùng thấy gì | Người dùng nên làm gì |
|---|---|---|
| Đang tìm | Chỉ báo loading khi ứng dụng đang quét và tổng hợp room lân cận. | Chờ trong vùng có thiết bị khác; có thể kéo để làm mới. |
| Có kết quả | Danh sách card room: tên, số người gần đó, thời hạn còn lại và nút mở. | Chọn room muốn vào. |
| Chưa có room | Thông báo chưa tìm thấy room, kèm lựa chọn tạo room. | Đến gần người đang tạo room, làm mới hoặc tạo room mới. |
| Tạm dừng | Thẻ hướng dẫn setup, ví dụ Bluetooth, quyền Nearby/Bluetooth hoặc Location chưa sẵn sàng. | Dùng nút trên thẻ để cấp quyền/bật dịch vụ cần thiết. |
| Lỗi tạm thời | Trạng thái discovery không thể tiếp tục ngay lúc đó. | Kiểm tra Bluetooth/quyền, sau đó thử lại. |

Discovery chỉ hiển thị room còn hoạt động và còn thời hạn. Một room đã đóng hoặc hết hạn không còn là lựa chọn để tham gia.

## 3. Chọn và join một Room

1. Người dùng chạm card room hoặc nút **Mở**.
2. Ứng dụng chuyển sang màn hình room và bắt đầu một lần join. Room được ưu tiên sử dụng kết nối BLE trong giai đoạn này.
3. Nếu người dùng chưa có khóa truy cập room, màn hình hiển thị trạng thái đang kết nối/đang chuẩn bị quyền truy cập. Ứng dụng gửi yêu cầu khóa đến chủ room hoặc peer phù hợp.
4. Khi nhận khóa hợp lệ, ứng dụng tải metadata room (tên, chủ phòng và thông tin hiển thị), sau đó hoàn tất vào room.
5. Người dùng thấy danh sách tin nhắn, số người ở gần và ô soạn tin. Có thể gửi tin nhắn công khai trong room.

Việc join không cần chủ phòng duyệt. Điều kiện là room đang hoạt động, chưa hết hạn và thiết bị vẫn có thể liên lạc qua Nearby/BLE.

## 4. Trong khi chờ join

Trong lúc key hoặc metadata chưa về, người dùng vẫn ở màn hình room nhưng không nên coi việc vào room đã hoàn tất. Ứng dụng giữ lớp loading và hạn chế thao tác gửi cho đến khi quyền truy cập sẵn sàng.

Các khả năng người dùng có thể gặp:

- **Kết nối thành công:** lớp loading biến mất, room sẵn sàng để đọc/gửi tin nhắn.
- **Bluetooth, quyền Nearby hoặc Location bị tắt:** xuất hiện banner giải thích; thao tác gửi tạm dừng đến khi người dùng khắc phục.
- **Chủ room/peer không còn trong vùng hoặc phản hồi quá lâu:** ứng dụng thông báo hết thời gian chờ và quay về discovery. Người dùng có thể thử lại khi ở gần peer hơn.
- **Room đóng hoặc hết hạn:** room trở thành không hoạt động; người dùng được đưa về discovery.

Nếu một callback join đến muộn sau khi người dùng đã vào room khác hoặc rời room, ứng dụng bỏ qua callback đó. Callback cũ không được phép thay thế room đang mở.

## 5. Gửi và nhận tin nhắn

Sau khi room sẵn sàng, người dùng có thể gửi tin nhắn công khai cho các peer đang lân cận. Tin nhắn cần Bluetooth và các quyền Nearby còn khả dụng.

Khi Nearby Room đang hoạt động, ứng dụng ưu tiên traffic của room. Các công việc mesh không liên quan được giữ lại và tiếp tục sau khi người dùng rời room; điều này không phải lỗi gửi tin nhắn của người dùng.

Người dùng có thể:

- xem danh sách người tham gia gần đó;
- trả lời/chỉnh sửa/xóa tin nhắn của mình nếu giao diện cho phép;
- block một người dùng trên thiết bị của mình;
- rời room.

Chủ room có thêm quyền mute hoặc unmute người tham gia trong room và đóng room sớm.

## 6. Rời room hoặc quay lại discovery

Khi người dùng chọn **Rời room**, nhấn quay lại hoặc room đóng/hết hạn:

1. Phiên Nearby Room hiện tại được giải phóng.
2. Những công việc mesh không liên quan đã tạm giữ được đánh thức và tiếp tục nếu còn hợp lệ.
3. Ứng dụng quay lại Room Discovery để tìm room khác.

Rời room không xóa Private Group, không gửi yêu cầu kết bạn và không tạo lịch sử room công khai lâu dài.

## Checklist nhanh cho người dùng

1. Bật Bluetooth và cấp quyền Nearby/Bluetooth (và Location nếu thiết bị yêu cầu).
2. Mở **Nearby Rooms**.
3. Hoàn tất tên hiển thị và avatar nếu được hỏi.
4. Chờ room xuất hiện hoặc kéo để làm mới.
5. Chạm room cần tham gia, chờ trạng thái chuẩn bị truy cập hoàn tất.
6. Gửi tin nhắn khi ô soạn đã hoạt động.
7. Rời room khi không còn cần thiết để quay lại discovery và cho các tác vụ mesh khác tiếp tục.
