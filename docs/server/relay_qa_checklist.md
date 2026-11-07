# Relay QA Checklist

## Relay
- [ ] Đồng bộ relay khi chưa chọn group → không pull/push, hiển thị lý do rõ ràng.
- [ ] Relay down rồi lên lại → retry/backoff đúng, không spam.
- [ ] Relay trả 500/timeout → app giữ state, không xoá cursor sai.
- [ ] Event signature sai → bị reject, UI không crash.
- [ ] Payload quá lớn → bị reject, báo lỗi rõ.
- [ ] Relay trả schema sai → client drop, không crash.
- [ ] Multiple relays → push/pull song song không bị double-write.
- [ ] Relay: push ok nhưng pull fail → status/log phản ánh “partial success”.
- [ ] Relay: giới hạn concurrent sync → không spike CPU/network khi nhiều relay.
- [ ] Relay: không log payload/body/gid trong server logs (redaction hoạt động).
- [ ] Relay: audit log chỉ chứa metadata (action/counts/ip), không chứa nội dung sự kiện.
- [ ] Relay: backup DB không công khai; quyền truy cập hạn chế.
- [ ] Relay: admin UI chỉ qua HTTPS; session cookie có Secure khi HTTPS.
- [ ] Relay: request signature bật cho ingest/fetch (nếu cấu hình yêu cầu).
