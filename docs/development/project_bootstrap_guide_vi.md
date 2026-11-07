# Project Bootstrap Guide (VI)

Tài liệu này là guideline tạo dự án mới theo cách chuyên nghiệp, dựa trên kinh nghiệm triển khai thực tế trong dự án hiện tại.

## 1) Mục tiêu & phạm vi

Trước khi viết code, cần chốt:
- Mục tiêu sản phẩm (MVP/phase 1): chức năng nào bắt buộc.
- Đối tượng dùng: operator, admin, user cuối.
- Rủi ro: security, availability, data integrity.
- Môi trường: dev/staging/prod, on-prem/cloud.

Output: 1 tài liệu scope ngắn (1–2 trang) + danh sách task.

## 2) Tài liệu khởi đầu (bắt buộc)

1) **Technical spec**: mục tiêu, hệ thống, ràng buộc.
   - Ví dụ: `docs/specs/PHASE_1_TECHNICAL_SPEC.md`
2) **UI spec**: màn hình, data source, UX rules.
   - Ví dụ: `docs/server/admin_ui_pages_spec.md`
3) **API conventions**: format response, error model, auth.
   - Ví dụ: `docs/server/admin_ui_development_guideline.md`
4) **Task list**: chia milestone theo task/subtask.
   - Ví dụ: `docs/milestones/Phase_1_4_Task_List.md`

## 3) Cấu trúc repo chuẩn (đề xuất)

- `server/`: launcher chung + docker-compose + env chung.
  - `start.sh`: entrypoint chạy local/dev/prod compose.
  - `relay/`: relay runtime, migrations, scripts, admin UI.
  - `registry/`: registry runtime, scripts, registry admin UI.
- `docs/`: đặc tả & guides.
  - `milestones/`: task list theo phase.
  - `server/`: developer guides & quy trình.
- `shared/`: schema + fixtures dùng chung.
- `docker-compose*.yml`: dev/prod profiles.
- `README.md`: entrypoint cho dev mới.

## 4) Quy trình tạo dự án (theo thứ tự)

1) **Khởi tạo repo**
   - Tạo skeleton thư mục chuẩn.
   - Viết `README.md` tối thiểu: run dev, run test, migrate.
2) **Chuẩn hoá coding conventions**
   - API conventions + error schema.
   - Logging & metrics policy.
3) **Thiết kế schema + migrations**
   - DB tables cơ bản + migrations.
   - Chốt naming conventions.
4) **Tạo core modules**
   - Config, logging, db, cache.
   - Routing split (public vs admin).
5) **Xây API nền**
   - Health check, auth admin.
   - Stub endpoints theo spec.
6) **Xây UI nền**
   - Layout template + assets.
   - Pages skeleton (empty states).
7) **Lấp data flow**
   - API -> UI wiring.
   - Mock data nếu API chưa đủ.
8) **Viết test scripts**
   - Smoke tests theo từng API.
   - Scripts nên nằm trong `server/relay/scripts/` hoặc `server/registry/scripts/` theo service.
9) **Runbook & ops**
   - Production guide.
   - TLS/mTLS guidance.
10) **Review checklist**
   - Security, reliability, performance, audit.

## 5) Tài liệu chính cần có (gợi ý)

- **Spec tổng quan**: technical spec.
- **UI spec**: page spec, data source, UI states.
- **API conventions**: response schema, errors, auth.
- **Operational guide**: setup prod + TLS + backup.
- **Developer guide**: map module/file.
- **Task list**: checklists + test notes.

## 6) Nguyên tắc chia task

- Mỗi task có mục tiêu rõ ràng + test note.
- Subtask rõ ràng: API, DB, UI, Scripts.
- Đánh dấu:
  - `[ ]` chưa làm
  - `[-]` làm xong nhưng chưa test
  - `[x]` đã test pass

## 6.1) Quy trình backlog (risky & improvement)

- Có **2 backlog riêng**:
  - `android/docs/backlogs/map_ui_crash_risks.md`: rủi ro/crash analysis.
  - `android/docs/backlogs/improvement_ideas.md`: ý tưởng cải thiện hiệu năng/UX/chất lượng.
- Mục trong backlog **chưa phải task**; chỉ là ý tưởng hoặc rủi ro cần theo dõi.
- Sau khi thảo luận, **mới quyết định**:
  - **Promote** thành task trong task list, hoặc
  - **Drop** nếu không còn cần thiết.

## 7) Quy tắc đặt file khi thêm feature

- **Relay public API**: `server/relay/src/routes.js`
- **Relay admin API**: `server/relay/src/routes_admin.js`
- **Registry API**: `server/registry/src/index.js`
- **Logic core**: module riêng trong `server/relay/src/` hoặc `server/registry/src/`
- **DB schema**: migration relay trong `server/relay/migrations/` ; schema registry nằm trong `server/registry/src/db.js`
- **Relay Admin UI**: `server/relay/src/public/admin/*` + `assets/admin.js`
- **Registry Admin UI**: `server/registry/src/public/admin-registry/*` + `assets/registry_admin.js`
- **Test**: `server/relay/scripts/test-*` hoặc `server/registry/scripts/test-*`

## 8) Checklist khởi động nhanh (template)

1) Tạo repo + folder chuẩn
2) Viết `README.md`
3) Tạo `docs/` + spec cơ bản
4) Tạo skeleton server + admin UI
5) Tạo DB + migrations
6) Build API stubs
7) Build UI stubs
8) Viết smoke tests
9) Setup docker-compose
10) Viết production guide

## 9) Gợi ý kiểm tra chất lượng

- **API**: đúng conventions, error code chuẩn.
- **Security**: auth/admin boundary, token handling.
- **Observability**: logs + metrics + audit log.
- **Docs**: spec + task list luôn cập nhật.

## 10) Template: Phase plan + risk list

```
Phase: <Tên phase>
Objective:
- <Mục tiêu chính>

In scope:
- <Feature/Module 1>
- <Feature/Module 2>

Out of scope:
- <Feature/Module không làm trong phase này>

Milestones:
1) <Milestone 1>
2) <Milestone 2>

Deliverables:
- <Doc/API/UI/Test>

Risks:
- <Rủi ro 1>: <Impact> / <Mitigation>
- <Rủi ro 2>: <Impact> / <Mitigation>

Assumptions:
- <Assumption 1>

Acceptance:
- <Điều kiện pass>
```

## 11) Definition of Done (DoD) theo loại task

### API
- Endpoint đúng API conventions (response/error schema).
- Auth/permission đúng boundary.
- Validation đầy đủ (input/edge cases).
- Logs và metrics có event chính.
- Có smoke test script trong đúng service folder (`server/relay/scripts/` hoặc `server/registry/scripts/`).
- Spec/task list cập nhật.

### UI
- UI hiển thị đúng theo spec (empty/error/loading).
- UX flows chính chạy ok (create/edit/delete).
- Tương tác API đúng (status + error handling).
- Responsive tối thiểu desktop + mobile.
- Spec/task list cập nhật.

### Tests
- Script chạy được bằng 1 command.
- Có expected output rõ ràng.
- Fails when điều kiện sai (negative case).
- Tài liệu test note cập nhật.

### Docs
- Nội dung phù hợp spec/behavior hiện tại.
- Liên kết/đường dẫn đúng.
- Ngôn ngữ nhất quán (VI/EN).
- Task list cập nhật status.

---

Tài liệu này nên cập nhật sau mỗi phase để phản ánh quy trình thực tế.
