# Review Icon BLE Mesh va Cloud Sync tren App Bar

> Pham vi: review ma nguon hien tai cua `include_app_header.xml`,
> `StatusIndicatorUpdater`, `BleForegroundService`, `BleMeshStatus`,
> `RelaySyncUiEvaluator`, `RelaySyncLifecycleCoordinator`, va
> `RelaySyncSettings`.
>
> Phuong phap: static review. Tai lieu mo ta contract hien tai cua UI; chua
> thay the kiem thu tren thiet bi voi Bluetooth, Internet va relay that.

## 1. Tom tat

App bar co hai icon trang thai nam ben trai menu:

| Icon | Drawable | Muc dich hien tai | Mau mac dinh |
| --- | --- | --- | --- |
| BLE mesh | `ic_mesh` | Trang thai runtime cua mesh va tin hieu peer nearby | `status_off` xam |
| Cloud sync | `ic_satellite` | Kha nang dung relay khi Cloud Sync bat va Internet duoc Android xac thuc | `status_off` xam |

Ca hai icon dung cung enum `StatusIndicatorUpdater.State`:

| State | Mau | Ma mau |
| --- | --- | --- |
| `ON` | Xanh la | `#22C55E` |
| `PAUSED` | Cam | `#F59E0B` |
| `ERROR` | Do | `#EF4444` |
| `OFF` | Xam | `#6B7280` |

Hai icon dung chung bang mau, nhung **khong dung chung y nghia**. Icon BLE la
signal ve transport runtime va peer nearby. Icon Cloud la signal ve duong relay
da bat, co Internet, va co endpoint/managed access duoc cau hinh; no khong la
bang chung cua mot lan dong bo thanh cong gan day.

## 2. Thanh phan va ownership

```text
include_app_header.xml
  |- header_status_ble   (ic_mesh)
  `- header_status_relay (ic_satellite)
          |
          v
StatusIndicatorUpdater.updateHeader(activity)
  |- bleStateForUi() -> mau icon BLE
  `- relayState()    -> mau icon Cloud
```

- `include_app_header.xml` dat hai `ImageView` 18dp, content description lan
  luot la `BLE status` va `Relay status`. Layout khong hien thi text, tooltip,
  hay legend cho mau.
- `StatusIndicatorUpdater.start(activity)` duoc goi trong `onResume` cua cac
  activity co app header, trong do co Group Chat, Nearby Rooms, Nearby Room,
  Main, Settings va Statistics. Cac activity goi `stop(activity)` khi pause/deactivate.
- `StatusIndicatorUpdater` luu observer theo activity trong `WeakHashMap`.
  Moi activity co receiver, network callback, BLE listener va ticker rieng.
- `BleForegroundService` la nguon su that cho mesh dang chay qua hai atomic
  flags `isMeshRunning()` va `isServiceRunning()`.
- `BleMeshStatus` luu cac timestamp runtime: startup, scan callback,
  advertise, recovery, stall, traffic va peer count cache.
- `RelaySyncSettings` luu Cloud Sync enabled, relay URLs, managed access va
  timestamp sync success. `RelaySyncLifecycleCoordinator` start/stop foreground
  relay service hoac periodic worker theo setting va process lifecycle.

## 3. Logic icon BLE mesh

### 3.1 Quyet dinh state

`bleStateForUi(context)` ap dung thu tu sau:

1. Neu `BleForegroundService.isMeshRunning()` la `false` -> `OFF`.
2. Neu mesh dang chay va co startup/transport stall chua duoc recovery trong
   60 giay -> `ERROR`.
3. Lay peer count tu cache `BleMeshStatus` (TTL 15 giay), fallback sang runtime.
4. Neu peer count > 0 -> `ON`.
5. Neu khong co peer va mesh dang trong cua so transition -> `PAUSED`.
6. Con lai -> `ERROR`.

Do do, `ON` cua BLE co nghia chinh xac la: **mesh dang chay, khong co stall
gan day, va dang thay it nhat mot peer nearby**. No khong chi co nghia
"Bluetooth da bat" hay "mesh da enable".

### 3.2 Dieu kien `PAUSED`

Khi mesh dang chay va chua co peer, UI dung `PAUSED` neu it nhat mot dieu kien
sau dung:

- auto recovery, startup recovery, hoac stall recovery trong 60 giay;
- scan callback/start gan day trong 60 giay;
- advertise started gan day trong 5 phut;
- mesh start gan day trong 15 giay;
- adaptive state la `idle`;
- scan bi skip gan day trong 30 giay.

### 3.3 Dieu kien `ERROR`

Khi mesh van duoc danh dau running, nhung khong co peer va khong thuoc mot dieu
kien `PAUSED`, icon chuyen do. Ngoai ra, startup/transport stall chua duoc
recovery trong 60 giay cung chuyen do ngay lap tuc.

### 3.4 Lifecycle va thao tac nguoi dung

| Hanh dong | Hanh vi hien tai |
| --- | --- |
| Cham icon BLE khi mesh khong running | Luu user enabled, start service neu `canStartMesh()`, enqueue work. Neu thieu permission/Location/Bluetooth/advertiser -> dialog block. |
| Cham icon BLE khi mesh dang running | Stop mesh, huy work, doi 3 giay, sau do start lai. Day la **restart**, khong phai toggle off. |
| Giu icon BLE 1 giay | Hien toast huong dan giu 5 giay de tat. |
| Giu icon BLE 5 giay | Set user enabled = false, stop mesh va huy work. Day la manual off thuc su. |
| Bluetooth chuyen `STATE_OFF` | Neu service/mesh dang ton tai, hien dialog Bluetooth. |
| Location tat tren Android <= 11 | Neu service/mesh dang ton tai, hien dialog Location Services. |

`canStartMesh()` yeu cau BLE permission, Location Services tren Android <= 11,
Bluetooth adapter enabled va BLE advertiser kha dung. Khi app vao foreground,
`PeerMeshApp` co the auto-start mesh neu user enabled, service chua chay va
`canStartMesh()` dung.

## 4. Logic icon Cloud Sync

### 4.1 Dieu kien truoc khi danh gia relay

`relayState(context)` tra `OFF` neu mot trong cac dieu kien sau sai:

- Build/capability khong cho phep Relay Sync.
- `RelaySyncSettings.isEnabled()` la `false`.
- Android khong co active network co ca `INTERNET` va `VALIDATED` capability.

Neu qua duoc ba dieu kien tren, updater lay:

- `lastSuccessMs`;
- danh sach direct relay URL sau filter/selection/fallback;
- trang thai managed relay access;
- `hasRecentSuccess`: success trong 5 phut gan nhat.

### 4.2 Mapping Relay indicator

| Dieu kien | State icon Cloud |
| --- | --- |
| Capability off, setting off, hoac Internet khong validated | `OFF` |
| Internet valid va co direct relay URL hoac managed relay configured | `ON` |
| Internet valid, Cloud Sync enabled, nhung khong co direct hay managed path | `PAUSED` |

`RelaySyncUiEvaluator` tra `ON` ngay ca khi `hasRecentSuccess = false`, mien la
co path duoc cau hinh. Test hien co xac nhan ro contract nay voi managed path.
Vi vay mau xanh Cloud hien tai co nghia: **duong relay duoc cau hinh va thiet bi
co Internet duoc xac thuc**, khong co nghia "relay vua sync thanh cong".

`ERROR` khong duoc `relayState()` tra ve. Loi push/pull gan day, relay HTTP
failure, va success qua han khong tu lam icon Cloud do neu endpoint van duoc
cau hinh va Internet van validated.

### 4.3 Lifecycle va thao tac nguoi dung

| Hanh dong | Hanh vi hien tai |
| --- | --- |
| Cham icon Cloud khi Relay Sync available | Dao `relay_sync_enabled`. Tat -> stop service/work. Bat -> mark attempt va yeu cau foreground sync ngay. |
| Cham icon Cloud khi capability khong available | Stop relay lifecycle, mo Pro gate cho feature Shared Live Trail, sau do refresh header. |
| App foreground | Relay lifecycle uu tien foreground service khi Cloud Sync enabled. |
| App background | Normal mode giu foreground service; Battery Saver chuyen periodic worker. |

## 5. Co che refresh

`StatusIndicatorUpdater.start()` refresh ngay mot lan, sau do refresh theo bon
nguon:

1. Broadcast receiver: Bluetooth state, Location state, va
   `RelaySyncService.ACTION_RELAY_STATUS_CHANGED`.
2. `ConnectivityManager.NetworkCallback`: network available, capability change,
   network lost.
3. `BleMeshStatus` listener: debounce 150 ms.
4. Ticker 2 giay: bao dam timestamp-based state tu het han duoc tinh lai.

`updateHeader()` lay ca hai `ImageView`; neu khong tim thay icon BLE thi ham
return som va icon Cloud cung khong duoc update. Hien tai header chung co ca
hai icon, nen day la assumption hop le cua layout hien tai.

## 6. Dialog va prompt lien quan icon

- Location prompt chi ap dung Android <= 11, BLE service dang chay, Bluetooth
  dang bat va Location Services dang tat. Cooldown 30 giay.
- Trong Group Chat/Nearby screen khi Cloud path khong available, title Location
  doi thanh `Nearby Communication Paused`; cac man khac dung
  `Turn On Location Services`.
- Bluetooth dialog dung cooldown 30 giay, body phan biet Cloud path con dung
  hay khong, va co `NOT NOW` / `OPEN BLUETOOTH`.
- Khi mo Group Chat ma Location hoac Bluetooth da tat, `ActiveGroupManager`
  goi dialog tu `StatusIndicatorUpdater` thay vi toast cho hai truong hop nay.

## 7. Nhan xet review va rui ro

### R1 - Cloud xanh co the bi hieu nham la da ket noi relay (muc do: cao)

Code va test hien tai co chu dich de xem direct/managed path + Internet valid la
`ON`, du khong co sync success trong 5 phut. Nguoi dung co the hieu xanh la
"cloud dang hoat dong" trong khi endpoint khong phan hoi hoac push/pull dang
that bai.

**Khuyen nghi:** doi contract thanh ba phan ro rang: `configured`, `syncing`,
`healthy`; hoac chi dung xanh khi co success gan day, cam khi path cau hinh
nhung chua duoc xac minh, va do khi outcome failure gan day.

### R2 - BLE xanh do peer presence, khong chi do mesh healthy (muc do: cao)

Mesh dang chay binh thuong nhung khong co peer se la cam trong cua so transition
hoac do khi timestamp het han. Dieu nay tron hai khai niem: health cua transport
va so luong peer. Mot vung khong co nguoi gan day co the hien do du mesh da
enable va khong co loi he thong.

**Khuyen nghi:** tach `transportHealth` khoi `nearbyPeerPresence`. Vi du: icon
BLE xanh khi service healthy; badge/text phu hien `0 peers`; cam cho starting /
recovering; do chi cho block/stall that su.

### R3 - Tap BLE va tap Cloud co semantics khac va khong ro (muc do: trung binh)

Tap Cloud la enable/disable setting. Tap BLE khi dang chay la restart, con tat
thuc su lai can long-press 5 giay. Header chi co content description ve status,
khong noi ro action, va khong co visual affordance cho long-press.

**Khuyen nghi:** them tooltip/accessibility action ro rang, hoac mo status
sheet/quick settings khi tap. Neu giu long-press, hien hint o lan dau va dung
mot icon/menu rieng cho restart.

### R4 - Shared enum lam mo mat y nghia (muc do: trung binh)

Ca hai icon dung `State.ON/OFF/PAUSED/ERROR`, nhung Cloud hien khong bao gio
tra `ERROR`, con BLE `ON` yeu cau peer > 0. Mot legend mau chung se sai neu
khong neu ro tung transport.

**Khuyen nghi:** dung model typed rieng, vi du `BleHeaderState` va
`CloudHeaderState`, sau do map tung model sang mau/accessibility description.

### R5 - `idle` co the giu BLE o `PAUSED` vo thoi han (muc do: trung binh)

`isBleMeshTransitional()` chi kiem tra `lastAdaptiveState == "idle"` ma khong
kiem tra `lastAdaptiveStateAtMs`. Khi state nay khong duoc thay doi, icon co the
o cam mai mai, bat chap timestamp scan/advertise da cu.

**Khuyen nghi:** ap dung TTL cho adaptive state hoac clear state khi mesh stop /
recover thanh cong.

### R6 - Thieu test cho adapter state cua App bar (muc do: trung binh)

Co unit test cho `RelaySyncUiEvaluator`, nhung chua co test cho
`StatusIndicatorUpdater.bleStateForUi()`, mapping mau, refresh signals, click/
hold semantics, hay prompt cooldown. Cac logic nay hien nam trong Android
singleton nen kho unit test.

**Khuyen nghi:** tach pure evaluator cho BLE header va Cloud header, inject
clock/runtime snapshot, roi them test bang quyet dinh state.

### R7 - `updateHeader()` co early return theo icon BLE (muc do: thap)

Neu mot layout tai su dung `StatusIndicatorUpdater` co Cloud icon nhung khong
co BLE icon, Cloud icon se khong duoc refresh do ham return som. Day chua la
loi voi shared header hien tai, nhung la coupling de gay regression khi tai su
dung layout.

**Khuyen nghi:** resolve tung icon doc lap va update icon ton tai.

## 8. Contract UI de xuat

Neu muc tieu la de nguoi dung hieu nhanh, contract nen la:

| Transport | Xam | Cam | Xanh | Do |
| --- | --- | --- | --- | --- |
| BLE | Disabled, permission/hardware requirement chua dap ung | Starting, recovering, hoac khong co peer (neu van muon hien peer state) | Transport healthy; peer count hien rieng | Blocked/stalled sau khi retry/recovery that bai |
| Cloud | Setting off, Internet off, hoac capability off | Cau hinh duong relay nhung chua xac minh sync | Recent successful sync | Recent push/pull failure, auth/config error, hoac relay unreachable |

Trong contract nay, App bar icon la luong transport; Group Chat connectivity
banner la luong kha nang lien lac cua group. Khong nen bat nguoi dung suy ra
trang thai group tu mot icon transport don le.

## 9. File tham chieu

- `android/app/src/main/res/layout/include_app_header.xml`
- `android/app/src/main/java/app/peermesh/StatusIndicatorUpdater.kt`
- `android/app/src/main/java/app/peermesh/ble/BleForegroundService.kt`
- `android/app/src/main/java/app/peermesh/ble/BleMeshStatus.kt`
- `android/app/src/main/java/app/peermesh/relay/RelaySyncUiEvaluator.kt`
- `android/app/src/main/java/app/peermesh/relay/RelaySyncLifecycleCoordinator.kt`
- `android/app/src/main/java/app/peermesh/relay/RelaySyncSettings.kt`
- `android/app/src/test/java/app/peermesh/relay/RelaySyncUiEvaluatorTest.kt`

## 10. Migration sau khi bo App bar controls

Ban UI moi khong con cho nguoi dung stop/restart Nearby hoac toggle Cloud Sync.
De tranh install cu bi ket o trang thai tat do control App bar da bi bo, app chay
migration mot lan trong `PeerMeshApp.onCreate()`:

- `ble_mesh_user_enabled` duoc dat lai `true` va danh dau
  `automatic_nearby_migrated`.
- `relay_sync_enabled` duoc dat lai `true` va danh dau
  `automatic_cloud_sync_migrated`.

Hai field cu van duoc giu de tuong thich lifecycle va worker hien tai, nhung
khong con la production user controls. Runtime van tu quyet dinh co the chay hay
khong dua tren permission, Bluetooth, Location Services (Android <= 11),
Internet validated, capability va process lifecycle.

## 11. Trang thai sau refactor UI

App header production da bo `header_status_ble`, `header_status_relay` va toan
bo touch/long-press control cua chung. `StatusIndicatorUpdater` khong con tim
header views hay toggle transport; no chi giu auto-resume theo system readiness,
prompt he thong va legacy state cho Statistics.

Group Chat dung evaluator typed rieng:

- `NearbyTransportHealth`: `Blocked`, `Starting`, `Healthy`, `Stalled`.
- `OnlinePathState`: `Unavailable`, `AvailableUnverified`, `Healthy`.
- `GroupChatConnectivityState`: kha nang lien lac cua group, khong suy ra tu
  mau icon cu.

Banner duoi app bar hien primary/secondary text va action phuc hoi khi can.
Nearby healthy voi 0 peer hien `Nearby ready`, khong hien red error. Online path
configured nhung chua co recent success chi hien `Online sync is available`,
khong khang dinh da sync thanh cong.
