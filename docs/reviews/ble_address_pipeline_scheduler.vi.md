# BLE Address Pipeline Scheduler (Pre-Implementation Design)

## 1. Muc tieu

Thiet ke bo dieu phoi de loai bo race condition tren queue BLE (dac biet `ConcurrentModificationException`) khi nhieu luong/callback cung thao tac tren cung `address`.

Y tuong nen tang:
- Moi `address` duoc xem la 1 "san pham".
- He thong co 1 "day chuyen chinh" (`main conveyor`) de lap lich theo vong.
- Cong viec cua moi `address` duoc gan theo nhan (`label`), xu ly tung buoc, xong buoc nao thi dua lai vao day chuyen chinh de xu ly buoc tiep theo.

---

## 2. Nhan xu ly (labels)

Nhan duoc su dung:
- `ENQUEUE`
- `WRITE_NEXT`
- `CONNECT`
- `CLEANUP`
- `RETRY`

Moi `address` co the co nhieu nhan dang cho cung luc, nhung tai 1 thoi diem chi xu ly 1 nhan theo thu tu uu tien.

---

## 3. Thanh phan chinh

### 3.1 Main Conveyor

Hang doi toan cuc chua danh sach `address` can xu ly theo round-robin.

### 3.2 Address State

Moi `address` co trang thai rieng:
- `pendingLabels` (tap nhan dang cho, set semantics - khong cho phep duplicate label cung loai)
- `inMainQueue` (da nam tren main conveyor hay chua)
- `busy` (dang duoc worker xu ly hay khong)
- `connect_state`:
  - `0 = DISCONNECTED`
  - `1 = CONNECTING`
  - `2 = CONNECTED`
- `lastFailureReason`, `failureCount`, `nextConnectAllowedAt` (phuc vu backoff/chuan doan)
- queue du lieu hien huu (`pendingByDevice[address]`)

### 3.3 Single Scheduler Thread

Toan bo thao tac thay doi state + queue cua scheduler chay tren 1 thread/handler duy nhat (serial).

Quy tac quan trong:
- Callback BLE/Nordic khong sua truc tiep queue.
- Callback chi duoc goi `postLabel(address, label)`.

### 3.4 Mo hinh 2 tang queue (UserOutbox -> TransportQueue)

De tach luong UI/input khoi luong transport, ap dung 2 tang queue:
- `UserOutbox` (tang producer):
  - Noi UI ghi vao khi user bam gui (chat/map/trail).
  - Bat buoc DB-backed (durable).
  - Khong duoc sua truc tiep `pendingByDevice`.
- `TransportQueue` (`pendingByDevice[address]`, tang consumer):
  - Chi scheduler thread so huu va thao tac.
  - Duoc nap du lieu tu `UserOutbox` qua nhan `ENQUEUE` theo batch co gioi han.

Rules:
1. UI thread chi append vao `UserOutbox`, tra control ngay.
2. Scheduler drain `UserOutbox` theo batch nho (`N` ban ghi/lan + cooldown), roi moi day vao `TransportQueue`.
3. ACK co the co staging rieng, nhung van phai theo cung ownership rule: khong callback/UI nao duoc ghi thang vao `pendingByDevice`.
4. State delivery khong doi (`pending -> sent -> delivered/seen`), nhung nguon cua `ENQUEUE` la `UserOutbox` thay vi UI push truc tiep.

---

## 4. Invariants bat buoc

1. Cung 1 `address` khong bao gio co 2 worker dong thoi.
2. Moi truy cap `pendingByDevice[address]` phai di qua scheduler serial.
3. `address` khong duoc duplicate vo han tren main conveyor (`inMainQueue` canh bao).
4. Xu ly theo quantum nho (1 nhan/luot) de dam bao fairness.
5. Bat buoc tach biet `UserOutbox` va `TransportQueue`; cam mutate truc tiep `TransportQueue` tu thread khac.

---

## 5. Thu tu uu tien labels

De xuat thu tu uu tien:
1. `CONNECT`
2. `WRITE_NEXT`
3. `ENQUEUE`
4. `RETRY`
5. `CLEANUP`

Ly do:
- `CONNECT` can duoc dam bao de mo duong truyen.
- `WRITE_NEXT` uu tien day du lieu dang co.
- `ENQUEUE` va `RETRY` bo sung cong viec moi.
- `CLEANUP` la tac vu bao tri, uu tien thap hon.

---

## 5.1 Quy tac connect_state

Trang thai ket noi cua moi `address` duoc quan ly boi 1 field duy nhat:
- `connect_state=0` (DISCONNECTED)
- `connect_state=1` (CONNECTING)
- `connect_state=2` (CONNECTED)

Rules chuyen trang thai:
1. Bat dau connect:
   - `0 -> 1`
2. Services discovered thanh cong:
   - `1 -> 2`
3. Connect timeout / status=133 / disconnect:
   - `1|2 -> 0`
4. Write failed:
   - Neu loi tam thoi (khong mat link): giu `2`, dat nhan `RETRY`
   - Neu loi lien ket (gatt failure/disconnect): `2 -> 0`, dat nhan `CONNECT`
5. `WRITE_NEXT` chi duoc xu ly khi `connect_state=2`, neu khong se uu tien `CONNECT`.

### Rule chi tiet khi `connect_state=1` (CONNECTING)

Trong trang thai `CONNECTING`, scheduler phai coi address dang "giu slot ket noi":
1. Khong tao connect moi cho cung address (khong connect chong).
2. `ENQUEUE` van duoc phep: them packet vao queue binh thuong.
3. `WRITE_NEXT` khong thuc thi ngay: chi dat pending, cho den khi len `CONNECTED`.
4. `CLEANUP` o che do timeout-check:
   - Neu qua `CONNECT_TIMEOUT_MS` ma van la `1`:
     - set `connect_state: 1 -> 0`
     - luu reason `connect_timeout`
     - dat nhan `RETRY/CONNECT` theo backoff.
5. Neu callback thanh cong (`services discovered`):
   - `1 -> 2`, uu tien day `WRITE_NEXT`.
6. Neu callback fail (`status=133`, disconnect, fail cung):
   - `1 -> 0`, ap backoff, xep lai `CONNECT`.
7. Backoff gate cho `CONNECT`:
   - Neu `now < nextConnectAllowedAt`, khong thuc thi `CONNECT` ngay.
   - Dat lich requeue sau delay con lai de tranh connect storm.

---

## 6. Luong xu ly mot vong

1. He thong nhan su kien (`postLabel(address, label)`).
2. `label` duoc them vao `pendingLabels[address]`.
3. Neu `inMainQueue=false` va `busy=false` thi dua `address` vao cuoi main conveyor.
   - Neu main conveyor truoc do dang rong, trigger `drainLoop` ngay.
4. Worker lay 1 `address` tu dau conveyor.
5. Chon 1 `label` uu tien cao nhat trong `pendingLabels[address]`.
6. Xu ly duy nhat label do.
7. Ket thuc label:
   - cap nhat state/label tiep theo (neu co),
   - neu con viec thi dua `address` ve cuoi conveyor,
   - neu het viec thi danh dau `inMainQueue=false`.

---

## 7. So do khoi ASCII

```text
                   +----------------------------------+
                   |  Event Sources / Callbacks       |
                   |  - BLE callbacks                 |
                   |  - Retry timers                  |
                   |  - Cleanup tick                  |
                   +----------------+-----------------+
                                    |
                                    | postLabel(address, LABEL)
                                    v
                    +---------------+----------------+
                    |  Scheduler Input (serial)      |
                    |  - update pendingLabels         |
                    |  - enqueue address if needed    |
                    +---------------+----------------+
                                    |
                                    v
                        +-----------+-----------+
                        |   Main Conveyor       |
                        |  [addrA, addrB, ...]  |
                        +-----------+-----------+
                                    |
                                    v
                        +-----------+-----------+
                        |  Pick one address     |
                        |  busy=true            |
                        +-----------+-----------+
                                    |
                                    v
                    +---------------+----------------+
                    | Label Selector (priority)      |
                    | CONNECT > WRITE_NEXT > ...     |
                    +---------------+----------------+
                                    |
                                    v
                    +---------------+----------------+
                    | Execute exactly 1 label        |
                    | (atomic on scheduler thread)   |
                    +---------------+----------------+
                                    |
                      +-------------+-------------+
                      |                           |
                      v                           v
        +-------------+-------------+   +---------+----------------+
        | still has pending labels? |   | no pending labels        |
        +-------------+-------------+   +---------+----------------+
                      | yes                         | no
                      v                             v
        +-------------+-------------+   +---------+----------------+
        | requeue address at tail   |   | inMainQueue=false        |
        | busy=false                |   | busy=false               |
        +---------------------------+   +--------------------------+
```

### 7.1 So do 2 tang queue

```text
UI/Input Thread
   |
   | append event/message
   v
[UserOutbox (DB-backed)]
   |
   | scheduler drain (bounded batch, cooldown)
   v
[TransportQueue: pendingByDevice[address]]
   |
   | pipeline theo nhan (CONNECT/WRITE_NEXT/RETRY/CLEANUP)
   v
BLE link
```

---

## 8. State machine (moi address)

```text
         +---------+
         |  IDLE   |
         +----+----+
              |
              | postLabel
              v
      +-------+--------+
      |  QUEUED_MAIN   |
      +-------+--------+
              |
              | picked by scheduler
              v
      +-------+--------+
      |    BUSY        |
      +---+--------+---+
          |        |
          | more   | no more
          | work   | work
          v        v
   +------+----+  +------+
  | QUEUED_MAIN| | IDLE |
   +-----------+  +------+
```

State machine rieng cho ket noi:

```text
DISCONNECTED(0) --start CONNECT--> CONNECTING(1) --services OK--> CONNECTED(2)
CONNECTING(1) --timeout/133--> DISCONNECTED(0)
CONNECTED(2) --disconnect/link fail--> DISCONNECTED(0)
CONNECTED(2) --write fail recoverable--> CONNECTED(2) + LABEL(RETRY)
```

---

## 9. Pseudocode

```text
postLabel(address, label):
  state[address].pendingLabels.add(label)
  if !state[address].inMainQueue && !state[address].busy:
    wasEmpty = mainQueue.isEmpty()
    mainQueue.addLast(address)
    state[address].inMainQueue = true
    if wasEmpty:
      scheduleDrainLoop()

drainLoop():
  while mainQueue not empty:
    address = mainQueue.removeFirst()
    st = state[address]
    st.inMainQueue = false
    if st.busy: continue
    st.busy = true

    label = pickHighestPriority(st.pendingLabels)
    if label != null:
      if label == CONNECT && now() < st.nextConnectAllowedAt:
        mainQueue.addLast(address)
        st.inMainQueue = true
        st.busy = false
        continue
      st.pendingLabels.remove(label)
      execute(address, label)   // queue ops only here

    st.busy = false
    if st.pendingLabels not empty:
      mainQueue.addLast(address)
      st.inMainQueue = true
```

---

## 10. Loi ich va trade-off

### Loi ich
- Loai bo race condition tren `ArrayDeque`.
- Hanh vi du doan duoc (deterministic hon).
- Fairness tot hon giua nhieu `address`.

### Trade-off
- Tang do phuc tap scheduler state.
- Can ky luat "khong truy cap queue truc tiep ngoai scheduler".

---

## 11. Tieu chi acceptance truoc khi merge code

1. Khong con crash `ConcurrentModificationException` trong stress test.
2. Khong co thao tac truc tiep `pendingByDevice` ngoai scheduler path.
3. `address` khong bi duplicate vo han tren main conveyor.
4. TC7 retest on dinh hon (khong crash, throughput chap nhan duoc).
5. Log marker scheduler cho phep trace ro:
   - label enqueue
   - label execute
   - requeue
   - drain idle
