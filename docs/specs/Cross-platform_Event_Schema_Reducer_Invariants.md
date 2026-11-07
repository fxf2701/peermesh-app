# Cross-platform Event Schema & Reducer Invariants

## Offline-first Mesh App

Spec overview and architecture context:
- `docs/specs/PHASE_2_TECHNICAL_SPEC.md`

> **Goal:** Định nghĩa **Event schema chuẩn** + các **invariants** (bất biến) cho reducers để:
>
> - Android & iOS tạo/parse event giống nhau
> - Reducers **deterministic / idempotent / order-independent**
> - Chống replay/flood mức cơ bản
> - TTL & cleanup nhất quán

---

## 0) Terminology

- **Event:** gói dữ liệu append-only lan truyền qua mesh/relay.
- **Envelope:** lớp transport (opaque), mang payload đã mã hoá.
- **Reducer:** hàm dựng state từ event log (materialized views).
- **LWW:** Last-Write-Wins (theo timestamp + tie-break).
- **Tombstone:** event đánh dấu xoá (delete marker).
- **Canonical JSON:** cách serialize JSON ổn định (để MAC giống nhau).

---

## 1) Canonical Serialization (Bắt buộc)

### 1.1 Rules

Để MAC/dedupe giống nhau, **mọi envelope đều phải canonicalize:**

- Object keys **sort lexicographically** (Unicode code point)
- Không có whitespace thừa
- String dùng UTF-8 chuẩn
- Số:
  - Không dùng float cho time (dùng int)
  - Dùng int cho epoch seconds
- Boolean/literal chuẩn JSON

### 1.2 Canonical JSON function

- Android: dùng serializer có “stable key order” hoặc tự sort map trước khi encode.
- iOS: encode dictionary theo key sorted (tự implement nếu cần).
- **Không phụ thuộc** serializer mặc định của OS.

> Nếu canonical khác nhau, MAC có thể fail → split mạng.

---

## 2) Event Envelope (Schema chuẩn)

### 2.1 Top-level structure (Transport)

```json
{
  "v": 1,
  "gid": "group_hash",
  "mid": "event_id",
  "ts": 1736500000,
  "ttl": 6,
  "enc": { "ct": "base64url" },
  "sig": { "mac": "base64url" }
}
```

### 2.2 Field definitions (Normative)

- `v` *(int, required)*: schema version.
- `gid` *(string, required)*: group identifier (hash(passphrase+salt), encoded as hex or base64url).
- `mid` *(string, required)*: event_id (single id for dedupe).
- `ts` *(int, required)*: created timestamp (epoch seconds; int).
- `ttl` *(int, optional)*: hop TTL for BLE forwarding (not storage TTL).
- `enc` *(object, required)*: encrypted payload (EventCore JSON).
- `sig.mac` *(string, required)*: MAC over canonical envelope (excluding `sig`), encoded as base64url.

### 2.3 MAC computation

`mac = HMAC-SHA256(group_key, canonical_json(envelope_without_sig))`

- Canonical JSON computed over envelope **excluding** `sig`.
- Output `base64url` to align with Phase 2 spec.

---

## 3) Event Core (Decrypted payload)

```json
{
  "ev": 1,
  "id": "event_id",
  "type": "CHAT.MSG",
  "actor": { "did": "device_id" },
  "time": { "t": 1736500000, "lt": 12 },
  "ref": { "root": "thread_id", "parent": null, "target": null },
  "meta": { "ttl_s": 86400, "prio": "normal" },
  "data": {}
}
```

### 3.1 Validation rules (before ingest)

Event chỉ được ingest nếu:

- `ev` supported
- `gid` matches current group
- `id == mid` (event_id alignment)
- `type` hợp lệ (CHAT.* / MAP.* / SYS.*)
- `time.t` không vượt quá skew window (xem 5.5)
- `sig.mac` valid
- `mid` chưa seen gần đây (replay protection)

> Nếu validation fail: drop silent (không tạo UI noise).

---

## 4) Event Types & Data (P0 minimal)

### 4.1 CHAT.MSG / CHAT.REPLY

- Payload trong `data` chứa `text`, `thread_id`, `reply_to` (nếu có).
- Giới hạn độ dài: **200 chars**.

### 4.2 CHAT.EDIT / CHAT.DEL (Tombstone)

- EDIT cập nhật nội dung theo LWW.
- DEL đánh dấu tombstone cho message mục tiêu.

### 4.3 MAP.MARKER.ADD / MAP.MARKER.UPD / MAP.MARKER.DEL

- Marker fields: `lat`, `lon`, `radius_m`, `note`, `kind`.
- DEL là tombstone.

### 4.4 MAP.AREA.ADD / MAP.AREA.UPD / MAP.AREA.DEL

- Area fields: polygon/geometry theo spec Phase 2.
- DEL là tombstone.

### 4.5 SYS.*

- Optional: `SYS.PRESENCE`, `SYS.ALERT`.

---

## 5) Reducer Invariants (Bất biến bắt buộc)

### 5.1 Deterministic

Given same set of events, reducer must produce same state.

### 5.2 Idempotent

Applying same event twice must not change state after first apply.

### 5.3 Order-independent

Event arrival order is arbitrary. Reducer must tolerate out-of-order.

### 5.4 LWW conflict resolution (standard)

For entity updates:

- Compare by `time.t` (event created time)
- If `time.t` differs: higher wins
- If `time.t` equal: tie-break by `time.lt`, then `actor.did` (device_id, lexicographic)

### 5.5 Clock skew tolerance

- Accept skew up to ±10 minutes.
- If skew exceeds, still ingest but mark `time.t` as untrusted and use tie-breakers.

### 5.6 Tombstones dominate

Delete events must override earlier creates/updates.

### 5.7 TTL expiration is part of state

- If `meta.ttl_s` present, use it.
- Else fallback to type default TTL.
- Transport `ttl` applies only to BLE forwarding, not storage.

---

## 6) Dedupe / Replay Protection

Maintain caches:

- `seen_event_id`: `key = gid + ":" + event_id` (LRU TTL 2–10 min)

Drop event if:

- event_id seen OR DB unique index `(gid, event_id)` already exists.

---

## 7) Storage Model Requirements (Event Log + Materialized Views)

### 7.1 Event log table minimum

- `gid`
- `event_id`
- `type`
- `created_t`
- `actor_did`
- `core_blob` (decrypted EventCore JSON bytes)
- `enc_blob` (original envelope enc for audit/debug, optional)
- `ingested_at` (local time; not used for conflict resolution)

### 7.2 Materialized tables minimum

- `chat_messages` (message_id, thread_id, text, created_t, actor_did, status)
- `map_markers` (marker_id, lat, lon, radius_m, created_t, status)
- `map_areas` (area_id, geometry, created_t, status)

---

## 8) Validation & Drop Policies

### 8.1 Hard limits (drop if exceeded)

- Max event size (bytes)
- Max text length (200 chars)
- Max note length
- Max radius
- Max events per sender per interval (rate limit)

### 8.2 Soft limits (degrade gracefully)

- If overloaded:
  - drop low-priority first (CHAT before ALERT)
  - keep latest ALERT

---

## 9) Cross-platform Test Vectors (Must-have)

### 9.1 Golden fixtures

Tạo bộ “golden fixtures” (canonical envelope + expected MAC) để Android/iOS verify:

- Same passphrase → same `gid`
- Same envelope → same canonical JSON bytes
- Same envelope → same `sig.mac`
- Reducer apply set → same materialized state

### 9.2 Reducer property tests

- Idempotency: apply same event N lần
- Commutativity: permute order of events
- Tombstone dominance: delete then add, add then delete
- Clock skew: `time.t` ± skew window

---

## 10) Migration & Versioning

- `v` increments only when schema breaks.
- Backward compatibility:
  - ignore unknown fields
  - drop unknown `type` unless feature flag enabled

---

## 11) Summary (Non-negotiables)

- **Canonical JSON** phải giống nhau
- **MAC over envelope (no sig)** phải giống nhau (base64url)
- Reducers: deterministic + idempotent + order-independent
- LWW + stable tie-break (`time.t`, `time.lt`, `actor.did`)
- Tombstones dominate
- TTL enforced by `meta.ttl_s`, transport `ttl` chỉ cho BLE forwarding
