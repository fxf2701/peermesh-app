# Deep Trace Engine v1

## Mục tiêu

Deep trace engine giúp bạn truy một `msgId`, `hash`, `#123`, hoặc token bất kỳ xuyên qua nhiều log BLE mesh, rồi trả ra:

- events theo từng device
- stage xa nhất mà token đi được
- divergence point giữa các device
- layer nghi ngờ
- một report markdown sẵn để paste cho Codex

## Dùng trực tiếp

```bash
python3 scripts/debug-kit/deep_trace.py "<needle>" logs/device-A_*.txt logs/device-B_*.txt logs/device-C_*.txt
```

## Xuất report markdown

```bash
python3 scripts/debug-kit/deep_trace.py "<needle>" logs/device-A_*.txt logs/device-B_*.txt logs/device-C_*.txt \
  --format markdown \
  --output logs/analysis/deep_trace_<needle>.md
```

## Xuất JSON

```bash
python3 scripts/debug-kit/deep_trace.py "<needle>" logs/device-A_*.txt logs/device-B_*.txt logs/device-C_*.txt \
  --format json \
  --output logs/analysis/deep_trace_<needle>.json
```

## Nó phát hiện gì

Engine hiện phân loại heuristic theo các nhóm:

- `enqueue`
- `send_ok`
- `recv`
- `reassembly_progress`
- `reassembly_complete`
- `dedup_drop`
- `retry`
- `dispatch_ok`
- `dispatch_fail`
- `queue_timeout`
- `send_fail`
- `gatt_error`
- `connect_cap`

## Giới hạn hiện tại

- vẫn là heuristic dựa trên text pattern
- mạnh nhất khi log có token ổn định như `msgId=...`, `hash=...`, `#123`
- chưa parse state machine thật
- chưa tự nối chunk cùng message nếu token không rõ ràng

## Cách tích hợp vào mesh_debug.sh

Thêm subcommand kiểu:

```bash
mesh_debug.sh deep-trace <needle> [log_files ...]
```

Và gọi:

```bash
python3 "$REPO_ROOT/scripts/debug-kit/deep_trace.py" "$needle" "${files[@]}"
```

## Prompt nên dùng với Codex

```text
Use the deep trace report below.
1. Summarize the runtime flow for this token.
2. Identify the last confirmed step and the first missing step per device.
3. Rank the top 3 root causes.
4. Show supporting and contradicting evidence.
5. Do not patch yet.
```
