# Tech Notes: Adaptive Cooldown on DB Saturation

## Tóm tắt vấn đề
Khi DB pool bị bão hòa, request sẽ xếp hàng và chờ connection, dẫn tới độ trễ tăng nhanh,
timeout, và cuối cùng gây lỗi lan rộng. Hiện hệ thống chỉ quan sát qua metrics/logs,
chưa có cơ chế tự giảm tải.

Mục tiêu của task là bổ sung cơ chế "cooldown" tối thiểu để giảm tải khi DB nghẽn,
giúp hệ thống ổn định và giảm nguy cơ sập dây chuyền.

## Tín hiệu kích hoạt
Sử dụng metrics hiện có:
- `db_pool_waiting`
- `db_pool_wait_ms_avg` hoặc `db_pool_wait_ms_last`

Ngưỡng đề xuất (config):
- `DB_COOLDOWN_WAITING_THRESHOLD` (vd 10)
- `DB_COOLDOWN_WAIT_MS_THRESHOLD` (vd 50ms)
- `DB_COOLDOWN_MIN_SECONDS` (vd 15s)

## Hành vi khi kích hoạt
- Ingest trả `429` + `Retry-After` trong thời gian cooldown.
- Federation sync giảm nhịp: tăng backoff và giảm concurrency.
- Ghi log sự kiện `db_cooldown_on`.

## Hành vi hồi phục
- Tắt cooldown khi `db_pool_waiting == 0` và `db_pool_wait_ms_avg < threshold`
  trong N chu kỳ liên tiếp.
- Ghi log `db_cooldown_off`.

## Metrics bổ sung
- `db_cooldown_active` (gauge 0/1)
- `db_cooldown_activations_total` (counter)

## Vị trí tích hợp (gợi ý)
- `server/relay/src/app.js`: tạo cooldown controller tương tự error budget/partition check.
- Router kiểm tra state trước khi xử lý ingest/federation.

## Test tối thiểu
- Mock pool stats để vượt ngưỡng, assert ingest trả 429.
- Assert `db_cooldown_active=1`, `db_cooldown_activations_total` tăng.
- Sau N chu kỳ ổn định, assert cooldown tắt và request phục hồi.
