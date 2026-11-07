# BLE Mesh ACK Flow (Delivered/Seen) - Technical Note

## 1. Muc tieu

Tai lieu nay mo ta chi tiet luong ACK trong chat:
- ACK `received` de doi trang thai sender tu `sent` -> `delivered`.
- ACK `seen` de doi trang thai sender tu `delivered/sent` -> `seen`.

Pham vi:
- App layer (UI + reducer + ingest notifier + ACK manager).
- Transport layer BLE mesh (queue/priority/backpressure) o muc can thiet de hieu vi sao ACK co the rot.

---

## 2. Dinh nghia nhanh

- `CHAT_MSG/CHAT_REPLY`: event noi dung tin nhan.
- `SYS.ACK`: event ACK, `ack_type`:
  - `received`: bao da nhan duoc tin.
  - `seen`: bao da hien thi/duoc user nhin thay tren chat UI.
- `target_id` / `target_ids`: danh sach `message_id` ma ACK tro toi.

---

## 3. Luong xu ly chi tiet

### 3.1 Sender gui message

1. User bam send o `GroupChatActivity`.
2. App tao `EventCore` chat + insert vao bang `events`.
3. Ngay trong transaction, `ChatReducer.apply(...)` upsert `chat_messages` voi:
   - `delivery_status = sent`
4. App goi `EnvelopeSender.send(...)` de phat len BLE mesh.
5. App dang ky pending resend qua `MeshAckManager.enqueuePending(...)`.

Y nghia:
- Tin luon co o DB local truoc.
- Trang thai ban dau luon la `sent`.

### 3.2 Receiver nhan message va tao ACK received

1. `EnvelopeReceiverImpl` nhan blob.
2. Verify/decrypt + validate event.
3. `EventIngestor.ingest(...)` insert event + goi reducer.
4. `IngestNotifier.notifyInserted(...)` phat su kien event moi.
5. `MeshAckManager.onEventInserted(...)` bat event:
   - Neu la `CHAT_MSG/CHAT_REPLY` va khong phai message cua chinh minh
   - => `enqueueAck(..., "received")`

### 3.3 Receiver tao ACK seen

1. `GroupChatActivity` dang nghe `IngestNotifier`.
2. Khi event moi cua group duoc render o UI:
   - Neu actor khac voi device hien tai
   - => `MeshAckManager.sendSeenAck(...)`
3. `sendSeenAck` co chot `seenSent` de tranh gui seen lap cho cung 1 target.

### 3.4 ACK batching + send

1. `MeshAckManager` gom ACK theo key `(gid|ackType)`.
2. Cua so batch:
   - `ACK_BATCH_WINDOW_MS = 400ms`
   - flush som neu dat `ACK_BATCH_MAX_TARGETS = 24`
3. Khi flush:
   - target chunk 8 target/event (`ACK_TARGETS_PER_EVENT = 8`)
   - tao `SYS.ACK` qua `EventFactory.sysAck(...)`
   - send qua `EnvelopeSender.send(...)`

### 3.5 Sender nhan ACK va doi trang thai

1. ACK vao `EnvelopeReceiverImpl` -> `EventIngestor` -> `ChatReducer.applyAck(...)`.
2. Neu `ack_type = received`:
   - update `received_ack_by`, `received_group_ack_by`
   - tang `received_ack_count`, `received_group_ack_count`
   - `delivery_status = max(current, delivered)`
3. Neu `ack_type = seen`:
   - update `seen_by`
   - `delivery_status = seen`
4. UI doc `delivery_status` de hien thi `Sent/Delivered/Seen`.

### 3.6 Dieu kien dung resend pending message

`MeshAckManager.shouldStopResend(...)` hien tai dung resend khi:
- `received_group_ack_count >= 1` va
- `received_ack_count >= required`
- `required = max(1, min(4, nearbyDeviceCount))`

---

## 4. ASCII sequence diagram (end-to-end)

```text
User(Sender)      Sender App              BLE Mesh               Receiver App         Receiver UI
    |                 |                       |                        |                   |
    | tap Send        |                       |                        |                   |
    |---------------->| create CHAT_MSG       |                        |                   |
    |                 | insert events+chat    |                        |                   |
    |                 | delivery=sent         |                        |                   |
    |                 | enqueuePending(msg)   |                        |                   |
    |                 | send envelope --------+----------------------->| verify/decrypt    |
    |                 |                       |                        | ingest + reducer   |
    |                 |                       |                        | notifyInserted     |
    |                 |                       |                        | enqueue ACK(received)
    |                 |                       |<-----------------------+ send SYS.ACK(received)
    |                 | ingest ACK(received)  |                        |                   |
    |                 | reducer: sent->deliv  |                        |                   |
    |                 |                       |                        | render message     |
    |                 |                       |                        |------------------->|
    |                 |                       |                        | sendSeenAck(target)|
    |                 |                       |<-----------------------+ send SYS.ACK(seen) |
    |                 | ingest ACK(seen)      |                        |                   |
    |                 | reducer: -> seen      |                        |                   |
```

---

## 5. ASCII state machine (delivery status)

```text
               +------------------+
               |      SENT        |
               +------------------+
                 |            |
                 | ACK(received)
                 v            |
          +-------------------+----+
          |    DELIVERED           |
          +------------------------+
                 |
                 | ACK(seen)
                 v
          +------------------------+
          |        SEEN            |
          +------------------------+

Notes:
- ACK(seen) co the den truoc ACK(received) trong dieu kien thuc te.
- Khi ACK(seen) den, reducer dat thang SEEN.
```

---

## 6. Cac diem co the gay ACK rot/tre

1. ACK la background priority:
   - `DtnTransportPolicy.PRIORITY_ACK_BACKGROUND = 9`.
2. BLE queue co cap ACK/data:
   - ACK co the bi drop khi queue cang (per-device/global caps).
3. Fairness:
   - ACK duoc chen theo quota sau mot so data burst (`ACK_SEND_QUOTA_DATA_BURST`).
4. Discovery/connect bat on dinh:
   - Message data co the qua duoc 1 chieu nhung ACK chieu nguoc bi mat.

---

## 7. Marker ActivityLog khuyen nghi cho ACK debug

Nen bat toi thieu:
- `ACK enqueue`
- `ACK batch flush`
- `ACK send success/fail`
- `ACK ingest received`
- `ACK applied delivered`
- `ACK applied seen`

Da co san trong code:
- `ACK applied delivered`
- `ACK applied seen`
- cac marker reducer upsert lien quan ACK.

---

## 8. Checklist verify nhanh trong 1 phien test

1. Sender co `delivery_status=sent` ngay sau send.
2. Receiver ingest duoc CHAT_MSG.
3. Receiver co tao `ACK(received)`.
4. Sender ingest duoc `SYS.ACK(received)` -> len `delivered`.
5. Receiver render UI va tao `ACK(seen)`.
6. Sender ingest duoc `SYS.ACK(seen)` -> len `seen`.
7. Neu fail: doi chieu queue cap/drop/connect markers truoc khi ket luan reducer loi.

