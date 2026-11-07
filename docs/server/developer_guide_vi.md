# Developer Guide (VI)

Tài liệu này giúp dev mới biết nhanh: file nào phụ trách chức năng nào, thư mục nào dùng cho mục đích gì, và nên tạo file ở đâu khi thêm tính năng mới.

## 1) Cấu trúc repo (tổng quan)

- `server/`: backend chính (API, jobs, admin UI static).
- `shared/`: tài liệu/chung về schema + fixtures.
- `docs/`: đặc tả, hướng dẫn vận hành, ghi chú kỹ thuật.
- `scripts/`: tiện ích tổng quát (build/deploy ở mức repo).
- `android/`, `ios/`: mobile app (nếu có).
- `export/`, `test/`: dữ liệu/export và test đặc thù (tuỳ repo).
- `docker-compose*.yml`: cấu hình môi trường chạy cục bộ/production.

## 2) Backend (server/)

### Điểm vào chính
- `server/start.sh`: launcher cho local dev stack.
- `server/stop.sh`: dừng local dev stack.
- `server/relay/start.sh`, `server/relay/stop.sh`: launcher production riêng cho relay.
- `server/registry/start.sh`, `server/registry/stop.sh`: launcher production riêng cho registry.
- `server/relay/src/index.js`: khởi động relay runtime.
- `server/relay/src/app.js`: cấu hình relay app, middleware, routing.
- `server/registry/src/index.js`: khởi động registry runtime + admin UI.

### Routing/API
- `server/relay/src/routes.js`: relay public API (ingest/fetch/federation/client session).
- `server/relay/src/routes_admin.js`: relay admin API + static relay admin UI.
- `server/registry/src/index.js`: registry API + registry admin API/static handler.

### Federation (kết nối relay)
- `server/relay/src/federation.js`: core logic federation (join/handshake/heartbeat/jobs).
- `server/relay/src/federation_admin_state.js`: trạng thái admin/federation hỗ trợ UI.
- `server/relay/src/federation_tls.js`: yêu cầu TLS/mTLS cho federation.
- `server/relay/src/federation_hops.js`: logic liên quan hop-chain (nếu dùng).
- `server/relay/src/request_signatures.js`: HMAC ký/verify request.
- `server/relay/src/peer_policy.js`: rules allow/ban/peer policy.
- `server/registry/src/federation_sessions.js`: registry-issued federation session grants.
- `server/registry/src/client_access_grants.js`: managed client access grants.

### Storage/DB/Cache
- `server/relay/src/db.js`: relay DB connection + helpers.
- `server/relay/src/redis.js`: relay redis client.
- `server/relay/src/storage.js`: relay event storage.
- `server/relay/migrations/`: relay SQL migrations.
- `server/registry/src/db.js`: registry SQLite persistence + helpers.

### Observability/Protection
- `server/relay/src/logger.js`: relay logging.
- `server/relay/src/metrics.js`: relay metrics.
- `server/relay/src/rate_limit.js`, `server/relay/src/rate_limit_spike.js`: relay rate limiting.
- `server/relay/src/audit_log.js`: relay audit log.
- `server/relay/src/db_cooldown.js`: relay DB cooldown khi quá tải.

### Admin UI (static)
- Relay HTML: `server/relay/src/public/admin/*.html`
  - `peers.html`, `peer_detail.html`, `banlist.html`, `reputation.html`, `audit.html`, `dashboard.html`, `login.html`
- Relay assets: `server/relay/src/public/admin/assets/admin.js`, `admin.css`
- Registry HTML: `server/registry/src/public/admin-registry/*.html`
- Registry assets: `server/registry/src/public/admin-registry/assets/registry_admin.js`, `registry_admin.css`

### Data runtime
- `server/data/`: dữ liệu runtime (ví dụ `public_token.txt`, `audit.log`).

### Scripts
Scripts được tách theo môi trường + service:
- Dev local:
  - `server/start.sh`
  - `server/stop.sh`
  - dùng `server/docker-compose.dev.yml` + `server/.env.dev`
- Production relay:
  - `server/relay/start.sh`
  - `server/relay/stop.sh`
  - dùng `server/relay/docker-compose.yml` + `server/relay/.env`
- Production registry:
  - `server/registry/start.sh`
  - `server/registry/stop.sh`
  - dùng `server/registry/docker-compose.yml` + `server/registry/.env`
- `server/relay/scripts/`: relay build/migrate/test/admin utilities.
- `server/registry/scripts/`: registry test/signing/admin utilities.

## 2.1) Môi trường dev vs production

Không dùng chung launcher giữa dev và production nữa:

- Dev/local simulation:
  - `server/start.sh`
  - `server/stop.sh`
  - `server/.env.dev`
  - `server/docker-compose.dev.yml`
- Production registry:
  - `server/registry/start.sh`
  - `server/registry/stop.sh`
  - `server/registry/.env`
  - `server/registry/docker-compose.yml`
- Production relay:
  - `server/relay/start.sh`
  - `server/relay/stop.sh`
  - `server/relay/.env`
  - `server/relay/docker-compose.yml`

Hai file `server/.env.registry.dev` và `server/.env.relay.dev` không còn là nguồn cấu hình sống cho dev stack hiện tại.

## 3) Admin UI (cách tìm nhanh)

Nếu cần sửa UI/UX:
- Relay UI: `server/relay/src/public/admin/*.html`
- Relay JS/CSS: `server/relay/src/public/admin/assets/admin.js`, `admin.css`
- Registry UI: `server/registry/src/public/admin-registry/*.html`
- Registry JS/CSS: `server/registry/src/public/admin-registry/assets/registry_admin.js`, `registry_admin.css`

Nếu UI cần dữ liệu mới:
1) Relay UI: thêm/đổi API trong `server/relay/src/routes_admin.js`, wire trong `admin.js`
2) Registry UI: thêm/đổi API trong `server/registry/src/index.js`, wire trong `registry_admin.js`
3) Cập nhật đúng spec liên quan

## 4) Quy tắc đặt file khi thêm tính năng

- **Relay API public**: thêm/đổi trong `server/relay/src/routes.js`.
- **Relay admin API**: thêm/đổi trong `server/relay/src/routes_admin.js`.
- **Registry API/Admin**: thêm/đổi trong `server/registry/src/index.js`.
- **Logic core**: tạo module trong `server/relay/src/` hoặc `server/registry/src/`.
- **Jobs/timers**: federation jobs ở `server/relay/src/federation.js`; registry coordination ở `server/registry/src/*`.
- **DB schema**: relay migration trong `server/relay/migrations/`; registry persistence trong `server/registry/src/db.js`.
- **UI admin**: chỉnh đúng UI folder của relay hoặc registry.
- **Tests**: thêm script dưới `server/relay/scripts/` hoặc `server/registry/scripts/`.

## 5) Map nhanh theo chức năng phổ biến

### Federation join/handshake/heartbeat
- API: `server/relay/src/routes.js`
- Logic: `server/relay/src/federation.js`
- Signature/HMAC: `server/relay/src/request_signatures.js`
- Policy/banlist: `server/relay/src/peer_policy.js`
- Admin view: `server/relay/src/routes_admin.js` + `peers.html` + `admin.js`

### Peer list / Peer detail (Admin UI)
- HTML: `server/relay/src/public/admin/peers.html`, `peer_detail.html`
- Data/API: `server/relay/src/routes_admin.js`
- Fetch/render: `server/relay/src/public/admin/assets/admin.js`

### Banlist/Reputation/Audit
- API admin: `server/relay/src/routes_admin.js`
- UI: `banlist.html`, `reputation.html`, `audit.html` + `admin.js`
- Audit log: `server/relay/src/audit_log.js`, runtime file `server/data/audit.log`

## 6) Map chi tiết theo module

### Federation
- Core logic: `server/relay/src/federation.js`
- API public: `server/relay/src/routes.js` (join/handshake/heartbeat/events/stats)
- API admin: `server/relay/src/routes_admin.js` (peers list/detail/history, inbound peers)
- Signature/HMAC: `server/relay/src/request_signatures.js`
- Peer policy/banlist: `server/relay/src/peer_policy.js`
- TLS/mTLS enforcement: `server/relay/src/federation_tls.js`
- Admin state for UI: `server/relay/src/federation_admin_state.js`
- UI: `server/relay/src/public/admin/peers.html`, `peer_detail.html`, `admin.js`
- Tests: `server/relay/scripts/test-federation-*.sh`, `server/relay/scripts/test-federation-*.js`

### Banlist
- API admin: `server/relay/src/routes_admin.js` (`/admin/api/banlist*`)
- UI: `server/relay/src/public/admin/banlist.html` + `admin.js`
- DB/Migration: `server/relay/migrations/*banlist*`
- Tests: `server/relay/scripts/test-banlist-*.sh`, `server/relay/scripts/test-banlist-*.js`

### Reputation
- API admin: `server/relay/src/routes_admin.js` (`/admin/api/reputation*`)
- UI: `server/relay/src/public/admin/reputation.html` + `admin.js`
- DB/Migration: `server/relay/migrations/*reputation*`
- Tests: `server/relay/scripts/test-reputation-*.sh`, `server/relay/scripts/run-reputation-crud-tests.sh`

### Audit
- API admin: `server/relay/src/routes_admin.js` (`/admin/api/audit`)
- Core: `server/relay/src/audit_log.js`
- UI: `server/relay/src/public/admin/audit.html` + `admin.js`
- Runtime log: `server/data/audit.log`
- Tests: `server/relay/scripts/test-federation-audit-api.sh`, `server/relay/scripts/test-audit-log.js`

## 7) Quick links (theo task list hiện tại)

- Task list: `docs/milestones/Phase_1_4_Task_List.md`
- UI spec: `docs/server/admin_ui_pages_spec.md`
- API conventions: `docs/server/admin_ui_development_guideline.md`
- Operator/Admin guides: `server/relay/docs/relay_admin_operator_guide.md`, `server/relay/docs/relay_admin_ui_guide.md`
- Production guide: `server/relay/docs/relay_production_guide.md`
- Federation TLS guide: `server/relay/docs/relay_tls_edge_guide.md`

## 8) Khi debug nhanh

- Logs: `docker logs <container>` hoặc file `server/data/audit.log`.
- Token hiện tại: `server/data/public_token.txt`

---

Gợi ý: cập nhật tài liệu này khi thêm feature mới để dev khác dễ tìm đúng file.
