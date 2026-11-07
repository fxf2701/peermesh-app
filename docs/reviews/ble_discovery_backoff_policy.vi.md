# Chinh sach Backoff cho BLE Discovery (VN)

## Pham vi
Tai lieu nay dinh nghia co che discovery + backoff cho BLE mesh trong Phase 3.
Muc tieu la xu ly hien tuong giao tiep mot chieu da ghi nhan o TC7/TC10.

## Van de hien tai
Co truong hop app bao `no nearby or send failed` du UI van thay thiet bi gan.
Nguyen nhan chinh:
- backoff theo address qua chat, co the loai bo het target tam thoi
- BLE address thay doi, target cu nhanh bi sai
- retry co xu huong bam vao target loi, bo qua target moi/fresh

## Muc tieu policy
- Giu duoc tien do gui/nhan trong moi truong song vo tuyen khong on dinh.
- Tranh vong lap connect spam gay nong may, ton pin.
- Uu tien message event truoc ACK.
- Co telemetry ro rang de debug.

## Cau truc danh sach target
Duy tri 2 pool theo runtime cua group:

1. MTL (Main Targets List)
- Danh sach target dang hoat dong de gui.
- Co gioi han (mac dinh: 10 deviceId).
- Sap xep theo diem (do moi, RSSI, lich su thanh cong).

2. PTL (Potential Targets List)
- Danh sach du phong + target dang bi phat.
- Co gioi han lon (mac dinh: 64 deviceId), khong de vo han.
- Uu tien sap xep:
  1) target fresh chua bi phat
  2) target bi phat nhung sap het han phat

## Khoa dinh danh
Backoff/penalty phai theo `deviceId` (khong theo BLE address).
Address chi la locator moi nhat cua mot deviceId on dinh.

## Rule ingest discovery
Moi scan hit hop le:
- Validate manufacturer payload/signature.
- Trich `deviceId`.
- Update map `deviceId -> latestAddress` + `lastSeen`.
- Neu `deviceId` da o MTL: cap nhat score.
- Neu chua co: chen vao PTL (hoac refresh node PTL).
- Neu MTL con cho trong: chi promote target tot nhat tu PTL len MTL khi target do khong con trong thoi gian phat.

## Chinh sach backoff
### Che do binh thuong (`nearby >= 2`)
- Connect fail -> phat target theo cua so thoi gian (mac dinh 5s, exponential co tran).
- Demote target fail tu MTL xuong PTL.
- Chi promote target phu hop nhat tu PTL len MTL neu da het thoi gian phat.
- Tiep tuc xoay tua qua MTL.

### Che do probe mot target (`nearby == 1`)
- Khong tat han an toan, nhung bo qua backoff nang.
- Cho retry nhanh (mac dinh 1-2s) voi target duy nhat dang thay.
- Van dat tran so lan probe trong cua so thoi gian de tranh spam cung.

### Fallback khi het target
Neu tat ca MTL dang bi phat:
- Van cho 1 probe candidate (target sap het han phat hoac fresh nhat),
  thay vi fail cung ngay lap tuc.

## So do luong ASCII
```text
[Scan hit]
   |
   v
[Validate payload + trich deviceId]
   |
   +-- invalid --> [Drop]
   |
   v
[Cap nhat latestAddress + lastSeen + score]
   |
   +-- deviceId da o MTL? -- co --> [Refresh node MTL] ---+
   |                                                      |
  khong                                                   v
   |                                              [Tick scheduler gui]
   v                                                      |
[Chen/refresh node PTL]                                   v
   |                                              [Lay target xoay tua tu MTL]
   +-- MTL con slot? -- khong ---------------------------+
   |
  co
   |
   v
[Target PTL tot nhat da het phat?]
   |
   +-- chua --> [Giu o PTL]
   |
  roi
   |
   v
[Promote PTL -> MTL]

==================== VONG GUI / FAIL ====================
[Thu connect/gui target]
   |
   +-- thanh cong --> [Giu trong MTL, reset fail counter]
   |
   +-- that bai --> [Ap dung backoff theo deviceId]
                    |
                    v
                 [Demote MTL -> PTL]
                    |
                    v
      [Promote target PTL tot nhat da het phat]
                    |
                    v
                 [Tiep tuc xoay tua]

==================== CASE DAC BIET ====================
[nearby == 1]
   |
   v
[Single-target probe mode]
   |
   v
[Retry interval ngan, co tran]

## Ghi chu thuc te (2026-03-08)
- De tranh bam sai dia chi qua lau, chi su dung `lastSeenAddrByDeviceId` khi `seenAt <= DEVICE_TTL_MS`.
- Quiet window sau `status=133` dung 1 muc co dinh 20s (khong escalation) cho do on dinh tot nhat trong 5 lan test gan nhat.
[Retry nhanh, co gioi han so lan thu]

[Tat ca MTL dang bi phat]
   |
   v
[Chon 1 probe candidate]
```

## Lich xoay tua
- Round-robin chay tren MTL.
- Moi fail thi chuyen sang target tiep theo ngay.
- Rule fairness: khong retry ngay cung target fail trong cung 1 cycle ngan,
  tru khi dang o che do single-target.

## ACK policy
- ACK van xu ly trong BLE mesh service.
- Message event uu tien cao hon ACK.
- ACK batch/piggyback khong duoc chiem het queue slot danh cho message.

## Tham so mac dinh de xuat
- `MTL_CAP = 10`
- `PTL_CAP = 64`
- `BASE_PENALTY_MS = 5000`
- `PENALTY_MAX_MS = 30000`
- `SINGLE_TARGET_PROBE_MS = 1500`
- `DEVICE_FRESH_TTL_MS = 30000`

## PTL Capacity thich ung theo Heap Headroom
PTL capacity nen scale theo app heap headroom (RAM con trong cua process), khong dua truc tiep vao tong RAM may.

Muc de xuat runtime:
- `< 24 MB` headroom: `PTL_CAP = 24`
- `24 - 48 MB` headroom: `PTL_CAP = 48`
- `48 - 96 MB` headroom: `PTL_CAP = 96`
- `> 96 MB` headroom: `PTL_CAP = 160` (tran cung)

Rule van hanh:
- Giu `MTL_CAP` co dinh (`10`), chi PTL thay doi theo tier.
- Ap `PTL_CAP_MAX = 160`.
- Khi co memory pressure (`onTrimMemory`), ha tier va prune PTL ngay.

## Marker telemetry can log
- `Discovery pool update`: deviceId, pool, score inputs
- `MTL promote` / `MTL demote`: reason
- `Backoff apply`: deviceId, penaltyMs, failReason
- `Backoff bypass single-target`: deviceId
- `All targets penalized -> probe`: chosen deviceId
- `Target selected`: deviceId, latestAddress, pool

## Lo trinh trien khai
1. Phase A (rui ro thap)
- Giu transport hien tai, them penalty theo deviceId + single-target probe + telemetry.
2. Phase B
- Trien khai cau truc MTL/PTL day du voi promote/demote.
3. Phase C
- Tinh chinh tham so dua tren du lieu retest TC7/TC10.

## Exit criteria
TC7 retest pass khi:
- 2 chieu gui/nhan du 10/10 trong cung session
- khong con burst `no nearby or send failed` keo dai mot chieu

TC10 retest pass khi:
- sau thao tac screen-off/background, 2 chieu recover trong khoang delay chap nhan duoc
- khong co blackout mot chieu keo dai do starvation target
