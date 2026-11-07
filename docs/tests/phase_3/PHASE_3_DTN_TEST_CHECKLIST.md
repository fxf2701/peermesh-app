# Phase 3 DTN Test Checklist

Status: Draft for execution  
Scope: Android DTN Store-Carry-Forward + BLE transport integration (Phase 3)  
Primary references:
- `docs/milestones/Phase_3_Task_List.md`
- `docs/specs/DTN_IMPLEMENTATION_BLUEPRINT.md`
- `docs/tests/DTN_Simulation_Scenarios_for_Peer_Mesh.md`
- `docs/specs/ble_dtn_transport_kpi_slo.md` (normative KPI/SLO and outcome definitions)

## 1) Mục tiêu kiểm thử
- Xác nhận các task `BLP-A` đến `BLP-F` hoạt động đúng theo blueprint.
- Xác nhận các scenario `S1` đến `S8` pass với bằng chứng log/metric.
- Đảm bảo không regression luồng hiện tại (chat/map/live trail).

## 2) Test environment checklist
- [x] Build bằng Java 17.
- [x] Android SDK path hợp lệ (`ANDROID_HOME` / `sdk.dir`).
- [ ] Ít nhất 3 thiết bị Android thật để test partition bridge.
- [x] BLE permission + location permission đã cấp đầy đủ.
- [x] Có thể bật/tắt internet để mô phỏng offline/relay fallback.
- [x] Bật log/diagnostics cho DTN (control packet + drop reason).

## 3) Pre-flight smoke
- [x] App launch ổn định, không crash.
- [x] Tạo group, gửi nhận chat bình thường.
- [x] Tạo/sửa/xóa map marker/area bình thường.
- [x] Live trail start/point/end chạy được.
- [x] Không có lỗi signature/ingest bất thường trước khi chạy test DTN.

## 4) Functional checklist theo task

### 4.1 `BLP-A` Envelope & DTN metadata
- [ ] Envelope có `dtn` metadata (`ttl_seconds`, `hop_count`, `max_hops`, `priority`).
Manual test steps cho mục trên:
    1. Cài app test build mới nhất trên 2 thiết bị (`A` và `B`) cùng group `gid`.
    2. Bật BLE + cấp đủ quyền trên cả hai thiết bị.
    3. Trên thiết bị `A`, gửi lần lượt 4 event: `CHAT.MSG`, `MAP.MARKER.ADD`, `SYS.MEMBER.REMOVED`, `SYS.ACK` (hoặc action tương đương tạo ACK).
    4. Ưu tiên mở `ActivityLog` trên `A` hoặc `B` để theo dõi evidence; chỉ dùng `logcat` (tag `EnvelopeSender`, `BleMeshTransport`, `EventIngestor`) khi cần debug sâu.
    5. Tìm JSON envelope outbound/inbound tương ứng từng event vừa gửi.
    6. Verify mỗi envelope đều có object `dtn` và đủ 4 field:
    7. `dtn.ttl_seconds` có giá trị > 0 (mặc định hiện tại: `3600` nếu không override).
    8. `dtn.hop_count` ban đầu bằng `0` ở gói gửi mới.
    9. `dtn.max_hops` có giá trị mặc định đúng (hiện tại: `20`).
    10. `dtn.priority` đúng theo type:
    11. `CHAT.MSG` -> `3`
    12. `MAP.*` -> `2`
    13. `SYS.MEMBER.REMOVED` -> `4`
    14. `SYS.ACK` -> `1`
    15. Chụp lại log evidence cho từng type và đính kèm vào report mục 8.
- [ ] Signature verification fail nếu `dtn` bị tamper sau khi ký.
- [ ] Default transport TTL và max hops luôn được set tại send path.
- [ ] Priority mapping theo event type hoạt động đúng (alert/safety/map/chat).

### 4.2 `BLP-B` DTN inventory sync protocol
- [ ] Decode/encode control packet: `hello`, `inv_have`, `inv_want`, `data`, `ack`.
- [ ] Session lifecycle per-peer/per-gid ổn định qua nhiều lần contact.
- [ ] Missing-ID pull flow chạy đúng (peer chỉ request thiếu gì thì nhận cái đó).
- [ ] Batch transfer không gửi full backlog khi không cần.
- [ ] BLE transport route đúng control packet vs event envelope.

### 4.3 `BLP-C` Room schema DTN state
- [ ] Tạo đủ bảng: `dtn_peer_state`, `dtn_advertised`, `dtn_sync_cursor`, `dtn_pending_pull`.
- [ ] DAO query/update hoạt động đúng theo peer/gid.
- [ ] Migration pass khi upgrade từ DB version cũ.
- [ ] Index lookup theo `(peer_id,gid)` và `(gid,event_id)` có hiệu lực.

### 4.4 `BLP-D` Event store query + eviction
- [ ] Query inventory-window trả event id đúng thứ tự.
- [ ] Fetch theo `(gid,event_id)` trả đúng envelope/data.
- [ ] Cursor-based sync query không skip và không duplicate bất thường.
- [ ] Eviction policy đúng thứ tự: expired -> low priority -> oldest.
- [ ] Storage cap có hiệu lực khi vượt ngân sách.

### 4.5 `BLP-E` Forwarding hardening
- [ ] Drop khi quá transport TTL.
- [ ] Drop khi vượt `max_hops`.
- [ ] Forward thì `hop_count` tăng đúng 1.
- [ ] Dedupe guard hoạt động trước forwarding/sync send.
- [ ] Priority scheduler ưu tiên critical/safety trước map/chat.

### 4.6 `BLP-F` Observability
- [ ] Có log marker cho `hello/inv_have/inv_want/data/ack` sent/recv.
- [ ] Có summary sync counter (sent/recv/missing/dup/drop).
- [ ] Có drop reason rõ ràng (`ttl|hops|dedupe|invalid|rate_limited|queue_full`).
- [ ] Có thể xem trạng thái sync theo peer để debug.

## 5) Scenario checklist (S1-S8)
- [ ] `S1` Urban blackout partition bridge pass.
- [ ] `S2` Node di chuyển giữa 3 partition pass.
- [ ] `S3` Burst event (1000 messages/5 phút) pass.
- [ ] `S4` TTL hết hạn giữa đường pass.
- [ ] `S5` Node power cycle pass.
- [ ] `S6` Rogue node spam pass.
- [ ] `S7` Sparse environment pass.
- [ ] `S8` Multi-radio integration pass.

## 6) Regression checklist
- [ ] Chat UX không chậm đáng kể sau khi bật DTN sync.
- [ ] Map reducer không có duplicate hiển thị.
- [ ] Live trail không mất batch ngoài TTL policy.
- [ ] ACK/resend flow không bị flood sau restart.
- [ ] Relay pull/push không bị ảnh hưởng bởi envelope có `dtn`.

## 7) Evidence cần thu thập cho mỗi test run
- [ ] Build info: commit hash, branch, app version.
- [ ] Thiết bị tham gia: model + OS version + vai trò.
- [ ] Config test: TTL/hops/priority/storage cap/rate limit.
- [ ] Phân loại từng khoảng là normal hay disrupted, kèm clock offset giữa các thiết bị.
- [ ] Event ID, batch ID, sender/receiver/render timestamp và delivery/render latency.
- [ ] Retry count, session ID, peer stable ID, address rotation và transport restart.
- [ ] Log lifecycle `HELLO -> INV_HAVE -> INV_WANT -> DATA -> ACK -> cursor commit -> disconnect cleanup`; stage không tới phải ghi `not reached` và lý do.
- [ ] Dependency repair latency, action delivery latency và final convergence latency được tách riêng khi canonical/root bị thiếu.
- [ ] Metric snapshot: duplicate ratio, drop counters, queue/storage growth, latency, convergence, crash/ANR.
- [ ] Final materialized state và kết quả theo vocabulary chuẩn, kèm root cause/action items.

## 8) Test report template (copy nhanh)
```md
### Test Case: <ID/Name>
- Date:
- Build/Commit:
- Devices:
- Preconditions:
- Steps:
1.
2.
3.
- Expected:
- Actual:
- Evidence (log/screenshot/metric):
- Operating conditions: NORMAL | DISRUPTED (interval/reason):
- Protocol lifecycle (mark not reached with reason where applicable):
- Result: PASS | PASS WITH ACCEPTED LIMITATION | DEGRADED | INCONCLUSIVE | FAIL | BLOCKED
- Notes / Follow-up:
```

## 9) Exit criteria Phase 3
- [ ] `BLP-A..BLP-F` hoàn tất và được verify bằng test evidence.
- [ ] `S1..S8` pass theo matrix trong task list.
- [ ] Không regression major trên chat/map/live trail.
- [ ] KPI/SLO về eventual delivery, bounded normal-condition latency, observable progress và final state đều đạt hoặc limitation được chấp thuận rõ ràng.
- [ ] Có tài liệu kết quả test và open issues rõ ràng trước release gate.
