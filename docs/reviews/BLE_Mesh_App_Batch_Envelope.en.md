# BLE Mesh App Batch Envelope + App-Signature

## 1. Goals

- Pack multiple `EventEnvelope` items into a single app-level batch to reduce BLE overhead.
- Apply an app-level HMAC signature to reject non-ecosystem payloads.
- Enforce payload caps to avoid `Chunking` crashes on low MTU devices.

## 2. Scope

- Sender wraps `EventEnvelope` items into `AppBatchEnvelope`.
- Receiver verifies HMAC, then decodes each item and ingests as usual.
- Receiver relays `APP_BATCH` by forwarding the original batch payload, instead of immediately rebuilding a new batch from extracted events.
- No changes to the outer `EventEnvelope` / `AppBatchEnvelope` transport schema.
- Inner `EventCore` chat payload conventions may evolve independently as long as they remain valid canonical `EventCore` items inside the batch.

## 2.1 Chat event payload convention

This document does not redefine the outer batch/envelope shape, but the current recommended inner chat-event convention is:

`CHAT.MSG`
```json
{
  "ref": {
    "root": null,
    "parent": null,
    "target": null
  },
  "data": {
    "text": "..."
  }
}
```

`CHAT.REPLY`
```json
{
  "ref": {
    "root": null,
    "parent": "target-message-id",
    "target": null
  },
  "data": {
    "text": "..."
  }
}
```

`CHAT.EDIT`
```json
{
  "ref": {
    "root": null,
    "parent": null,
    "target": "target-message-id"
  },
  "data": {
    "text": "...",
    "tct": 1782900050
  }
}
```

Rules:
- `CHAT.REPLY` should use `ref.parent` as the reply target and should not duplicate that target in `data.reply_to`.
- `CHAT.EDIT` should use `ref.target` as the edited-message target and should not duplicate that target in `data.target_id`.
- `CHAT.EDIT.data.tct` is the target message's original creation time in epoch seconds and allows receivers to place an edited message into chat history even if the base `CHAT.MSG` never arrives.
- `CHAT.MSG`, `CHAT.REPLY`, and `CHAT.EDIT` should continue to share the same user-facing max text length, but payload-budget validation should be sized against the heaviest chat variant.

Approximate relative payload overhead versus `CHAT.MSG` with the same text length:
- Current duplicated-target shape:
  - `CHAT.REPLY`: `+98` bytes
  - `CHAT.EDIT`: `+99` bytes
- Recommended balanced shape:
  - `CHAT.REPLY`: `+41` bytes
  - `CHAT.EDIT`: `+58` bytes

This keeps the app-batch transport contract unchanged while reducing wasted chat payload space inside each encrypted `EventEnvelope`.

## 3. Data format

`AppBatchEnvelope` (canonical JSON):
- `v`: version (current `1`)
- `kind`: fixed `"APP_BATCH"`
- `ts`: timestamp (ms)
- `items`: list of base64url-encoded `EventEnvelope`
- `app_sig`: HMAC (SHA-256), computed over canonical JSON without `app_sig`

## 4. Signing & verification

- `AppBatchCrypto.sign(...)` generates `app_sig` using HMAC-SHA256.
- `EnvelopeReceiverImpl`:
  - If `kind == APP_BATCH`, verify HMAC.
  - On success, decode `items` and ingest each item.
  - On failure, drop.
- Non-batch envelopes:
  - If `REQUIRE_APP_SIGNATURE=true`, drop any envelope not coming from `RELAY`.

## 5. Per-type batch windows

- `MESSAGE`: 1.0s
- `ACK`: 1.5s
- `MAP`: 3s
- `TRAIL`: 8s
- `OTHER`: 5s

## 6. Receiver batch-forward policy

- For a newly accepted `APP_BATCH`, receiver still:
  - verifies HMAC
  - extracts `items`
  - ingests/renders each event
- Then receiver schedules original-batch forwarding with a fixed budget:
  - attempt `1`: immediate
  - attempt `2`: delayed retry after `6s`
- The forwarded payload keeps the original `batch_id` stable across relay hops.
- Receiver emits:
  - `BLE batch forward queued`
  - `BLE batch forward skipped`

## 7. Payload caps

- `MAX_BATCH_BYTES = 4KB`
- `HARD_MAX_PAYLOAD_BYTES = 2.4KB`
- If a signed/encoded batch exceeds hard cap -> drop batch (warn log).

## 8. Duplicate suppression / pending guard

- Sender-side long TTL suppression is no longer part of the current baseline.
- The active duplicate guard is now the in-memory `active pending` check inside `AppBatcher`:
  - if the same `eventId` is already pending in the local batch queue, it is skipped
  - once that pending batch is emitted or dropped, the event can be enqueued again by higher-level logic

## 9. Current limitations

- Batcher keeps pending in memory only. If app/service is killed before flush, batch is lost.
- No batch-level resend (single broadcast).
- No sender-side persisted `batch_send_state` / per-device sent tracking yet.
- Interrupted write/cleanup can still leave delivery incomplete even when batch-forward logic is correct.

## 10. Implementation details / Metrics

### 10.1 Code touchpoints

- Sender:
  - `android/data/src/main/java/app/peermesh/data/transport/EnvelopeSender.kt`
  - `android/data/src/main/java/app/peermesh/data/transport/AppBatcher.kt`
- Receiver:
  - `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt`
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
- Signature:
  - `android/security/src/main/java/app/peermesh/security/crypto/AppBatchCrypto.kt`
  - `android/security/src/main/java/app/peermesh/security/crypto/AppMac.kt`
  - `android/core/src/main/java/app/peermesh/core/json/AppBatchCanonicalizer.kt`
  - `android/core/src/main/java/app/peermesh/core/model/EventModels.kt`

### 10.2 Metrics to observe

- `batch_size_bytes` distribution (pre- and post-signing/encoding).
- `items_per_batch` by type (MESSAGE/ACK/MAP/TRAIL/OTHER).
- `drop_reason=payload_too_large` count (hard cap).
- `verify_fail` count (app signature mismatch).
- BLE transport throughput before/after batching (recv/render counts).
- `batch_forward_attempts` by `batch_id` and receiver device.
- `batch_forward_skipped` reasons (`no_target`, `enqueue_failed`, `ttl_expired`, etc.).
- `APP batch emit` vs `APP batch extract` correlation by `batch_id`.

### 10.3 Success signals

- No `payload too large` crash during multi-device tests.
- Receiver drops non-app payloads as expected (except RELAY).
- Original sender-built batches are observable across relay hops via stable `batch_id`.
- Message delivery latency remains stable or improves under burst.

## 11. Backlog

- Persist batch + per-device sent tracking + resend loop (TTL 5 minutes).
- If one item exceeds hard cap, research item-fragment + reassembly before render.
- Add sender-side `batch_send_state` so `write_failed_status_133` can identify exactly which `batch_id` and `event_ids` need resend.
