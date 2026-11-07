# Developer Guide (Mobile, VI)

Tài liệu này giúp dev mới nắm nhanh cấu trúc repo liên quan mobile, biết nơi đặt code khi bắt đầu Phase 2, và tìm đúng tài liệu kỹ thuật khi triển khai.

## 1) Cấu trúc repo (tổng quan)

- `android/`: skeleton Android client (Kotlin).
- `ios/`: skeleton iOS client (Swift).
- `shared/`: tài liệu/schemas chung (event model, invariants).
- `docs/`: đặc tả kỹ thuật, task list, ghi chú hệ thống.
- `server/`: relay server (Phase 1), dùng cho sync online.
- `scripts/`: tiện ích chung của repo.
  - Backlog: `android/docs/backlogs/map_ui_crash_risks.md` (rủi ro/crash), `android/docs/backlogs/improvement_ideas.md` (ý tưởng cải thiện).
    Mục trong backlog **chưa là task**; chỉ sau khi thảo luận mới promote hoặc loại bỏ.

## 2) Mobile clients (android/ & ios/)

### Điểm hiện tại
- Cả `android/` và `ios/` mới là skeleton.
- Phase 2 hướng tới mobile nói chung, nhưng task list hiện tại tập trung Android để làm chuẩn.
- Phase 2.1 sẽ tiếp tục triển khai iOS dựa trên baseline Android.
- Tài liệu nền tảng để triển khai Phase 2:
  - `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
  - `docs/milestones/Phase_2_Task_List.md`
  - `docs/specs/Project_Overview_Technical_Spec.md`
  - `docs/specs/Architecture_Diagram.md`

### Android
- README: `android/README.md`
- Docs: `android/docs/README.md`

### iOS
- README: `ios/README.md`
- Docs: `ios/docs/README.md`

## 3) Định hướng cấu trúc code (khi scaffold dự án)

> Chưa có app code. Khi tạo project thực tế, nên thống nhất cấu trúc theo các layer trong spec.

### Layer gợi ý
- **UI Layer**: chat/map/group UI.
- **Application Logic Layer**: event factory, reducers, TTL/cleanup.
- **Security & Group Layer**: KDF, encrypt/decrypt, MAC verify.
- **Storage Layer**: SQLite + migrations + materialized views.
- **Transport Layer**: BLE mesh (Android: custom BLE transport) + relay sync.

## 4) Quy tắc đặt file khi thêm tính năng (gợi ý)

- **Event model / reducers**: đặt trong module domain/core (Android/iOS).
- **Storage / migrations**: module data/storage.
- **BLE transport**: module transport/ble.
- **Relay sync**: module transport/relay.
- **Crypto / group**: module security/group.
- **UI**: module ui/chat, ui/map, ui/group.
- **Background jobs**: module workers/schedulers.
- **Tests**: unit tests cho crypto/reducer, integration cho BLE/relay.

## 5) Map nhanh theo chức năng Phase 2

### Event model & reducers
- Spec chính: `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
- Invariants: `docs/specs/Cross-platform_Event_Schema_Reducer_Invariants.md`

### BLE mesh transport
- Spec chính: `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
- Kiến trúc tổng thể: `docs/specs/Architecture_Diagram.md`

### Relay sync (online)
- Spec relay: `docs/specs/PHASE_1_TECHNICAL_SPEC.md`
- Kiến trúc tổng thể: `docs/specs/Architecture_Diagram.md`

### Task list triển khai
- `docs/milestones/Phase_2_Task_List.md`

## 6) Quick links

- Tổng quan kỹ thuật: `docs/specs/Project_Overview_Technical_Spec.md`
- Phase 2 spec: `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
- Kiến trúc hệ thống: `docs/specs/Architecture_Diagram.md`
- Task list Phase 2: `docs/milestones/Phase_2_Task_List.md`
- UI checklist Phase 2 Android: `docs/mobile/phase2_android_ui_checklist.md`
- Threat model (VI): `docs/private/THREAT_MODEL_VI.md`

## 7) Chính sách tương thích Android

- Ưu tiên chính: Android 11 trở lên. Ổn định và đúng chức năng cho 11+ là mặc định.
- Android dưới 11 là optional và chỉ best-effort.
- Nếu thay đổi có thể làm giảm độ ổn định hoặc đúng chức năng trên Android 11+, phải nêu rõ và chờ xác nhận trước khi làm.
- Nếu thay đổi chủ yếu để cải thiện Android dưới 11 nhưng tăng rủi ro cho 11+, không được làm nếu chưa được đồng ý.

## 8.1) Debug + cài app Android (USB và Wireless)

### Yêu cầu trước
- Đã cài Android SDK (qua Android Studio).
- Đặt SDK location trong `android/local.properties`:
  ```
  sdk.dir=/home/USER/Android/Sdk
  ```
- Dùng adb từ SDK (mới hơn adb của hệ):
  ```
  export ANDROID_SDK_ROOT=/home/USER/Android/Sdk
  export PATH=$ANDROID_SDK_ROOT/platform-tools:$PATH
  ```

### Bật Developer options + USB debugging (ví dụ Samsung)
1) Settings -> About phone -> Software information
2) Chạm Build number 7 lần (nhập PIN nếu được hỏi)
3) Settings -> Developer options -> bật USB debugging

### Debug qua USB (có dây)
1) Cắm cáp USB và xác nhận Allow USB debugging trên điện thoại.
2) Kiểm tra:
  ```
  adb devices
  ```
3) Cài app:
  ```
  cd android
  ./gradlew :app:installOfficialDebug
  ```

### Debug qua Wi-Fi (Wireless debugging)
1) Developer options -> Wireless debugging -> ON
2) Pair bằng mã:
  ```
  adb pair <PHONE_IP>:<PAIR_PORT>
  ```
3) Connect bằng cổng khác (IP address & Port):
  ```
  adb connect <PHONE_IP>:<CONNECT_PORT>
  adb devices
  ```
4) Cài app:
  ```
  cd android
  ./gradlew :app:installOfficialDebug
  ```
5) An toàn: tắt Wireless debugging sau khi dùng.

### Ghi chú
- `./gradlew test` chỉ chạy unit tests, không cài app lên thiết bị.
- Dùng `:app:installEmergencyDebug` để cài bản emergency.

## 8.2) Chạy emulator Android (CLI)

### Yêu cầu trước
- Đã cài Android SDK Command-line Tools.
- Đã có system image (API 34, Google APIs x86_64).
- Tuỳ chọn: thêm SDK paths và alias vào `~/.bashrc`:
```
echo 'export ANDROID_SDK_ROOT=/home/USER/Android/Sdk' >> ~/.bashrc
echo 'export PATH=$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH' >> ~/.bashrc
echo 'alias emupixel=\"$ANDROID_SDK_ROOT/emulator/emulator -avd Pixel34\"' >> ~/.bashrc
source ~/.bashrc
```

### Cài system image (CLI)
```
export ANDROID_SDK_ROOT=/home/USER/Android/Sdk
export PATH=$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH
sdkmanager "system-images;android-34;google_apis;x86_64"
```

### Tạo AVD
```
avdmanager create avd -n Pixel34 -k "system-images;android-34;google_apis;x86_64"
```

### Chạy emulator
```
$ANDROID_SDK_ROOT/emulator/emulator -avd Pixel34
```

### Gỡ AVD
```
avdmanager delete avd -n Pixel34
```

### Troubleshooting
- Emulator không chạy: cài lại system image và tạo lại AVD.
- Thiếu kernel / image hỏng: xoá thư mục system image rồi cài lại bằng `sdkmanager`.
- Chậm hoặc màn hình đen: thử image khác (Google APIs) hoặc tắt hardware acceleration.
- Lỗi "Not in GZIP format" khi cài (download hỏng):
  ```
  rm -rf /home/USER/Android/Sdk/.temp
  rm -rf /home/USER/Android/Sdk/system-images/android-34/google_apis/x86_64
  sdkmanager "system-images;android-34;google_apis;x86_64"
  ```
- Lỗi "Can't find 'Linux version ' string in kernel image" (system image hỏng):
  ```
  rm -rf /home/USER/Android/Sdk/system-images/android-34/google_apis/x86_64
  sdkmanager "system-images;android-34;google_apis;x86_64"
  avdmanager delete avd -n Pixel34
  avdmanager create avd -n Pixel34 -k "system-images;android-34;google_apis;x86_64"
  ```
- Lỗi "Connection refused" khi `adb connect`:
  - Dùng IP:PORT hiển thị trong Wireless debugging (không dùng pairing port).
  - Chạy lại:
  ```
  adb connect <PHONE_IP>:<CONNECT_PORT>
  adb devices
  ```
- Lỗi "SDK path not found":
  - Đặt `android/local.properties`:
  ```
  sdk.dir=/home/USER/Android/Sdk
  ```

## 9) Checklist triển khai Phase 2 theo module

> Dùng để onboard nhanh và chia task theo module. Ưu tiên hoàn thành core trước UI.  
> Phase 2 tập trung Android; iOS có thể dùng checklist này làm baseline sau.

### 9.1 Core data model & crypto
- [ ] Định nghĩa event schema + envelope (event_id == mid).
- [ ] JSON canonicalization cho MAC.
- [ ] KDF (scrypt/Argon2id) + lưu salt.
- [ ] HMAC verify + tie-breaker rules.
- [ ] Anti-replay cache theo `(gid, event_id)` TTL 24h.
Definition of done: tạo/verify được event + MAC end-to-end, test unit đi qua các case canonicalization/KDF/MAC.

### 9.2 Storage layer (SQLite)
- [ ] Events table (append-only, unique `(gid, event_id)`).
- [ ] Indexes (gid/event_id/created_t/type).
- [ ] Materialized tables: chat + map.
- [ ] Transaction wrapper: insert + reducer.
- [ ] Deduplication enforcement.
Definition of done: insert 1 event -> reducer chạy trong transaction -> trạng thái chat/map đọc được từ materialized tables.

### 9.3 Reducers
- [ ] Chat reducer (MSG/EDIT/DEL, LWW + tie-breaker).
- [ ] Map reducer (MARKER/AREA, LWW + tombstone).
- [ ] TTL precedence (meta.ttl_s).
- [ ] Clock skew handling (tie-breaker rule).
Definition of done: cùng một tập event bất kỳ thứ tự cho kết quả state giống nhau.

### 9.4 Transport: BLE mesh (Android: custom BLE transport)
- [ ] Broadcast envelope + receive.
- [ ] Chunking/reassembly.
- [ ] Flood control (jitter/backoff).
- [ ] Dedupe chunk/envelope + cache TTL.
- [ ] Payload size limit handling.
Definition of done: 2 thiết bị trao đổi được payload > MTU, không nhân bản event sau khi nhận lại.

### 9.5 Transport: Relay sync (online)
- [ ] Push new events (opaque).
- [ ] Pull by cursor + persistence per relay.
- [ ] Retry/backoff + jitter.
- [ ] Clock skew handling in reducers.
Definition of done: mất mạng rồi bật lại, client sync được đúng delta và không reapply event trùng.

### 9.6 UI
- [ ] Chat UI (thread list + messages).
- [ ] Map UI (markers/areas CRUD).
- [ ] Group join/create UI (passphrase + gid).
- [ ] Status indicators (BLE/relay).
Definition of done: tạo group, gửi chat, thêm marker, thấy trạng thái sync cập nhật khi có BLE/relay.

### 9.7 Background jobs
- [ ] Cleanup job (expired + old events).
- [ ] Sync worker (foreground + periodic).
- [ ] Backpressure / rate limiting BLE ingress.
Definition of done: job chạy định kỳ, cleanup không làm mất event còn TTL, BLE ingress không làm app lag.

### 7.8 Testing
- [ ] Unit tests: crypto (KDF, MAC canonicalization).
- [ ] Unit tests: reducers (LWW, tombstones).
- [ ] Integration: BLE ingest pipeline.
- [ ] Integration: relay sync push/pull.
Definition of done: CI chạy test xanh, có test coverage tối thiểu cho crypto/reducers.

---

Gợi ý: cập nhật tài liệu này khi bắt đầu scaffold code để dev khác dễ tìm đúng module.
