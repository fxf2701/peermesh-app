package app.peermesh.transport.ble

import android.bluetooth.le.ScanSettings
import android.bluetooth.le.AdvertiseSettings
import java.util.UUID

object BleConfig {
    // Fixed service UUID for mesh payloads; keep consistent across clients.
    val serviceUuid: UUID = UUID.fromString("e268f3c1-5adb-4412-be04-f4a04f9b3d1a")
    val characteristicUuid: UUID = UUID.fromString("e268f3c1-5adb-4412-be04-f4a04f9b3d1b")
    // Conservative payload limit for BLE GATT chunk (fits default MTU 23).
    const val defaultGattPayloadBytes = 20
    // Preferred MTU for GATT (ATT payload = MTU - 3).
    const val desiredMtu = 185
    // Upper bound for negotiated chunk size (safety cap across devices).
    const val maxChunkBytesCap = 180
    const val chunkCacheTtlSeconds = 10L
    @Volatile var scanFilterEnabled: Boolean = true
    @Volatile var advertiseSingleChunk: Boolean = false
    const val manufacturerId: Int = 0x1234
    @Volatile var useManufacturerData: Boolean = false
    @Volatile var advertisedDeviceId: ByteArray? = null
    @Volatile var debugCorruptNextChunk: Boolean = false
    @Volatile var scanMode: Int = ScanSettings.SCAN_MODE_BALANCED
    @Volatile var scanReportDelayMs: Long = 2000L
    @Volatile var advertiseMode: Int = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    @Volatile var advertiseTxPower: Int = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
    @Volatile var forwardOpaqueMode: Boolean = false
    @Volatile var useNordicBackend: Boolean = false
    // Rollback-friendly guard: only recover orphan in-progress batch as complete
    // when transport already wrote all payload bytes.
    @Volatile var enableOrphanRecoverOnFullBytes: Boolean = true
}
