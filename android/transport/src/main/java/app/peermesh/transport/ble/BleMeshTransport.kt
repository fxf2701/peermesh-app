package app.peermesh.transport.ble

import android.annotation.SuppressLint
import app.peermesh.transport.ble.dtn.DtnControlRoutePolicy
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import app.peermesh.core.encoding.Base64Url
import app.peermesh.transport.adapter.MeshDispatchJob
import app.peermesh.transport.adapter.MeshDispatchJobSource
import app.peermesh.transport.adapter.MeshTrafficHint
import app.peermesh.transport.adapter.MeshTransport
import app.peermesh.transport.adapter.MeshTransportDiagnosticsListener
import app.peermesh.transport.adapter.MeshSendAdmissionResult
import app.peermesh.transport.adapter.NearbyForegroundDiagnosticLabels
import app.peermesh.transport.adapter.NearbyRoomAdvertisementProvider
import app.peermesh.transport.adapter.NearbyRoomDiscoveryListener
import app.peermesh.core.transport.EnvelopeReceiver
import app.peermesh.core.transport.EnvelopeReceiveResult
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.AppBatchEnvelope
import app.peermesh.core.model.EventEnvelope
import app.peermesh.security.crypto.AppBatchCrypto
import app.peermesh.transport.ble.client.BleClientBackend
import app.peermesh.transport.ble.client.BleClientCallbacks
import app.peermesh.transport.ble.client.BleClientConnection
import app.peermesh.transport.ble.client.GattClientBackend
import app.peermesh.transport.ble.dtn.DtnControlRouteResult
import app.peermesh.transport.ble.dtn.DtnControlRouter
import app.peermesh.transport.ble.dtn.DtnControlCodec
import app.peermesh.transport.ble.dtn.DtnControlQueuePolicy
import app.peermesh.transport.ble.dtn.DtnControlPacket
import app.peermesh.transport.ble.dtn.DtnControlTypes
import app.peermesh.transport.ble.dtn.DtnDiagnosticEvent
import app.peermesh.transport.ble.dtn.DtnDiagnosticsSink
import app.peermesh.transport.ble.dtn.DtnRepairSuppressionStore
import app.peermesh.transport.ble.dtn.DtnSyncEngine
import java.util.ArrayDeque
import java.util.LinkedHashSet
import java.util.UUID
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private object LocalDeviceIdentity {
    private const val PREFS_NAME = "ble_mesh_identity"
    private const val KEY_DEVICE_ID = "local_device_id"
    private const val DEVICE_ID_BYTES = 8

    fun normalize(value: String?): String? {
        return value
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.length == DEVICE_ID_BYTES * 2 && it.all { ch -> ch in '0'..'9' || ch in 'A'..'F' } }
    }

    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        normalize(prefs.getString(KEY_DEVICE_ID, null))?.let { return it }

        val bytes = ByteArray(DEVICE_ID_BYTES)
        SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { byte -> "%02X".format(byte) }
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun persist(context: Context, deviceId: String) {
        val normalized = normalize(deviceId) ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_ID, normalized)
            .apply()
    }
}

@SuppressLint("MissingPermission", "HardwareIds", "ObsoleteSdkInt")
class BleMeshTransport(
    context: Context,
    private val receiver: EnvelopeReceiver,
    private val clientBackend: BleClientBackend = GattClientBackend(),
    private val dispatchJobSource: MeshDispatchJobSource? = null,
    dtnSyncEngine: DtnSyncEngine? = null,
    private val dtnActiveGidProvider: (() -> String?)? = null,
    private val dtnSyncGidProvider: (() -> List<String>)? = null,
    private val dtnDiagnostics: DtnDiagnosticsSink? = null,
    private val opaqueRelayStore: OpaqueRelayStore? = null,
    private val dtnRepairSuppressionStore: DtnRepairSuppressionStore? = null,
) : MeshTransport {
    private enum class PipelineLabel {
        ENQUEUE,
        WRITE_NEXT,
        CONNECT,
        CLEANUP,
        RETRY,
    }
    private enum class MeshMode {
        FULL_RELAY,
        RECEIVE_ONLY,
    }
    private enum class CleanupMode {
        TIMEOUT_CHECK,
        FORCE,
    }
    private data class RelayCompensationState(
        var firstAcceptedAtMs: Long,
        var attemptCount: Int = 0,
        val targetAddresses: LinkedHashSet<String> = LinkedHashSet(),
    )
    private data class BatchForwardState(
        val batchId: String,
        val payload: ByteArray,
        val sourceAddress: String,
        var sourceDeviceId: String?,
        val originDeviceIds: MutableSet<String>,
        var firstAcceptedAtMs: Long,
        var executedAttempts: Int = 0,
        var sourceResolveRetries: Int = 0,
        val targetAddresses: LinkedHashSet<String> = LinkedHashSet(),
        val attemptsByDeviceId: ConcurrentHashMap<String, Int> = ConcurrentHashMap(),
        val terminalDeviceIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )
    private data class DelayedRelayState(
        val key: String,
        val batchId: String,
        val payload: ByteArray,
        val sourceAddress: String,
        val sourceDeviceId: String?,
        val originDeviceIds: Set<String>,
        val targetDeviceId: String,
        var createdAtMs: Long,
        var fireAtMs: Long,
        var deferredBusyCount: Int = 0,
        var fired: Boolean = false,
    )
    private data class DtnHelloSessionContext(
        val deviceId: String,
        val address: String,
        val gid: String,
        val sessionId: String,
        val startedAtMs: Long,
    )
    private val appContext = context.applicationContext
    private val reassembler = Chunking.Reassembler(ttlSeconds = BleConfig.chunkCacheTtlSeconds)
    private val chunkDeduper = ChunkDeduper(ttlSeconds = BleConfig.chunkCacheTtlSeconds)
    private val envelopeDeduper = EnvelopeDeduper(ttlSeconds = DEDUPE_TTL_SECONDS)
    private val inboundEventDeduper = EventIdDeduper(ttlSeconds = DEDUPE_TTL_SECONDS)
    private val forwardDeduper = EventIdDeduper(ttlSeconds = DEDUPE_TTL_SECONDS)
    private val forwardThrottle = ForwardThrottle(maxForwards = 30, windowSeconds = 60)
    private val relayCompensationStateByKey = ConcurrentHashMap<String, RelayCompensationState>()
    private val batchForwardStateById = ConcurrentHashMap<String, BatchForwardState>()
    private val delayedRelayStateByKey = ConcurrentHashMap<String, DelayedRelayState>()
    private val opaqueRetryCapsulesByBatchAndDevice = ConcurrentHashMap<String, MutableSet<String>>()
    private val hotOpaqueRetryTokensByDevice = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val opaqueRetryInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastOpaqueRetryAtMs: Long = 0L
    private val workerThread = HandlerThread("BleMeshTransportWorker").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val advertiseSupervisorLock = Any()
    @Volatile private var meshMode = MeshMode.FULL_RELAY
    private var advertiseRetryIndex = 0
    private var advertiseRetryRunnable: Runnable? = null
    private var advertiseRecheckRunnable: Runnable? = null
    private var discoveryBurstToken = 0L
    private val floodControl = FloodControl()
    private var baseScanMode = BleConfig.scanMode
    private var baseScanReportDelayMs = BleConfig.scanReportDelayMs
    private var baseAdvertiseMode = BleConfig.advertiseMode
    private var baseAdvertiseTxPower = BleConfig.advertiseTxPower
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val hasBleFeature =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    private val advertiser: BluetoothLeAdvertiser? = adapter?.bluetoothLeAdvertiser
    private val scanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner
    private val serviceUuid = ParcelUuid(BleConfig.serviceUuid)
    private val characteristicUuid: UUID = BleConfig.characteristicUuid
    private val dtnSyncEngine = dtnSyncEngine
    private val dtnControlRouter = dtnSyncEngine?.let { DtnControlRouter(it, dtnDiagnostics) }
    private val dtnHelloLastSentAtByDeviceId = ConcurrentHashMap<String, Long>()
    private val dtnHelloSessionByDeviceAndGid = ConcurrentHashMap<String, DtnHelloSessionContext>()
    @Volatile private var nearbyRoomAdvertisementProvider: NearbyRoomAdvertisementProvider? = null
    @Volatile private var nearbyRoomDiscoveryListener: NearbyRoomDiscoveryListener? = null
    @Volatile private var nearbyForegroundMode = false
    @Volatile private var nearbyForegroundReason = "NORMAL"
    private data class ForegroundDrain(val itemId: String, val batchId: String?, val startedAtMs: Long)
    private val foregroundDrainByAddress = ConcurrentHashMap<String, ForegroundDrain>()
    private val pausedAtMsByItemId = ConcurrentHashMap<String, Long>()
    private val lastForegroundDequeueLogAtMsByAddress = ConcurrentHashMap<String, Long>()
    private val dtnForegroundRetryAttemptsBySessionKey = ConcurrentHashMap<String, Int>()
    private val dtnForegroundRetryScheduledKeys = ConcurrentHashMap.newKeySet<String>()

    private fun foregroundRuntimeState(): String =
        nearbyForegroundReason.substringAfter("_to_", nearbyForegroundReason).substringBefore(':')

    private fun trafficClass(packet: PendingPacket): String = when {
        packet.trafficHint == MeshTrafficHint.NEARBY_ROOM_KEY_BOOTSTRAP -> "NEARBY_KEY_BOOTSTRAP"
        packet.trafficHint == MeshTrafficHint.NEARBY_ROOM_CONTROL -> "NEARBY_CONTROL"
        packet.trafficHint == MeshTrafficHint.NEARBY_ROOM_DATA -> "NEARBY_DATA"
        packet.dtnControlType != null -> "NON_NEARBY_DTN"
        packet.batchId != null -> "NON_NEARBY_APP_BATCH"
        else -> "NON_NEARBY_RELAY"
    }

    private fun trafficClass(hint: MeshTrafficHint, dtn: Boolean, batch: Boolean): String = when {
        hint == MeshTrafficHint.NEARBY_ROOM_KEY_BOOTSTRAP -> "NEARBY_KEY_BOOTSTRAP"
        hint == MeshTrafficHint.NEARBY_ROOM_CONTROL -> "NEARBY_CONTROL"
        hint == MeshTrafficHint.NEARBY_ROOM_DATA -> "NEARBY_DATA"
        dtn -> "NON_NEARBY_DTN"
        batch -> "NON_NEARBY_APP_BATCH"
        else -> "NON_NEARBY_RELAY"
    }

    private fun foregroundDiagnostic(label: String, message: String) {
        diagnosticsListener?.onNearbyForegroundDiagnostic(label, message)
    }

    private fun resolveLocalAddress(): String {
        return try {
            val localAdapter = adapter ?: return ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val granted = appContext.checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return ""
            }
            localAdapter.address.orEmpty()
        } catch (se: SecurityException) {
            ""
        }
    }

    private var scanCallbacks = 0L
    private var scanNoRecord = 0L
    private var scanResults = 0L
    private var scanMatched = 0L
    private var lastScanLogAtMs = 0L
    private var lastScanStartAtMs = 0L
    private var lastScanCallbackAtMs = 0L
    private var lastScanAutoRecoverAtMs = 0L
    private var autoRecoverStreak = 0
    private var lastWriteLogAtMs = 0L
    @Volatile private var scanStarted = false
    private var running = false

    private val localAddress: String = resolveLocalAddress()
    @Volatile private var localDeviceId: String = LocalDeviceIdentity.getOrCreate(appContext)
    @Volatile private var localDeviceIdBytes: ByteArray = deviceIdHexToBytes(localDeviceId)

    private fun deviceIdHexToBytes(deviceId: String): ByteArray {
        return deviceId.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun syncLocalDeviceIdFromDtnPeerId(peerId: String?, reason: String) {
        val normalized = LocalDeviceIdentity.normalize(peerId) ?: return
        if (normalized == localDeviceId) return
        val previous = localDeviceId
        localDeviceId = normalized
        localDeviceIdBytes = deviceIdHexToBytes(normalized)
        LocalDeviceIdentity.persist(appContext, normalized)
        Log.i(TAG, "local_device_identity synced reason=$reason previous=$previous stable=$normalized")
        diagnosticsListener?.onAdvertisedDeviceId(normalized)
        if (running && advertiseCallback != null) {
            stopAdvertising()
            startAdvertising(force = true)
        }
    }

    private fun extractLocalPeerIdFromDtnControlPayload(payload: ByteArray): String? {
        val text = runCatching { payload.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val keys = listOf("peer_id", "source_peer_id", "sender_peer_id", "from_peer_id", "device_id", "d")
        for (key in keys) {
            val regex = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"([0-9A-Fa-f]{16})\"")
            val value = regex.find(text)?.groupValues?.getOrNull(1)
            val normalized = LocalDeviceIdentity.normalize(value)
            if (normalized != null) return normalized
        }
        return null
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null

    private val lastSeenDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val lastSeenAtMs = ConcurrentHashMap<String, Long>()
    private val lastSeenRssi = ConcurrentHashMap<String, Int>()
    private val lastSeenDeviceId = ConcurrentHashMap<String, String>()
    private val lastSeenAddrByDeviceId = ConcurrentHashMap<String, String>()
    private val lastSeenIdAtMs = ConcurrentHashMap<String, Long>()
    // Canonical identity map for physical peers whose advertised id changed after
    // reset/recreate. Keys are stale ids observed in queues/candidate pools; values
    // are the latest canonical DTN peer id we should target.
    private val canonicalDeviceIdAlias = ConcurrentHashMap<String, String>()
    private val lastActiveAtMsByDeviceId = ConcurrentHashMap<String, Long>()
    private val lastGraceLogAtMsByDeviceId = ConcurrentHashMap<String, Long>()
    private val lastTargetByDeviceId = ConcurrentHashMap<String, String>()
    private val dataWarmupUntilByAddress = ConcurrentHashMap<String, Long>()
    private val lastTargetSelectionAtByDeviceId = ConcurrentHashMap<String, Long>()
    private val freshDeliveredAtMsByDeviceId = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val freshEventCreatedAtMs = ConcurrentHashMap<String, Long>()
    private val batchAttemptSeqGenerator = AtomicLong(1L)
    private val queuedNoWriteRebindCountByBatchDevice = ConcurrentHashMap<String, Int>()
    private data class PendingPacket(
        val payload: ByteArray,
        val isAck: Boolean,
        val itemId: String,
        val deviceId: String,
        val trafficHint: MeshTrafficHint = MeshTrafficHint.DEFAULT,
        val nearbyForegroundProtected: Boolean = false,
        val dtnControlType: String? = null,
        val dtnControlGid: String? = null,
        val dtnControlSessionId: String? = null,
        val payloadBytes: Int,
        val totalChunks: Int,
        val chunkBytes: Int,
        val batchId: String? = null,
        val batchEventIds: List<String> = emptyList(),
        val batchAttemptSeq: Long? = null,
    )
    private enum class ItemSendStatus {
        IN_PROGRESS,
        SENT,
        FAILED,
    }
    private data class ItemSendState(
        val itemId: String,
        val deviceId: String,
        val payloadBytes: Int,
        var totalChunks: Int,
        var writtenChunks: Int = 0,
        var writtenBytes: Int = 0,
        var failed: Boolean = false,
        var startedAtMs: Long,
        var lastProgressAtMs: Long,
        var completedAtMs: Long? = null,
        var status: ItemSendStatus = ItemSendStatus.IN_PROGRESS,
    )
    private data class BatchPayloadMeta(
        val batchId: String,
        val eventIds: List<String>,
        val createdAtMs: Long,
    )

    private data class DtnControlSessionMeta(
        val gid: String?,
        val sessionId: String?,
    )
    private data class BatchSendState(
        val batchId: String,
        val deviceId: String,
        val eventIds: List<String>,
        val createdAtMs: Long,
        val payloadBytes: Int,
        var totalChunks: Int,
        var writtenChunks: Int = 0,
        var writtenBytes: Int = 0,
        var failed: Boolean = false,
        var startedAtMs: Long,
        var lastProgressAtMs: Long,
        var hasWriteAttempted: Boolean = false,
        var completedAtMs: Long? = null,
        var status: ItemSendStatus = ItemSendStatus.IN_PROGRESS,
        val attemptSeq: Long,
    )
    private data class DeviceCandidate(
        val deviceId: String,
        val address: String,
        val seenAtMs: Long,
        val rssi: Int,
    )
    private data class AddressState(
        var connectState: Int = CONNECT_STATE_DISCONNECTED,
        var lastFailureReason: String? = null,
        var failureCount: Int = 0,
        var nextConnectAllowedAt: Long = 0L,
        var lastConnectAttemptAt: Long = 0L,
        var writeInFlight: Boolean = false,
    )
    private enum class ItemState {
        ENQUEUED,
        CONNECTING,
        BACKOFF,
        DONE,
    }
    private val pendingByDevice = ConcurrentHashMap<String, ArrayDeque<PendingPacket>>()
    private val pendingQueueLock = Any()
    private val schedulerLock = Any()
    private val pipelinePendingLabels = ConcurrentHashMap<String, LinkedHashSet<PipelineLabel>>()
    private val stagedEnqueueByAddress = ConcurrentHashMap<String, ArrayDeque<PendingPacket>>()
    private val heldEnqueueByAddress = ConcurrentHashMap<String, ArrayDeque<PendingPacket>>()
    private val pendingCleanupModeByAddress = ConcurrentHashMap<String, CleanupMode>()
    private val pendingRetryReasonByAddress = ConcurrentHashMap<String, String>()
    private val pendingCleanupReasonByAddress = ConcurrentHashMap<String, String>()
    private val localDisconnectReasonByAddress = ConcurrentHashMap<String, String>()
    private val pipelineMainQueue = ArrayDeque<String>()
    private val pipelineInMainQueue = HashSet<String>()
    private val pipelineBusy = HashSet<String>()
    @Volatile private var pipelineDrainScheduled = false
    private val dataSinceAckByDevice = ConcurrentHashMap<String, Int>()
    private val mtuByDevice = ConcurrentHashMap<String, Int>()
    private val maxChunkByDevice = ConcurrentHashMap<String, Int>()
    private val clientByDevice = ConcurrentHashMap<String, BleClientConnection>()
    private val connectingClient = ConcurrentHashMap<String, BleClientConnection>()
    private val characteristicByDevice = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val connecting = ConcurrentHashMap<String, Boolean>()
    private val addressStateByAddress = ConcurrentHashMap<String, AddressState>()
    private val cooldownUntilByAddress = ConcurrentHashMap<String, Long>()
    private val cooldownUntilByDeviceId = ConcurrentHashMap<String, Long>()
    private val deviceIdDwellUntil = ConcurrentHashMap<String, Long>()
    private val deviceIdRotationCooldownUntil = ConcurrentHashMap<String, Long>()
    private val deviceIdRotationRecoverUntil = ConcurrentHashMap<String, Long>()
    private val deviceIdRotationRecoverAddress = ConcurrentHashMap<String, String>()
    private val deviceIdQuietUntil = ConcurrentHashMap<String, Long>()
    private val last133AtByDeviceId = ConcurrentHashMap<String, Long>()
    private val lastWriteFailed133AtByAddress = ConcurrentHashMap<String, Long>()
    private val post133RecoveryUntilByDeviceId = ConcurrentHashMap<String, Long>()
    private val connectStartedAtMs = ConcurrentHashMap<String, Long>()
    private val lastProgressAtMs = ConcurrentHashMap<String, Long>()
    private val connectRetryScheduledByAddress = ConcurrentHashMap<String, Boolean>()
    private val itemStateByKey = ConcurrentHashMap<String, ItemState>()
    private val itemBackoffUntilByKey = ConcurrentHashMap<String, Long>()
    private val activeItemKeyByAddress = ConcurrentHashMap<String, String>()
    private val activePendingPacketByAddress = ConcurrentHashMap<String, PendingPacket>()
    private val activeMeshEnvelopeItemIdByAddress = ConcurrentHashMap<String, String>()
    private val activeDtnControlItemIdByAddress = ConcurrentHashMap<String, String>()
    private val pulledDispatchJobByAddress =
        ConcurrentHashMap<String, MeshDispatchJob>()
    private val itemSendStateByKey = ConcurrentHashMap<String, ItemSendState>()
    private val batchSendStateByKey = ConcurrentHashMap<String, BatchSendState>()
    private val lastInProgressStuckWatchdogLogAtMsByKey = ConcurrentHashMap<String, Long>()
    private val lastEnqueueLabelAtMsByAddress = ConcurrentHashMap<String, Long>()
    private val noJobSkipStreakByDeviceId = ConcurrentHashMap<String, Int>()
    private val lastNoJobSkipAtMsByDeviceId = ConcurrentHashMap<String, Long>()
    @Volatile private var lastDataActivityAtMs: Long = 0L
    private val lastSingleProbeAttemptAtMsByAddress = ConcurrentHashMap<String, Long>()
    private val targetPoolLock = Any()
    private val mainTargetDeviceIds = LinkedHashSet<String>()
    private val potentialTargetDeviceIds = LinkedHashSet<String>()
    @Volatile private var consecutiveNoCandidateSelections = 0
    @Volatile private var lastNoCandidateRecoveryAtMs = 0L
    @Volatile private var reducedGattCapUntilMs = 0L
    private var lastHardResetAtMs = 0L
    @Volatile private var lastServerStartedAtMs: Long = 0L
    @Volatile private var lastServerConnAtMs: Long = 0L
    @Volatile private var lastRecvChunkAtMs: Long = 0L
    @Volatile private var lastTxChunkAtMs: Long = 0L
    @Volatile private var lastStartStuckResetAtMs: Long = 0L
    @Volatile private var lastPeerFairnessLogAtMs: Long = 0L
    @Volatile private var lastConnectCapBlockedLogAtMs: Long = 0L
    @Volatile private var lastCandidatePoolSnapshotAtMs: Long = 0L
    @Volatile private var lastCandidatePoolSnapshotSignature: String = ""
    @Volatile private var lastTargetSelectionDecisionAtMs: Long = 0L
    @Volatile private var lastTargetSelectionDecisionSignature: String = ""
    @Volatile private var lastNearbyBreakdownAtMs: Long = 0L
    @Volatile private var lastNearbyBreakdownSignature: String = ""

    private fun itemSendStateKey(itemId: String, deviceId: String): String = "$itemId|$deviceId"
    private fun batchSendStateKey(batchId: String, deviceId: String): String = "$batchId|$deviceId"

    private fun ensureItemSendState(
        itemId: String,
        deviceId: String,
        payloadBytes: Int,
        totalChunks: Int,
        nowMs: Long,
    ): ItemSendState {
        val key = itemSendStateKey(itemId, deviceId)
        return itemSendStateByKey.compute(key) { _, existing ->
            when {
                existing == null -> ItemSendState(
                    itemId = itemId,
                    deviceId = deviceId,
                    payloadBytes = payloadBytes,
                    totalChunks = totalChunks,
                    startedAtMs = nowMs,
                    lastProgressAtMs = nowMs,
                )
                else -> {
                    ItemSendState(
                        itemId = itemId,
                        deviceId = deviceId,
                        payloadBytes = payloadBytes,
                        totalChunks = totalChunks,
                        startedAtMs = nowMs,
                        lastProgressAtMs = nowMs,
                    )
                }
            }
        }!!
    }

    private fun ensureBatchSendState(
        batchId: String,
        deviceId: String,
        eventIds: List<String>,
        createdAtMs: Long,
        payloadBytes: Int,
        totalChunks: Int,
        nowMs: Long,
    ): BatchSendState {
        val key = batchSendStateKey(batchId, deviceId)
        return batchSendStateByKey.compute(key) { _, existing ->
            val nextAttemptSeq = batchAttemptSeqGenerator.incrementAndGet()
            when {
                existing == null -> BatchSendState(
                    batchId = batchId,
                    deviceId = deviceId,
                    eventIds = eventIds,
                    createdAtMs = createdAtMs,
                    payloadBytes = payloadBytes,
                    totalChunks = totalChunks,
                    startedAtMs = nowMs,
                    lastProgressAtMs = nowMs,
                    attemptSeq = nextAttemptSeq,
                )
                else -> {
                    Log.i(
                        TAG,
                        "mesh attempt rollover batch=$batchId device=$deviceId old_attempt=${existing.attemptSeq} new_attempt=$nextAttemptSeq",
                    )
                    BatchSendState(
                        batchId = batchId,
                        deviceId = deviceId,
                        eventIds = eventIds,
                        createdAtMs = createdAtMs,
                        payloadBytes = payloadBytes,
                        totalChunks = totalChunks,
                        startedAtMs = nowMs,
                        lastProgressAtMs = nowMs,
                        attemptSeq = nextAttemptSeq,
                    )
                }
            }
        }!!
    }

    private fun hasPendingTransportWorkForItemDevice(
        itemId: String,
        deviceId: String,
        currentPacket: PendingPacket? = null,
    ): Boolean {
        val hasActiveOtherPacket = activePendingPacketByAddress.values.any { packet ->
            packet.itemId == itemId &&
                packet.deviceId == deviceId &&
                packet !== currentPacket
        }
        if (hasActiveOtherPacket) return true
        if (hasHeldPacketsForItemDevice(itemId, deviceId)) return true
        if (queuedPacketCountForItemDevice(itemId, deviceId) > 0) return true
        if (stagedPacketCountForItemDevice(itemId, deviceId) > 0) return true
        return false
    }

    private fun removeActivePendingPacket(address: String, reason: String): PendingPacket? {
        val packet = activePendingPacketByAddress.remove(address) ?: return null
        diagnosticsListener?.onMeshActivePacketReleased(
            address = address,
            reason = reason,
            itemId = packet.itemId,
            batchId = packet.batchId,
            deviceId = packet.deviceId,
            isAck = packet.isAck,
            payloadBytes = packet.payloadBytes,
            totalChunks = packet.totalChunks,
            attemptSeq = packet.batchAttemptSeq,
        )
        return packet
    }

    private fun logBatchStateTransition(
        snapshot: BatchSendState,
        transition: String,
        reason: String?,
    ) {
        val queuedCount = queuedPacketCountForItemDevice(snapshot.batchId, snapshot.deviceId)
        val stagedCount = stagedPacketCountForItemDevice(snapshot.batchId, snapshot.deviceId)
        val hasHeld = hasHeldPacketsForItemDevice(snapshot.batchId, snapshot.deviceId)
        val hasActivePacket = activePendingPacketByAddress.values.any { packet ->
            packet.batchId == snapshot.batchId && packet.deviceId == snapshot.deviceId
        }
        diagnosticsListener?.onBleBatchStateTransition(
            batchId = snapshot.batchId,
            deviceId = snapshot.deviceId,
            transition = transition,
            reason = reason,
            attemptSeq = snapshot.attemptSeq,
            writtenChunks = snapshot.writtenChunks,
            totalChunks = snapshot.totalChunks,
            writtenBytes = snapshot.writtenBytes,
            payloadBytes = snapshot.payloadBytes,
            queuedCount = queuedCount,
            stagedCount = stagedCount,
            hasHeld = hasHeld,
            hasActivePacket = hasActivePacket,
            waitingDevices = waitingDevicesCount(),
            connectCapBlocked = !canOpenNewGatt(),
        )
    }

    private fun onPacketWriteSuccess(packet: PendingPacket, nowMs: Long) {
        val key = itemSendStateKey(packet.itemId, packet.deviceId)
        var justCompleted = false
        val snapshot = itemSendStateByKey.computeIfPresent(key) { _, existing ->
            if (existing.status == ItemSendStatus.SENT) {
                if (!hasPendingTransportWorkForItemDevice(existing.itemId, existing.deviceId, packet)) {
                    return@computeIfPresent existing
                }
                existing.status = ItemSendStatus.IN_PROGRESS
                existing.completedAtMs = null
                existing.failed = false
                existing.writtenChunks = 0
                existing.writtenBytes = 0
                existing.startedAtMs = nowMs
            }
            val packetTotalChunks = packet.totalChunks.coerceAtLeast(1)
            val minimumExpectedChunks = (existing.writtenChunks + 1).coerceAtLeast(1)
            val syncedTotalChunks = packetTotalChunks.coerceAtLeast(minimumExpectedChunks)
            if (existing.totalChunks != syncedTotalChunks) {
                val oldTotalChunks = existing.totalChunks
                Log.i(
                    TAG,
                    "mesh chunk geometry update item=${existing.itemId} device=${existing.deviceId} total_chunks=${oldTotalChunks}=>${syncedTotalChunks} packet_total=${packet.totalChunks}",
                )
                diagnosticsListener?.onMeshChunkGeometryUpdate(
                    scope = "item",
                    itemId = existing.itemId,
                    batchId = null,
                    deviceId = existing.deviceId,
                    oldTotalChunks = oldTotalChunks,
                    newTotalChunks = syncedTotalChunks,
                    packetTotalChunks = packet.totalChunks,
                )
                existing.totalChunks = syncedTotalChunks
            }
            existing.writtenChunks = (existing.writtenChunks + 1).coerceAtMost(existing.totalChunks)
            val packetPayloadBytes = Chunking.payloadBytesFromChunkBytes(packet.chunkBytes)
            existing.writtenBytes = (existing.writtenBytes + packetPayloadBytes).coerceAtMost(existing.payloadBytes)
            existing.lastProgressAtMs = nowMs
            if (!existing.failed &&
                existing.writtenChunks >= existing.totalChunks &&
                !hasPendingTransportWorkForItemDevice(existing.itemId, existing.deviceId, packet)
            ) {
                existing.status = ItemSendStatus.SENT
                existing.completedAtMs = nowMs
                justCompleted = true
            }
            existing
        } ?: return
        diagnosticsListener?.onBleSendProgress(
            itemId = snapshot.itemId,
            deviceId = snapshot.deviceId,
            writtenChunks = snapshot.writtenChunks,
            totalChunks = snapshot.totalChunks,
            writtenBytes = snapshot.writtenBytes,
            payloadBytes = snapshot.payloadBytes,
        )
        if (justCompleted) {
            diagnosticsListener?.onBleSendComplete(
                itemId = snapshot.itemId,
                deviceId = snapshot.deviceId,
                totalChunks = snapshot.totalChunks,
                payloadBytes = snapshot.payloadBytes,
            )
            packet.dtnControlType?.let { type ->
                diagnosticsListener?.onDtnControlWireComplete(
                    address = resolveAddressForDeviceId(packet.deviceId) ?: "unknown",
                    type = type,
                    payloadBytes = packet.payloadBytes,
                    totalChunks = packet.totalChunks,
                )
            }
            maybeQueueNextDtnSessionTask(packet)
        }
        onBatchPacketWriteSuccess(packet, nowMs)
    }

    private fun maybeQueueNextDtnSessionTask(packet: PendingPacket) {
        if (packet.dtnControlType == null) return
        val gid = packet.dtnControlGid ?: return
        val sessionId = packet.dtnControlSessionId ?: return
        val nextPacket = dtnSyncEngine?.onSessionTaskCompleted(
            peerId = packet.deviceId,
            gid = gid,
            sessionId = sessionId,
        ) ?: return
        if (nearbyForegroundMode) {
            dtnSyncEngine?.deferActiveSessionTask(packet.deviceId, gid, sessionId)
            dtnDiagnostics?.log(
                DtnDiagnosticEvent(
                    label = "DTN ${nextPacket.type} deferred",
                    type = nextPacket.type,
                    direction = "deferred",
                    peerId = packet.deviceId,
                    gid = gid,
                    sessionId = sessionId,
                    idCount = nextPacket.ids.size,
                    dataCount = nextPacket.data.size,
                    reason = "nearby_foreground session_task_followup retry_consumed=false",
                )
            )
            return
        }
        val encoded = DtnControlCodec.encode(nextPacket)
        val address = resolveAddressForDeviceId(packet.deviceId) ?: return
        val queued = broadcastEnvelopeToTargets(
            blob = encoded,
            targetAddresses = listOf(address),
            preferDtnFastPath = true,
        )
        dtnDiagnostics?.log(
            DtnDiagnosticEvent(
                label = "DTN ${nextPacket.type} ${if (queued) "sent" else "send_failed"}",
                type = if (nextPacket.type == DtnControlTypes.DATA && nextPacket.carrierData.isNotEmpty()) {
                    "carrier_data"
                } else {
                    nextPacket.type
                },
                direction = if (queued) "sent" else "send_failed",
                peerId = packet.deviceId,
                gid = gid,
                sessionId = sessionId,
                idCount = nextPacket.ids.size,
                dataCount = nextPacket.data.size,
                reason = if (queued) "session_task_followup" else "enqueue_failed session_task_followup",
            )
        )
    }

    private fun onBatchPacketWriteSuccess(packet: PendingPacket, nowMs: Long) {
        val batchId = packet.batchId ?: return
        if (packet.batchEventIds.isEmpty()) return
        val key = batchSendStateKey(batchId, packet.deviceId)
        var justCompleted = false
        val snapshot = batchSendStateByKey.computeIfPresent(key) { _, existing ->
            val packetAttemptSeq = packet.batchAttemptSeq
            if (packetAttemptSeq != null && packetAttemptSeq != existing.attemptSeq) {
                Log.i(
                    TAG,
                    "mesh stale attempt ignored batch=${existing.batchId} device=${existing.deviceId} packet_attempt=$packetAttemptSeq state_attempt=${existing.attemptSeq} phase=success",
                )
                return@computeIfPresent existing
            }
            if (existing.status == ItemSendStatus.SENT) {
                if (!hasPendingTransportWorkForItemDevice(existing.batchId, existing.deviceId, packet)) {
                    return@computeIfPresent existing
                }
                existing.status = ItemSendStatus.IN_PROGRESS
                existing.completedAtMs = null
                existing.failed = false
                existing.writtenChunks = 0
                existing.writtenBytes = 0
                existing.startedAtMs = nowMs
            }
            val packetTotalChunks = packet.totalChunks.coerceAtLeast(1)
            val minimumExpectedChunks = (existing.writtenChunks + 1).coerceAtLeast(1)
            val syncedTotalChunks = packetTotalChunks.coerceAtLeast(minimumExpectedChunks)
            if (existing.totalChunks != syncedTotalChunks) {
                val oldTotalChunks = existing.totalChunks
                Log.i(
                    TAG,
                    "mesh chunk geometry update batch=${existing.batchId} device=${existing.deviceId} total_chunks=${oldTotalChunks}=>${syncedTotalChunks} packet_total=${packet.totalChunks}",
                )
                diagnosticsListener?.onMeshChunkGeometryUpdate(
                    scope = "batch",
                    itemId = null,
                    batchId = existing.batchId,
                    deviceId = existing.deviceId,
                    oldTotalChunks = oldTotalChunks,
                    newTotalChunks = syncedTotalChunks,
                    packetTotalChunks = packet.totalChunks,
                )
                existing.totalChunks = syncedTotalChunks
            }
            existing.writtenChunks = (existing.writtenChunks + 1).coerceAtMost(existing.totalChunks)
            val packetPayloadBytes = Chunking.payloadBytesFromChunkBytes(packet.chunkBytes)
            existing.writtenBytes = (existing.writtenBytes + packetPayloadBytes).coerceAtMost(existing.payloadBytes)
            existing.lastProgressAtMs = nowMs
            if (!existing.failed &&
                existing.writtenChunks >= existing.totalChunks &&
                !hasPendingTransportWorkForItemDevice(existing.batchId, existing.deviceId, packet)
            ) {
                existing.status = ItemSendStatus.SENT
                existing.completedAtMs = nowMs
                justCompleted = true
            }
            existing
        } ?: return
        diagnosticsListener?.onBleBatchSendProgress(
            batchId = snapshot.batchId,
            deviceId = snapshot.deviceId,
            eventIds = snapshot.eventIds,
            writtenChunks = snapshot.writtenChunks,
            totalChunks = snapshot.totalChunks,
            writtenBytes = snapshot.writtenBytes,
            payloadBytes = snapshot.payloadBytes,
        )
        if (justCompleted) {
            logBatchStateTransition(
                snapshot = snapshot,
                transition = "in_progress_to_sent",
                reason = "transport_complete",
            )
            markFreshDelivered(
                deviceId = snapshot.deviceId,
                eventIds = snapshot.eventIds,
                createdAtMs = snapshot.createdAtMs,
                nowMs = nowMs,
            )
            markBatchForwardTerminal(
                batchId = snapshot.batchId,
                deviceId = snapshot.deviceId,
                delivered = true,
                reason = null,
            )
            diagnosticsListener?.onBleBatchSendComplete(
                batchId = snapshot.batchId,
                deviceId = snapshot.deviceId,
                eventIds = snapshot.eventIds,
                totalChunks = snapshot.totalChunks,
                payloadBytes = snapshot.payloadBytes,
            )
            onPulledDispatchJobTerminal(
                deviceId = snapshot.deviceId,
                batchId = snapshot.batchId,
                reason = "batch_complete",
            )
        }
    }

    private fun markSendPathStarted(packet: PendingPacket, nowMs: Long) {
        val itemKey = itemSendStateKey(packet.itemId, packet.deviceId)
        itemSendStateByKey.computeIfPresent(itemKey) { _, existing ->
            if (existing.status != ItemSendStatus.IN_PROGRESS) return@computeIfPresent existing
            if (existing.writtenChunks > 0) return@computeIfPresent existing
            existing.startedAtMs = nowMs
            existing.lastProgressAtMs = nowMs
            existing
        }

        val batchId = packet.batchId ?: return
        if (packet.batchEventIds.isEmpty()) return
        val batchKey = batchSendStateKey(batchId, packet.deviceId)
        batchSendStateByKey.computeIfPresent(batchKey) { _, existing ->
            if (existing.status != ItemSendStatus.IN_PROGRESS) return@computeIfPresent existing
            if (existing.writtenChunks > 0) return@computeIfPresent existing
            existing.startedAtMs = nowMs
            existing.lastProgressAtMs = nowMs
            existing.hasWriteAttempted = true
            existing
        }
    }

    private fun markFreshDelivered(
        deviceId: String,
        eventIds: List<String>,
        createdAtMs: Long,
        nowMs: Long,
    ) {
        if (eventIds.isEmpty()) return
        pruneFreshWindow(nowMs)
        if (createdAtMs < nowMs - FRESH_WINDOW_MS) return
        eventIds.forEach { eventId ->
            freshEventCreatedAtMs[eventId] = createdAtMs
        }
        val delivered = freshDeliveredAtMsByDeviceId.getOrPut(deviceId) { ConcurrentHashMap() }
        eventIds.forEach { eventId ->
            delivered[eventId] = createdAtMs
        }
    }

    private fun freshMissingCount(deviceId: String, nowMs: Long): Int {
        pruneFreshWindow(nowMs)
        val cutoff = nowMs - FRESH_WINDOW_MS
        val freshEvents = freshEventCreatedAtMs.filterValues { it >= cutoff }
        if (freshEvents.isEmpty()) return 0
        val delivered = freshDeliveredAtMsByDeviceId[deviceId]
        if (delivered == null) {
            // Do not boost never-scheduled candidates as "missing all fresh events".
            // This avoids fairness bias toward unrelated nearby devices.
            if (!hasOutboundHistoryForDevice(deviceId)) return 0
            return freshEvents.size
        }
        return freshEvents.keys.count { eventId -> !delivered.containsKey(eventId) }
    }

    private fun hasOutboundHistoryForDevice(deviceId: String): Boolean {
        if (batchSendStateByKey.values.any { state -> state.deviceId == deviceId }) return true
        if (itemSendStateByKey.values.any { state -> state.deviceId == deviceId }) return true
        return false
    }

    private fun noteNoJobSkip(deviceId: String, nowMs: Long, lastReason: String) {
        val previousAt = lastNoJobSkipAtMsByDeviceId[deviceId] ?: 0L
        val previous = noJobSkipStreakByDeviceId[deviceId] ?: 0
        val next = if (nowMs - previousAt <= NO_JOB_SKIP_STREAK_WINDOW_MS) previous + 1 else 1
        noJobSkipStreakByDeviceId[deviceId] = next
        lastNoJobSkipAtMsByDeviceId[deviceId] = nowMs
        val suppressedUntilMs = if (next >= NO_JOB_SKIP_STREAK_THRESHOLD) nowMs + NO_JOB_SKIP_SUPPRESS_MS else 0L
        diagnosticsListener?.onDispatchNoJobStreak(
            deviceId = deviceId,
            streak = next,
            suppressedUntilMs = suppressedUntilMs,
            lastReason = lastReason,
        )
    }

    private fun clearNoJobSkip(deviceId: String) {
        noJobSkipStreakByDeviceId.remove(deviceId)
        lastNoJobSkipAtMsByDeviceId.remove(deviceId)
    }

    private fun isRepeatedNoJobDevice(deviceId: String, nowMs: Long): Boolean {
        val streak = noJobSkipStreakByDeviceId[deviceId] ?: return false
        if (streak < NO_JOB_SKIP_STREAK_THRESHOLD) return false
        val lastAt = lastNoJobSkipAtMsByDeviceId[deviceId] ?: return false
        return nowMs - lastAt <= NO_JOB_SKIP_SUPPRESS_MS
    }

    private fun hasRelatedOutboundPathForCandidate(candidate: DeviceCandidate, nowMs: Long): Boolean {
        if (hasStrictOutboundPathForCandidate(candidate)) return true
        if (hasRecentConnectOrDiscoveryForDevice(candidate.deviceId, nowMs)) return true
        return false
    }

    private fun isDeviceInMainTargetPool(deviceId: String): Boolean {
        return synchronized(targetPoolLock) {
            mainTargetDeviceIds.contains(deviceId)
        }
    }

    private fun maybeLogCandidatePoolSnapshot(candidates: Map<String, DeviceCandidate>, nowMs: Long) {
        val (sessionPeers, mainPool, potentialPool) = synchronized(targetPoolLock) {
            val sessionIds = mainTargetDeviceIds.filter { id ->
                !isFallbackAddressId(id) && candidates[id] != null
            }
            Triple(
                sessionIds.joinToString(",").ifBlank { "none" },
                mainTargetDeviceIds.count { id -> candidates[id] != null },
                potentialTargetDeviceIds.count { id -> candidates[id] != null },
            )
        }
        val freshCount = candidates.values.count { candidate -> nowMs - candidate.seenAtMs < DEVICE_TTL_MS }
        val graceCount = (candidates.size - freshCount).coerceAtLeast(0)
        val canonicalCount = candidates.values.count { candidate ->
            !isFallbackAddressId(candidate.deviceId) && lastSeenAddrByDeviceId[candidate.deviceId] == candidate.address
        }
        val signature = "$sessionPeers|$freshCount|$graceCount|$canonicalCount|$mainPool|$potentialPool"
        val shouldLog =
            signature != lastCandidatePoolSnapshotSignature ||
                nowMs - lastCandidatePoolSnapshotAtMs >= CANDIDATE_SNAPSHOT_LOG_COOLDOWN_MS
        if (!shouldLog) return
        lastCandidatePoolSnapshotSignature = signature
        lastCandidatePoolSnapshotAtMs = nowMs
        diagnosticsListener?.onCandidatePoolSnapshot(
            sessionPeers = sessionPeers,
            fresh = freshCount,
            grace = graceCount,
            canonical = canonicalCount,
            mainPool = mainPool,
            potentialPool = potentialPool,
        )
    }

    private fun maybeLogTargetSelectionDecision(
        available: Int,
        effectiveAvailable: Int,
        probeUsed: Boolean,
        selectedIds: String,
        rejectReasonByPeer: String,
        nowMs: Long,
    ) {
        val signature = "$available|$effectiveAvailable|$probeUsed|$selectedIds|$rejectReasonByPeer"
        val shouldLog =
            signature != lastTargetSelectionDecisionSignature ||
                nowMs - lastTargetSelectionDecisionAtMs >= TARGET_SELECTION_DECISION_LOG_COOLDOWN_MS
        if (!shouldLog) return
        lastTargetSelectionDecisionSignature = signature
        lastTargetSelectionDecisionAtMs = nowMs
        diagnosticsListener?.onTargetSelectionDecision(
            available = available,
            effectiveAvailable = effectiveAvailable,
            probeUsed = probeUsed,
            selectedIds = selectedIds,
            rejectReasonByPeer = rejectReasonByPeer,
        )
    }

    private fun maybeLogNearbyCountBreakdown(
        nearbyTotal: Int,
        sessionNearby: Int,
        nonSessionNearby: Int,
        nowMs: Long,
    ) {
        val signature = "$nearbyTotal|$sessionNearby|$nonSessionNearby"
        val shouldLog =
            signature != lastNearbyBreakdownSignature ||
                nowMs - lastNearbyBreakdownAtMs >= NEARBY_BREAKDOWN_LOG_COOLDOWN_MS
        if (!shouldLog) return
        lastNearbyBreakdownSignature = signature
        lastNearbyBreakdownAtMs = nowMs
        diagnosticsListener?.onNearbyCountBreakdown(
            nearbyTotal = nearbyTotal,
            sessionNearby = sessionNearby,
            nonSessionNearby = nonSessionNearby,
        )
    }

    private fun hasStrictOutboundPathForCandidate(candidate: DeviceCandidate): Boolean {
        val address = candidate.address
        if (activePendingPacketByAddress[address]?.isAck == false) return true
        if (hasPendingDataForAddress(address)) return true
        if (hasHeldDataForAddress(address)) return true
        if (hasAnyQueuedOrHeldWorkForAddress(address)) return true
        val pulled = pulledDispatchJobByAddress[address]
        if (pulled != null && pulled.deviceId == candidate.deviceId) return true
        if (hasOutboundHistoryForDevice(candidate.deviceId)) return true
        return false
    }

    private fun pruneFreshWindow(nowMs: Long) {
        val cutoff = nowMs - FRESH_WINDOW_MS
        freshEventCreatedAtMs.entries.removeIf { it.value < cutoff }
        freshDeliveredAtMsByDeviceId.entries.removeIf { (_, delivered) ->
            delivered.entries.removeIf { it.value < cutoff }
            delivered.isEmpty()
        }
    }

    private fun onPacketWriteFailure(packet: PendingPacket, reason: String, nowMs: Long) {
        val key = itemSendStateKey(packet.itemId, packet.deviceId)
        val snapshot = itemSendStateByKey.computeIfPresent(key) { _, existing ->
            if (existing.status != ItemSendStatus.IN_PROGRESS) return@computeIfPresent existing
            existing.failed = true
            existing.status = ItemSendStatus.FAILED
            existing.lastProgressAtMs = nowMs
            existing.completedAtMs = nowMs
            existing
        } ?: return
        diagnosticsListener?.onBleSendFailed(
            itemId = snapshot.itemId,
            deviceId = snapshot.deviceId,
            writtenChunks = snapshot.writtenChunks,
            totalChunks = snapshot.totalChunks,
            writtenBytes = snapshot.writtenBytes,
            payloadBytes = snapshot.payloadBytes,
            reason = reason,
        )
        packet.dtnControlType?.let { type ->
            diagnosticsListener?.onDtnControlWireFailed(
                address = resolveAddressForDeviceId(packet.deviceId) ?: "unknown",
                type = type,
                reason = reason,
                payloadBytes = packet.payloadBytes,
                totalChunks = packet.totalChunks,
            )
        }
        onBatchPacketWriteFailure(packet, reason, nowMs)
    }

    private fun onBatchPacketWriteFailure(packet: PendingPacket, reason: String, nowMs: Long) {
        val batchId = packet.batchId ?: return
        if (packet.batchEventIds.isEmpty()) return
        val key = batchSendStateKey(batchId, packet.deviceId)
        val snapshot = batchSendStateByKey.computeIfPresent(key) { _, existing ->
            val packetAttemptSeq = packet.batchAttemptSeq
            if (packetAttemptSeq != null && packetAttemptSeq != existing.attemptSeq) {
                Log.i(
                    TAG,
                    "mesh stale attempt ignored batch=${existing.batchId} device=${existing.deviceId} packet_attempt=$packetAttemptSeq state_attempt=${existing.attemptSeq} phase=failure reason=$reason",
                )
                return@computeIfPresent existing
            }
            if (existing.status != ItemSendStatus.IN_PROGRESS) return@computeIfPresent existing
            existing.failed = true
            existing.status = ItemSendStatus.FAILED
            existing.lastProgressAtMs = nowMs
            existing.completedAtMs = nowMs
            existing
        } ?: return
        logBatchStateTransition(
            snapshot = snapshot,
            transition = "in_progress_to_failed",
            reason = reason,
        )
        diagnosticsListener?.onBleBatchSendFailed(
            batchId = snapshot.batchId,
            deviceId = snapshot.deviceId,
            eventIds = snapshot.eventIds,
            writtenChunks = snapshot.writtenChunks,
            totalChunks = snapshot.totalChunks,
            writtenBytes = snapshot.writtenBytes,
            payloadBytes = snapshot.payloadBytes,
            reason = reason,
        )
        handleOpaqueRetrySendFailed(
            batchId = snapshot.batchId,
            deviceId = snapshot.deviceId,
            reason = reason,
            writtenChunks = snapshot.writtenChunks,
            totalChunks = snapshot.totalChunks,
        )
        markBatchForwardTerminal(
            batchId = snapshot.batchId,
            deviceId = snapshot.deviceId,
            delivered = false,
            reason = reason,
        )
        onPulledDispatchJobTerminal(
            deviceId = snapshot.deviceId,
            batchId = snapshot.batchId,
            reason = reason,
        )
    }

    private fun dropQueuedPacketsForItem(address: String, itemId: String) {
        synchronized(pendingQueueLock) {
            pendingByDevice[address]?.removeIf { it.itemId == itemId }
        }
        synchronized(schedulerLock) {
            stagedEnqueueByAddress[address]?.removeIf { it.itemId == itemId }
            heldEnqueueByAddress[address]?.removeIf { it.itemId == itemId }
        }
    }

    private fun dropStaleBatchPacketsForAttempt(
        batchId: String,
        deviceId: String,
        currentAttemptSeq: Long,
    ) {
        val droppedSummaries = ArrayList<Triple<String, String, Int>>()
        fun removeStaleFromQueue(queue: ArrayDeque<PendingPacket>): Int {
            var dropped = 0
            val keep = ArrayDeque<PendingPacket>()
            while (queue.isNotEmpty()) {
                val packet = queue.removeFirst()
                val staleAttempt = packet.batchAttemptSeq == null || packet.batchAttemptSeq != currentAttemptSeq
                if (packet.batchId == batchId && packet.deviceId == deviceId && staleAttempt) {
                    dropped += 1
                } else {
                    keep.addLast(packet)
                }
            }
            while (keep.isNotEmpty()) {
                queue.addLast(keep.removeFirst())
            }
            return dropped
        }

        synchronized(pendingQueueLock) {
            pendingByDevice.entries.forEach { (address, queue) ->
                val dropped = removeStaleFromQueue(queue)
                if (dropped > 0) {
                    droppedSummaries.add(Triple("pending", address, dropped))
                }
            }
            pendingByDevice.entries.removeIf { it.value.isEmpty() }
        }
        synchronized(schedulerLock) {
            stagedEnqueueByAddress.entries.forEach { (address, queue) ->
                val dropped = removeStaleFromQueue(queue)
                if (dropped > 0) {
                    droppedSummaries.add(Triple("staged", address, dropped))
                }
            }
            heldEnqueueByAddress.entries.forEach { (address, queue) ->
                val dropped = removeStaleFromQueue(queue)
                if (dropped > 0) {
                    droppedSummaries.add(Triple("held", address, dropped))
                }
            }
            stagedEnqueueByAddress.entries.removeIf { it.value.isEmpty() }
            heldEnqueueByAddress.entries.removeIf { it.value.isEmpty() }
        }

        droppedSummaries.forEach { (sourceQueue, address, dropped) ->
            Log.i(
                TAG,
                "mesh stale packet dropped batch=$batchId device=$deviceId addr=$address source=$sourceQueue dropped=$dropped attempt=$currentAttemptSeq",
            )
            diagnosticsListener?.onMeshStalePacketDropped(
                batchId = batchId,
                deviceId = deviceId,
                address = address,
                sourceQueue = sourceQueue,
                dropped = dropped,
                currentAttemptSeq = currentAttemptSeq,
            )
        }
    }

    private fun failInterruptedActivePacket(address: String, reason: String, nowMs: Long) {
        val packet = removeActivePendingPacket(address, reason = "interrupt_$reason") ?: return
        dropQueuedPacketsForItem(address, packet.itemId)
        onPacketWriteFailure(packet, reason = "interrupted_$reason", nowMs = nowMs)
        onPulledDispatchJobTerminal(
            deviceId = packet.deviceId,
            batchId = packet.batchId,
            reason = "interrupted_$reason",
        )
    }

    private fun failAllInFlightPackets(reason: String, nowMs: Long) {
        val representativeByKey = LinkedHashMap<String, PendingPacket>()
        activePendingPacketByAddress.values.forEach { packet ->
            representativeByKey.putIfAbsent(itemSendStateKey(packet.itemId, packet.deviceId), packet)
        }
        synchronized(pendingQueueLock) {
            pendingByDevice.values.forEach { queue ->
                queue.forEach { packet ->
                    representativeByKey.putIfAbsent(itemSendStateKey(packet.itemId, packet.deviceId), packet)
                }
            }
        }
        synchronized(schedulerLock) {
            stagedEnqueueByAddress.values.forEach { queue ->
                queue.forEach { packet ->
                    representativeByKey.putIfAbsent(itemSendStateKey(packet.itemId, packet.deviceId), packet)
                }
            }
        }
        representativeByKey.values.forEach { packet ->
            onPacketWriteFailure(packet, reason = "interrupted_$reason", nowMs = nowMs)
        }
    }

    private fun failQueuedWithoutProgress(
        itemId: String,
        batchId: String?,
        deviceId: String,
        eventIds: List<String>,
        payloadBytes: Int,
        totalChunks: Int,
        reason: String,
        nowMs: Long,
        expectedBatchAttemptSeq: Long? = null,
    ) {
        val itemKeyMatches = itemSendStateKey(itemId, deviceId)
        val itemSnapshot = itemSendStateByKey.computeIfPresent(itemKeyMatches) { _, existing ->
            if (existing.status != ItemSendStatus.IN_PROGRESS) return@computeIfPresent existing
            existing.failed = true
            existing.status = ItemSendStatus.FAILED
            existing.lastProgressAtMs = nowMs
            existing.completedAtMs = nowMs
            existing
        }
        if (itemSnapshot != null) {
            diagnosticsListener?.onBleSendFailed(
                itemId = itemSnapshot.itemId,
                deviceId = itemSnapshot.deviceId,
                writtenChunks = itemSnapshot.writtenChunks,
                totalChunks = itemSnapshot.totalChunks,
                writtenBytes = itemSnapshot.writtenBytes,
                payloadBytes = itemSnapshot.payloadBytes,
                reason = reason,
            )
        }
        if (batchId != null && eventIds.isNotEmpty()) {
            val batchKeyMatches = batchSendStateKey(batchId, deviceId)
            val batchSnapshot = batchSendStateByKey.computeIfPresent(batchKeyMatches) { _, existing ->
                if (expectedBatchAttemptSeq != null && existing.attemptSeq != expectedBatchAttemptSeq) {
                    Log.i(
                        TAG,
                        "mesh stale attempt ignored batch=${existing.batchId} device=${existing.deviceId} expected_attempt=$expectedBatchAttemptSeq state_attempt=${existing.attemptSeq} phase=timeout reason=$reason",
                    )
                    return@computeIfPresent existing
                }
                if (existing.status != ItemSendStatus.IN_PROGRESS) return@computeIfPresent existing
                existing.failed = true
                existing.status = ItemSendStatus.FAILED
                existing.lastProgressAtMs = nowMs
                existing.completedAtMs = nowMs
                existing
            }
            if (batchSnapshot != null) {
                logBatchStateTransition(
                    snapshot = batchSnapshot,
                    transition = "in_progress_to_failed",
                    reason = reason,
                )
                diagnosticsListener?.onBleBatchSendFailed(
                    batchId = batchSnapshot.batchId,
                    deviceId = batchSnapshot.deviceId,
                    eventIds = batchSnapshot.eventIds,
                    writtenChunks = batchSnapshot.writtenChunks,
                    totalChunks = batchSnapshot.totalChunks,
                    writtenBytes = batchSnapshot.writtenBytes,
                    payloadBytes = batchSnapshot.payloadBytes,
                    reason = reason,
                )
                handleOpaqueRetrySendFailed(
                    batchId = batchSnapshot.batchId,
                    deviceId = batchSnapshot.deviceId,
                    reason = reason,
                    writtenChunks = batchSnapshot.writtenChunks,
                    totalChunks = batchSnapshot.totalChunks,
                )
                markBatchForwardTerminal(
                    batchId = batchSnapshot.batchId,
                    deviceId = batchSnapshot.deviceId,
                    delivered = false,
                    reason = reason,
                )
                onPulledDispatchJobTerminal(
                    deviceId = batchSnapshot.deviceId,
                    batchId = batchSnapshot.batchId,
                    reason = reason,
                )
            }
        } else if (batchId != null) {
            diagnosticsListener?.onBleBatchSendFailed(
                batchId = batchId,
                deviceId = deviceId,
                eventIds = eventIds,
                writtenChunks = 0,
                totalChunks = totalChunks,
                writtenBytes = 0,
                payloadBytes = payloadBytes,
                reason = reason,
            )
            handleOpaqueRetrySendFailed(
                batchId = batchId,
                deviceId = deviceId,
                reason = reason,
                writtenChunks = 0,
                totalChunks = totalChunks,
            )
            markBatchForwardTerminal(
                batchId = batchId,
                deviceId = deviceId,
                delivered = false,
                reason = reason,
            )
            onPulledDispatchJobTerminal(
                deviceId = deviceId,
                batchId = batchId,
                reason = reason,
            )
        }
    }

    private fun maybeRecoverInProgressBatchAsComplete(
        batchId: String,
        deviceId: String,
        nowMs: Long,
    ): Boolean {
        val batchKey = batchSendStateKey(batchId, deviceId)
        var batchCompleted = false
        val batchSnapshot = batchSendStateByKey.computeIfPresent(batchKey) { _, existing ->
            if (existing.status != ItemSendStatus.IN_PROGRESS) return@computeIfPresent existing
            if (existing.writtenBytes < existing.payloadBytes) return@computeIfPresent existing
            existing.failed = false
            existing.status = ItemSendStatus.SENT
            existing.completedAtMs = nowMs
            existing.lastProgressAtMs = nowMs
            if (existing.writtenChunks < existing.totalChunks) {
                existing.writtenChunks = existing.totalChunks
            }
            batchCompleted = true
            existing
        } ?: return false
        if (!batchCompleted) return false
        logBatchStateTransition(
            snapshot = batchSnapshot,
            transition = "in_progress_to_sent",
            reason = "orphan_recovered_bytes_complete",
        )

        val itemKey = itemSendStateKey(batchId, deviceId)
        var itemCompleted = false
        val itemSnapshot = itemSendStateByKey.computeIfPresent(itemKey) { _, existing ->
            if (existing.status != ItemSendStatus.IN_PROGRESS) return@computeIfPresent existing
            if (existing.writtenBytes < existing.payloadBytes) return@computeIfPresent existing
            existing.failed = false
            existing.status = ItemSendStatus.SENT
            existing.completedAtMs = nowMs
            existing.lastProgressAtMs = nowMs
            if (existing.writtenChunks < existing.totalChunks) {
                existing.writtenChunks = existing.totalChunks
            }
            itemCompleted = true
            existing
        }

        if (itemCompleted && itemSnapshot != null) {
            diagnosticsListener?.onBleSendComplete(
                itemId = itemSnapshot.itemId,
                deviceId = itemSnapshot.deviceId,
                totalChunks = itemSnapshot.totalChunks,
                payloadBytes = itemSnapshot.payloadBytes,
            )
        }

        markFreshDelivered(
            deviceId = batchSnapshot.deviceId,
            eventIds = batchSnapshot.eventIds,
            createdAtMs = batchSnapshot.createdAtMs,
            nowMs = nowMs,
        )
        markBatchForwardTerminal(
            batchId = batchSnapshot.batchId,
            deviceId = batchSnapshot.deviceId,
            delivered = true,
            reason = null,
        )
        diagnosticsListener?.onBleBatchSendComplete(
            batchId = batchSnapshot.batchId,
            deviceId = batchSnapshot.deviceId,
            eventIds = batchSnapshot.eventIds,
            totalChunks = batchSnapshot.totalChunks,
            payloadBytes = batchSnapshot.payloadBytes,
        )
        onPulledDispatchJobTerminal(
            deviceId = batchSnapshot.deviceId,
            batchId = batchSnapshot.batchId,
            reason = "orphan_recovered_bytes_complete",
        )
        Log.i(
            TAG,
            "Recovered in-progress orphan as complete batch=$batchId device=$deviceId bytes=${batchSnapshot.writtenBytes}/${batchSnapshot.payloadBytes}",
        )
        return true
    }

    private fun dropQueuedPacketsForItemDevice(itemId: String, deviceId: String) {
        val droppedAddresses = LinkedHashSet<String>()
        val activeAddresses = activePendingPacketByAddress.entries
            .filter { (_, packet) -> packet.itemId == itemId && packet.deviceId == deviceId }
            .map { it.key }
        activeAddresses.forEach { address ->
            droppedAddresses.add(address)
            removeActivePendingPacket(address, reason = "drop_queued_item_device")
        }
        synchronized(pendingQueueLock) {
            pendingByDevice.entries.forEach { (address, queue) ->
                if (queue.removeIf { it.itemId == itemId && it.deviceId == deviceId }) {
                    droppedAddresses.add(address)
                }
            }
        }
        synchronized(schedulerLock) {
            stagedEnqueueByAddress.entries.forEach { (address, queue) ->
                if (queue.removeIf { it.itemId == itemId && it.deviceId == deviceId }) {
                    droppedAddresses.add(address)
                }
            }
            heldEnqueueByAddress.entries.forEach { (address, queue) ->
                if (queue.removeIf { it.itemId == itemId && it.deviceId == deviceId }) {
                    droppedAddresses.add(address)
                }
            }
        }
        droppedAddresses.forEach { address ->
            val key = itemKey(address, itemId)
            itemStateByKey.computeIfPresent(key) { _, state ->
                when (state) {
                    ItemState.ENQUEUED, ItemState.CONNECTING -> null
                    else -> state
                }
            }
            activeItemKeyByAddress.computeIfPresent(address) { _, activeKey ->
                if (activeKey == key) null else activeKey
            }
        }
    }

    private fun moveQueuedItemToHeld(
        address: String,
        firstPacket: PendingPacket,
        reason: String,
    ): Int {
        val moved = ArrayList<PendingPacket>()
        moved.add(firstPacket)

        synchronized(pendingQueueLock) {
            val queue = pendingByDevice[address]
            if (queue != null && queue.isNotEmpty()) {
                val keep = ArrayDeque<PendingPacket>()
                while (queue.isNotEmpty()) {
                    val packet = queue.removeFirst()
                    if (packet.itemId == firstPacket.itemId && packet.deviceId == firstPacket.deviceId) {
                        moved.add(packet)
                    } else {
                        keep.addLast(packet)
                    }
                }
                if (keep.isEmpty()) {
                    pendingByDevice.remove(address)
                } else {
                    pendingByDevice[address] = keep
                }
            }
        }

        stageHeldPackets(address, moved)

        if (firstPacket.batchId != null && firstPacket.batchEventIds.isNotEmpty()) {
            diagnosticsListener?.onBleBatchTargetPending(
                batchId = firstPacket.batchId,
                deviceId = firstPacket.deviceId,
                eventIds = firstPacket.batchEventIds,
                reason = reason,
            )
        }

        Log.i(
            TAG,
            "WRITE gate moved item to held addr=$address item=${firstPacket.itemId} device=${firstPacket.deviceId} reason=$reason moved=${moved.size}",
        )
        return moved.size
    }

    private fun hasHeldPacketsForItemDevice(itemId: String, deviceId: String): Boolean {
        synchronized(schedulerLock) {
            return heldEnqueueByAddress.values.any { queue ->
                queue.any { it.itemId == itemId && it.deviceId == deviceId }
            }
        }
    }

    private fun hasQueuedPacketsForItemDevice(itemId: String, deviceId: String): Boolean {
        synchronized(pendingQueueLock) {
            return pendingByDevice.values.any { queue ->
                queue.any { it.itemId == itemId && it.deviceId == deviceId }
            }
        }
    }

    private fun queuedPacketCountForItemDevice(itemId: String, deviceId: String): Int {
        synchronized(pendingQueueLock) {
            return pendingByDevice.values.sumOf { queue ->
                queue.count { it.itemId == itemId && it.deviceId == deviceId }
            }
        }
    }

    private fun stagedPacketCountForItemDevice(itemId: String, deviceId: String): Int {
        synchronized(schedulerLock) {
            return stagedEnqueueByAddress.values.sumOf { queue ->
                queue.count { it.itemId == itemId && it.deviceId == deviceId }
            }
        }
    }

    private fun headQueuedPacketAgeMs(itemId: String, deviceId: String, nowMs: Long): Long? {
        val batchState = batchSendStateByKey[batchSendStateKey(itemId, deviceId)]
        val itemState = itemSendStateByKey[itemSendStateKey(itemId, deviceId)]
        val startedAt = batchState?.startedAtMs ?: itemState?.startedAtMs ?: return null
        return (nowMs - startedAt).coerceAtLeast(0L)
    }

    private fun hasRecentConnectOrDiscoveryForDevice(deviceId: String, nowMs: Long): Boolean {
        val addresses = candidateAddressesForDevice(deviceId, nowMs)
        return addresses.any { address ->
            val lastProgress = lastProgressAtMs[address] ?: 0L
            val connectStarted = connectStartedAtMs[address] ?: 0L
            val recentProgress = lastProgress > 0L && nowMs - lastProgress < RECENT_CONNECT_GRACE_MS
            val recentConnect = connectStarted > 0L && nowMs - connectStarted < RECENT_CONNECT_GRACE_MS
            recentProgress || recentConnect
        }
    }

    private fun hasStartedRealSendPathForDevice(
        deviceId: String,
        itemStartedAtMs: Long,
        nowMs: Long,
    ): Boolean {
        val addresses = candidateAddressesForDevice(deviceId, nowMs)
        if (addresses.isEmpty()) return false

        return addresses.any { address ->
            val connectStarted = connectStartedAtMs[address] ?: 0L
            val lastProgress = lastProgressAtMs[address] ?: 0L
            val activePacket = activePendingPacketByAddress[address]
            val state = connectState(address)

            activePacket != null ||
                connectStarted >= itemStartedAtMs ||
                lastProgress >= itemStartedAtMs ||
                state == CONNECT_STATE_CONNECTING ||
                state == CONNECT_STATE_CONNECTED ||
                clientByDevice.containsKey(address) ||
                connectingClient.containsKey(address)
        }
    }

    private fun isConnectCapBlockedWithoutActiveLane(
        deviceId: String,
        nowMs: Long,
    ): Boolean {
        if (canOpenNewGatt()) return false
        if (waitingDevicesCount() <= 0) return false
        val addresses = candidateAddressesForDevice(deviceId, nowMs)
        return addresses.none { address ->
            connectState(address) == CONNECT_STATE_CONNECTED ||
                connectState(address) == CONNECT_STATE_CONNECTING ||
                clientByDevice.containsKey(address) ||
                connectingClient.containsKey(address) ||
                connecting.contains(address)
        }
    }

    private fun bestCurrentAddressForDevice(deviceId: String, nowMs: Long): String? {
        return candidateAddressesForDevice(deviceId, nowMs)
            .firstOrNull { address ->
                lastSeenDevices[address] != null &&
                    (!isAddressPenaltyActive(address, nowMs) || connectState(address) == CONNECT_STATE_CONNECTED)
            }
    }

    private fun movePacketsForItemDeviceToAddress(
        itemId: String,
        deviceId: String,
        targetAddress: String,
    ): Int {
        var moved = 0
        val movedPackets = ArrayList<PendingPacket>()
        val sourceAddresses = LinkedHashSet<String>()

        activePendingPacketByAddress.entries
            .filter { (address, packet) ->
                address != targetAddress && packet.itemId == itemId && packet.deviceId == deviceId
            }
            .forEach { (address, _) ->
                removeActivePendingPacket(address, reason = "rebind_no_write_attempt")?.let { packet ->
                    movedPackets.add(packet)
                    sourceAddresses.add(address)
                }
            }

        synchronized(pendingQueueLock) {
            pendingByDevice.entries.forEach { (address, queue) ->
                if (address == targetAddress) return@forEach
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    val packet = iterator.next()
                    if (packet.itemId == itemId && packet.deviceId == deviceId) {
                        movedPackets.add(packet)
                        sourceAddresses.add(address)
                        iterator.remove()
                    }
                }
            }
            pendingByDevice.entries.removeIf { it.value.isEmpty() }
        }

        synchronized(schedulerLock) {
            stagedEnqueueByAddress.entries.forEach { (address, queue) ->
                if (address == targetAddress) return@forEach
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    val packet = iterator.next()
                    if (packet.itemId == itemId && packet.deviceId == deviceId) {
                        movedPackets.add(packet)
                        sourceAddresses.add(address)
                        iterator.remove()
                    }
                }
            }
            heldEnqueueByAddress.entries.forEach { (address, queue) ->
                if (address == targetAddress) return@forEach
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    val packet = iterator.next()
                    if (packet.itemId == itemId && packet.deviceId == deviceId) {
                        movedPackets.add(packet)
                        sourceAddresses.add(address)
                        iterator.remove()
                    }
                }
            }
            stagedEnqueueByAddress.entries.removeIf { it.value.isEmpty() }
            heldEnqueueByAddress.entries.removeIf { it.value.isEmpty() }
        }

        if (movedPackets.isEmpty()) return 0

        val ready = isAddressReadyForHeldRelease(targetAddress)
        synchronized(schedulerLock) {
            val queue = if (ready) {
                stagedEnqueueByAddress.getOrPut(targetAddress) { ArrayDeque() }
            } else {
                heldEnqueueByAddress.getOrPut(targetAddress) { ArrayDeque() }
            }
            movedPackets.forEach { packet -> queue.addLast(packet) }
        }

        sourceAddresses.forEach { address ->
            val key = itemKey(address, itemId)
            itemStateByKey.computeIfPresent(key) { _, state ->
                when (state) {
                    ItemState.ENQUEUED, ItemState.CONNECTING -> null
                    else -> state
                }
            }
            activeItemKeyByAddress.computeIfPresent(address) { _, activeKey ->
                if (activeKey == key) null else activeKey
            }
        }
        markItemEnqueued(targetAddress, itemId)
        offerDeviceForRoundRobin(targetAddress)
        postLabel(targetAddress, if (ready) PipelineLabel.ENQUEUE else PipelineLabel.CONNECT)
        moved = movedPackets.size
        return moved
    }

    private fun tryRebindQueuedNoWriteAttempt(
        state: BatchSendState,
        nowMs: Long,
    ): Boolean {
        if (state.hasWriteAttempted || state.writtenChunks > 0) return false
        val key = batchSendStateKey(state.batchId, state.deviceId)
        val attempts = queuedNoWriteRebindCountByBatchDevice.merge(key, 1) { old, _ -> old + 1 } ?: 1
        if (attempts > QUEUED_NO_WRITE_REBIND_MAX_ATTEMPTS) {
            Log.w(
                TAG,
                "queued_no_write_attempt rebind exhausted batch=${state.batchId} device=${state.deviceId} attempts=$attempts",
            )
            return false
        }

        val targetAddress = bestCurrentAddressForDevice(state.deviceId, nowMs) ?: run {
            Log.i(
                TAG,
                "queued_no_write_attempt rebind skipped batch=${state.batchId} device=${state.deviceId} reason=no_current_address attempts=$attempts",
            )
            return false
        }

        val moved = movePacketsForItemDeviceToAddress(
            itemId = state.batchId,
            deviceId = state.deviceId,
            targetAddress = targetAddress,
        )
        if (moved <= 0) {
            Log.i(
                TAG,
                "queued_no_write_attempt rebind skipped batch=${state.batchId} device=${state.deviceId} target=$targetAddress reason=no_packets attempts=$attempts",
            )
            return false
        }

        state.startedAtMs = nowMs
        state.lastProgressAtMs = nowMs
        state.failed = false
        state.status = ItemSendStatus.IN_PROGRESS
        state.completedAtMs = null
        itemSendStateByKey.computeIfPresent(itemSendStateKey(state.batchId, state.deviceId)) { _, existing ->
            existing.startedAtMs = nowMs
            existing.lastProgressAtMs = nowMs
            existing.failed = false
            existing.status = ItemSendStatus.IN_PROGRESS
            existing.completedAtMs = null
            existing
        }

        Log.i(
            TAG,
            "queued_no_write_attempt rebound batch=${state.batchId} device=${state.deviceId} target=$targetAddress packets=$moved attempt=$attempts",
        )
        return true
    }

    private fun cleanupQueuedWithoutProgress(nowMs: Long) {
        batchSendStateByKey.values
            .forEach { state ->
                if (state.status != ItemSendStatus.IN_PROGRESS) return@forEach
                if (state.writtenChunks > 0) {
                    maybeLogInProgressStuckWatchdog(state, nowMs)
                    val progressAgeMs = nowMs - state.lastProgressAtMs
                    if (progressAgeMs <= IN_PROGRESS_STUCK_WATCHDOG_MS) return@forEach

                    val hasActive = activePendingPacketByAddress.values.any {
                        it.batchId == state.batchId && it.deviceId == state.deviceId
                    }
                    if (hasActive) return@forEach

                    val hasHeld = hasHeldPacketsForItemDevice(state.batchId, state.deviceId)
                    if (hasHeld) return@forEach

                    val queuedCount = queuedPacketCountForItemDevice(state.batchId, state.deviceId)
                    val stagedCount = stagedPacketCountForItemDevice(state.batchId, state.deviceId)
                    val hasQueuedOrStaged = queuedCount > 0 || stagedCount > 0
                    if (hasQueuedOrStaged) return@forEach

                    if (BleConfig.enableOrphanRecoverOnFullBytes &&
                        state.writtenBytes >= state.payloadBytes &&
                        maybeRecoverInProgressBatchAsComplete(
                            batchId = state.batchId,
                            deviceId = state.deviceId,
                            nowMs = nowMs,
                        )
                    ) {
                        return@forEach
                    }

                    val recent133 = (last133AtByDeviceId[state.deviceId] ?: 0L).let { last ->
                        last > 0L && nowMs - last <= POST_133_ORPHAN_GRACE_MS
                    }
                    val progressedEnough =
                        state.totalChunks > 0 &&
                            state.writtenChunks * 100 >=
                            state.totalChunks * POST_133_ORPHAN_GRACE_MIN_PROGRESS_PERCENT
                    if (recent133 && progressedEnough &&
                        progressAgeMs <= IN_PROGRESS_STUCK_WATCHDOG_MS + POST_133_ORPHAN_GRACE_MS
                    ) {
                        Log.i(
                            TAG,
                            "Skip in_progress_orphan timeout batch=${state.batchId} device=${state.deviceId} progressAgeMs=$progressAgeMs reason=recent_133_recovery_grace",
                        )
                        return@forEach
                    }

                    Log.w(
                        TAG,
                        "in_progress_orphan timeout batch=${state.batchId} device=${state.deviceId} progressAgeMs=$progressAgeMs chunks=${state.writtenChunks}/${state.totalChunks} bytes=${state.writtenBytes}/${state.payloadBytes} queued=$queuedCount staged=$stagedCount held=$hasHeld active=$hasActive",
                    )

                    dropQueuedPacketsForItemDevice(itemId = state.batchId, deviceId = state.deviceId)
                    failQueuedWithoutProgress(
                        itemId = state.batchId,
                        batchId = state.batchId,
                        deviceId = state.deviceId,
                        eventIds = state.eventIds,
                        payloadBytes = state.payloadBytes,
                        totalChunks = state.totalChunks,
                        reason = "in_progress_orphan_timeout",
                        nowMs = nowMs,
                        expectedBatchAttemptSeq = state.attemptSeq,
                    )
                    return@forEach
                }
                val ageMs = nowMs - state.startedAtMs
                if (!state.hasWriteAttempted) {
                    val sendPathStarted = hasStartedRealSendPathForDevice(
                        deviceId = state.deviceId,
                        itemStartedAtMs = state.startedAtMs,
                        nowMs = nowMs,
                    )
                    val noWriteAttemptTimeoutMs = if (sendPathStarted) {
                        QUEUED_NO_PROGRESS_TIMEOUT_MS
                    } else {
                        QUEUED_NO_WRITE_ATTEMPT_TIMEOUT_MS
                    }
                    if (ageMs <= noWriteAttemptTimeoutMs) return@forEach

                    val hasActive = activePendingPacketByAddress.values.any {
                        it.batchId == state.batchId && it.deviceId == state.deviceId
                    }
                    if (hasActive) return@forEach

                    val hasHeld = hasHeldPacketsForItemDevice(state.batchId, state.deviceId)
                    val queuedCount = queuedPacketCountForItemDevice(state.batchId, state.deviceId)
                    val stagedCount = stagedPacketCountForItemDevice(state.batchId, state.deviceId)
                    val stillQueued = queuedCount > 0 || stagedCount > 0 || hasHeld
                    val recentConnect = hasRecentConnectOrDiscoveryForDevice(state.deviceId, nowMs)
                    val connectCapBlockedNoLane = isConnectCapBlockedWithoutActiveLane(
                        deviceId = state.deviceId,
                        nowMs = nowMs,
                    )
                    val rotationRecoveryActive = isRotationRecoveryActiveForDevice(state.deviceId, nowMs)
                    val siblingProgress = hasRecentSiblingProgressForDevice(state.deviceId, nowMs)
                    val otherActiveLaneProgress = hasRecentOtherActiveLaneProgressForDevice(state.deviceId, nowMs)

                    if (stillQueued && connectCapBlockedNoLane) {
                        Log.i(
                            TAG,
                            "Skip queued_no_write_attempt timeout batch=${state.batchId} device=${state.deviceId} ageMs=$ageMs queued=$queuedCount staged=$stagedCount reason=connect_cap_waiting_lane",
                        )
                        return@forEach
                    }

                    if (stillQueued && recentConnect && ageMs <= QUEUED_NO_PROGRESS_TIMEOUT_MS + RECENT_CONNECT_GRACE_MS) {
                        Log.i(
                            TAG,
                            "Skip queued_no_write_attempt timeout batch=${state.batchId} device=${state.deviceId} ageMs=$ageMs queued=$queuedCount staged=$stagedCount sendPathStarted=$sendPathStarted timeoutMs=$noWriteAttemptTimeoutMs reason=recent_connect_grace",
                        )
                        return@forEach
                    }

                    if (stillQueued && rotationRecoveryActive &&
                        ageMs <= QUEUED_NO_PROGRESS_TIMEOUT_MS + ROTATION_RECOVERY_TIMEOUT_GRACE_MS
                    ) {
                        Log.i(
                            TAG,
                            "Skip queued_no_write_attempt timeout batch=${state.batchId} device=${state.deviceId} ageMs=$ageMs queued=$queuedCount staged=$stagedCount reason=rotation_recovery_grace",
                        )
                        return@forEach
                    }

                    if (stillQueued && siblingProgress &&
                        ageMs <= QUEUED_NO_PROGRESS_TIMEOUT_MS + SIBLING_PROGRESS_TIMEOUT_GRACE_MS
                    ) {
                        Log.i(
                            TAG,
                            "Skip queued_no_write_attempt timeout batch=${state.batchId} device=${state.deviceId} ageMs=$ageMs queued=$queuedCount staged=$stagedCount reason=sibling_progress_grace",
                        )
                        return@forEach
                    }

                    if (stillQueued && otherActiveLaneProgress &&
                        ageMs <= QUEUED_NO_PROGRESS_TIMEOUT_MS + SIBLING_PROGRESS_TIMEOUT_GRACE_MS
                    ) {
                        Log.i(
                            TAG,
                            "Skip queued_no_write_attempt timeout batch=${state.batchId} device=${state.deviceId} ageMs=$ageMs queued=$queuedCount staged=$stagedCount reason=other_active_lane_progress_grace",
                        )
                        return@forEach
                    }

                    Log.w(
                        TAG,
                        "queued_no_write_attempt timeout batch=${state.batchId} device=${state.deviceId} ageMs=$ageMs timeoutMs=$noWriteAttemptTimeoutMs sendPathStarted=$sendPathStarted queued=$queuedCount staged=$stagedCount held=$hasHeld recentConnect=$recentConnect rotationRecovery=$rotationRecoveryActive siblingProgress=$siblingProgress otherActiveLaneProgress=$otherActiveLaneProgress",
                    )

                   if (tryRebindQueuedNoWriteAttempt(state, nowMs)) {
                        return@forEach
                    }

                    dropQueuedPacketsForItemDevice(itemId = state.batchId, deviceId = state.deviceId)
                    failQueuedWithoutProgress(
                        itemId = state.batchId,
                        batchId = state.batchId,
                        deviceId = state.deviceId,
                        eventIds = state.eventIds,
                        payloadBytes = state.payloadBytes,
                        totalChunks = state.totalChunks,
                        reason = "queued_no_write_attempt_timeout",
                        nowMs = nowMs,
                        expectedBatchAttemptSeq = state.attemptSeq,
                    )
                    return@forEach
                }

                if (ageMs <= QUEUED_NO_PROGRESS_TIMEOUT_MS) return@forEach

                val hasActive = activePendingPacketByAddress.values.any {
                    it.batchId == state.batchId && it.deviceId == state.deviceId
                }
                if (hasActive) return@forEach

                val hasHeld = hasHeldPacketsForItemDevice(state.batchId, state.deviceId)
                if (hasHeld) return@forEach

                val queuedCount = queuedPacketCountForItemDevice(state.batchId, state.deviceId)
                val stagedCount = stagedPacketCountForItemDevice(state.batchId, state.deviceId)
                val stillQueued = queuedCount > 0 || stagedCount > 0
                val recentConnect = hasRecentConnectOrDiscoveryForDevice(state.deviceId, nowMs)
                val connectCapBlockedNoLane = isConnectCapBlockedWithoutActiveLane(
                    deviceId = state.deviceId,
                    nowMs = nowMs,
                )
                val rotationRecoveryActive = isRotationRecoveryActiveForDevice(state.deviceId, nowMs)
                val siblingProgress = hasRecentSiblingProgressForDevice(state.deviceId, nowMs)

                if (stillQueued && connectCapBlockedNoLane) {
                    Log.i(
                        TAG,
                        "Skip queued_no_progress timeout batch=${state.batchId} device=${state.deviceId} ageMs=$ageMs queued=$queuedCount staged=$stagedCount reason=connect_cap_waiting_lane",
                    )
                    return@forEach
                }

                if (stillQueued && recentConnect && ageMs <= QUEUED_NO_PROGRESS_TIMEOUT_MS + RECENT_CONNECT_GRACE_MS) {
                    Log.i(
                        TAG,
                        "Skip queued_no_progress timeout batch=${state.batchId} device=${state.deviceId} ageMs=$ageMs queued=$queuedCount staged=$stagedCount reason=recent_connect_grace",
                    )
                    return@forEach
                }

                if (stillQueued && rotationRecoveryActive &&
                    ageMs <= QUEUED_NO_PROGRESS_TIMEOUT_MS + ROTATION_RECOVERY_TIMEOUT_GRACE_MS
                ) {
                    Log.i(
                        TAG,
                        "Skip queued_no_progress timeout batch=${state.batchId} device=${state.deviceId} ageMs=$ageMs queued=$queuedCount staged=$stagedCount reason=rotation_recovery_grace",
                    )
                    return@forEach
                }

                if (stillQueued && siblingProgress &&
                    ageMs <= QUEUED_NO_PROGRESS_TIMEOUT_MS + SIBLING_PROGRESS_TIMEOUT_GRACE_MS
                ) {
                    Log.i(
                        TAG,
                        "Skip queued_no_progress timeout batch=${state.batchId} device=${state.deviceId} ageMs=$ageMs queued=$queuedCount staged=$stagedCount reason=sibling_progress_grace",
                    )
                    return@forEach
                }

                Log.w(
                    TAG,
                    "queued_no_progress timeout batch=${state.batchId} device=${state.deviceId} ageMs=$ageMs queued=$queuedCount staged=$stagedCount held=$hasHeld recentConnect=$recentConnect rotationRecovery=$rotationRecoveryActive siblingProgress=$siblingProgress",
                )

                dropQueuedPacketsForItemDevice(itemId = state.batchId, deviceId = state.deviceId)
                failQueuedWithoutProgress(
                    itemId = state.batchId,
                    batchId = state.batchId,
                    deviceId = state.deviceId,
                    eventIds = state.eventIds,
                    payloadBytes = state.payloadBytes,
                    totalChunks = state.totalChunks,
                    reason = "queued_no_progress_timeout",
                    nowMs = nowMs,
                    expectedBatchAttemptSeq = state.attemptSeq,
                )
            }
    }

    private fun maybeLogInProgressStuckWatchdog(state: BatchSendState, nowMs: Long) {
        val progressAgeMs = nowMs - state.lastProgressAtMs
        if (progressAgeMs <= IN_PROGRESS_STUCK_WATCHDOG_MS) return
        val key = batchSendStateKey(state.batchId, state.deviceId)
        val lastLoggedAt = lastInProgressStuckWatchdogLogAtMsByKey[key] ?: 0L
        if (nowMs - lastLoggedAt < IN_PROGRESS_STUCK_LOG_COOLDOWN_MS) return
        lastInProgressStuckWatchdogLogAtMsByKey[key] = nowMs

        val queuedCount = queuedPacketCountForItemDevice(state.batchId, state.deviceId)
        val stagedCount = stagedPacketCountForItemDevice(state.batchId, state.deviceId)
        val hasHeld = hasHeldPacketsForItemDevice(state.batchId, state.deviceId)
        val hasActivePacket = activePendingPacketByAddress.values.any { packet ->
            packet.batchId == state.batchId && packet.deviceId == state.deviceId
        }
        val waiting = waitingDevicesCount()
        val connectCapBlocked = !canOpenNewGatt()

        diagnosticsListener?.onBleBatchInProgressStuckWatchdog(
            batchId = state.batchId,
            deviceId = state.deviceId,
            eventIds = state.eventIds,
            writtenChunks = state.writtenChunks,
            totalChunks = state.totalChunks,
            writtenBytes = state.writtenBytes,
            payloadBytes = state.payloadBytes,
            progressAgeMs = progressAgeMs,
        )
        diagnosticsListener?.onBleBatchInProgressStuckSnapshot(
            batchId = state.batchId,
            deviceId = state.deviceId,
            queuedCount = queuedCount,
            stagedCount = stagedCount,
            hasHeld = hasHeld,
            hasActivePacket = hasActivePacket,
            waitingDevices = waiting,
            connectCapBlocked = connectCapBlocked,
        )
        Log.w(
            TAG,
            "in_progress batch stuck batch=${state.batchId} device=${state.deviceId} progressAgeMs=$progressAgeMs chunks=${state.writtenChunks}/${state.totalChunks} bytes=${state.writtenBytes}/${state.payloadBytes} queued=$queuedCount staged=$stagedCount held=$hasHeld active=$hasActivePacket waiting=$waiting capBlocked=$connectCapBlocked",
        )
    }

    private fun pruneItemSendStates(nowMs: Long) {
        itemSendStateByKey.entries.removeIf { (_, state) ->
            when (state.status) {
                ItemSendStatus.SENT, ItemSendStatus.FAILED -> {
                    val terminalAt = state.completedAtMs ?: state.lastProgressAtMs
                    nowMs - terminalAt > ITEM_SEND_STATE_TTL_MS
                }
                ItemSendStatus.IN_PROGRESS -> nowMs - state.lastProgressAtMs > ITEM_SEND_STATE_STALE_MS
            }
        }
        batchSendStateByKey.entries.removeIf { (_, state) ->
            when (state.status) {
                ItemSendStatus.SENT, ItemSendStatus.FAILED -> {
                    val terminalAt = state.completedAtMs ?: state.lastProgressAtMs
                    nowMs - terminalAt > ITEM_SEND_STATE_TTL_MS
                }
                ItemSendStatus.IN_PROGRESS -> nowMs - state.lastProgressAtMs > ITEM_SEND_STATE_STALE_MS
            }
        }
    }
    private var cleanupRunnable: Runnable? = null
    private val writeScheduled = ConcurrentHashMap<String, Boolean>()
    private var backpressureActive = false
    private var lastTrafficSnapshot: BleTrafficSnapshot? = null
    private var idleIntervals = 0
    private var adaptiveState = AdaptiveState.BASE
    private var rrDeviceQueue: ArrayDeque<String> = ArrayDeque()
    private val rrDeviceSet = HashSet<String>()
    private val sendBudgetRemaining = ConcurrentHashMap<String, Int>()
    private val sendBudgetResetAtMs = ConcurrentHashMap<String, Long>()
    private val lastMfgLogAtMs = ConcurrentHashMap<String, Long>()
    private val lastMfgSkipLogAtMs = ConcurrentHashMap<String, Long>()
    private val lastRxMismatchLogAtMs = ConcurrentHashMap<String, Long>()
    private val lastTxMismatchLogAtMs = ConcurrentHashMap<String, Long>()
    private var inboundWriteCount = 0L
    private var lastInboundLogAtMs = 0L
    private var lastRxWindowLogAtMs = 0L
    @Volatile private var diagnosticsListener: MeshTransportDiagnosticsListener? = null

    private fun stateFor(address: String): AddressState {
        return addressStateByAddress.getOrPut(address) { AddressState() }
    }

    private fun setConnectState(address: String, state: Int, reason: String) {
        val st = stateFor(address)
        val previous = st.connectState
        st.connectState = state
        if (previous != state) {
            Log.i(TAG, "connect_state addr=$address from=$previous to=$state reason=$reason")
        }
    }

    private fun canAttemptConnectNow(address: String, nowMs: Long): Boolean {
        val st = stateFor(address)
        if (nowMs - st.lastConnectAttemptAt < CONNECT_ATTEMPT_COOLDOWN_MS) {
            return false
        }
        st.lastConnectAttemptAt = nowMs
        return true
    }

    private fun tryAcquireWriteSlot(address: String): Boolean {
        val st = stateFor(address)
        if (st.writeInFlight) return false
        st.writeInFlight = true
        return true
    }

    private fun releaseWriteSlot(address: String) {
        stateFor(address).writeInFlight = false
    }

    private fun connectState(address: String): Int {
        return addressStateByAddress[address]?.connectState ?: CONNECT_STATE_DISCONNECTED
    }

    private fun itemKey(address: String, itemId: String): String = "$address|$itemId"

    private fun currentItemId(address: String): String? {
        return synchronized(pendingQueueLock) {
            pendingByDevice[address]?.firstOrNull()?.itemId
        }
    }

    private fun hasMeshEnvelopeWorkForAddressItem(address: String, itemId: String): Boolean {
        val activePacket = activePendingPacketByAddress[address]
        if (activePacket?.itemId == itemId && activePacket.dtnControlType == null && !activePacket.isAck) {
            return true
        }
        val hasPending = synchronized(pendingQueueLock) {
            pendingByDevice[address]?.any {
                it.itemId == itemId && it.dtnControlType == null && !it.isAck
            } == true
        }
        if (hasPending) return true
        return synchronized(schedulerLock) {
            (stagedEnqueueByAddress[address]?.any {
                it.itemId == itemId && it.dtnControlType == null && !it.isAck
            } == true) ||
                (heldEnqueueByAddress[address]?.any {
                    it.itemId == itemId && it.dtnControlType == null && !it.isAck
                } == true)
        }
    }

    private fun canEnqueueItem(address: String, itemId: String, nowMs: Long): Boolean {
        val key = itemKey(address, itemId)
        val backoffUntil = itemBackoffUntilByKey[key] ?: 0L
        if (backoffUntil > nowMs) return false
        if (backoffUntil in 1..nowMs) {
            itemBackoffUntilByKey.remove(key)
        }
        return when (itemStateByKey[key]) {
            ItemState.ENQUEUED, ItemState.CONNECTING -> false
            else -> true
        }
    }

    private fun hasTransportWorkForAddressItemDevice(
        address: String,
        itemId: String,
        deviceId: String,
    ): Boolean {
        val activePacket = activePendingPacketByAddress[address]
        if (activePacket?.itemId == itemId && activePacket.deviceId == deviceId) return true
        val hasPending = synchronized(pendingQueueLock) {
            pendingByDevice[address]?.any { it.itemId == itemId && it.deviceId == deviceId } == true
        }
        if (hasPending) return true
        return synchronized(schedulerLock) {
            (stagedEnqueueByAddress[address]?.any { it.itemId == itemId && it.deviceId == deviceId } == true) ||
                (heldEnqueueByAddress[address]?.any { it.itemId == itemId && it.deviceId == deviceId } == true)
        }
    }

    private fun hasTerminalFailedState(itemId: String, deviceId: String): Boolean {
        val itemState = itemSendStateByKey[itemSendStateKey(itemId, deviceId)]
        if (itemState?.status == ItemSendStatus.FAILED) return true
        val batchState = batchSendStateByKey[batchSendStateKey(itemId, deviceId)]
        return batchState?.status == ItemSendStatus.FAILED
    }

    private fun maybeClearStaleEnqueueState(
        address: String,
        itemId: String,
        deviceId: String,
        reason: String,
    ): Boolean {
        val key = itemKey(address, itemId)
        val state = itemStateByKey[key]
        if (state != ItemState.ENQUEUED && state != ItemState.CONNECTING) return false
        if (hasTransportWorkForAddressItemDevice(address, itemId, deviceId)) return false
        if (!hasTerminalFailedState(itemId, deviceId)) return false
        if (!itemStateByKey.remove(key, state)) return false
        activeItemKeyByAddress.computeIfPresent(address) { _, activeKey ->
            if (activeKey == key) null else activeKey
        }
        Log.i(TAG, "ITEM stale enqueue state cleared key=$key state=$state reason=$reason")
        return true
    }

    private fun markItemEnqueued(address: String, itemId: String) {
        itemStateByKey[itemKey(address, itemId)] = ItemState.ENQUEUED
    }

    private fun markItemConnecting(address: String, itemId: String) {
        val key = itemKey(address, itemId)
        itemStateByKey[key] = ItemState.CONNECTING
        activeItemKeyByAddress[address] = key
    }

    private fun markItemBackoff(address: String, reason: String) {
        val key = activeItemKeyByAddress[address] ?: return
        itemStateByKey[key] = ItemState.BACKOFF
        itemBackoffUntilByKey[key] = System.currentTimeMillis() + ITEM_BACKOFF_MS
        Log.i(TAG, "ITEM backoff key=$key reason=$reason")
    }

    private fun markItemDoneIfDrained(address: String, itemId: String) {
        val hasPending = synchronized(pendingQueueLock) {
            pendingByDevice[address]?.any { it.itemId == itemId } == true
        }
        val hasStagedOrHeld = synchronized(schedulerLock) {
            (stagedEnqueueByAddress[address]?.any { it.itemId == itemId } == true) ||
                (heldEnqueueByAddress[address]?.any { it.itemId == itemId } == true)
        }
        if (!hasPending && !hasStagedOrHeld) {
            val key = itemKey(address, itemId)
            itemStateByKey[key] = ItemState.DONE
            if (activeItemKeyByAddress[address] == key) {
                activeItemKeyByAddress.remove(address)
            }
            activeDtnControlItemIdByAddress.remove(address, itemId)
        }
        if (!hasMeshEnvelopeWorkForAddressItem(address, itemId) &&
            activeMeshEnvelopeItemIdByAddress[address] == itemId
        ) {
            activeMeshEnvelopeItemIdByAddress.remove(address)
        }
    }

    private fun extractItemId(blob: ByteArray): String {
        extractBatchPayloadMeta(blob)?.let { return it.batchId }
        val mid = runCatching {
            val envelope = CanonicalJson.json.decodeFromString(EventEnvelope.serializer(), blob.decodeToString())
            envelope.mid
        }.getOrNull()
        if (!mid.isNullOrBlank()) return mid
        return blob.contentHashCode().toString()
    }

    private fun extractBatchPayloadMeta(blob: ByteArray): BatchPayloadMeta? {
        val batch = runCatching {
            CanonicalJson.json.decodeFromString(AppBatchEnvelope.serializer(), blob.decodeToString())
        }.getOrNull() ?: return null
        val eventIds = if (batch.v >= 2 && batch.items_v2.isNotEmpty()) {
            batch.items_v2.map { it.mid }
        } else {
            batch.items.mapNotNull { encoded ->
                runCatching { Base64Url.decode(encoded) }.getOrNull()?.let { bytes ->
                    runCatching {
                        CanonicalJson.json.decodeFromString(EventEnvelope.serializer(), bytes.decodeToString()).mid
                    }.getOrNull()
                }
            }
        }
        return BatchPayloadMeta(
            batchId = batchId(batch),
            eventIds = eventIds,
            createdAtMs = batch.ts,
        )
    }

    private fun postCleanup(address: String, mode: CleanupMode, reason: String, callSite: String? = null) {
        pendingCleanupModeByAddress.merge(address, mode) { old, incoming ->
            if (old == CleanupMode.FORCE || incoming == CleanupMode.FORCE) CleanupMode.FORCE else CleanupMode.TIMEOUT_CHECK
        }
        pendingCleanupReasonByAddress[address] = reason
        val nowMs = System.currentTimeMillis()
        val pending = synchronized(pendingQueueLock) { pendingByDevice[address]?.size ?: 0 }
        val lastProgress = lastProgressAtMs[address] ?: 0L
        val lastAge = if (lastProgress > 0L) nowMs - lastProgress else -1L
        diagnosticsListener?.onGattPostCleanup(
            address = address,
            reason = reason,
            mode = mode.name,
            pending = pending,
            lastProgressAgeMs = lastAge,
            callSite = callSite,
        )
        postLabel(address, PipelineLabel.CLEANUP)
    }

    private fun consumeLocalDisconnectReason(address: String): String? {
        return localDisconnectReasonByAddress.remove(address)
    }

    private fun markLocalDisconnectIntent(address: String, reason: String) {
        localDisconnectReasonByAddress[address] = reason
    }

    private fun postRetry(address: String, reason: String? = null) {
        if (!reason.isNullOrBlank()) {
            pendingRetryReasonByAddress[address] = reason
        }
        postLabel(address, PipelineLabel.RETRY)
    }

    private fun scheduleConnectRetry(address: String, delayMs: Long) {
        if (delayMs <= 0L) {
            postLabel(address, PipelineLabel.CONNECT)
            return
        }
        if (connectRetryScheduledByAddress.putIfAbsent(address, true) != null) return
        workerHandler.postDelayed({
            connectRetryScheduledByAddress.remove(address)
            if (running) {
                postLabel(address, PipelineLabel.CONNECT)
            }
        }, delayMs)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scanCallbacks += 1
            lastScanCallbackAtMs = System.currentTimeMillis()
            autoRecoverStreak = 0
            val record = result.scanRecord
            if (record == null) {
                scanNoRecord += 1
                if (scanCallbacks % 10L == 0L) {
                    Log.i(TAG, "BLE scan callbacks=$scanCallbacks noRecord=$scanNoRecord")
                }
                maybeLogScanStats()
                return
            }
            scanResults += 1
            val hasService = hasServiceUuid(record)
            val mfgValidation = if (BleConfig.useManufacturerData) {
                validateManufacturerData(record)
            } else {
                MfgValidation.OK
            }
            val device = result.device ?: return
            val address = device.address
            if (address == localAddress || address == INVALID_BLE_ADDRESS) {
                return
            }
            if (isStaleScanResult(result)) {
                if (BleConfig.useManufacturerData && (mfgValidation != MfgValidation.NO_MFG || hasService)) {
                    maybeLogMfgSkip(address, result.rssi, record, "stale")
                }
                maybeLogScanStats()
                return
            }
            val shouldMatch = if (BleConfig.useManufacturerData) {
                mfgValidation == MfgValidation.OK
            } else {
                hasService
            }
            if (shouldMatch) {
                if (BleConfig.useManufacturerData && isSelfManufacturerData(record)) {
                    maybeLogMfgSkip(address, result.rssi, record, "self")
                    maybeLogScanStats()
                    return
                }
                val matchedDeviceId = if (BleConfig.useManufacturerData) {
                    extractDeviceId(record)
                } else {
                    null
                }
                if (BleConfig.useManufacturerData && matchedDeviceId == null) {
                    maybeLogMfgSkip(address, result.rssi, record, "no_device_id")
                    maybeLogScanStats()
                    return
                }
                scanMatched += 1
                if (BleConfig.useManufacturerData) {
                    maybeLogMfgMatch(address, result.rssi, record, matchedDeviceId)
                }
                lastSeenDevices[address] = device
                val seenAtMs = System.currentTimeMillis()
                lastSeenAtMs[address] = seenAtMs
                lastSeenRssi[address] = result.rssi
                if (matchedDeviceId != null) {
                    val canonicalMatchedDeviceId = canonicalDeviceIdOrSelf(matchedDeviceId)
                    if (canonicalMatchedDeviceId == localDeviceId) {
                        Log.i(TAG, "stale_identity_suppressed addr=$address id=$matchedDeviceId reason=local_identity")
                        diagnosticsListener?.onNearbyRoomSelfSuppressed(
                            address = address,
                            matchedDeviceId = matchedDeviceId,
                            localDeviceId = localDeviceId,
                            reason = "local_identity",
                        )
                        maybeLogScanStats()
                        return
                    }
                    val previous = lastSeenDeviceId.put(address, canonicalMatchedDeviceId)
                    if (previous != null && previous != canonicalMatchedDeviceId) {
                        registerDeviceIdAlias(
                            staleDeviceId = previous,
                            canonicalTargetDeviceId = canonicalMatchedDeviceId,
                            address = address,
                            reason = "scan_same_address",
                            nowMs = seenAtMs,
                        )
                    }
                    if (previous != canonicalMatchedDeviceId) {
                        Log.i(
                            TAG,
                            "BLE device_id map addr=$address id=$canonicalMatchedDeviceId raw=$matchedDeviceId prev=${previous ?: "none"} rssi=${result.rssi}",
                        )
                        diagnosticsListener?.onBleDeviceIdMapped(
                            address = address,
                            deviceId = canonicalMatchedDeviceId,
                            previousId = previous,
                            rssi = result.rssi,
                        )
                    }
                    updateDeviceIdLastSeen(address, canonicalMatchedDeviceId, seenAtMs)
                    updateTargetPoolsOnDiscovery(canonicalMatchedDeviceId, seenAtMs)
                    maybeSendDtnHello(canonicalMatchedDeviceId, address, seenAtMs)
                    val nearbyPayload = nearbyRoomPayload(record)
                    val manufacturerData = record.getManufacturerSpecificData(BleConfig.manufacturerId)
                    diagnosticsListener?.onNearbyRoomDiscoveryPayload(
                        address = address,
                        deviceId = canonicalMatchedDeviceId,
                        manufacturerDataBytes = manufacturerData?.size ?: 0,
                        baseBytes = manufacturerDataBaseLength(),
                        nearbyPayloadBytes = nearbyPayload?.size ?: 0,
                        hasNearbyPayload = nearbyPayload != null && nearbyPayload.isNotEmpty(),
                        listenerPresent = nearbyRoomDiscoveryListener != null,
                        manufacturerDataHex = manufacturerData?.toHexString(),
                        nearbyPayloadHex = nearbyPayload?.toHexString(),
                    )
                    nearbyPayload?.let { payload ->
                        nearbyRoomDiscoveryListener?.onNearbyRoomMetadata(
                            canonicalMatchedDeviceId,
                            payload,
                            seenAtMs,
                            result.rssi,
                        )
                    }
                }
                offerDeviceForRoundRobin(address)
            } else if (BleConfig.useManufacturerData) {
                if (mfgValidation != MfgValidation.NO_MFG) {
                    val reason = when {
                        mfgValidation == MfgValidation.BAD_PREFIX -> "prefix_mismatch"
                        mfgValidation == MfgValidation.BAD_LENGTH -> "bad_len"
                        else -> "no_mfg"
                    }
                    maybeLogMfgSkip(address, result.rssi, record, reason)
                }
            }
            BleTrafficStats.markScanResult()
            if (scanCallbacks % 10L == 0L) {
                val uuidCount = record.serviceUuids?.size ?: 0
                Log.i(
                    TAG,
                    "BLE scan callbacks=$scanCallbacks records=$scanResults matched=$scanMatched serviceUuids=$uuidCount",
                )
            }
            maybeLogScanStats()
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            if (results.isEmpty()) return
            lastScanCallbackAtMs = System.currentTimeMillis()
            autoRecoverStreak = 0
            results.forEach { result ->
                val record = result.scanRecord ?: run {
                    scanNoRecord += 1
                    maybeLogScanStats()
                    return@forEach
                }
                scanResults += 1
                val hasService = hasServiceUuid(record)
                val mfgValidation = if (BleConfig.useManufacturerData) {
                    validateManufacturerData(record)
                } else {
                    MfgValidation.OK
                }
                val device = result.device ?: return@forEach
                val address = device.address
                if (address == localAddress || address == INVALID_BLE_ADDRESS) {
                    return@forEach
                }
                if (isStaleScanResult(result)) {
                    if (BleConfig.useManufacturerData && (mfgValidation != MfgValidation.NO_MFG || hasService)) {
                        maybeLogMfgSkip(address, result.rssi, record, "stale")
                    }
                    maybeLogScanStats()
                    return@forEach
                }
                val shouldMatch = if (BleConfig.useManufacturerData) {
                    mfgValidation == MfgValidation.OK
                } else {
                    hasService
                }
                if (shouldMatch) {
                    if (BleConfig.useManufacturerData && isSelfManufacturerData(record)) {
                        maybeLogMfgSkip(address, result.rssi, record, "self")
                        maybeLogScanStats()
                        return@forEach
                    }
                    val matchedDeviceId = if (BleConfig.useManufacturerData) {
                        extractDeviceId(record)
                    } else {
                        null
                    }
                    if (BleConfig.useManufacturerData && matchedDeviceId == null) {
                        maybeLogMfgSkip(address, result.rssi, record, "no_device_id")
                        maybeLogScanStats()
                        return@forEach
                    }
                    scanMatched += 1
                    if (BleConfig.useManufacturerData) {
                        maybeLogMfgMatch(address, result.rssi, record, matchedDeviceId)
                    }
                    lastSeenDevices[address] = device
                    val seenAtMs = System.currentTimeMillis()
                    lastSeenAtMs[address] = seenAtMs
                    lastSeenRssi[address] = result.rssi
                    if (matchedDeviceId != null) {
                        val canonicalMatchedDeviceId = canonicalDeviceIdOrSelf(matchedDeviceId)
                        if (canonicalMatchedDeviceId == localDeviceId) {
                            Log.i(TAG, "stale_identity_suppressed addr=$address id=$matchedDeviceId reason=local_identity")
                            diagnosticsListener?.onNearbyRoomSelfSuppressed(
                                address = address,
                                matchedDeviceId = matchedDeviceId,
                                localDeviceId = localDeviceId,
                                reason = "local_identity",
                            )
                            maybeLogScanStats()
                            return@forEach
                        }
                        val previous = lastSeenDeviceId.put(address, canonicalMatchedDeviceId)
                        if (previous != null && previous != canonicalMatchedDeviceId) {
                            registerDeviceIdAlias(
                                staleDeviceId = previous,
                                canonicalTargetDeviceId = canonicalMatchedDeviceId,
                                address = address,
                                reason = "scan_same_address",
                                nowMs = seenAtMs,
                            )
                        }
                        if (previous != canonicalMatchedDeviceId) {
                            Log.i(
                                TAG,
                                "BLE device_id map addr=$address id=$canonicalMatchedDeviceId raw=$matchedDeviceId prev=${previous ?: "none"} rssi=${result.rssi}",
                            )
                            diagnosticsListener?.onBleDeviceIdMapped(
                                address = address,
                                deviceId = canonicalMatchedDeviceId,
                                previousId = previous,
                                rssi = result.rssi,
                            )
                        }
                        updateDeviceIdLastSeen(address, canonicalMatchedDeviceId, seenAtMs)
                        updateTargetPoolsOnDiscovery(canonicalMatchedDeviceId, seenAtMs)
                        maybeSendDtnHello(canonicalMatchedDeviceId, address, seenAtMs)
                        val nearbyPayload = nearbyRoomPayload(record)
                        val manufacturerData = record.getManufacturerSpecificData(BleConfig.manufacturerId)
                        diagnosticsListener?.onNearbyRoomDiscoveryPayload(
                            address = address,
                            deviceId = canonicalMatchedDeviceId,
                            manufacturerDataBytes = manufacturerData?.size ?: 0,
                            baseBytes = manufacturerDataBaseLength(),
                            nearbyPayloadBytes = nearbyPayload?.size ?: 0,
                            hasNearbyPayload = nearbyPayload != null && nearbyPayload.isNotEmpty(),
                            listenerPresent = nearbyRoomDiscoveryListener != null,
                            manufacturerDataHex = manufacturerData?.toHexString(),
                            nearbyPayloadHex = nearbyPayload?.toHexString(),
                        )
                        nearbyPayload?.let { payload ->
                            nearbyRoomDiscoveryListener?.onNearbyRoomMetadata(
                                canonicalMatchedDeviceId,
                                payload,
                                seenAtMs,
                                result.rssi,
                            )
                        }
                    }
                    offerDeviceForRoundRobin(address)
                } else if (BleConfig.useManufacturerData) {
                    if (mfgValidation != MfgValidation.NO_MFG) {
                        val reason = when {
                            mfgValidation == MfgValidation.BAD_PREFIX -> "prefix_mismatch"
                            mfgValidation == MfgValidation.BAD_LENGTH -> "bad_len"
                            else -> "no_mfg"
                        }
                        maybeLogMfgSkip(address, result.rssi, record, reason)
                    }
                }
                maybeLogScanStats()
                BleTrafficStats.markScanResult()
            }
            Log.i(TAG, "BLE batch scan results=${results.size}")
            diagnosticsListener?.onBatchScanResults(results.size, scanResults, scanMatched)
        }

        override fun onScanFailed(errorCode: Int) {
            scanStarted = false
            Log.e(TAG, "BLE scan failed code=$errorCode")
        }
    }

    private fun canonicalDeviceId(deviceId: String?): String? {
        val initial = deviceId?.takeIf { it.isNotBlank() } ?: return deviceId
        if (isFallbackAddressId(initial)) return initial
        var current: String = initial
        val visited = HashSet<String>()
        while (true) {
            if (!visited.add(current)) return current
            val next = canonicalDeviceIdAlias[current] ?: return current
            if (next == current) return current
            current = next
        }
    }

    private fun canonicalDeviceIdOrSelf(deviceId: String): String {
        return canonicalDeviceId(deviceId) ?: deviceId
    }

    private fun canonicalDeviceIds(deviceIds: Set<String>?): Set<String>? {
        if (deviceIds == null) return null
        return deviceIds
            .map { canonicalDeviceIdOrSelf(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun registerDeviceIdAlias(
        staleDeviceId: String?,
        canonicalTargetDeviceId: String?,
        address: String?,
        reason: String,
        nowMs: Long,
    ) {
        val stale = canonicalDeviceId(staleDeviceId) ?: return
        val canonical = canonicalDeviceId(canonicalTargetDeviceId) ?: return
        if (stale.isBlank() || canonical.isBlank()) return
        if (stale == canonical) return
        if (isFallbackAddressId(stale) || isFallbackAddressId(canonical)) return
        if (canonical == localDeviceId) return

        canonicalDeviceIdAlias[stale] = canonical

        val bestAddress = address
            ?: lastSeenAddrByDeviceId[canonical]
            ?: lastSeenAddrByDeviceId[stale]
            ?: lastTargetByDeviceId[canonical]
            ?: lastTargetByDeviceId[stale]

        bestAddress?.let { addr ->
            lastSeenAddrByDeviceId[canonical] = addr
            if (lastSeenDeviceId[addr] == stale) {
                lastSeenDeviceId[addr] = canonical
            }
        }
        lastSeenAddrByDeviceId.remove(stale)

        val seenAt = maxOf(lastSeenIdAtMs[canonical] ?: 0L, lastSeenIdAtMs[stale] ?: 0L, nowMs)
        lastSeenIdAtMs[canonical] = seenAt
        lastSeenIdAtMs.remove(stale)

        moveLongState(lastActiveAtMsByDeviceId, stale, canonical, nowMs)
        moveLongState(lastGraceLogAtMsByDeviceId, stale, canonical, nowMs)
        moveLongState(lastTargetSelectionAtByDeviceId, stale, canonical, nowMs)
        moveLongState(cooldownUntilByDeviceId, stale, canonical, nowMs)
        moveLongState(deviceIdDwellUntil, stale, canonical, nowMs)
        moveLongState(deviceIdRotationCooldownUntil, stale, canonical, nowMs)
        moveLongState(deviceIdRotationRecoverUntil, stale, canonical, nowMs)
        moveLongState(deviceIdQuietUntil, stale, canonical, nowMs)
        moveLongState(last133AtByDeviceId, stale, canonical, nowMs)
        moveLongState(post133RecoveryUntilByDeviceId, stale, canonical, nowMs)

        deviceIdRotationRecoverAddress.remove(stale)?.let { recoverAddress ->
            deviceIdRotationRecoverAddress.putIfAbsent(canonical, recoverAddress)
        }

        lastTargetByDeviceId.remove(stale)?.let { oldAddress ->
            lastTargetByDeviceId.putIfAbsent(canonical, bestAddress ?: oldAddress)
        }

        val staleTokens = hotOpaqueRetryTokensByDevice.remove(stale)
        if (staleTokens != null) {
            hotOpaqueRetryTokensByDevice
                .computeIfAbsent(canonical) { ConcurrentHashMap() }
                .putAll(staleTokens)
        }

        val movedOpaqueKeys = migrateOpaqueRetryKeys(stale, canonical)
        val rewrittenQueued = rewriteQueuedPacketDeviceIds(stale, canonical, address)

        synchronized(targetPoolLock) {
            val wasMain = mainTargetDeviceIds.remove(stale)
            val wasPotential = potentialTargetDeviceIds.remove(stale)
            if (wasMain) {
                mainTargetDeviceIds.remove(canonical)
                mainTargetDeviceIds.add(canonical)
            } else if (wasPotential) {
                potentialTargetDeviceIds.remove(canonical)
                potentialTargetDeviceIds.add(canonical)
            }
            Unit
        }

        Log.i(
            TAG,
            "identity_alias_added stale=$stale canonical=$canonical addr=${bestAddress ?: "unknown"} reason=$reason rewritten_queued=$rewrittenQueued moved_opaque=$movedOpaqueKeys",
        )
        Log.i(
            TAG,
            "identity_canonicalized stale=$stale canonical=$canonical reason=$reason",
        )
    }

    private fun moveLongState(
        map: ConcurrentHashMap<String, Long>,
        staleDeviceId: String,
        canonicalDeviceId: String,
        nowMs: Long,
    ) {
        val staleValue = map.remove(staleDeviceId) ?: return
        map.merge(canonicalDeviceId, staleValue) { old, incoming -> maxOf(old, incoming, nowMs) }
    }

    private fun migrateOpaqueRetryKeys(staleDeviceId: String, canonicalDeviceId: String): Int {
        var moved = 0
        opaqueRetryCapsulesByBatchAndDevice.entries.toList().forEach { (key, capsules) ->
            val suffix = "\u0000$staleDeviceId"
            if (!key.endsWith(suffix)) return@forEach
            val batchId = key.removeSuffix(suffix)
            val canonicalKey = opaqueRetryKey(batchId, canonicalDeviceId)
            val removed = opaqueRetryCapsulesByBatchAndDevice.remove(key) ?: return@forEach
            opaqueRetryCapsulesByBatchAndDevice
                .computeIfAbsent(canonicalKey) { ConcurrentHashMap.newKeySet() }
                .addAll(removed)
            moved += 1
        }
        return moved
    }

    private fun rewriteQueuedPacketDeviceIds(
        staleDeviceId: String,
        canonicalDeviceId: String,
        preferredAddress: String?,
    ): Int {
        var rewritten = 0

        fun rewriteQueue(queue: ArrayDeque<PendingPacket>): ArrayDeque<PendingPacket> {
            val replacement = ArrayDeque<PendingPacket>(queue.size)
            queue.forEach { packet ->
                if (packet.deviceId == staleDeviceId) {
                    replacement.addLast(packet.copy(deviceId = canonicalDeviceId))
                    rewritten += 1
                } else {
                    replacement.addLast(packet)
                }
            }
            return replacement
        }

        synchronized(pendingQueueLock) {
            pendingByDevice.entries.toList().forEach { (address, queue) ->
                if (preferredAddress != null && address != preferredAddress && queue.none { it.deviceId == staleDeviceId }) {
                    return@forEach
                }
                pendingByDevice[address] = rewriteQueue(queue)
            }
        }
        synchronized(schedulerLock) {
            stagedEnqueueByAddress.entries.toList().forEach { (address, queue) ->
                if (preferredAddress != null && address != preferredAddress && queue.none { it.deviceId == staleDeviceId }) {
                    return@forEach
                }
                stagedEnqueueByAddress[address] = rewriteQueue(queue)
            }
            heldEnqueueByAddress.entries.toList().forEach { (address, queue) ->
                if (preferredAddress != null && address != preferredAddress && queue.none { it.deviceId == staleDeviceId }) {
                    return@forEach
                }
                heldEnqueueByAddress[address] = rewriteQueue(queue)
            }
        }
        activePendingPacketByAddress.entries.toList().forEach { (address, packet) ->
            if ((preferredAddress == null || address == preferredAddress) && packet.deviceId == staleDeviceId) {
                activePendingPacketByAddress[address] = packet.copy(deviceId = canonicalDeviceId)
                rewritten += 1
            }
        }
        if (rewritten > 0) {
            Log.i(
                TAG,
                "queued_target_rewritten stale=$staleDeviceId canonical=$canonicalDeviceId preferred_addr=${preferredAddress ?: "any"} count=$rewritten",
            )
        }
        return rewritten
    }

    private fun hasServiceUuid(record: android.bluetooth.le.ScanRecord): Boolean {
        if (record.serviceUuids?.any { it == serviceUuid } == true) return true
        val data = record.bytes ?: return false
        val target = uuidToLeBytes(serviceUuid.uuid)
        var index = 0
        while (index < data.size) {
            val len = data[index].toInt() and 0xFF
            if (len == 0) break
            val typeIndex = index + 1
            if (typeIndex >= data.size) break
            val type = data[typeIndex].toInt() and 0xFF
            if (type == 0x06 || type == 0x07) {
                var offset = typeIndex + 1
                val end = (index + 1 + len).coerceAtMost(data.size)
                while (offset + 16 <= end) {
                    if (data.copyOfRange(offset, offset + 16).contentEquals(target)) {
                        return true
                    }
                    offset += 16
                }
            }
            index += len + 1
        }
        return false
    }

    private fun uuidToLeBytes(uuid: java.util.UUID): ByteArray {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        return byteArrayOf(
            (lsb and 0xFF).toByte(),
            ((lsb shr 8) and 0xFF).toByte(),
            ((lsb shr 16) and 0xFF).toByte(),
            ((lsb shr 24) and 0xFF).toByte(),
            ((lsb shr 32) and 0xFF).toByte(),
            ((lsb shr 40) and 0xFF).toByte(),
            ((lsb shr 48) and 0xFF).toByte(),
            ((lsb shr 56) and 0xFF).toByte(),
            (msb and 0xFF).toByte(),
            ((msb shr 8) and 0xFF).toByte(),
            ((msb shr 16) and 0xFF).toByte(),
            ((msb shr 24) and 0xFF).toByte(),
            ((msb shr 32) and 0xFF).toByte(),
            ((msb shr 40) and 0xFF).toByte(),
            ((msb shr 48) and 0xFF).toByte(),
            ((msb shr 56) and 0xFF).toByte(),
        )
    }

    private fun hasValidManufacturerData(record: android.bluetooth.le.ScanRecord): Boolean {
        if (!BleConfig.useManufacturerData) return true
        return validateManufacturerData(record) == MfgValidation.OK
    }

    private fun isSelfManufacturerData(record: android.bluetooth.le.ScanRecord): Boolean {
        if (!BleConfig.useManufacturerData) return false
        val deviceId = localDeviceIdBytes
        val data = record.getManufacturerSpecificData(BleConfig.manufacturerId) ?: return false
        if (data.size < manufacturerDataBaseLength()) return false
        val prefixOk = data.copyOfRange(0, MFG_PREFIX.size).contentEquals(MFG_PREFIX)
        if (!prefixOk) return false
        val idPart = data.copyOfRange(MFG_PREFIX.size, MFG_PREFIX.size + deviceId.size)
        return idPart.contentEquals(deviceId)
    }

    private fun extractDeviceId(record: android.bluetooth.le.ScanRecord): String? {
        if (!BleConfig.useManufacturerData) return null
        val data = record.getManufacturerSpecificData(BleConfig.manufacturerId) ?: return null
        val baseLength = manufacturerDataBaseLength()
        if (data.size < baseLength) return null
        if (!data.copyOfRange(0, MFG_PREFIX.size).contentEquals(MFG_PREFIX)) return null
        val id = data.copyOfRange(MFG_PREFIX.size, baseLength)
        return id.joinToString("") { byte -> "%02X".format(byte) }
    }

    private enum class MfgValidation {
        OK,
        NO_MFG,
        BAD_PREFIX,
        BAD_LENGTH,
    }

    private fun validateManufacturerData(record: android.bluetooth.le.ScanRecord): MfgValidation {
        val data = record.getManufacturerSpecificData(BleConfig.manufacturerId) ?: return MfgValidation.NO_MFG
        if (data.size < MFG_PREFIX.size) return MfgValidation.BAD_PREFIX
        if (!data.copyOfRange(0, MFG_PREFIX.size).contentEquals(MFG_PREFIX)) return MfgValidation.BAD_PREFIX
        val expectedLen = manufacturerDataBaseLength()
        return if (data.size >= expectedLen) MfgValidation.OK else MfgValidation.BAD_LENGTH
    }

    private fun manufacturerDataBaseLength(): Int = MFG_PREFIX.size + localDeviceIdBytes.size

    private fun nearbyRoomPayload(record: android.bluetooth.le.ScanRecord): ByteArray? {
        if (!BleConfig.useManufacturerData) return null
        val data = record.getManufacturerSpecificData(BleConfig.manufacturerId) ?: return null
        val baseLength = manufacturerDataBaseLength()
        if (data.size <= baseLength) return null
        return data.copyOfRange(baseLength, data.size)
    }

    private fun isStaleScanResult(result: ScanResult): Boolean {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000L
        return ageMs > STALE_SCAN_RESULT_MS
    }

    private fun maybeLogMfgMatch(
        address: String,
        rssi: Int,
        record: android.bluetooth.le.ScanRecord,
        matchedDeviceId: String?,
    ) {
        if (!BleConfig.useManufacturerData) return
        val nowMs = System.currentTimeMillis()
        val lastLoggedAtMs = lastMfgLogAtMs[address] ?: 0L
        if (nowMs - lastLoggedAtMs < 10_000L) return
        lastMfgLogAtMs[address] = nowMs
        val data = record.getManufacturerSpecificData(BleConfig.manufacturerId) ?: return
        val hex = data.joinToString("") { byte -> "%02X".format(byte) }
        Log.i(
            TAG,
            "BLE mfg match addr=$address rssi=$rssi len=${data.size} mfg=$hex id=${matchedDeviceId ?: "unknown"}",
        )
        diagnosticsListener?.onBleMfgMatch(
            address = address,
            rssi = rssi,
            length = data.size,
            hex = hex,
            matchedDeviceId = matchedDeviceId,
        )
        if (matchedDeviceId != null) {
            Log.i(TAG, "BLE scan matched device_id addr=$address id=$matchedDeviceId")
        }
    }

    private fun maybeLogMfgSkip(
        address: String,
        rssi: Int,
        record: android.bluetooth.le.ScanRecord,
        reason: String,
    ) {
        val nowMs = System.currentTimeMillis()
        val lastLoggedAtMs = lastMfgSkipLogAtMs[address] ?: 0L
        if (nowMs - lastLoggedAtMs < 10_000L) return
        lastMfgSkipLogAtMs[address] = nowMs
        val data = record.getManufacturerSpecificData(BleConfig.manufacturerId)
        val hex = data?.joinToString("") { byte -> "%02X".format(byte) }
        val len = data?.size ?: 0
        Log.i(
            TAG,
            "BLE mfg skip addr=$address rssi=$rssi reason=$reason len=$len mfg=${hex ?: "none"}",
        )
        diagnosticsListener?.onBleMfgSkip(
            address = address,
            rssi = rssi,
            reason = reason,
            length = len,
            hex = hex,
        )
    }

    private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02X".format(byte) }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val address = device?.address ?: return
            Log.i(TAG, "GATT server conn addr=$address status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val nowMs = System.currentTimeMillis()
                lastServerConnAtMs = nowMs
                markPeerActive(address, nowMs)
            }
            diagnosticsListener?.onGattServerConnection(address, status, newState)
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            Log.i(TAG, "GATT service added status=$status uuid=${service?.uuid}")
            diagnosticsListener?.onGattServiceAdded(status)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val server = gattServer
            if (device == null || server == null) {
                val reason = when {
                    device == null && server == null -> "drop_device_null_server_null"
                    device == null -> "drop_device_null"
                    else -> "drop_server_null"
                }
                Log.w(
                    TAG,
                    "gatt_write_drop_null req=$requestId response=$responseNeeded reason=$reason",
                )
                diagnosticsListener?.onGattWriteRejected(
                    device?.address ?: "unknown",
                    reason,
                    BluetoothGatt.GATT_FAILURE,
                )
                return
            }
            if (preparedWrite) {
                val status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                Log.w(
                    TAG,
                    "gatt_prepared_write_rejected addr=${device.address} req=$requestId offset=$offset response=$responseNeeded",
                )
                diagnosticsListener?.onGattWriteRejected(device.address, "prepared_write", status)
                if (responseNeeded) {
                    server.sendResponse(device, requestId, status, 0, null)
                }
                return
            }
            if (offset != 0) {
                val status = BluetoothGatt.GATT_INVALID_OFFSET
                Log.w(
                    TAG,
                    "gatt_offset_write_rejected addr=${device.address} req=$requestId offset=$offset response=$responseNeeded",
                )
                diagnosticsListener?.onGattWriteRejected(device.address, "invalid_offset:$offset", status)
                if (responseNeeded) {
                    server.sendResponse(device, requestId, status, 0, null)
                }
                return
            }
            val nowMs = System.currentTimeMillis()
            inboundWriteCount += 1
            if (nowMs - lastInboundLogAtMs >= INBOUND_LOG_INTERVAL_MS) {
                Log.i(TAG, "GATT inbound writes=$inboundWriteCount")
                lastInboundLogAtMs = nowMs
                inboundWriteCount = 0L
            }
            // Validate request before acknowledging SUCCESS to avoid "ghost success" (client thinks sent, we drop).
            if (characteristic?.uuid != characteristicUuid) {
                val status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                Log.w(
                    TAG,
                    "gatt_write_rejected_unsupported_char addr=${device.address} req=$requestId response=$responseNeeded",
                )
                diagnosticsListener?.onGattWriteRejected(device.address, "unsupported_characteristic", status)
                if (responseNeeded) {
                    server.sendResponse(device, requestId, status, 0, null)
                }
                return
            }
            if (value == null) {
                val status = BluetoothGatt.GATT_FAILURE
                Log.w(
                    TAG,
                    "gatt_write_rejected_null_value addr=${device.address} req=$requestId response=$responseNeeded",
                )
                diagnosticsListener?.onGattWriteRejected(device.address, "null_value", status)
                if (responseNeeded) {
                    server.sendResponse(device, requestId, status, 0, null)
                }
                return
            }

            val nowSeenMs = System.currentTimeMillis()
            Log.i(
                TAG,
                "GATT recv chunk bytes=${value.size} from=${device.address} offset=$offset prepared=$preparedWrite",
            )
            lastRecvChunkAtMs = nowSeenMs
            resolveStableDeviceIdForAddress(device.address)?.let { resolvedDeviceId ->
                lastSeenDeviceId[device.address] = resolvedDeviceId
                updateDeviceIdLastSeen(device.address, resolvedDeviceId, nowSeenMs)
            }
            markPeerActive(device.address, nowSeenMs)
            diagnosticsListener?.onGattRecvChunk(device.address, value.size)
            maybeLogRxTargetMismatch(device.address)

            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            val nowSeconds = System.currentTimeMillis() / 1000L
            val senderKey = device.address
            if (chunkDeduper.seen(senderKey, value, nowSeconds)) return
            val chunkPayload = if (BleConfig.debugCorruptNextChunk) {
                BleConfig.debugCorruptNextChunk = false
                val corrupted = value.clone()
                if (corrupted.isNotEmpty()) {
                    val last = corrupted.lastIndex
                    corrupted[last] = (corrupted[last].toInt() xor 0xFF).toByte()
                    Log.w(TAG, "Debug: corrupted one BLE chunk byte for CRC test")
                }
                corrupted
            } else {
                value
            }
            val reassembled = reassembler.add(senderKey, chunkPayload, nowSeconds)
            diagnosticsListener?.onReassemblyProgress(
                address = device.address,
                chunkId = reassembled.chunkId,
                index = reassembled.index,
                total = reassembled.total,
                received = reassembled.received,
                complete = reassembled.complete,
                checksumOk = reassembled.checksumOk,
                reason = reassembled.reason,
            )
            if (reassembled.complete && reassembled.payload != null) {
                if (!reassembled.checksumOk) {
                    Log.w(TAG, "Reassembly CRC mismatch; drop payload")
                    return
                }
                Log.i(TAG, "GATT reassembled bytes=${reassembled.payload.size} from=${device?.address}")
                diagnosticsListener?.onGattReassembled(device.address, reassembled.payload.size)
                BleTrafficStats.addDownloaded(reassembled.payload.size.toLong())
                BleTrafficStats.addReceivedPacket()
                DtnControlCodec.decode(reassembled.payload)?.let { packet ->
                    diagnosticsListener?.onDtnControlEnvelopeDetected(
                        address = device.address,
                        type = if (packet.type == DtnControlTypes.DATA && packet.carrierData.isNotEmpty()) {
                            "carrier_data"
                        } else {
                            packet.type
                        },
                        bytes = reassembled.payload.size,
                    )
                }
                val rawJson = reassembled.payload.toString(Charsets.UTF_8)
                if (handleDtnControlPayload(reassembled.payload, device.address)) return
                val parsedEnvelope = parseEnvelope(rawJson)
                if (parsedEnvelope != null &&
                    inboundEventDeduper.seen(parsedEnvelope.gid, parsedEnvelope.mid, nowSeconds)
                ) {
                    Log.w(
                        TAG,
                        "Skip ingest duplicate gid=${parsedEnvelope.gid} mid=${parsedEnvelope.mid}",
                    )
                    diagnosticsListener?.onForwardDedupeSkip(parsedEnvelope.gid, parsedEnvelope.mid)
                    return
                }
                if (!envelopeDeduper.seen(reassembled.payload, nowSeconds)) {
                    Log.i(TAG, "GATT deliver envelope bytes=${reassembled.payload.size}")
                    diagnosticsListener?.onGattDeliver(reassembled.payload.size)
                    val receiveResult = receiver.onEnvelopeReceivedWithMeta(reassembled.payload)
                    val accepted = receiveResult.accepted
                    if (!accepted && receiveResult.retryable) {
                        envelopeDeduper.forget(reassembled.payload)
                        foregroundDiagnostic(
                            NearbyForegroundDiagnosticLabels.RX_DEDUPE_RELEASE,
                            "packet_id=${reassembled.payload.contentHashCode().toUInt().toString(16).take(8)} reason=retryable_receiver_rejection",
                        )
                        Log.i(TAG, "Inbound envelope left retryable after receiver rejection")
                    }
                    val allowOpaque = !accepted &&
                        receiveResult.opaqueForwardAllowed &&
                        BleConfig.forwardOpaqueMode &&
                        canForwardOpaque(rawJson, nowSeconds)
                    val parsedBatch = parseAppBatch(rawJson)
                    diagnosticsListener?.onRelayForwardDecision(
                        kind = if (parsedBatch != null) "app_batch" else "envelope",
                        accepted = accepted,
                        relayEligible = receiveResult.relayEligible,
                        reason = when {
                            accepted && receiveResult.relayEligible -> "relay_candidate"
                            allowOpaque -> "opaque_candidate"
                            !accepted -> "not_accepted"
                            else -> "relay_ineligible"
                        },
                    )
                    val appBatch = if (accepted && receiveResult.relayEligible) parsedBatch else null
                    if (appBatch != null) {
                        val relayPayload = rawJson.toByteArray(Charsets.UTF_8)
                        val relayNowMs = System.currentTimeMillis()
                        val sourceDeviceId = resolveStableDeviceIdForAddress(device.address)
                        val relayOriginDeviceIds = appBatchRelayOriginDeviceIds(
                            batch = appBatch,
                            sourceDeviceId = sourceDeviceId,
                            receiverOriginDeviceIds = receiveResult.relayOriginDeviceIds,
                        )
                        val candidateDeviceIds =
                            relayCandidateDeviceIds(
                                nowMs = relayNowMs,
                                sourceAddress = device.address,
                                sourceDeviceId = sourceDeviceId,
                                originDeviceIds = relayOriginDeviceIds,
                            ).toList()
                        diagnosticsListener?.onRelayBatchSeedPending(
                            batchId = batchId(appBatch),
                            gid = appBatch.gid,
                            payload = relayPayload,
                            eventCount = batchEventCount(appBatch),
                            firstEventCreatedT = batchFirstEventCreatedT(appBatch),
                            lastEventCreatedT = batchLastEventCreatedT(appBatch),
                            deviceIds = candidateDeviceIds,
                            nowMs = relayNowMs,
                        )
                        val throttleAllowed = forwardThrottle.allow(nowSeconds)
                        val relayReason =
                            when {
                                !accepted -> "not_accepted"
                                !receiveResult.relayEligible -> "relay_ineligible"
                                candidateDeviceIds.isEmpty() -> "no_candidate_peer"
                                !throttleAllowed -> "throttle_blocked"
                                else -> "scheduled"
                            }
                        diagnosticsListener?.onBatchForwardDecision(
                            batchId = batchId(appBatch),
                            accepted = accepted,
                            relayEligible = receiveResult.relayEligible,
                            candidateDeviceIds = candidateDeviceIds,
                            throttleAllowed = throttleAllowed,
                            reason = relayReason,
                        )
                        if (candidateDeviceIds.isNotEmpty() && throttleAllowed) {
                            scheduleBatchForward(
                                batch = appBatch,
                                tagged = relayPayload,
                                sourceAddress = device.address,
                                originDeviceIds = relayOriginDeviceIds,
                                nowMs = relayNowMs,
                            )
                            scheduleDelayedRelay(
                                batch = appBatch,
                                tagged = relayPayload,
                                sourceAddress = device.address,
                                originDeviceIds = relayOriginDeviceIds,
                                nowMs = relayNowMs,
                            )
                        }
                    } else if (accepted && receiveResult.relayEligible && forwardThrottle.allow(nowSeconds)) {
                        maybeRelayCompensate(
                            tagged = rawJson.toByteArray(Charsets.UTF_8),
                            receiveResult = receiveResult,
                            sourceAddress = device.address,
                            nowMs = System.currentTimeMillis(),
                        )
                    } else if (allowOpaque && forwardThrottle.allow(nowSeconds)) {
                        val envelope = parsedEnvelope ?: parseEnvelope(rawJson)
                        val opaqueBatch = if (envelope == null) parsedBatch ?: parseAppBatch(rawJson) else null
                        val opaqueGid = envelope?.gid ?: opaqueBatch?.gid
                        val opaqueId = envelope?.mid ?: opaqueBatch?.let { batchId(it) }
                        if (opaqueGid.isNullOrBlank() || opaqueId.isNullOrBlank()) {
                            diagnosticsListener?.onRelayForwardDecision(
                                kind = "opaque",
                                accepted = accepted,
                                relayEligible = receiveResult.relayEligible,
                                reason = "invalid_payload_for_dedupe",
                            )
                            Log.w(TAG, "Skip forward: invalid opaque payload for dedupe")
                            return
                        }
                        if (forwardDeduper.seen(opaqueGid, opaqueId, nowSeconds)) {
                            diagnosticsListener?.onRelayForwardDecision(
                                kind = "opaque",
                                accepted = accepted,
                                relayEligible = receiveResult.relayEligible,
                                reason = "dedupe_hit",
                            )
                            Log.w(TAG, "Skip forward: dedupe hit gid=$opaqueGid id=$opaqueId")
                            diagnosticsListener?.onForwardDedupeSkip(opaqueGid, opaqueId)
                            return
                        }
                        val forwardPayload = rawJson.toByteArray(Charsets.UTF_8)
                        val originDeviceIds = opaqueOriginDeviceIds(
                            envelope = envelope,
                            batch = opaqueBatch,
                            sourceDeviceId = resolveStableDeviceIdForAddress(device.address),
                        )
                        val opaqueForwarded = saveOpaqueForRetry(
                            payload = forwardPayload,
                            envelope = envelope,
                            batch = opaqueBatch,
                            sourcePeerId = originDeviceIds.firstOrNull(),
                            nowSeconds = nowSeconds,
                        )
                        if (opaqueForwarded) {
                            scheduleOpaqueRetryDrain()
                        }
                        diagnosticsListener?.onRelayForwardDecision(
                            kind = "opaque",
                            accepted = accepted,
                            relayEligible = receiveResult.relayEligible,
                            reason = if (opaqueForwarded) {
                                "opaque_forward_scheduled"
                            } else {
                                "opaque_forward_skipped"
                            },
                        )
                    } else if (!accepted) {
                        diagnosticsListener?.onRelayForwardDecision(
                            kind = "envelope",
                            accepted = accepted,
                            relayEligible = receiveResult.relayEligible,
                            reason = "not_accepted_skip",
                        )
                        Log.w(TAG, "Skip forward: envelope not accepted")
                    }
                }
            }
        }
    }

    private fun maybeRelayCompensate(
        tagged: ByteArray,
        receiveResult: EnvelopeReceiveResult,
        sourceAddress: String,
        nowMs: Long,
    ) {
        val relayKey = receiveResult.relayKey ?: return
        val summary = receiveResult.relaySummary ?: "relay"
        val state = relayCompensationStateByKey.compute(relayKey) { _, existing ->
            existing ?: RelayCompensationState(firstAcceptedAtMs = nowMs)
        } ?: return
        if (nearbyForegroundMode) {
            state.firstAcceptedAtMs += NEARBY_FOREGROUND_RELAY_RECHECK_MS
            foregroundDiagnostic(
                NearbyForegroundDiagnosticLabels.RELAY_DEFERRED,
                "runtime_state=${foregroundRuntimeState()} relay_key=${relayKey.hashCode().toUInt().toString(16)} reason=foreground_policy retry_consumed=false",
            )
            Log.i(TAG, "NEARBY_FOREGROUND relay_deferred kind=compensation key=$relayKey")
            workerHandler.postDelayed(
                { maybeRelayCompensate(tagged, receiveResult, sourceAddress, System.currentTimeMillis()) },
                NEARBY_FOREGROUND_RELAY_RECHECK_MS,
            )
            return
        }
        val ageMs = nowMs - state.firstAcceptedAtMs
        if (ageMs > RELAY_COMPENSATION_WINDOW_MS) {
            diagnosticsListener?.onRelayCompensateSkipped(relayKey, summary, "window_expired", state.attemptCount)
            return
        }
        if (state.attemptCount >= RELAY_COMPENSATION_MAX_ATTEMPTS) {
            diagnosticsListener?.onRelayCompensateSkipped(relayKey, summary, "attempt_cap", state.attemptCount)
            return
        }
        val targetAddress = selectRelayCompensationTarget(nowMs, sourceAddress, state.targetAddresses)
        if (targetAddress == null) {
            diagnosticsListener?.onRelayCompensateSkipped(relayKey, summary, "no_target", state.attemptCount)
            return
        }
        val queued = broadcastEnvelopeToTargets(tagged, listOf(targetAddress))
        if (!queued) {
            diagnosticsListener?.onRelayCompensateSkipped(relayKey, summary, "enqueue_failed", state.attemptCount)
            return
        }
        state.attemptCount += 1
        state.targetAddresses.add(targetAddress)
        diagnosticsListener?.onRelayCompensateQueued(relayKey, summary, targetAddress, state.attemptCount)
    }

    private fun scheduleBatchForward(
        batch: AppBatchEnvelope,
        tagged: ByteArray,
        sourceAddress: String,
        originDeviceIds: Set<String>,
        nowMs: Long,
    ) {
        val id = batchId(batch)
        val sourceDeviceId = resolveStableDeviceIdForAddress(sourceAddress)
        if (sourceDeviceId.isNullOrBlank()) {
            diagnosticsListener?.onBatchForwardSkipped(id, "last_hop_unknown_drop", attempt = 0, delayMs = 0L)
            return
        }
        val effectiveOriginDeviceIds = LinkedHashSet(originDeviceIds)
        effectiveOriginDeviceIds.add(sourceDeviceId)
        effectiveOriginDeviceIds.add(localDeviceId)
        val state = batchForwardStateById.putIfAbsent(
            id,
            BatchForwardState(
                batchId = id,
                payload = tagged,
                sourceAddress = sourceAddress,
                sourceDeviceId = sourceDeviceId,
                originDeviceIds = effectiveOriginDeviceIds.toMutableSet(),
                firstAcceptedAtMs = nowMs,
            ),
        )
        if (state != null) {
            state.originDeviceIds.addAll(effectiveOriginDeviceIds)
            diagnosticsListener?.onBatchForwardSkipped(id, "already_scheduled", attempt = 0, delayMs = 0L)
            return
        }
        val candidateDeviceIds = relayCandidateDeviceIds(
            nowMs = nowMs,
            sourceAddress = sourceAddress,
            sourceDeviceId = sourceDeviceId,
            originDeviceIds = originDeviceIds,
        ).toList()
        diagnosticsListener?.onRelayBatchSeedPending(
            batchId = id,
            gid = batch.gid,
            payload = tagged,
            eventCount = batchEventCount(batch),
            firstEventCreatedT = batchFirstEventCreatedT(batch),
            lastEventCreatedT = batchLastEventCreatedT(batch),
            deviceIds = candidateDeviceIds,
            nowMs = nowMs,
        )
        scheduleBatchForwardAttempt(id, attempt = 1, delayMs = 0L)
        scheduleBatchForwardAttempt(id, attempt = 2, delayMs = BATCH_FORWARD_RETRY_DELAY_MS)
    }

    private fun scheduleDelayedRelay(
        batch: AppBatchEnvelope,
        tagged: ByteArray,
        sourceAddress: String,
        originDeviceIds: Set<String>,
        nowMs: Long,
    ) {
        val sourceDeviceId = resolveStableDeviceIdForAddress(sourceAddress)
        if (sourceDeviceId.isNullOrBlank()) {
            diagnosticsListener?.onDelayedRelayDropped(
                batchId = batchId(batch),
                targetDeviceId = "unknown",
                reason = "last_hop_unknown_drop",
            )
            return
        }
        if (!shouldScheduleDelayedRelay(batch, tagged, sourceDeviceId, nowMs)) return

        val effectiveOriginDeviceIds = LinkedHashSet(originDeviceIds)
        effectiveOriginDeviceIds.add(sourceDeviceId)
        effectiveOriginDeviceIds.add(localDeviceId)

        val targetDeviceId = selectDelayedRelayTargetDeviceId(
            batchId = batchId(batch),
            sourceAddress = sourceAddress,
            sourceDeviceId = sourceDeviceId,
            originDeviceIds = effectiveOriginDeviceIds,
            nowMs = nowMs,
        ) ?: return

        val key = delayedRelayKey(batchId(batch), targetDeviceId)
        if (delayedRelayStateByKey.containsKey(key)) {
            diagnosticsListener?.onDelayedRelayDropped(
                batchId = batchId(batch),
                targetDeviceId = targetDeviceId,
                reason = "duplicate",
            )
            return
        }

        val jitterMs = ThreadLocalRandom.current().nextLong(0L, DELAYED_RELAY_JITTER_MS + 1L)
        val fireAtMs = nowMs + DELAYED_RELAY_DELAY_MS + jitterMs
        delayedRelayStateByKey[key] = DelayedRelayState(
            key = key,
            batchId = batchId(batch),
            payload = tagged,
            sourceAddress = sourceAddress,
            sourceDeviceId = sourceDeviceId,
            originDeviceIds = effectiveOriginDeviceIds,
            targetDeviceId = targetDeviceId,
            createdAtMs = nowMs,
            fireAtMs = fireAtMs,
        )

        diagnosticsListener?.onDelayedRelayScheduled(
            batchId = batchId(batch),
            sourceDeviceId = sourceDeviceId ?: "unknown",
            targetDeviceId = targetDeviceId,
            delayMs = DELAYED_RELAY_DELAY_MS + jitterMs,
        )

        workerHandler.postDelayed(
            { executeDelayedRelay(key) },
            DELAYED_RELAY_DELAY_MS + jitterMs,
        )
    }

    private fun executeDelayedRelay(key: String) {
        if (!running) return
        val state = delayedRelayStateByKey[key] ?: return
        if (state.fired) return

        val nowMs = System.currentTimeMillis()
        if (nearbyForegroundMode) {
            state.createdAtMs += NEARBY_FOREGROUND_RELAY_RECHECK_MS
            state.fireAtMs = nowMs + NEARBY_FOREGROUND_RELAY_RECHECK_MS
            Log.i(TAG, "NEARBY_FOREGROUND relay_deferred kind=delayed batch=${state.batchId}")
            workerHandler.postDelayed(
                { executeDelayedRelay(key) },
                NEARBY_FOREGROUND_RELAY_RECHECK_MS,
            )
            return
        }
        if (nowMs - state.createdAtMs > DELAYED_RELAY_MAX_AGE_MS) {
            delayedRelayStateByKey.remove(key)
            diagnosticsListener?.onDelayedRelayDropped(
                batchId = state.batchId,
                targetDeviceId = state.targetDeviceId,
                reason = "expired",
            )
            return
        }

        val targetStillNeeded = diagnosticsListener
            ?.selectRelayBatchTargetDeviceIds(state.batchId, listOf(state.targetDeviceId), nowMs)
            ?.contains(state.targetDeviceId)
            ?: true

        if (!targetStillNeeded) {
            delayedRelayStateByKey.remove(key)
            diagnosticsListener?.onDelayedRelayDropped(
                batchId = state.batchId,
                targetDeviceId = state.targetDeviceId,
                reason = "target_no_longer_missing",
            )
            return
        }

        val targetNearby = relayCandidateDeviceIds(
            nowMs = nowMs,
            sourceAddress = state.sourceAddress,
            sourceDeviceId = state.sourceDeviceId,
            originDeviceIds = state.originDeviceIds,
        ).contains(state.targetDeviceId)

        if (!targetNearby) {
            delayedRelayStateByKey.remove(key)
            diagnosticsListener?.onDelayedRelayDropped(
                batchId = state.batchId,
                targetDeviceId = state.targetDeviceId,
                reason = "target_not_nearby",
            )
            return
        }

        if (isMeshBusyForDelayedRelay()) {
            if (state.deferredBusyCount >= DELAYED_RELAY_MAX_BUSY_DEFERS) {
                delayedRelayStateByKey.remove(key)
                diagnosticsListener?.onDelayedRelayDropped(
                    batchId = state.batchId,
                    targetDeviceId = state.targetDeviceId,
                    reason = "still_busy",
                )
                return
            }
            state.deferredBusyCount += 1
            state.fireAtMs = nowMs + DELAYED_RELAY_BUSY_DEFER_MS
            diagnosticsListener?.onDelayedRelayDeferred(
                batchId = state.batchId,
                targetDeviceId = state.targetDeviceId,
                deferMs = DELAYED_RELAY_BUSY_DEFER_MS,
                pending = totalPendingPackets(),
            )
            workerHandler.postDelayed(
                { executeDelayedRelay(key) },
                DELAYED_RELAY_BUSY_DEFER_MS,
            )
            return
        }

        if (state.originDeviceIds.contains(state.targetDeviceId)) {
            delayedRelayStateByKey.remove(key)
            diagnosticsListener?.onDelayedRelayDropped(
                batchId = state.batchId,
                targetDeviceId = state.targetDeviceId,
                reason = "skip_sender_origin",
            )
            return
        }

        val queued = broadcastEnvelopeToDeviceIds(state.payload, setOf(state.targetDeviceId))
        state.fired = true
        delayedRelayStateByKey.remove(key)

        diagnosticsListener?.onDelayedRelayFired(
            batchId = state.batchId,
            targetDeviceId = state.targetDeviceId,
            queued = queued,
        )
    }

    private fun scheduleBatchForwardAttempt(batchId: String, attempt: Int, delayMs: Long) {
        workerHandler.postDelayed(
            {
                executeBatchForwardAttempt(batchId, attempt, delayMs)
            },
            delayMs,
        )
    }

    private fun executeBatchForwardAttempt(batchId: String, attempt: Int, delayMs: Long) {
        if (!running) return
        val nowMs = System.currentTimeMillis()
        val state = batchForwardStateById[batchId] ?: return
        if (nearbyForegroundMode) {
            state.firstAcceptedAtMs += NEARBY_FOREGROUND_RELAY_RECHECK_MS
            Log.i(TAG, "NEARBY_FOREGROUND relay_deferred kind=batch batch=$batchId attempt=$attempt")
            scheduleBatchForwardAttempt(
                batchId = batchId,
                attempt = attempt,
                delayMs = NEARBY_FOREGROUND_RELAY_RECHECK_MS,
            )
            return
        }
        if (state.sourceDeviceId == null) {
            state.sourceDeviceId = resolveStableDeviceIdForAddress(state.sourceAddress)
        }
        state.sourceDeviceId?.takeIf { it.isNotBlank() }?.let { state.originDeviceIds.add(it) }
        state.originDeviceIds.add(localDeviceId)
        if (attempt <= state.executedAttempts) return
        val ageMs = nowMs - state.firstAcceptedAtMs
        if (ageMs > BATCH_FORWARD_STATE_TTL_MS) {
            batchForwardStateById.remove(batchId)
            diagnosticsListener?.onBatchForwardSkipped(batchId, "ttl_expired", attempt, delayMs)
            return
        }
        val candidateDeviceIds = relayCandidateDeviceIds(
            nowMs = nowMs,
            sourceAddress = state.sourceAddress,
            sourceDeviceId = state.sourceDeviceId,
            originDeviceIds = state.originDeviceIds,
        ).toList()
        if (state.sourceDeviceId == null) {
            if (state.sourceResolveRetries < BATCH_FORWARD_SOURCE_RESOLVE_RETRY_MAX) {
                state.sourceResolveRetries += 1
                diagnosticsListener?.onBatchForwardSkipped(
                    batchId,
                    "source_device_unknown_soft retry=${state.sourceResolveRetries}/$BATCH_FORWARD_SOURCE_RESOLVE_RETRY_MAX",
                    attempt,
                    delayMs,
                )
                scheduleBatchForwardAttempt(
                    batchId = batchId,
                    attempt = attempt,
                    delayMs = BATCH_FORWARD_SOURCE_RESOLVE_RETRY_DELAY_MS,
                )
                return
            } else {
                state.executedAttempts = attempt
                diagnosticsListener?.onBatchForwardSkipped(
                    batchId,
                    "source_device_unknown_drop",
                    attempt,
                    delayMs,
                )
                batchForwardStateById.remove(batchId)
                return
            }
        } else {
            state.sourceResolveRetries = 0
        }
        diagnosticsListener?.onRelayBatchSeedPending(
            batchId = batchId,
            gid = null,
            payload = state.payload,
            eventCount = 0,
            firstEventCreatedT = state.firstAcceptedAtMs / 1000L,
            lastEventCreatedT = state.firstAcceptedAtMs / 1000L,
            deviceIds = candidateDeviceIds,
            nowMs = nowMs,
        )
        if (candidateDeviceIds.isEmpty()) {
            state.executedAttempts = attempt
            diagnosticsListener?.onBatchForwardSkipped(batchId, "no_candidate_peer", attempt, delayMs)
            if (attempt >= BATCH_FORWARD_MAX_ATTEMPTS) {
                batchForwardStateById.remove(batchId)
            }
            return
        }
        val relayTargetDeviceIds = diagnosticsListener
            ?.selectRelayBatchTargetDeviceIds(batchId, candidateDeviceIds, nowMs)
            ?.toCollection(LinkedHashSet())
            ?: LinkedHashSet(candidateDeviceIds)
        if (relayTargetDeviceIds.isEmpty()) {
            state.executedAttempts = attempt
            diagnosticsListener?.onBatchForwardSkipped(
                batchId,
                "no_pending_peer candidates=${candidateDeviceIds.joinToString(",")}",
                attempt,
                delayMs,
            )
            if (attempt >= BATCH_FORWARD_MAX_ATTEMPTS) {
                batchForwardStateById.remove(batchId)
            }
            return
        }
        val stickyTargetAddress = state.targetAddresses.lastOrNull()?.takeIf { address ->
            val deviceId = lastSeenDeviceId[address]
            deviceId != null &&
                relayTargetDeviceIds.contains(deviceId) &&
                deviceId != localDeviceId &&
                !state.originDeviceIds.contains(deviceId) &&
                nowMs - (lastSeenAtMs[address] ?: 0L) < DEVICE_TTL_MS
        }
        val targetAddress = stickyTargetAddress
            ?: selectRelayTargetForDeviceIds(
                nowMs = nowMs,
                sourceAddress = state.sourceAddress,
                sourceDeviceId = state.sourceDeviceId,
                originDeviceIds = state.originDeviceIds,
                targetDeviceIds = relayTargetDeviceIds,
                attemptedAddresses = state.targetAddresses,
            )
        if (targetAddress == null) {
            state.executedAttempts = attempt
            diagnosticsListener?.onBatchForwardSkipped(batchId, "no_target", attempt, delayMs)
            if (attempt >= BATCH_FORWARD_MAX_ATTEMPTS) {
                batchForwardStateById.remove(batchId)
            }
            return
        }
        val targetDeviceId = lastSeenDeviceId[targetAddress]
        if (targetDeviceId != null && state.originDeviceIds.contains(targetDeviceId)) {
            state.executedAttempts = attempt
            diagnosticsListener?.onBatchForwardSkipped(
                batchId,
                "skip_sender_origin target=$targetDeviceId",
                attempt,
                delayMs,
            )
            if (attempt >= BATCH_FORWARD_MAX_ATTEMPTS) {
                batchForwardStateById.remove(batchId)
            }
            return
        }
        val queued = broadcastEnvelopeToTargets(state.payload, listOf(targetAddress))
        state.executedAttempts = attempt
        if (!queued) {
            diagnosticsListener?.onBatchForwardSkipped(batchId, "enqueue_failed", attempt, delayMs)
        } else {
            state.targetAddresses.add(targetAddress)
            lastSeenDeviceId[targetAddress]?.let { targetDeviceId ->
                state.attemptsByDeviceId[targetDeviceId] = attempt
            }
            diagnosticsListener?.onBatchForwardQueued(batchId, targetAddress, attempt, delayMs)
        }
    }

    private fun markBatchForwardTerminal(
        batchId: String,
        deviceId: String,
        delivered: Boolean,
        reason: String?,
    ) {
        if (delivered) {
            scheduleOpaqueSuccessCleanup(batchId, deviceId)
        }
        val state = batchForwardStateById[batchId] ?: return
        if (!state.terminalDeviceIds.add(deviceId)) return
        val attempt = state.attemptsByDeviceId[deviceId] ?: state.executedAttempts
        if (delivered) {
            diagnosticsListener?.onBatchForwardDelivered(batchId, deviceId, attempt)
            batchForwardStateById.remove(batchId)
            return
        } else {
            diagnosticsListener?.onBatchForwardFailed(batchId, deviceId, reason ?: "unknown", attempt)
        }
        if (state.executedAttempts >= BATCH_FORWARD_MAX_ATTEMPTS &&
            state.terminalDeviceIds.size >= state.attemptsByDeviceId.size
        ) {
            batchForwardStateById.remove(batchId)
        }
    }

    private fun scheduleOpaqueSuccessCleanup(batchId: String, deviceId: String) {
        val eventIds = batchEventIdsForDevice(batchId, deviceId)
        if (Looper.myLooper() == workerThread.looper) {
            clearOpaqueCapsulesForBatch(batchId, deviceId, eventIds)
            return
        }
        workerHandler.post {
            clearOpaqueCapsulesForBatch(batchId, deviceId, eventIds)
        }
    }

    private fun clearOpaqueCapsulesForBatch(batchId: String, deviceId: String, eventIds: List<String>) {
        val store = opaqueRelayStore ?: return
        val nowSeconds = System.currentTimeMillis() / 1000L
        val deviceIdCandidates = opaqueRetryDeviceIdCandidates(deviceId)
        val rememberedCapsules = takeRememberedOpaqueRetryCapsules(batchId, deviceIdCandidates)
        clearHotOpaqueRetry(batchId, deviceIdCandidates, rememberedCapsules)
        val mode = if (rememberedCapsules.isNotEmpty()) "capsules" else "batch"
        val successMarkProbes = runCatching {
            deviceIdCandidates.associateWith { candidateId ->
                if (rememberedCapsules.isNotEmpty()) {
                    store.probeMarkDeliveryDeliveredForCapsules(rememberedCapsules, candidateId, nowSeconds)
                } else {
                    store.probeMarkDeliveryDeliveredByBatchId(batchId, candidateId, nowSeconds)
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "Opaque retry mark delivered failed batch=$batchId device=$deviceId", error)
            emptyMap()
        }
        var marked = 0
        deviceIdCandidates.forEach { candidateId ->
            val counts = successMarkProbes[candidateId]
            val probeUnavailable = if (counts == null) "probe_unavailable" else null
            val fallbackMarked = if (counts == null) {
                runCatching {
                    if (rememberedCapsules.isNotEmpty()) {
                        store.markDeliveryDeliveredForCapsules(rememberedCapsules, candidateId, nowSeconds)
                    } else {
                        store.markDeliveryDeliveredByBatchId(batchId, candidateId, nowSeconds)
                    }
                }.getOrDefault(0)
            } else {
                0
            }
            marked += counts?.marked ?: fallbackMarked
            diagnosticsListener?.onOpaqueRetryPreMarkProbe(
                stage = "send_complete",
                batchId = batchId,
                deviceId = deviceId,
                candidateDeviceId = candidateId,
                mode = mode,
                capsuleCount = counts?.capsuleCount ?: 0,
                probeUnavailable = probeUnavailable,
            )
            diagnosticsListener?.onOpaqueRetryPreMarkResult(
                stage = "send_complete",
                batchId = batchId,
                deviceId = deviceId,
                candidateDeviceId = candidateId,
                mode = mode,
                capsuleCount = counts?.capsuleCount ?: 0,
                eligible = counts?.marked ?: fallbackMarked,
                missingCapsule = counts?.missingCapsule ?: 0,
                alreadyDelivered = counts?.alreadyDelivered ?: 0,
                failedFinal = counts?.failedFinal ?: 0,
                eligibleButZero = counts?.eligibleButZero ?: 0,
                probeUnavailable = probeUnavailable,
            )
        }
        diagnosticsListener?.onOpaqueRetryBookkeepingProbe(
            stage = "send_complete",
            batchId = batchId,
            deviceId = deviceId,
            deviceIdCandidates = deviceIdCandidates,
            rememberedCapsules = rememberedCapsules.size,
            marked = marked,
            mode = mode,
            preMarkReasonSummary = null,
            deliveryStateSummary = store.describeBatchDelivery(batchId, deviceId),
        )
        val deliveredCapsuleIds = runCatching {
            deviceIdCandidates.flatMap { candidateId ->
                if (rememberedCapsules.isNotEmpty()) {
                    store.listDeliveredCapsuleIdsForCapsules(rememberedCapsules, candidateId)
                } else {
                    store.listDeliveredCapsuleIdsByBatchId(batchId, candidateId)
                }
            }.distinct()
        }.getOrElse { error ->
            Log.w(TAG, "Opaque retry delivered-id lookup failed batch=$batchId device=$deviceId", error)
            emptyList()
        }
        val deleted = if (deliveredCapsuleIds.isNotEmpty()) {
            runCatching { store.deleteCapsules(deliveredCapsuleIds) }.getOrElse { error ->
                Log.w(TAG, "Opaque retry success cleanup failed batch=$batchId device=$deviceId", error)
                0
            }
        } else {
            0
        }
        diagnosticsListener?.onOpaqueRetryBookkeepingProbe(
            stage = "send_complete_cleanup",
            batchId = batchId,
            deviceId = deviceId,
            deviceIdCandidates = deviceIdCandidates,
            rememberedCapsules = rememberedCapsules.size,
            marked = deleted,
            mode = mode,
            preMarkReasonSummary = "delivered_capsules=${deliveredCapsuleIds.size}",
            deliveryStateSummary = store.describeBatchDelivery(batchId, deviceId),
        )
        if (deleted > 0) {
            diagnosticsListener?.onOpaqueRetryDeleted(
                batchId = batchId,
                capsuleCount = deleted,
                reason = "success_cleanup",
            )
            if (eventIds.isNotEmpty()) {
                dtnRepairSuppressionStore?.clearLocallyPendingRelay(eventIds)
                dtnRepairSuppressionStore?.markLocallyForwardedSuccess(
                    eventIds = eventIds,
                    nowSeconds = nowSeconds,
                )
                dtnRepairSuppressionStore?.markPeerSatisfied(
                    peerId = deviceId,
                    eventIds = eventIds,
                    nowSeconds = nowSeconds,
                )
            }
            Log.i(
                TAG,
                "Opaque retry success cleanup batch=$batchId device=$deviceId deleted=$deleted delivered=${deliveredCapsuleIds.size}",
            )
        } else if (deliveredCapsuleIds.isNotEmpty()) {
            Log.i(
                TAG,
                "Opaque retry success cleanup skipped batch=$batchId device=$deviceId delivered=${deliveredCapsuleIds.size} deleted=0",
            )
        }
        if (marked > 0) {
            diagnosticsListener?.onOpaqueRetryMarkForwarded(
                batchId = batchId,
                capsuleCount = marked,
            )
            diagnosticsListener?.onOpaqueRetrySendComplete(
                batchId = batchId,
                deviceId = deviceId,
                capsuleCount = marked,
            )
            Log.i(TAG, "Opaque retry marked delivered batch=$batchId device=$deviceId capsules=$marked")
        } else if (rememberedCapsules.isNotEmpty()) {
            Log.i(
                TAG,
                "Opaque retry send_complete suppressed batch=$batchId device=$deviceId aliases=${deviceIdCandidates.joinToString(",")} remembered=${rememberedCapsules.size} reason=already_delivered_or_missing",
            )
        }
    }

    private fun batchEventIdsForDevice(batchId: String, deviceId: String): List<String> {
        return batchSendStateByKey[batchSendStateKey(batchId, deviceId)]?.eventIds ?: emptyList()
    }

    private fun handleOpaqueRetrySendFailed(
        batchId: String,
        deviceId: String,
        reason: String,
        writtenChunks: Int,
        totalChunks: Int,
    ) {
        val store = opaqueRelayStore ?: return
        val nowSeconds = System.currentTimeMillis() / 1000L
        val deviceIdCandidates = opaqueRetryDeviceIdCandidates(deviceId)
        val rememberedCapsules = takeRememberedOpaqueRetryCapsules(batchId, deviceIdCandidates)
        val mode = if (rememberedCapsules.isNotEmpty()) "capsules" else "batch"
        val preMarkReasonSummaries = runCatching {
            deviceIdCandidates.associateWith { candidateId ->
                if (rememberedCapsules.isNotEmpty()) {
                    store.describeDeliveredMarkReasonsForCapsules(rememberedCapsules, candidateId)
                } else {
                    store.describeDeliveredMarkReasonsForBatch(batchId, candidateId)
                }
            }
        }.getOrNull().orEmpty()
        val marked = runCatching {
            if (rememberedCapsules.isNotEmpty()) {
                deviceIdCandidates.sumOf { candidateId ->
                    store.markDeliveryFailedForCapsules(rememberedCapsules, candidateId, nowSeconds, reason)
                }
            } else {
                deviceIdCandidates.sumOf { candidateId ->
                    store.markDeliveryFailedByBatchId(batchId, candidateId, nowSeconds, reason)
                }
            }
        }.getOrDefault(0)
        preMarkReasonSummaries.forEach { (candidateId, counts) ->
            diagnosticsListener?.onOpaqueRetryPreMarkProbe(
                stage = "send_failed",
                batchId = batchId,
                deviceId = deviceId,
                candidateDeviceId = candidateId,
                mode = mode,
                capsuleCount = counts?.capsuleCount ?: 0,
                probeUnavailable = if (counts == null) "main_thread_or_unavailable" else null,
            )
            diagnosticsListener?.onOpaqueRetryPreMarkResult(
                stage = "send_failed",
                batchId = batchId,
                deviceId = deviceId,
                candidateDeviceId = candidateId,
                mode = mode,
                capsuleCount = counts?.capsuleCount ?: 0,
                eligible = counts?.eligible ?: 0,
                missingCapsule = counts?.missingCapsule ?: 0,
                alreadyDelivered = counts?.alreadyDelivered ?: 0,
                failedFinal = counts?.failedFinal ?: 0,
                eligibleButZero = 0,
                probeUnavailable = if (counts == null) "main_thread_or_unavailable" else null,
            )
        }
        diagnosticsListener?.onOpaqueRetryBookkeepingProbe(
            stage = "send_failed",
            batchId = batchId,
            deviceId = deviceId,
            deviceIdCandidates = deviceIdCandidates,
            rememberedCapsules = rememberedCapsules.size,
            marked = marked,
            mode = mode,
            preMarkReasonSummary = null,
            deliveryStateSummary = store.describeBatchDelivery(batchId, deviceId),
        )
        if (marked <= 0) {
            if (rememberedCapsules.isNotEmpty()) {
                Log.i(
                    TAG,
                    "Opaque retry failure suppressed batch=$batchId device=$deviceId aliases=${deviceIdCandidates.joinToString(",")} remembered=${rememberedCapsules.size} reason=already_delivered_or_missing failure=$reason chunks=$writtenChunks/$totalChunks",
                )
            }
            return
        }
        rememberHotOpaqueRetry(batchId, deviceIdCandidates, rememberedCapsules, System.currentTimeMillis())
        scheduleOpaqueRetryDrainSoon(OPAQUE_RETRY_HOT_RETRY_DELAY_MS)
        scheduleOpaqueRetryDrainSoon(OPAQUE_RETRY_HOT_RETRY_SECOND_DELAY_MS)
        diagnosticsListener?.onOpaqueRetryFailed(
            batchId = batchId,
            deviceId = deviceId,
            reason = reason,
            writtenChunks = writtenChunks,
            totalChunks = totalChunks,
            capsuleCount = marked,
        )
    }

    private fun opaqueRetryDeviceIdCandidates(deviceId: String): List<String> {
        val canonicalId = canonicalDeviceIdOrSelf(deviceId)
        return buildList {
            add(deviceId)
            if (canonicalId != deviceId) add(canonicalId)
            canonicalDeviceIdAlias.entries.forEach { (staleId, resolvedId) ->
                if (canonicalDeviceIdOrSelf(staleId) == canonicalId) add(staleId)
                if (canonicalDeviceIdOrSelf(resolvedId) == canonicalId) add(resolvedId)
            }
        }.filter { it.isNotBlank() }.distinct()
    }

    private fun takeRememberedOpaqueRetryCapsules(
        batchId: String,
        deviceIds: Collection<String>,
    ): Set<String> {
        val remembered = LinkedHashSet<String>()
        deviceIds.forEach { candidateId ->
            remembered += opaqueRetryCapsulesByBatchAndDevice.remove(opaqueRetryKey(batchId, candidateId)).orEmpty()
        }
        return remembered
    }

    private fun parseAppBatch(rawJson: String): AppBatchEnvelope? {
        return runCatching {
            CanonicalJson.json.decodeFromString(AppBatchEnvelope.serializer(), rawJson)
        }.getOrNull()
    }

    private fun batchId(batch: AppBatchEnvelope): String {
        val mac = batch.app_sig?.mac
        if (!mac.isNullOrBlank()) return mac.take(12)
        return "ts:${batch.ts}"
    }

    private fun batchEventCount(batch: AppBatchEnvelope): Int {
        return if (batch.v >= 2 && batch.items_v2.isNotEmpty()) {
            batch.items_v2.size
        } else {
            batch.items.size
        }
    }

    private fun batchFirstEventCreatedT(batch: AppBatchEnvelope): Long {
        return batch.ts
    }

    private fun batchLastEventCreatedT(batch: AppBatchEnvelope): Long {
        return batch.ts
    }

    private fun updateDeviceIdLastSeen(address: String, deviceId: String, seenAtMs: Long) {
        val canonicalId = canonicalDeviceIdOrSelf(deviceId)
        if (canonicalId != deviceId) {
            registerDeviceIdAlias(
                staleDeviceId = deviceId,
                canonicalTargetDeviceId = canonicalId,
                address = address,
                reason = "last_seen_canonical_alias",
                nowMs = seenAtMs,
            )
        }
        val previousAddress = lastSeenAddrByDeviceId.put(canonicalId, address)
        lastSeenIdAtMs[canonicalId] = seenAtMs
        lastSeenDeviceId[address] = canonicalId
        if (previousAddress != null && previousAddress != address) {
            handleDeviceAddressRotation(canonicalId, previousAddress, address, seenAtMs)
        }
    }

    private fun handleDeviceAddressRotation(
        deviceId: String,
        previousAddress: String,
        newAddress: String,
        nowMs: Long,
    ) {
        setCooldownForAddress(
            address = previousAddress,
            durationMs = ROTATION_ADDR_COOLDOWN_MS,
            nowMs = nowMs,
            sourceReason = "rotation_previous_addr",
            deviceId = deviceId,
        )
        setDeviceIdRotationRecover(deviceId, newAddress, ROTATION_RECOVER_GRACE_MS, nowMs)
        lastActiveAtMsByDeviceId[deviceId] = nowMs

        if (lastTargetByDeviceId[deviceId] == previousAddress) {
            // Future selections should prefer the new address,
            // but do not aggressively tear down the old lane if it is still draining.
            lastTargetByDeviceId[deviceId] = newAddress
        }

        val hasStartedControlEnvelope = activeDtnControlItemIdByAddress[previousAddress] != null
        val hasActivePacket = activePendingPacketByAddress[previousAddress] != null
        val writeInFlight = addressStateByAddress[previousAddress]?.writeInFlight == true
        val shouldRebindQueuedControl = DtnControlRoutePolicy.shouldRebindQueuedEnvelope(
            hasStartedEnvelope = hasStartedControlEnvelope,
            hasActivePacket = hasActivePacket,
            writeInFlight = writeInFlight,
        )
        val reboundControl = if (shouldRebindQueuedControl) {
            migrateQueuedDtnControlForDevice(
                fromAddress = previousAddress,
                toAddress = newAddress,
                deviceId = deviceId,
            )
        } else {
            0
        }

        val oldLaneStillBusy = hasActiveDrainPathForAddress(previousAddress)

        if (!oldLaneStillBusy) {
            migratePendingQueue(previousAddress, newAddress)
            val movedDeferred = migrateDeferredQueues(previousAddress, newAddress)
            if (movedDeferred > 0 || reboundControl > 0) {
                offerDeviceForRoundRobin(newAddress)
            }
        } else {
            Log.i(
                TAG,
                "rotation_recovery keep_old_lane_alive id=$deviceId old=$previousAddress new=$newAddress reason=old_lane_can_drain rebound_control=$reboundControl started_control=$hasStartedControlEnvelope active_packet=$hasActivePacket write_in_flight=$writeInFlight",
            )
        }

        workerHandler.post {
            val existing = clientByDevice[previousAddress]
            if (existing != null) {
                val shouldDrainOldLane = hasActiveDrainPathForAddress(previousAddress)

                if (shouldDrainOldLane) {
                    Log.i(
                        TAG,
                        "rotation_recovery preserve_old_connection id=$deviceId old=$previousAddress new=$newAddress",
                    )
                    // Keep old connection alive temporarily so the in-flight lane can drain safely.
                    postLabel(previousAddress, PipelineLabel.WRITE_NEXT)
                    postLabel(newAddress, PipelineLabel.CONNECT)
                } else {
                    cleanupConnection(previousAddress, existing, closeQueue = false, reason = "device_id_rotated")
                }
            }

            val connectingConn = connectingClient[previousAddress]
            if (connectingConn != null && !hasActiveDrainPathForAddress(previousAddress)) {
                try {
                    markLocalDisconnectIntent(previousAddress, "device_id_rotated_connecting")
                    connectingConn.disconnect()
                    connectingConn.close()
                } catch (_: Exception) {
                }
                connectingClient.remove(previousAddress)
                connecting.remove(previousAddress)
                connectStartedAtMs.remove(previousAddress)
                setConnectState(previousAddress, CONNECT_STATE_DISCONNECTED, "device_id_rotated")
            }

            // Ensure the new address gets a chance to establish its own lane for future traffic.
            postLabel(newAddress, PipelineLabel.ENQUEUE)
            postLabel(newAddress, PipelineLabel.CONNECT)
        }
    }

    private fun deviceKeyForAddress(address: String): String {
        return resolveStableDeviceIdForAddress(address) ?: "addr:$address"
    }

    private fun resolveStableDeviceIdForAddress(address: String): String? {
        lastSeenDeviceId[address]?.let { return canonicalDeviceIdOrSelf(it) }
        lastSeenAddrByDeviceId.entries.firstOrNull { it.value == address }?.key?.let {
            return canonicalDeviceIdOrSelf(it)
        }
        lastTargetByDeviceId.entries.firstOrNull { it.value == address }?.key?.let {
            return canonicalDeviceIdOrSelf(it)
        }
        return null
    }

    private fun markPeerActive(address: String, seenAtMs: Long = System.currentTimeMillis()) {
        val key = canonicalDeviceIdOrSelf(deviceKeyForAddress(address))
        lastSeenAtMs[address] = seenAtMs
        lastSeenIdAtMs[key] = seenAtMs
        if (!key.startsWith("addr:")) {
            lastSeenAddrByDeviceId[key] = address
        }
        lastActiveAtMsByDeviceId[key] = seenAtMs
        scheduleOpaqueRetryDrain(seenAtMs)
    }

    private fun maybeLogGraceCandidate(
        deviceId: String,
        address: String,
        seenAgeMs: Long,
        activeAgeMs: Long,
        nowMs: Long,
    ) {
        val last = lastGraceLogAtMsByDeviceId[deviceId] ?: 0L
        if (nowMs - last < GRACE_LOG_INTERVAL_MS) return
        lastGraceLogAtMsByDeviceId[deviceId] = nowMs
        diagnosticsListener?.onBleCandidateGraceUsed(
            deviceId = deviceId,
            address = address,
            seenAgeMs = seenAgeMs,
            activeAgeMs = activeAgeMs,
        )
    }

    private fun isFallbackAddressId(deviceId: String): Boolean = deviceId.startsWith("addr:")

    private fun hasStableDeviceId(candidates: Map<String, DeviceCandidate>): Boolean {
        return candidates.keys.any { !isFallbackAddressId(it) }
    }

    private fun buildFreshCandidates(nowMs: Long): Map<String, DeviceCandidate> {
        if (!BleConfig.useManufacturerData) {
            val map = LinkedHashMap<String, DeviceCandidate>()
            lastSeenAtMs.forEach { (address, seenAt) ->
                val seenAgeMs = nowMs - seenAt
                val deviceId = "addr:$address"
                val activeAt = lastActiveAtMsByDeviceId[deviceId] ?: 0L
                val activeAgeMs = if (activeAt > 0L) nowMs - activeAt else Long.MAX_VALUE
                val fresh = seenAgeMs < DEVICE_TTL_MS
                val grace = !fresh && activeAgeMs <= ACTIVE_PEER_GRACE_MS && seenAgeMs <= ACTIVE_PEER_MAX_SEEN_AGE_MS
                if (!fresh && !grace) return@forEach
                map[address] = DeviceCandidate(
                    deviceId = deviceId,
                    address = address,
                    seenAtMs = seenAt,
                    rssi = lastSeenRssi[address] ?: Int.MIN_VALUE,
                )
                if (grace) {
                    maybeLogGraceCandidate(deviceId, address, seenAgeMs, activeAgeMs, nowMs)
                }
            }
            return map
        }
        val bestById = LinkedHashMap<String, DeviceCandidate>()
        lastSeenAtMs.forEach { (address, seenAt) ->
            val deviceId = canonicalDeviceIdOrSelf(lastSeenDeviceId[address] ?: "addr:$address")
            val canonicalAddress = lastSeenAddrByDeviceId[deviceId] ?: address
            val canonicalSeenAt = lastSeenAtMs[canonicalAddress] ?: seenAt
            val useCanonical = nowMs - canonicalSeenAt <= DEVICE_TTL_MS
            val effectiveAddress = if (useCanonical) canonicalAddress else address
            val effectiveSeenAt = if (useCanonical) canonicalSeenAt else seenAt
            val seenAgeMs = nowMs - effectiveSeenAt
            val activeAt = lastActiveAtMsByDeviceId[deviceId] ?: 0L
            val activeAgeMs = if (activeAt > 0L) nowMs - activeAt else Long.MAX_VALUE
            val fresh = seenAgeMs < DEVICE_TTL_MS
            val grace = !fresh && activeAgeMs <= ACTIVE_PEER_GRACE_MS && seenAgeMs <= ACTIVE_PEER_MAX_SEEN_AGE_MS
            if (!fresh && !grace) return@forEach
            val candidate = DeviceCandidate(
                deviceId = deviceId,
                address = effectiveAddress,
                seenAtMs = effectiveSeenAt,
                rssi = lastSeenRssi[effectiveAddress] ?: (lastSeenRssi[address] ?: Int.MIN_VALUE),
            )
            val current = bestById[deviceId]
            if (
                current == null ||
                candidate.seenAtMs > current.seenAtMs ||
                (candidate.seenAtMs == current.seenAtMs && candidate.rssi > current.rssi)
            ) {
                bestById[deviceId] = candidate
            }
            if (grace) {
                maybeLogGraceCandidate(deviceId, effectiveAddress, seenAgeMs, activeAgeMs, nowMs)
            }
        }
        return bestById
    }

    private fun updateTargetPoolsOnDiscovery(deviceId: String, seenAtMs: Long) {
        val canonicalId = canonicalDeviceIdOrSelf(deviceId)
        synchronized(targetPoolLock) {
            if (!isFallbackAddressId(canonicalId)) {
                mainTargetDeviceIds.removeIf { isFallbackAddressId(it) }
                potentialTargetDeviceIds.removeIf { isFallbackAddressId(it) }
            } else {
                val hasStableTargets = mainTargetDeviceIds.any { !isFallbackAddressId(it) } ||
                    potentialTargetDeviceIds.any { !isFallbackAddressId(it) }
                if (hasStableTargets) {
                    mainTargetDeviceIds.remove(canonicalId)
                    potentialTargetDeviceIds.remove(canonicalId)
                    return
                }
            }
            if (mainTargetDeviceIds.remove(canonicalId)) {
                mainTargetDeviceIds.add(canonicalId)
                return
            }
            if (mainTargetDeviceIds.size < MTL_CAP) {
                mainTargetDeviceIds.add(canonicalId)
                return
            }
            potentialTargetDeviceIds.remove(canonicalId)
            potentialTargetDeviceIds.add(canonicalId)
            prunePotentialTargetsLocked(seenAtMs)
        }
    }

    private fun syncTargetPools(candidates: Map<String, DeviceCandidate>, nowMs: Long) {
        synchronized(targetPoolLock) {
            val stableIdAvailable = hasStableDeviceId(candidates)
            if (stableIdAvailable) {
                mainTargetDeviceIds.removeIf { isFallbackAddressId(it) }
                potentialTargetDeviceIds.removeIf { isFallbackAddressId(it) }
            }
            mainTargetDeviceIds.removeIf { candidates[it] == null }
            potentialTargetDeviceIds.removeIf { candidates[it] == null }

            val candidateIds = candidates.keys
                .asSequence()
                .filter { !stableIdAvailable || !isFallbackAddressId(it) }
                .sortedByDescending { candidates[it]?.seenAtMs ?: 0L }
                .toList()
            candidateIds.forEach { id ->
                if (mainTargetDeviceIds.contains(id) || potentialTargetDeviceIds.contains(id)) return@forEach
                if (mainTargetDeviceIds.size < MTL_CAP) {
                    mainTargetDeviceIds.add(id)
                } else {
                    potentialTargetDeviceIds.add(id)
                }
            }

            val promote = potentialTargetDeviceIds.iterator()
            while (mainTargetDeviceIds.size < MTL_CAP && promote.hasNext()) {
                val id = promote.next()
                if (isPenaltyActive(id, nowMs)) continue
                promote.remove()
                mainTargetDeviceIds.add(id)
            }
            prunePotentialTargetsLocked(nowMs)
        }
    }

    private fun chooseProbeCandidate(
        candidates: Map<String, DeviceCandidate>,
        nowMs: Long,
    ): DeviceCandidate? {
        return candidates.values
            .sortedWith(compareByDescending<DeviceCandidate> { it.seenAtMs }.thenByDescending { it.rssi })
            .firstOrNull { candidate ->
                val lastProbe = lastSingleProbeAttemptAtMsByAddress[candidate.address] ?: 0L
                nowMs - lastProbe >= SINGLE_TARGET_PROBE_MS
            }
    }

    private fun prunePotentialTargetsLocked(nowMs: Long) {
        val cap = currentPotentialTargetCap()
        if (potentialTargetDeviceIds.size <= cap) return
        val ranked = potentialTargetDeviceIds
            .mapNotNull { id ->
                val candidate = lastSeenAddrByDeviceId[id]?.let { addr ->
                    val seenAt = lastSeenAtMs[addr] ?: return@let null
                    val rssi = lastSeenRssi[addr] ?: Int.MIN_VALUE
                    DeviceCandidate(id, addr, seenAt, rssi)
                } ?: return@mapNotNull null
                id to candidate
            }
            .sortedWith(
                compareBy<Pair<String, DeviceCandidate>> { isPenaltyActive(it.first, nowMs) }
                    .thenByDescending { it.second.seenAtMs }
                    .thenByDescending { it.second.rssi },
            )
            .take(cap)
            .map { it.first }
            .toSet()
        potentialTargetDeviceIds.removeIf { it !in ranked }
    }

    private fun currentPotentialTargetCap(): Int {
        return when {
            Runtime.getRuntime().maxMemory() >= PTL_CAP_HIGH_RAM_BYTES -> PTL_CAP_MAX
            Runtime.getRuntime().maxMemory() >= PTL_CAP_MID_RAM_BYTES -> PTL_CAP_HIGH
            Runtime.getRuntime().maxMemory() >= PTL_CAP_LOW_RAM_BYTES -> PTL_CAP_MID
            else -> PTL_CAP_LOW
        }
    }

    private fun candidateAddressesForDevice(deviceId: String, nowMs: Long): List<String> {
        val canonicalId = canonicalDeviceIdOrSelf(deviceId)
        val addresses = LinkedHashSet<String>()
        // Pin active connected session first to avoid address churn and reconnect storms.
        clientByDevice.keys.forEach { address ->
            if (canonicalDeviceIdOrSelf(lastSeenDeviceId[address] ?: "") == canonicalId && connectState(address) == CONNECT_STATE_CONNECTED) {
                addresses.add(address)
            }
        }
        lastSeenAddrByDeviceId[canonicalId]?.let { canonical ->
            val seenAt = lastSeenAtMs[canonical] ?: 0L
            if (nowMs - seenAt <= DEVICE_TTL_MS) {
                addresses.add(canonical)
            }
        }
        lastSeenDeviceId.forEach { (address, id) ->
            if (canonicalDeviceIdOrSelf(id) != canonicalId) return@forEach
            val seenAt = lastSeenAtMs[address] ?: 0L
            if (nowMs - seenAt <= ACTIVE_PEER_MAX_SEEN_AGE_MS) {
                addresses.add(address)
            }
        }
        val currentTarget = lastTargetByDeviceId[canonicalId]
        return addresses
            .sortedWith(
                compareByDescending<String> { connectState(it) == CONNECT_STATE_CONNECTED }
                    .thenByDescending { it == currentTarget }
                    .thenByDescending { lastSeenAtMs[it] ?: 0L }
                    .thenByDescending { lastSeenRssi[it] ?: Int.MIN_VALUE },
            )
    }

    private fun isAddressPenaltyActive(address: String, nowMs: Long): Boolean {
        val nextAllowed = addressStateByAddress[address]?.nextConnectAllowedAt ?: 0L
        return nowMs < nextAllowed
    }

    private fun isCooldownActiveForAddress(address: String, nowMs: Long): Boolean {
        val until = cooldownUntilByAddress[address] ?: 0L
        return nowMs < until
    }

    private fun isCooldownActiveForDevice(deviceId: String, nowMs: Long): Boolean {
        val until = cooldownUntilByDeviceId[deviceId] ?: 0L
        return nowMs < until
    }

    private fun isDeviceIdQuietActive(deviceId: String, nowMs: Long): Boolean {
        val until = deviceIdQuietUntil[deviceId] ?: 0L
        return nowMs < until
    }

    private fun applyQuietAfter133(deviceId: String, nowMs: Long) {
        last133AtByDeviceId[deviceId] = nowMs
        setDeviceIdQuiet(deviceId, QUIET_AFTER_133_MS, nowMs)
    }

    private fun markWriteFailed133(address: String, deviceId: String, nowMs: Long) {
        lastWriteFailed133AtByAddress[address] = nowMs
        val until = nowMs + POST_133_RECOVERY_MS
        post133RecoveryUntilByDeviceId[deviceId] = maxOf(post133RecoveryUntilByDeviceId[deviceId] ?: 0L, until)
        setMaxChunkSize(address, BleConfig.defaultGattPayloadBytes)
    }

    private fun shouldSuppressDuplicateStatus133Retry(address: String, nowMs: Long): Boolean {
        val recentWriteFailAt = lastWriteFailed133AtByAddress[address] ?: return false
        return nowMs - recentWriteFailAt <= STATUS_133_DUPLICATE_RETRY_SUPPRESS_MS
    }

    private fun isPost133RecoveryActive(deviceId: String, nowMs: Long): Boolean {
        val until = post133RecoveryUntilByDeviceId[deviceId] ?: 0L
        return nowMs < until
    }

    private fun post133RecoveryWriteDelayMs(address: String, nowMs: Long): Long {
        val deviceId = resolveStableDeviceIdForAddress(address) ?: return 0L
        if (!isPost133RecoveryActive(deviceId, nowMs)) return 0L
        val lastProgress = lastProgressAtMs[address] ?: return 0L
        val elapsed = nowMs - lastProgress
        if (elapsed >= POST_133_MIN_WRITE_GAP_MS) return 0L
        return POST_133_MIN_WRITE_GAP_MS - elapsed
    }

    private fun isDeviceIdSelectionLocked(deviceId: String, candidateAddress: String, nowMs: Long): Boolean {
        if (isRotationRecoveryCandidate(deviceId, candidateAddress, nowMs)) return false
        val currentAddress = lastTargetByDeviceId[deviceId] ?: return false
        if (currentAddress == candidateAddress) return false
        val currentSeenAt = lastSeenAtMs[currentAddress] ?: 0L
        if (nowMs - currentSeenAt > DEVICEID_MISSING_GRACE_MS) return false
        val dwellUntil = deviceIdDwellUntil[deviceId] ?: 0L
        if (nowMs < dwellUntil) return true
        val rotationUntil = deviceIdRotationCooldownUntil[deviceId] ?: 0L
        return nowMs < rotationUntil
    }

    private fun setCooldownForAddress(
        address: String,
        durationMs: Long,
        nowMs: Long,
        sourceReason: String,
        deviceId: String? = lastSeenDeviceId[address],
    ) {
        if (durationMs <= 0L) return
        val existingUntil = cooldownUntilByAddress[address] ?: 0L
        val existingRemainingMs = (existingUntil - nowMs).coerceAtLeast(0L)
        val until = nowMs + durationMs
        cooldownUntilByAddress[address] = maxOf(cooldownUntilByAddress[address] ?: 0L, until)
        diagnosticsListener?.onAddressCooldownSet(
            address = address,
            deviceId = deviceId,
            durationMs = durationMs,
            sourceReason = sourceReason,
            existingRemainingMs = existingRemainingMs,
            hasNearbyRoomControlWork = deviceId?.let(::hasNearbyRoomPriorityWorkForDevice) == true,
        )
    }

    private fun setCooldownForDevice(deviceId: String, durationMs: Long, nowMs: Long) {
        if (durationMs <= 0L) return
        val until = nowMs + durationMs
        cooldownUntilByDeviceId[deviceId] = maxOf(cooldownUntilByDeviceId[deviceId] ?: 0L, until)
    }

    private fun setDeviceIdQuiet(deviceId: String, durationMs: Long, nowMs: Long) {
        if (durationMs <= 0L) return
        val until = nowMs + durationMs
        deviceIdQuietUntil[deviceId] = maxOf(deviceIdQuietUntil[deviceId] ?: 0L, until)
    }

    private fun setDeviceIdDwell(deviceId: String, durationMs: Long, nowMs: Long) {
        if (durationMs <= 0L) return
        val until = nowMs + durationMs
        deviceIdDwellUntil[deviceId] = maxOf(deviceIdDwellUntil[deviceId] ?: 0L, until)
    }

    private fun setDeviceIdRotationCooldown(deviceId: String, durationMs: Long, nowMs: Long) {
        if (durationMs <= 0L) return
        val until = nowMs + durationMs
        deviceIdRotationCooldownUntil[deviceId] =
            maxOf(deviceIdRotationCooldownUntil[deviceId] ?: 0L, until)
    }

    private fun setDeviceIdRotationRecover(
        deviceId: String,
        address: String,
        durationMs: Long,
        nowMs: Long,
    ) {
        if (durationMs <= 0L) return
        deviceIdRotationRecoverAddress[deviceId] = address
        deviceIdRotationRecoverUntil[deviceId] =
            maxOf(deviceIdRotationRecoverUntil[deviceId] ?: 0L, nowMs + durationMs)
    }

    private fun isRotationRecoveryCandidate(
        deviceId: String,
        address: String,
        nowMs: Long,
    ): Boolean {
        val until = deviceIdRotationRecoverUntil[deviceId] ?: return false
        if (nowMs >= until) {
            deviceIdRotationRecoverUntil.remove(deviceId)
            deviceIdRotationRecoverAddress.remove(deviceId, address)
            return false
        }
        return deviceIdRotationRecoverAddress[deviceId] == address
    }

    private fun isPenaltyActive(deviceId: String, nowMs: Long): Boolean {
        val addresses = candidateAddressesForDevice(deviceId, nowMs)
        if (addresses.isEmpty()) return false
        return addresses.all { address -> isAddressPenaltyActive(address, nowMs) }
    }

    private fun migratePendingQueue(fromAddress: String, toAddress: String) {
        if (fromAddress == toAddress) return
        synchronized(pendingQueueLock) {
            val source = pendingByDevice[fromAddress] ?: return
            if (source.isEmpty()) return
            val target = pendingByDevice.getOrPut(toAddress) { ArrayDeque() }
            while (source.isNotEmpty()) {
                target.addLast(source.removeFirst())
            }
            pendingByDevice.remove(fromAddress)
        }
        rrDeviceSet.remove(fromAddress)
        rrDeviceQueue.remove(fromAddress)
        offerDeviceForRoundRobin(toAddress)
    }

    private fun migrateDeferredQueues(fromAddress: String, toAddress: String): Int {
        if (fromAddress == toAddress) return 0
        var moved = 0
        synchronized(schedulerLock) {
            val staged = stagedEnqueueByAddress.remove(fromAddress)
            if (!staged.isNullOrEmpty()) {
                val target = stagedEnqueueByAddress.getOrPut(toAddress) { ArrayDeque() }
                while (staged.isNotEmpty()) {
                    target.addLast(staged.removeFirst())
                    moved += 1
                }
            }

            val held = heldEnqueueByAddress.remove(fromAddress)
            if (!held.isNullOrEmpty()) {
                val targetHeld = heldEnqueueByAddress.getOrPut(toAddress) { ArrayDeque() }
                while (held.isNotEmpty()) {
                    targetHeld.addLast(held.removeFirst())
                    moved += 1
                }
            }
        }
        return moved
    }

    private fun migrateQueuedDtnControlForDevice(
        fromAddress: String,
        toAddress: String,
        deviceId: String,
    ): Int {
        if (fromAddress == toAddress) return 0
        var moved = 0

        fun moveMatching(
            source: ArrayDeque<PendingPacket>?,
            target: ArrayDeque<PendingPacket>,
        ): Int {
            if (source.isNullOrEmpty()) return 0
            val keep = ArrayDeque<PendingPacket>(source.size)
            var localMoved = 0
            while (source.isNotEmpty()) {
                val packet = source.removeFirst()
                if (packet.deviceId == deviceId && packet.dtnControlType != null) {
                    target.addLast(packet)
                    localMoved += 1
                } else {
                    keep.addLast(packet)
                }
            }
            while (keep.isNotEmpty()) source.addLast(keep.removeFirst())
            return localMoved
        }

        synchronized(pendingQueueLock) {
            val source = pendingByDevice[fromAddress]
            if (!source.isNullOrEmpty()) {
                val target = pendingByDevice.getOrPut(toAddress) { ArrayDeque() }
                moved += moveMatching(source, target)
                if (source.isEmpty()) pendingByDevice.remove(fromAddress)
            }
        }
        synchronized(schedulerLock) {
            val stagedSource = stagedEnqueueByAddress[fromAddress]
            if (!stagedSource.isNullOrEmpty()) {
                val target = stagedEnqueueByAddress.getOrPut(toAddress) { ArrayDeque() }
                moved += moveMatching(stagedSource, target)
                if (stagedSource.isEmpty()) stagedEnqueueByAddress.remove(fromAddress)
            }

            val heldSource = heldEnqueueByAddress[fromAddress]
            if (!heldSource.isNullOrEmpty()) {
                val target = heldEnqueueByAddress.getOrPut(toAddress) { ArrayDeque() }
                moved += moveMatching(heldSource, target)
                if (heldSource.isEmpty()) heldEnqueueByAddress.remove(fromAddress)
            }
        }

        if (moved > 0) {
            rrDeviceSet.remove(fromAddress)
            rrDeviceQueue.remove(fromAddress)
            offerDeviceForRoundRobin(toAddress)
            Log.i(
                TAG,
                "DTN control route rebound id=$deviceId old=$fromAddress new=$toAddress packets=$moved",
            )
            postLabel(toAddress, PipelineLabel.ENQUEUE)
            postLabel(toAddress, PipelineLabel.CONNECT)
        }
        return moved
    }

    private fun migrateRotatedAddressQueuesIfNeeded(fromAddress: String): Int {
        val deviceId = resolveStableDeviceIdForAddress(fromAddress) ?: return 0
        val canonicalAddress = lastSeenAddrByDeviceId[deviceId] ?: return 0
        if (canonicalAddress == fromAddress) return 0
        if (hasActiveDrainPathForAddress(fromAddress)) return 0

        migratePendingQueue(fromAddress, canonicalAddress)
        val movedDeferred = migrateDeferredQueues(fromAddress, canonicalAddress)
        if (movedDeferred > 0) {
            Log.i(
                TAG,
                "rotation_recovery migrate_deferred_after_cleanup id=$deviceId old=$fromAddress new=$canonicalAddress moved=$movedDeferred",
            )
            postLabel(canonicalAddress, PipelineLabel.ENQUEUE)
            postLabel(canonicalAddress, PipelineLabel.CONNECT)
        }
        return movedDeferred
    }

    private fun rotateAddressAfterFailure(deviceId: String, failedAddress: String, nowMs: Long): String? {
        val replacement = candidateAddressesForDevice(deviceId, nowMs)
            .firstOrNull { address ->
                address != failedAddress &&
                    !isAddressPenaltyActive(address, nowMs) &&
                    lastSeenDevices[address] != null
            } ?: return null
        lastSeenAddrByDeviceId[deviceId] = replacement
        setCooldownForAddress(
            address = failedAddress,
            durationMs = ROTATION_ADDR_COOLDOWN_MS,
            nowMs = nowMs,
            sourceReason = "rotation_failed_addr",
            deviceId = deviceId,
        )
        setCooldownForDevice(deviceId, ROTATION_DEVICE_COOLDOWN_MS, nowMs)
        setDeviceIdDwell(deviceId, DEVICEID_DWELL_MS, nowMs)
        setDeviceIdRotationCooldown(deviceId, DEVICEID_ROTATION_DEBOUNCE_MS, nowMs)
        migratePendingQueue(failedAddress, replacement)
        Log.i(TAG, "GATT rotate target id=$deviceId failed=$failedAddress next=$replacement")
        return replacement
    }

    private val gattCallback = object : BleClientCallbacks {
        override fun onConnectionStateChange(
            connection: BleClientConnection,
            status: Int,
            newState: Int,
        ) {
            val address = connection.address
            val nowMs = System.currentTimeMillis()
            val deviceId = lastSeenDeviceId[address] ?: "addr:$address"
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT connect failed status=$status addr=$address")
                val localReason = consumeLocalDisconnectReason(address)
                diagnosticsListener?.onGattDisconnectReason(
                    address = address,
                    origin = if (localReason != null) "local_cleanup" else "callback_error",
                    reason = localReason ?: "status_$status",
                    status = status,
                    newState = newState,
                )
                if (status == 133) {
                    applyQuietAfter133(deviceId, nowMs)
                } else {
                    setDeviceIdQuiet(deviceId, QUIET_AFTER_DISCONNECT_MS, nowMs)
                }
                setConnectState(address, CONNECT_STATE_DISCONNECTED, "connect_failed_status_$status")
                if (status == 133 && shouldSuppressDuplicateStatus133Retry(address, nowMs)) {
                    Log.i(TAG, "Suppress duplicate status=133 retry addr=$address")
                    return
                }
                markItemBackoff(address, "connect_failed_status_$status")
                postRetry(address, reason = "status=$status")
                postCleanup(address, CleanupMode.FORCE, reason = "callback_status_$status", callSite = "gatt_callback")
                return
            }
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected addr=$address")
                diagnosticsListener?.onGattConnected(address)
                markPeerActive(address)
                clientByDevice[address] = connection
                connecting.remove(address)
                connectingClient.remove(address)
                connectStartedAtMs.remove(address)
                lastProgressAtMs[address] = System.currentTimeMillis()
                setMaxChunkSize(address, BleConfig.defaultGattPayloadBytes)
                resetConnectFailures(address)

                if (hasHeldDataForAddress(address)) {
                    postLabel(address, PipelineLabel.ENQUEUE)
                }

                if (!clientBackend.discoverServices(connection)) {
                    Log.w(TAG, "GATT discoverServices failed addr=$address")
                    postRetry(address, reason = "discover_services_failed")
                    postCleanup(address, CleanupMode.FORCE, reason = "discover_services_failed", callSite = "gatt_callback")
                }
            } else {
                Log.i(TAG, "GATT disconnected addr=$address")
                val localReason = consumeLocalDisconnectReason(address)
                diagnosticsListener?.onGattDisconnectReason(
                    address = address,
                    origin = if (localReason != null) "local_cleanup" else "remote_or_link",
                    reason = localReason ?: "state_disconnected",
                    status = status,
                    newState = newState,
                )
                setDeviceIdQuiet(deviceId, QUIET_AFTER_DISCONNECT_MS, nowMs)
                setConnectState(address, CONNECT_STATE_DISCONNECTED, "state_disconnected")
                markItemBackoff(address, "state_disconnected")
                postRetry(address, reason = "state_disconnected")
                postCleanup(address, CleanupMode.FORCE, reason = "state_disconnected", callSite = "gatt_callback")
            }
        }

        override fun onServicesDiscovered(connection: BleClientConnection, status: Int) {
            val address = connection.address
            val gatt = connection.gatt
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                postRetry(address, reason = "services_discovery_status_$status")
                postCleanup(address, CleanupMode.FORCE, reason = "services_discovery_status_$status", callSite = "gatt_callback")
                return
            }
            Log.i(TAG, "GATT services discovered addr=$address")
            diagnosticsListener?.onGattServicesDiscovered(address)
            val service = gatt.getService(BleConfig.serviceUuid)
            val characteristic = service?.getCharacteristic(characteristicUuid)
            if (characteristic == null) {
                Log.w(TAG, "GATT characteristic missing for $address")
                postRetry(address, reason = "characteristic_missing")
                postCleanup(address, CleanupMode.FORCE, reason = "characteristic_missing", callSite = "gatt_callback")
                return
            }
            characteristicByDevice[address] = characteristic
            setConnectState(address, CONNECT_STATE_CONNECTED, "services_discovered")
            val servicesReadyAtMs = System.currentTimeMillis()
            lastProgressAtMs[address] = servicesReadyAtMs
            dataWarmupUntilByAddress[address] = servicesReadyAtMs + FIRST_BATCH_WARMUP_MS
            requestMtuIfSupported(connection)

            if (hasHeldDataForAddress(address)) {
                postLabel(address, PipelineLabel.ENQUEUE)
            } else if (connectState(address) == CONNECT_STATE_CONNECTED && clientByDevice[address] != null) {
                maybePullDispatchJobForAddress(address, reason = "services_discovered_idle")
            }

            workerHandler.postDelayed({
                if (!running) return@postDelayed
                if (connectState(address) != CONNECT_STATE_CONNECTED) return@postDelayed

                val released = releaseHeldToStagedIfReady(address)
                if (released > 0) {
                    Log.i(TAG, "Released held packets after warmup addr=$address count=$released")
                }

                if (released > 0 || hasPendingDataForAddress(address)) {
                    postLabel(address, PipelineLabel.ENQUEUE)
                    postLabel(address, PipelineLabel.WRITE_NEXT)
                }
            }, FIRST_BATCH_WARMUP_MS)

            if (POST_CONNECT_WRITE_DELAY_MS > 0) {
                scheduleWrite(address, connection, POST_CONNECT_WRITE_DELAY_MS)
            } else {
                triggerWritePump(address)
            }
        }

        override fun onMtuChanged(connection: BleClientConnection, mtu: Int, status: Int) {
            val address = connection.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT MTU change failed addr=$address status=$status")
                diagnosticsListener?.onMtuNegotiation(address, success = false, mtu = mtu, chunkBytes = null)
                return
            }
            val attPayload = (mtu - 3).coerceAtLeast(BleConfig.defaultGattPayloadBytes)
            val chunkSize = minOf(attPayload, BleConfig.maxChunkBytesCap)
            mtuByDevice[address] = mtu
            setMaxChunkSize(address, chunkSize)
            Log.i(TAG, "GATT MTU negotiated addr=$address mtu=$mtu chunkBytes=$chunkSize")
            diagnosticsListener?.onMtuNegotiation(address, success = true, mtu = mtu, chunkBytes = chunkSize)

            val released = releaseHeldToStagedIfReady(address)
            val rebuiltStaged = rebuildStagedQueueForCurrentChunkSize(address)
            val rebuiltPending = rebuildPendingQueueForCurrentChunkSizeIfIdle(address)
            if (released > 0) {
                Log.i(TAG, "Released held packets after MTU ready addr=$address count=$released mtu=$mtu")
            }
            if (rebuiltStaged > 0 || rebuiltPending > 0) {
                Log.i(
                    TAG,
                    "Rebuilt queued packets after MTU ready addr=$address staged=$rebuiltStaged pending=$rebuiltPending chunkBytes=${maxChunkSizeFor(address)} mtu=$mtu",
                )
            }

            if (released > 0 || rebuiltStaged > 0 || rebuiltPending > 0 || hasPendingDataForAddress(address)) {
                postLabel(address, PipelineLabel.ENQUEUE)
                postLabel(address, PipelineLabel.WRITE_NEXT)
            }
        }

        override fun onCharacteristicWrite(
            connection: BleClientConnection,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val address = connection.address
            val packet = removeActivePendingPacket(address, reason = "gatt_write_callback")
            releaseWriteSlot(address)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT write failed status=$status addr=$address")
                diagnosticsListener?.onGattWriteFailed(address, status)
                if (packet != null) {
                    onPacketWriteFailure(packet, reason = "write_failed_status_$status", nowMs = System.currentTimeMillis())
                }
                val nowMs = System.currentTimeMillis()
                val deviceId = lastSeenDeviceId[address] ?: "addr:$address"
                val rotatedAfter133 = if (status == 133) {
                    rotateAddressAfterFailure(deviceId, address, nowMs)
                } else {
                    null
                }
                if (status == 133) {
                    if (rotatedAfter133 == null) {
                        applyQuietAfter133(deviceId, nowMs)
                    } else {
                        // Rotation path already applies cooldowns; avoid suppressing the sibling lane.
                        setDeviceIdQuiet(deviceId, QUIET_AFTER_DISCONNECT_MS, nowMs)
                    }
                    markWriteFailed133(address, deviceId, nowMs)
                } else {
                    setDeviceIdQuiet(deviceId, QUIET_AFTER_DISCONNECT_MS, nowMs)
                }
                setConnectState(address, CONNECT_STATE_DISCONNECTED, "write_failed_status_$status")
                markItemBackoff(address, "write_failed_status_$status")
                postRetry(address, reason = "write_failed_status_$status")
                postCleanup(address, CleanupMode.FORCE, reason = "write_failed_status_$status", callSite = "gatt_callback")
                return
            }
            Log.i(TAG, "GATT write ok addr=$address")
            diagnosticsListener?.onGattWriteOk(address)
            val nowMs = System.currentTimeMillis()
            lastProgressAtMs[address] = nowMs
            markPeerActive(address, nowMs)
            if (packet != null) {
                onPacketWriteSuccess(packet, nowMs)
            }
            val delayMs = writePacingDelayMs()
            if (delayMs > 0) {
                scheduleWrite(address, connection, delayMs)
            } else {
                triggerWritePump(address)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        diagnosticsListener?.onMeshStart(stage = "called")
        if (running) {
            diagnosticsListener?.onMeshStart(stage = "skipped", reason = "already_running")
            Log.i(TAG, "BLE mesh start skipped: already running")
            return
        }
        if (adapter == null) {
            diagnosticsListener?.onMeshStart(stage = "skipped", reason = "adapter_null")
            Log.w(TAG, "Bluetooth adapter missing; BLE mesh disabled")
            return
        }
        if (!hasBleFeature) {
            diagnosticsListener?.onMeshStart(stage = "skipped", reason = "ble_not_supported")
            Log.w(TAG, "BLE not supported; BLE mesh disabled")
            return
        }
        if (scanner == null) {
            Log.w(TAG, "BLE scanner unavailable; BLE mesh disabled")
            return
        }
        if (!adapter.isEnabled) {
            diagnosticsListener?.onMeshStart(stage = "skipped", reason = "bt_off")
            Log.w(TAG, "Bluetooth disabled; scan not started")
            return
        }
        if (advertiser == null) {
            switchToReceiveOnly("hard_failure:advertiser_null")
        } else {
            restoreFullRelay("startup")
        }
        diagnosticsListener?.onLocalAdapterAddress(localAddress)
        running = true
        scanCallbacks = 0L
        scanResults = 0L
        scanNoRecord = 0L
        scanMatched = 0L
        lastScanStartAtMs = 0L
        lastScanCallbackAtMs = 0L
        lastScanAutoRecoverAtMs = 0L
        autoRecoverStreak = 0
        startGattServer()
        startAdvertising()
        startScan()
        startCleanupLoop()
        diagnosticsListener?.onMeshStart(stage = "started")
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        scanner?.stopScan(scanCallback)
        scanStarted = false
        stopAdvertising()
        stopGattServer()
        stopCleanupLoop()
        failAllInFlightPackets(reason = "transport_stop", nowMs = System.currentTimeMillis())
        closeAllGatt()
        cancelAdvertiseRetry()
        cancelAdvertiseRecheck()
        running = false
        Log.i(TAG, "Stopped BLE scan (callbacks=$scanCallbacks records=$scanResults noRecord=$scanNoRecord)")
    }

    @SuppressLint("MissingPermission")
    override fun hardRestart() {
        Log.i(TAG, "BLE hard restart: stop -> clear caches -> start")
        stop()
        clearRuntimeState(skipInFlightFailure = true)
        start()
    }

    @SuppressLint("MissingPermission")
    override fun updateScanSettings() {
        if (!running) {
            diagnosticsListener?.onScanSettingsUpdate(running = false, reason = "not_running")
            return
        }
        diagnosticsListener?.onScanSettingsUpdate(running = true, reason = "reconfigure")
        scanStarted = false
        scanner?.stopScan(scanCallback)
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapterLocal = adapter
        if (adapterLocal == null) {
            diagnosticsListener?.onScanStartSkipped("adapter_null")
            return
        }
        if (!adapterLocal.isEnabled) {
            diagnosticsListener?.onScanStartSkipped("bt_off")
            return
        }
        if (!isLocationEnabledForScan()) {
            diagnosticsListener?.onScanStartSkipped("loc_off")
            return
        }
        if (!hasScanPermission()) {
            diagnosticsListener?.onScanStartSkipped("perm_missing")
            return
        }
        val scannerLocal = scanner
        if (scannerLocal == null) {
            diagnosticsListener?.onScanStartSkipped("scanner_null")
            return
        }
        if (scanStarted) {
            diagnosticsListener?.onScanStartSkipped("already_scanning")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(BleConfig.scanMode)
            .setReportDelay(BleConfig.scanReportDelayMs)
            .build()
        val filters = if (BleConfig.scanFilterEnabled) {
            listOf(
                ScanFilter.Builder()
                    .apply {
                        if (BleConfig.useManufacturerData) {
                            val mask = ByteArray(MFG_PREFIX.size) { 0xFF.toByte() }
                            setManufacturerData(BleConfig.manufacturerId, MFG_PREFIX, mask)
                        } else {
                            setServiceUuid(serviceUuid)
                        }
                    }
                    .build()
            )
        } else {
            emptyList()
        }
        Log.i(
            TAG,
            "Starting BLE scan serviceUuid=$serviceUuid scanner=${scanner != null} advertiser=${advertiser != null} filterEnabled=${BleConfig.scanFilterEnabled} scanMode=${BleConfig.scanMode} reportDelayMs=${BleConfig.scanReportDelayMs}",
        )
        scannerLocal.startScan(filters, settings, scanCallback)
        scanStarted = true
        lastScanStartAtMs = System.currentTimeMillis()
        diagnosticsListener?.onScanStart(
            scanMode = BleConfig.scanMode,
            reportDelayMs = BleConfig.scanReportDelayMs,
            filterEnabled = BleConfig.scanFilterEnabled,
            useManufacturerData = BleConfig.useManufacturerData,
        )
    }

    private fun canStartScanNow(): Boolean {
        val adapterLocal = adapter ?: return false
        if (!adapterLocal.isEnabled) return false
        if (!isLocationEnabledForScan()) return false
        if (!hasScanPermission()) return false
        return scanner != null
    }

    private fun autoRecoverTimeoutMs(): Long {
        return if (BleConfig.scanMode == ScanSettings.SCAN_MODE_LOW_POWER &&
            BleConfig.scanReportDelayMs >= LOW_POWER_REPORT_DELAY_THRESHOLD_MS
        ) {
            AUTO_RECOVER_SCAN_TIMEOUT_LOW_POWER_MS
        } else {
            AUTO_RECOVER_SCAN_TIMEOUT_MS
        }
    }

    private fun maybeAutoRecoverScan(nowMs: Long) {
        if (!running) return
        if (!canStartScanNow()) return
        if (autoRecoverStreak >= AUTO_RECOVER_STREAK_LIMIT &&
            nowMs - lastScanAutoRecoverAtMs < AUTO_RECOVER_STREAK_BACKOFF_MS
        ) {
            return
        }
        if (nowMs - lastScanAutoRecoverAtMs < AUTO_RECOVER_SCAN_COOLDOWN_MS) return
        val baseAtMs = if (lastScanCallbackAtMs > 0L) lastScanCallbackAtMs else lastScanStartAtMs
        if (baseAtMs == 0L) return
        val idleMs = nowMs - baseAtMs
        if (idleMs < autoRecoverTimeoutMs()) return
        lastScanAutoRecoverAtMs = nowMs
        autoRecoverStreak += 1
        val reason = if (scanStarted) "scan_stall" else "scan_inactive"
        Log.w(TAG, "BLE auto-recover scan reason=$reason idleMs=$idleMs scanStarted=$scanStarted")
        diagnosticsListener?.onScanAutoRecover(reason, idleMs, scanStarted)
        if (scanStarted) {
            updateScanSettings()
        } else {
            startScan()
        }
    }

    private fun isLocationEnabledForScan(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val locationManager =
            appContext.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                ?: return false
        return try {
            val gps = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
            val network = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            gps || network
        } catch (_: Exception) {
            false
        }
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            val fine = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val coarse = appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            fine || coarse
        }
    }

    override fun nearbyDeviceCount(nowMs: Long): Int {
        val candidates = buildFreshCandidates(nowMs)
        if (candidates.isEmpty()) return 0
        if (!BleConfig.useManufacturerData) {
            maybeLogNearbyCountBreakdown(
                nearbyTotal = candidates.size,
                sessionNearby = candidates.size,
                nonSessionNearby = 0,
                nowMs = nowMs,
            )
            return candidates.size
        }

        // Report "effective nearby" based on the active main target pool instead of raw scan noise.
        syncTargetPools(candidates, nowMs)
        val mainPoolCount = synchronized(targetPoolLock) {
            mainTargetDeviceIds.count { id -> candidates[id] != null }
        }
        if (mainPoolCount > 0) {
            val nonSession = (candidates.size - mainPoolCount).coerceAtLeast(0)
            maybeLogNearbyCountBreakdown(
                nearbyTotal = mainPoolCount,
                sessionNearby = mainPoolCount,
                nonSessionNearby = nonSession,
                nowMs = nowMs,
            )
            return mainPoolCount
        }

        val stableCount = candidates.values.count { candidate -> !isFallbackAddressId(candidate.deviceId) }
        val fallbackTotal = if (stableCount > 0) stableCount else candidates.size
        maybeLogNearbyCountBreakdown(
            nearbyTotal = fallbackTotal,
            sessionNearby = stableCount,
            nonSessionNearby = (candidates.size - stableCount).coerceAtLeast(0),
            nowMs = nowMs,
        )
        return fallbackTotal
    }

    override fun targetSnapshotForLog(nowMs: Long): String {
        if (!BleConfig.useManufacturerData) return "unavailable"
        val entries = lastTargetByDeviceId.entries.mapNotNull { (deviceId, address) ->
            val seenAt = lastSeenAtMs[address] ?: lastSeenIdAtMs[deviceId] ?: return@mapNotNull null
            if (nowMs - seenAt > DEVICE_TTL_MS) return@mapNotNull null
            "id=$deviceId addr=$address"
        }
        return if (entries.isEmpty()) "none" else entries.joinToString(" | ")
    }

    private fun handleDtnControlPayload(blob: ByteArray, sourceAddress: String): Boolean {
        val router = dtnControlRouter ?: return false
        val packet = DtnControlCodec.decode(blob) ?: return false
        rememberDtnPeerAddress(packet.peerId, sourceAddress)
        var deferredForForeground = false
        val result = router.route(
            blob = blob,
            isDeferred = { deferredForForeground },
            sendResponse = { response ->
                if (nearbyForegroundMode) {
                    val gid = packet.gid
                    val sessionId = packet.sessionId
                    deferredForForeground = gid != null && sessionId != null &&
                        dtnSyncEngine?.deferActiveSessionTask(packet.peerId, gid, sessionId) == true
                    false
                } else {
                    broadcastDtnControlResponseToPeer(response, packet.peerId, sourceAddress)
                }
            },
        )
        if (result !is DtnControlRouteResult.Handled) {
            diagnosticsListener?.onDtnControlHandleResult(
                address = sourceAddress,
                type = if (packet.type == DtnControlTypes.DATA && packet.carrierData.isNotEmpty()) {
                    "carrier_data"
                } else {
                    packet.type
                },
                handled = false,
                responseCount = 0,
                sentCount = 0,
            )
            return false
        }
        diagnosticsListener?.onDtnControlHandleResult(
            address = sourceAddress,
            type = if (packet.type == DtnControlTypes.DATA && packet.carrierData.isNotEmpty()) {
                "carrier_data"
            } else {
                packet.type
            },
            handled = true,
            responseCount = result.responseCount,
            sentCount = result.sentCount,
        )
        Log.i(
            TAG,
            "DTN control handled type=${result.packetType} responses=${result.responseCount} sent=${result.sentCount}",
        )
        return true
    }

    private fun rememberDtnPeerAddress(peerId: String?, sourceAddress: String) {
        val normalized = LocalDeviceIdentity.normalize(peerId) ?: return
        if (normalized.isBlank() || normalized == localDeviceId) return
        lastSeenDeviceId[sourceAddress] = normalized
        lastSeenAddrByDeviceId[normalized] = sourceAddress
        lastSeenAtMs[sourceAddress] = System.currentTimeMillis()
        lastSeenIdAtMs[normalized] = System.currentTimeMillis()
        resumeForegroundDeferredDtnTasks(reason = "peer_seen", peerId = normalized)
    }

    private fun broadcastDtnControlResponseToPeer(
        response: ByteArray,
        remotePeerId: String?,
        sourceAddress: String,
    ): Boolean {
        val targetAddress = chooseDtnReplyAddress(remotePeerId, sourceAddress)
        val useFastPath =
            connectState(targetAddress) == CONNECT_STATE_CONNECTED &&
                characteristicByDevice[targetAddress] != null
        return broadcastEnvelopeToTargets(
            blob = response,
            targetAddresses = listOf(targetAddress),
            preferDtnFastPath = useFastPath,
        )
    }

    private fun chooseDtnReplyAddress(remotePeerId: String?, sourceAddress: String): String {
        val normalized = LocalDeviceIdentity.normalize(remotePeerId)
        if (normalized.isNullOrBlank() || normalized == localDeviceId) return sourceAddress
        val nowMs = System.currentTimeMillis()
        val candidates = candidateAddressesForDevice(normalized, nowMs)
        return candidates.firstOrNull { address ->
            connectState(address) == CONNECT_STATE_CONNECTED &&
                characteristicByDevice[address] != null
        } ?: candidates.firstOrNull() ?: sourceAddress
    }

    private fun maybeSendDtnHello(deviceId: String, address: String, nowMs: Long) {
        if (nearbyForegroundMode) return
        val engine = dtnSyncEngine ?: return
        if (deviceId.isBlank() || isFallbackAddressId(deviceId)) return
        val gids = dtnSyncGidProvider?.invoke()
            ?.mapNotNull { it.takeIf(String::isNotBlank) }
            ?.distinct()
            ?.take(DTN_HELLO_MAX_SCOPES)
            ?: listOfNotNull(dtnActiveGidProvider?.invoke()?.takeIf { it.isNotBlank() })
        if (gids.isEmpty()) return
        gids.forEach { gid ->
            val throttleKey = "$deviceId|$gid"
            val lastSentAt = dtnHelloLastSentAtByDeviceId[throttleKey] ?: 0L
            if (nowMs - lastSentAt < DTN_HELLO_INTERVAL_MS) return@forEach
            dtnHelloLastSentAtByDeviceId[throttleKey] = nowMs
            val sessionId = buildDtnSessionId(deviceId, nowMs)
            val packet = engine.buildHelloPacket(
                gid = gid,
                sessionId = sessionId,
                nowSeconds = nowMs / 1000L,
            )
            val encoded = DtnControlCodec.encode(packet)
            syncLocalDeviceIdFromDtnPeerId(
                peerId = extractLocalPeerIdFromDtnControlPayload(encoded),
                reason = "dtn_hello_packet",
            )
            val queued = broadcastEnvelopeToTargets(encoded, listOf(address))
            dtnDiagnostics?.log(
                DtnDiagnosticEvent(
                    label = "DTN hello ${if (queued) "sent" else "drop"}",
                    type = packet.type,
                    direction = if (queued) "sent" else "drop",
                    peerId = deviceId,
                    gid = gid,
                    sessionId = sessionId,
                    reason = if (queued) null else "enqueue_failed",
                )
            )
            Log.i(
                TAG,
                "DTN hello ${if (queued) "queued" else "skipped"} peer=$deviceId gid=$gid session=$sessionId",
            )
            if (!queued) {
                dtnHelloLastSentAtByDeviceId.remove(throttleKey, nowMs)
                return@forEach
            }

            val sessionContext = DtnHelloSessionContext(
                deviceId = deviceId,
                address = address,
                gid = gid,
                sessionId = sessionId,
                startedAtMs = nowMs,
            )
            dtnHelloSessionByDeviceAndGid[throttleKey] = sessionContext
            scheduleDependencyRepairWant(
                engine = engine,
                throttleKey = throttleKey,
                sessionContext = sessionContext,
            )
        }
    }

    private fun scheduleDependencyRepairWant(
        engine: DtnSyncEngine,
        throttleKey: String,
        sessionContext: DtnHelloSessionContext,
    ) {
        workerHandler.post {
            val dependencyRoots = runCatching {
                engine.loadDependencyRepairWantIds(sessionContext.gid)
            }.getOrElse { error ->
                Log.w(
                    TAG,
                    "DTN dependency root lookup failed peer=${sessionContext.deviceId} " +
                        "gid=${sessionContext.gid} session=${sessionContext.sessionId}",
                    error,
                )
                emptyList()
            }
            if (dependencyRoots.isEmpty()) return@post

            mainHandler.post {
                val current = dtnHelloSessionByDeviceAndGid[throttleKey]
                if (current != sessionContext) {
                    Log.i(
                        TAG,
                        "DTN dependency want stale peer=${sessionContext.deviceId} " +
                            "gid=${sessionContext.gid} session=${sessionContext.sessionId} reason=session_replaced",
                    )
                    return@post
                }
                val mappedDeviceId = LocalDeviceIdentity.normalize(lastSeenDeviceId[sessionContext.address])
                val expectedDeviceId = LocalDeviceIdentity.normalize(sessionContext.deviceId)
                if (!running || mappedDeviceId != expectedDeviceId) {
                    dtnHelloSessionByDeviceAndGid.remove(throttleKey, sessionContext)
                    Log.i(
                        TAG,
                        "DTN dependency want stale peer=${sessionContext.deviceId} " +
                            "gid=${sessionContext.gid} session=${sessionContext.sessionId} reason=peer_not_current",
                    )
                    return@post
                }

                val dependencyWant = engine.buildDependencyRepairWantPacket(
                    remotePeerId = sessionContext.deviceId,
                    gid = sessionContext.gid,
                    sessionId = sessionContext.sessionId,
                    candidateRootIds = dependencyRoots,
                    nowSeconds = System.currentTimeMillis() / 1000L,
                ) ?: return@post
                val dependencyEncoded = DtnControlCodec.encode(dependencyWant)
                val dependencyQueued = broadcastEnvelopeToTargets(
                    dependencyEncoded,
                    listOf(sessionContext.address),
                )
                if (!dependencyQueued) {
                    engine.releaseDependencyRepairWantReservation(
                        remotePeerId = sessionContext.deviceId,
                        gid = sessionContext.gid,
                        sessionId = sessionContext.sessionId,
                        eventIds = dependencyWant.ids,
                    )
                    dtnHelloLastSentAtByDeviceId.remove(throttleKey, sessionContext.startedAtMs)
                    dtnHelloSessionByDeviceAndGid.remove(throttleKey, sessionContext)
                }
                dtnDiagnostics?.log(
                    DtnDiagnosticEvent(
                        label = "DTN dependency want ${if (dependencyQueued) "queued" else "send_failed"}",
                        type = DtnControlTypes.INV_WANT,
                        direction = if (dependencyQueued) "queued" else "send_failed",
                        peerId = sessionContext.deviceId,
                        gid = sessionContext.gid,
                        sessionId = sessionContext.sessionId,
                        idCount = dependencyWant.ids.size,
                        idSample = dependencyWant.ids.take(5),
                        reason = if (dependencyQueued) "proactive_after_hello" else "enqueue_failed",
                    )
                )
                Log.i(
                    TAG,
                    "DTN dependency want ${if (dependencyQueued) "queued" else "skipped"} " +
                        "peer=${sessionContext.deviceId} gid=${sessionContext.gid} " +
                        "session=${sessionContext.sessionId} roots=${dependencyWant.ids.size} " +
                        "root_sample=${dependencyWant.ids.take(5).joinToString(",")}",
                )
            }
        }
    }

    private fun buildDtnSessionId(deviceId: String, nowMs: Long): String {
        val nonce = ThreadLocalRandom.current().nextInt(0, Int.MAX_VALUE).toString(16)
        return "${deviceId.take(12)}-$nowMs-$nonce"
    }

    private fun forwardOpaqueEnvelope(
        blob: ByteArray,
        sourceAddress: String,
        sourceDeviceId: String?,
        originDeviceIds: Set<String> = emptySet(),
    ): Boolean {
        val rawJson = blob.toString(Charsets.UTF_8)
        val envelope = parseEnvelope(rawJson) ?: return false
        val dtn = envelope.dtn
        if (dtn != null) {
            val nowSeconds = System.currentTimeMillis() / 1000L
            val expiresAt = envelope.ts + dtn.ttl_seconds
            if (nowSeconds > expiresAt) return false
            if (dtn.hop_count >= dtn.max_hops) return false
            return broadcastOpaqueEnvelope(blob, sourceAddress, sourceDeviceId, originDeviceIds)
        }

        val ttl = envelope.ttl ?: return false
        val nextTtl = ttl - 1
        if (nextTtl <= 0) return false
        val updated = envelope.copy(ttl = nextTtl)
        val payload = CanonicalJson.json
            .encodeToString(EventEnvelope.serializer(), updated)
            .toByteArray(Charsets.UTF_8)
        return broadcastOpaqueEnvelope(payload, sourceAddress, sourceDeviceId, originDeviceIds)
    }

    private fun broadcastOpaqueEnvelope(
        blob: ByteArray,
        sourceAddress: String,
        sourceDeviceId: String?,
        originDeviceIds: Set<String> = emptySet(),
    ): Boolean {
        if (blob.isEmpty()) return false
        val nowMs = System.currentTimeMillis()
        val targetAddresses = selectTargetsForBroadcast(
            nowMs = nowMs,
            applySelectionSideEffects = false,
        ).keys.filter { address ->
            val targetDeviceId = resolveStableDeviceIdForAddress(address)
            address != sourceAddress &&
                targetDeviceId?.let { it != localDeviceId } != false &&
                targetDeviceId?.let { it != sourceDeviceId } != false &&
                targetDeviceId?.let { !originDeviceIds.contains(it) } != false
        }
        if (targetAddresses.isEmpty()) {
            Log.i(
                TAG,
                "Skip opaque forward: no target other than source addr=$sourceAddress device=${sourceDeviceId ?: "unknown"}",
            )
            return false
        }
        return broadcastEnvelopeToTargets(blob, targetAddresses)
    }

    private fun appBatchRelayOriginDeviceIds(
        batch: AppBatchEnvelope,
        sourceDeviceId: String?,
        receiverOriginDeviceIds: Set<String>,
    ): Set<String> {
        val ids = LinkedHashSet<String>()
        receiverOriginDeviceIds.forEach { id ->
            id.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        }
        sourceDeviceId?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        batch.sender_device_id?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        batch.sender_relay_id?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        return ids
    }

    private fun opaqueOriginDeviceIds(
        envelope: EventEnvelope?,
        batch: AppBatchEnvelope?,
        sourceDeviceId: String?,
    ): Set<String> {
        val ids = LinkedHashSet<String>()
        sourceDeviceId?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        batch?.sender_device_id?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        batch?.sender_relay_id?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        // Single envelopes do not yet carry a clear relay origin. Source device
        // identity is the strongest available signal for this legacy path.
        if (envelope != null) sourceDeviceId?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        return ids
    }

    private fun saveOpaqueForRetry(
        payload: ByteArray,
        envelope: EventEnvelope?,
        batch: AppBatchEnvelope?,
        sourcePeerId: String?,
        nowSeconds: Long,
    ): Boolean {
        val payloadSha = sha256Hex(payload)
        val fallbackBatchId = batch?.let { batchId(it) }
        val fallbackKind = when {
            batch != null -> OpaqueRelayCapsuleKind.APP_BATCH
            envelope != null -> OpaqueRelayCapsuleKind.ENVELOPE
            else -> null
        }
        val store = opaqueRelayStore ?: run {
            diagnosticsListener?.onOpaqueRetryStore(
                capsuleId = null,
                batchId = fallbackBatchId,
                kind = fallbackKind,
                reason = "store_missing",
                payloadSha256 = payloadSha,
                sourcePeerId = sourcePeerId,
                payloadBytes = payload.size.toLong(),
            )
            return false
        }
        val capsule = buildOpaqueCapsule(payload, envelope, batch, sourcePeerId, nowSeconds) ?: run {
            diagnosticsListener?.onOpaqueRetryStore(
                capsuleId = null,
                batchId = fallbackBatchId,
                kind = fallbackKind,
                reason = "capsule_build_failed",
                payloadSha256 = payloadSha,
                sourcePeerId = sourcePeerId,
                payloadBytes = payload.size.toLong(),
            )
            return false
        }
        val saved = runCatching { store.upsert(capsule) }.getOrDefault(false)
        Log.i(
            TAG,
            "Opaque retry store ${if (saved) "saved" else "skipped"} id=${capsule.capsuleId} kind=${capsule.kind} gid=${capsule.gid} source=${sourcePeerId ?: "unknown"}",
        )
        diagnosticsListener?.onOpaqueRetryStore(
            capsuleId = capsule.capsuleId,
            batchId = capsule.batchId,
            kind = capsule.kind,
            reason = if (saved) "store_saved" else "store_rejected",
            payloadSha256 = capsule.payloadSha256,
            sourcePeerId = sourcePeerId,
            payloadBytes = capsule.payloadBytes,
        )
        if (!saved) {
            diagnosticsListener?.onOpaqueRetrySkipped(
                capsuleId = capsule.capsuleId,
                batchId = capsule.batchId,
                kind = capsule.kind,
                reason = "store_rejected",
                targetCount = 0,
            )
        } else {
            opaqueCapsuleEventIds(capsule)?.takeIf { it.isNotEmpty() }?.let { eventIds ->
                dtnRepairSuppressionStore?.markLocallyPendingRelay(
                    eventIds = eventIds,
                    nowSeconds = nowSeconds,
                )
            }
        }
        return saved
    }

    private fun opaqueCapsuleEventIds(capsule: OpaqueRelayCapsule): List<String>? {
        capsule.eventId?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
        val rawJson = capsule.payload.decodeToString()
        val batch = runCatching {
            CanonicalJson.json.decodeFromString(AppBatchEnvelope.serializer(), rawJson)
        }.getOrNull() ?: return null
        return if (batch.v >= 2 && batch.items_v2.isNotEmpty()) {
            batch.items_v2.mapNotNull { item ->
                item.mid.takeIf { it.isNotBlank() }
            }.distinct()
        } else {
            batch.items.mapNotNull { encoded ->
                runCatching {
                    val bytes = Base64Url.decode(encoded)
                    CanonicalJson.json.decodeFromString(EventEnvelope.serializer(), bytes.decodeToString()).mid
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }.distinct()
        }
    }

    private fun buildOpaqueCapsule(
        payload: ByteArray,
        envelope: EventEnvelope?,
        batch: AppBatchEnvelope?,
        sourcePeerId: String?,
        nowSeconds: Long,
    ): OpaqueRelayCapsule? {
        val gid = envelope?.gid ?: batch?.gid ?: return null
        if (gid.isBlank()) return null
        val id = envelope?.mid ?: batch?.let { batchId(it) } ?: return null
        val dtn = envelope?.dtn ?: batch?.dtn
        val ttlSeconds = envelope?.ttl ?: batch?.ttl ?: dtn?.ttl_seconds
        val createdAt = envelope?.ts ?: batch?.ts ?: nowSeconds
        val expiresAt = ttlSeconds?.let { createdAt + it }
        if (expiresAt != null && expiresAt <= nowSeconds) return null
        val payloadSha = sha256Hex(payload)
        val kind = if (batch != null) OpaqueRelayCapsuleKind.APP_BATCH else OpaqueRelayCapsuleKind.ENVELOPE
        return OpaqueRelayCapsule(
            capsuleId = "ble_${sha256Hex("$kind|$gid|$id|$payloadSha".toByteArray(Charsets.UTF_8)).take(40)}",
            gid = gid,
            eventId = envelope?.mid,
            batchId = batch?.let { batchId(it) },
            kind = kind,
            payload = payload,
            payloadSha256 = payloadSha,
            payloadBytes = payload.size.toLong(),
            createdAtSeconds = createdAt,
            receivedAtSeconds = nowSeconds,
            expiresAtSeconds = expiresAt,
            hopCount = dtn?.hop_count ?: 0,
            maxHops = dtn?.max_hops ?: 20,
            priority = dtn?.priority ?: 1,
            sourcePeerId = sourcePeerId,
        )
    }

    private fun scheduleOpaqueRetryDrain(
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ) {
        if (opaqueRelayStore == null || !running) return
        if (!force && nowMs - lastOpaqueRetryAtMs < OPAQUE_RETRY_DRAIN_INTERVAL_MS) return
        if (!opaqueRetryInFlight.compareAndSet(false, true)) return
        lastOpaqueRetryAtMs = nowMs
        workerHandler.post {
            try {
                drainOpaqueRetryStore()
            } finally {
                opaqueRetryInFlight.set(false)
            }
        }
    }

    private fun scheduleOpaqueRetryDrainSoon(delayMs: Long) {
        if (opaqueRelayStore == null || !running) return
        workerHandler.postDelayed({
            scheduleOpaqueRetryDrain(force = true)
        }, delayMs)
    }

    private fun drainOpaqueRetryStore() {
        val store = opaqueRelayStore ?: return
        if (nearbyForegroundMode) {
            Log.i(TAG, "NEARBY_FOREGROUND relay_deferred kind=opaque_retry")
            scheduleOpaqueRetryDrainSoon(NEARBY_FOREGROUND_RELAY_RECHECK_MS)
            return
        }
        val nowMs = System.currentTimeMillis()
        val nowSeconds = nowMs / 1000L
        pruneHotOpaqueRetries(nowMs)
        val cleaned = runCatching { store.cleanupExpired(nowSeconds) }.getOrDefault(0)
        if (cleaned > 0) {
            diagnosticsListener?.onOpaqueRetryDeleted(
                batchId = "expired_or_orphan",
                capsuleCount = cleaned,
                reason = "ttl_cleanup",
            )
        }
        val targetsByAddress = selectTargetsForBroadcast(
            nowMs = nowMs,
            applySelectionSideEffects = false,
        ).keys.mapNotNull { address ->
            val deviceId = resolveStableDeviceIdForAddress(address) ?: return@mapNotNull null
            address to deviceId
        }
        if (targetsByAddress.isEmpty()) return

        var queuedCount = 0
        targetsByAddress.forEach { (address, deviceId) ->
            if (queuedCount >= OPAQUE_RETRY_DRAIN_LIMIT) return@forEach
            val hasHotRetry = hasHotOpaqueRetryForDevice(deviceId, nowMs)
            val perTargetLimit = if (hasHotRetry) {
                OPAQUE_RETRY_HOT_PER_TARGET_LIMIT
            } else {
                OPAQUE_RETRY_PER_TARGET_LIMIT
            }
            val capsules = runCatching {
                store.listForwardableForDevice(
                    deviceId = deviceId,
                    nowSeconds = nowSeconds,
                    limit = perTargetLimit,
                )
            }.getOrElse { error ->
                Log.w(TAG, "Opaque retry load failed device=$deviceId", error)
                emptyList()
            }.filter { capsule ->
                deviceId != capsule.sourcePeerId
            }.sortedWith(
                compareByDescending<OpaqueRelayCapsule> { capsule ->
                    hotOpaqueRetryRank(capsule, deviceId, nowMs)
                }.thenByDescending { capsule ->
                    capsule.receivedAtSeconds
                }
            )
            if (capsules.isEmpty()) return@forEach
            capsules.forEach { capsule ->
                if (queuedCount >= OPAQUE_RETRY_DRAIN_LIMIT) return@forEach
                val queued = broadcastEnvelopeToTargets(capsule.payload, listOf(address))
                Log.i(
                    TAG,
                    "Opaque retry ${if (queued) "queued" else "skipped"} id=${capsule.capsuleId} target=$deviceId",
                )
                if (queued) {
                    store.markDeliveryQueued(capsule.capsuleId, deviceId, nowSeconds)
                    rememberOpaqueRetryCapsule(capsule, deviceId)
                    diagnosticsListener?.onOpaqueRetryQueuedState(
                        capsuleId = capsule.capsuleId,
                        batchId = capsule.batchId,
                        deviceId = deviceId,
                        deliveryStateSummary = store.describeDelivery(capsule.capsuleId, deviceId),
                    )
                    queuedCount += 1
                    diagnosticsListener?.onOpaqueRetryQueued(
                        capsuleId = capsule.capsuleId,
                        batchId = capsule.batchId,
                        kind = capsule.kind,
                        targetCount = 1,
                    )
                } else {
                    capsule.batchId?.let { batchId ->
                        store.markDeliveryFailedByBatchId(
                            batchId = batchId,
                            deviceId = deviceId,
                            nowSeconds = nowSeconds,
                            reason = "enqueue_failed",
                        )
                        rememberHotOpaqueRetry(batchId, deviceId, setOf(capsule.capsuleId), nowMs)
                        scheduleOpaqueRetryDrainSoon(OPAQUE_RETRY_HOT_RETRY_DELAY_MS)
                    }
                    diagnosticsListener?.onOpaqueRetrySkipped(
                        capsuleId = capsule.capsuleId,
                        batchId = capsule.batchId,
                        kind = capsule.kind,
                        reason = "enqueue_failed",
                        targetCount = 1,
                    )
                }
            }
        }
    }

    private fun rememberHotOpaqueRetry(
        batchId: String,
        deviceId: String,
        capsuleIds: Collection<String>,
        nowMs: Long,
    ) {
        rememberHotOpaqueRetry(batchId, listOf(deviceId), capsuleIds, nowMs)
    }

    private fun rememberHotOpaqueRetry(
        batchId: String,
        deviceIds: Collection<String>,
        capsuleIds: Collection<String>,
        nowMs: Long,
    ) {
        val expiresAtMs = nowMs + OPAQUE_RETRY_HOT_TTL_MS
        deviceIds.distinct().forEach { deviceId ->
            val tokens = hotOpaqueRetryTokensByDevice.computeIfAbsent(deviceId) { ConcurrentHashMap() }
            tokens[hotOpaqueRetryBatchToken(batchId)] = expiresAtMs
            capsuleIds.forEach { capsuleId ->
                tokens[hotOpaqueRetryCapsuleToken(capsuleId)] = expiresAtMs
            }
        }
    }

    private fun clearHotOpaqueRetry(
        batchId: String,
        deviceId: String,
        capsuleIds: Collection<String>,
    ) {
        clearHotOpaqueRetry(batchId, listOf(deviceId), capsuleIds)
    }

    private fun clearHotOpaqueRetry(
        batchId: String,
        deviceIds: Collection<String>,
        capsuleIds: Collection<String>,
    ) {
        deviceIds.distinct().forEach { deviceId ->
            val tokens = hotOpaqueRetryTokensByDevice[deviceId] ?: return@forEach
            tokens.remove(hotOpaqueRetryBatchToken(batchId))
            capsuleIds.forEach { capsuleId ->
                tokens.remove(hotOpaqueRetryCapsuleToken(capsuleId))
            }
            if (tokens.isEmpty()) {
                hotOpaqueRetryTokensByDevice.remove(deviceId, tokens)
            }
        }
    }

    private fun hasHotOpaqueRetryForDevice(deviceId: String, nowMs: Long): Boolean {
        val tokens = hotOpaqueRetryTokensByDevice[deviceId] ?: return false
        return tokens.any { (_, expiresAtMs) -> expiresAtMs > nowMs }
    }

    private fun hotOpaqueRetryRank(
        capsule: OpaqueRelayCapsule,
        deviceId: String,
        nowMs: Long,
    ): Int {
        val tokens = hotOpaqueRetryTokensByDevice[deviceId] ?: return 0
        val capsuleHot = tokens[hotOpaqueRetryCapsuleToken(capsule.capsuleId)]?.let { it > nowMs } == true
        val batchId = capsule.batchId
        val batchHot = batchId != null &&
            tokens[hotOpaqueRetryBatchToken(batchId)]?.let { it > nowMs } == true
        return when {
            capsuleHot -> 2
            batchHot -> 1
            else -> 0
        }
    }

    private fun pruneHotOpaqueRetries(nowMs: Long) {
        hotOpaqueRetryTokensByDevice.entries.removeIf { (_, tokens) ->
            tokens.entries.removeIf { (_, expiresAtMs) -> expiresAtMs <= nowMs }
            tokens.isEmpty()
        }
    }

    private fun hotOpaqueRetryBatchToken(batchId: String): String = "batch:$batchId"

    private fun hotOpaqueRetryCapsuleToken(capsuleId: String): String = "capsule:$capsuleId"

    private fun rememberOpaqueRetryCapsule(capsule: OpaqueRelayCapsule, deviceId: String) {
        val batchId = capsule.batchId ?: return
        opaqueRetryCapsulesByBatchAndDevice
            .computeIfAbsent(opaqueRetryKey(batchId, deviceId)) { ConcurrentHashMap.newKeySet() }
            .add(capsule.capsuleId)
    }

    private fun opaqueRetryKey(batchId: String, deviceId: String): String = "$batchId\u0000$deviceId"

    @SuppressLint("MissingPermission")
    override fun broadcastEnvelope(blob: ByteArray): Boolean {
        return broadcastEnvelopeInternal(
            blob = blob,
            preferredDeviceIds = null,
            trafficHint = MeshTrafficHint.DEFAULT,
        )
    }

    @SuppressLint("MissingPermission")
    override fun broadcastEnvelopeToDeviceIds(blob: ByteArray, deviceIds: Set<String>): Boolean {
        return broadcastEnvelopeInternal(
            blob = blob,
            preferredDeviceIds = deviceIds,
            trafficHint = MeshTrafficHint.DEFAULT,
        )
    }

    @SuppressLint("MissingPermission")
    override fun broadcastEnvelopeWithHint(blob: ByteArray, hint: MeshTrafficHint): Boolean {
        return broadcastEnvelopeInternal(
            blob = blob,
            preferredDeviceIds = null,
            trafficHint = hint,
        )
    }

    @SuppressLint("MissingPermission")
    override fun broadcastEnvelopeToDeviceIdsWithHint(
        blob: ByteArray,
        deviceIds: Set<String>,
        hint: MeshTrafficHint,
    ): Boolean {
        return broadcastEnvelopeInternal(
            blob = blob,
            preferredDeviceIds = deviceIds,
            trafficHint = hint,
        )
    }

    override fun admitEnvelopeWithHint(
        blob: ByteArray,
        hint: MeshTrafficHint,
    ): MeshSendAdmissionResult = admitEnvelopeInternal(blob, null, hint)

    override fun admitEnvelopeToDeviceIdsWithHint(
        blob: ByteArray,
        deviceIds: Set<String>,
        hint: MeshTrafficHint,
    ): MeshSendAdmissionResult = admitEnvelopeInternal(blob, deviceIds, hint)

    private fun admitEnvelopeInternal(
        blob: ByteArray,
        preferredDeviceIds: Set<String>?,
        trafficHint: MeshTrafficHint,
    ): MeshSendAdmissionResult {
        if (!BleNearbyTrafficPolicy.allowsAdmission(nearbyForegroundMode, trafficHint, isAckPayload(blob))) {
            val trafficClass = trafficClass(trafficHint, isDtnControlPayload(blob), extractBatchPayloadMeta(blob) != null)
            foregroundDiagnostic(
                NearbyForegroundDiagnosticLabels.ADMISSION_DEFERRED,
                "runtime_state=${foregroundRuntimeState()} traffic_class=$trafficClass classification_source=traffic_hint result=DEFERRED api_return=typed semantic=foreground_deferred source_retained=true retry_consumed=false future_resume_path_exists=true",
            )
            return MeshSendAdmissionResult.DeferredForeground
        }
        return if (broadcastEnvelopeInternal(blob, preferredDeviceIds, trafficHint)) {
            MeshSendAdmissionResult.Enqueued
        } else {
            MeshSendAdmissionResult.Rejected("broadcast_rejected")
        }
    }

    @SuppressLint("MissingPermission")
    private fun broadcastEnvelopeInternal(
        blob: ByteArray,
        preferredDeviceIds: Set<String>?,
        trafficHint: MeshTrafficHint,
    ): Boolean {
        val localAdapter = adapter
        if (localAdapter == null || !hasBleFeature || advertiser == null || scanner == null) {
            return false
        }
        if (!localAdapter.isEnabled) return false
        if (blob.isEmpty()) return false
        val nowMs = System.currentTimeMillis()
        val canonicalPreferred = canonicalDeviceIds(preferredDeviceIds)
        var targets = selectTargetsForBroadcast(nowMs, canonicalPreferred)
        if (targets.isEmpty() && !canonicalPreferred.isNullOrEmpty()) {
            val preferredIds = canonicalPreferred.joinToString(",")
            Log.i(
                TAG,
                "BLE target fallback preferred=$preferredIds reason=preferred_targets_unavailable",
            )
            targets = selectTargetsForBroadcast(nowMs, preferredDeviceIds = null)
            diagnosticsListener?.onBlePreferredTargetFallback(
                preferredIds = preferredIds,
                preferredResultCount = 0,
                fallbackSelectedIds = targets.keys.joinToString(",").ifBlank { "none" },
                reason = "preferred_targets_unavailable",
            )
        }
        if (targets.isEmpty()) {
            Log.i(TAG, "No nearby devices to send")
            return false
        }
        return broadcastEnvelopeToTargets(
            blob = blob,
            targetAddresses = targets.keys.toList(),
            trafficHint = trafficHint,
        )
    }

    private fun broadcastEnvelopeToTargets(
        blob: ByteArray,
        targetAddresses: List<String>,
        preferDtnFastPath: Boolean = false,
        trafficHint: MeshTrafficHint = MeshTrafficHint.DEFAULT,
    ): Boolean {
        if (targetAddresses.isEmpty()) return false
        val isAckPayload = isAckPayload(blob)
        if (!BleNearbyTrafficPolicy.allowsAdmission(nearbyForegroundMode, trafficHint, isAckPayload)) {
            val trafficClass = trafficClass(
                hint = trafficHint,
                dtn = isDtnControlPayload(blob),
                batch = extractBatchPayloadMeta(blob) != null,
            )
            foregroundDiagnostic(
                NearbyForegroundDiagnosticLabels.ADMISSION_DEFERRED,
                "runtime_state=${foregroundRuntimeState()} traffic_class=$trafficClass classification_source=traffic_hint " +
                    "item_id=${extractItemId(blob)} batch_id=${extractBatchPayloadMeta(blob)?.batchId ?: "none"} " +
                    "result=DEFERRED api_return=false semantic=foreground_deferred source_retained=true retry_consumed=false future_resume_path_exists=true",
            )
            Log.i(
                TAG,
                "NEARBY_FOREGROUND admission_blocked hint=$trafficHint targets=${targetAddresses.size}",
            )
            return false
        }
        val isDtnControlPayload = isDtnControlPayload(blob)
        val dtnControlType = if (isDtnControlPayload) inferDtnControlDiagnosticType(blob) else null
        val dtnControlSessionMeta = if (isDtnControlPayload) inferDtnControlSessionMeta(blob) else null
        val batchMeta = extractBatchPayloadMeta(blob)
        val itemId = extractItemId(blob)
        if (nearbyForegroundMode && BleNearbyTrafficPolicy.isNearbyRoomTraffic(trafficHint)) {
            foregroundDiagnostic(
                NearbyForegroundDiagnosticLabels.ADMISSION_ALLOWED,
                "runtime_state=${foregroundRuntimeState()} traffic_class=${trafficClass(trafficHint, isDtnControlPayload, batchMeta != null)} " +
                    "classification_source=traffic_hint item_id=$itemId batch_id=${batchMeta?.batchId ?: "none"} result=ALLOWED",
            )
        }
        val nowMs = System.currentTimeMillis()
        pruneItemSendStates(nowMs)
        if (!isAckPayload) {
            lastDataActivityAtMs = nowMs
        }
        if (batchMeta != null) {
            noteFreshEvents(batchMeta, nowMs)
        }
        updateBackpressure(totalPendingBytes())
        val enqueuedTargets = LinkedHashSet<String>()
        targetAddresses.forEach { address ->
            val deviceId = canonicalDeviceIdOrSelf(lastSeenDeviceId[address] ?: "addr:$address")
            if (deviceId == localDeviceId) {
                Log.i(TAG, "stale_identity_suppressed addr=$address id=$deviceId reason=enqueue_local_identity")
                return@forEach
            }

            var canEnqueue = canEnqueueItem(address, itemId, nowMs)
            if (!canEnqueue &&
                maybeClearStaleEnqueueState(
                    address = address,
                    itemId = itemId,
                    deviceId = deviceId,
                    reason = "enqueue_blocked_retry",
                )
            ) {
                canEnqueue = canEnqueueItem(address, itemId, nowMs)
            }
            if (!canEnqueue) {
                if (batchMeta != null) {
                    diagnosticsListener?.onBleBatchTargetPending(
                        batchId = batchMeta.batchId,
                        deviceId = deviceId,
                        eventIds = batchMeta.eventIds,
                        reason = "enqueue_blocked",
                    )
                }
                return@forEach
            }

            val lastEnqueueAt = lastEnqueueLabelAtMsByAddress[address] ?: 0L
            val shouldApplyEnqueueLabelCooldown =
                isAckPayload || (batchMeta == null && !isDtnControlPayload)
            if (shouldApplyEnqueueLabelCooldown && nowMs - lastEnqueueAt < ENQUEUE_LABEL_COOLDOWN_MS) {
                if (batchMeta != null) {
                    diagnosticsListener?.onBleBatchTargetPending(
                        batchId = batchMeta.batchId,
                        deviceId = deviceId,
                        eventIds = batchMeta.eventIds,
                        reason = "enqueue_label_cooldown",
                    )
                }
                return@forEach
            }

            val chunks = Chunking.split(blob, maxChunkSizeFor(address))
            if (chunks.isEmpty()) {
                if (batchMeta != null) {
                    diagnosticsListener?.onBleBatchTargetPending(
                        batchId = batchMeta.batchId,
                        deviceId = deviceId,
                        eventIds = batchMeta.eventIds,
                        reason = "empty_chunks",
                    )
                }
                return@forEach
            }
            if (nearbyForegroundMode &&
                BleNearbyTrafficPolicy.isNearbyRoomTraffic(trafficHint) &&
                !hasNearbyForegroundQueueHeadroom(address, chunks.size)
            ) {
                foregroundDiagnostic(
                    NearbyForegroundDiagnosticLabels.QUEUE_RESERVE,
                    "runtime_state=${foregroundRuntimeState()} addr=$address incoming_chunks=${chunks.size} result=rejected traffic_class=${trafficClass(trafficHint, isDtnControlPayload, batchMeta != null)}",
                )
                Log.w(
                    TAG,
                    "NEARBY_FOREGROUND admission_blocked hint=$trafficHint addr=$address reason=reserve_exhausted chunks=${chunks.size}",
                )
                return@forEach
            }
            if (nearbyForegroundMode && BleNearbyTrafficPolicy.isNearbyRoomTraffic(trafficHint)) {
                foregroundDiagnostic(
                    NearbyForegroundDiagnosticLabels.QUEUE_RESERVE,
                    "runtime_state=${foregroundRuntimeState()} addr=$address incoming_chunks=${chunks.size} result=accepted traffic_class=${trafficClass(trafficHint, isDtnControlPayload, batchMeta != null)}",
                )
            }

            val packets = chunks.map { chunk ->
                PendingPacket(
                    payload = chunk,
                    isAck = isAckPayload,
                    itemId = itemId,
                    deviceId = deviceId,
                    trafficHint = trafficHint,
                    nearbyForegroundProtected = nearbyForegroundMode &&
                        BleNearbyTrafficPolicy.isNearbyRoomTraffic(trafficHint),
                    dtnControlType = dtnControlType,
                    dtnControlGid = dtnControlSessionMeta?.gid,
                    dtnControlSessionId = dtnControlSessionMeta?.sessionId,
                    payloadBytes = blob.size,
                    totalChunks = chunks.size,
                    chunkBytes = chunk.size,
                    batchId = batchMeta?.batchId,
                    batchEventIds = batchMeta?.eventIds ?: emptyList(),
                )
            }

            // Keep DATA out of the pending queue until the peer is ready so an
            // unnegotiated chunk layout cannot saturate the per-peer queue.
            ensureItemSendState(
                itemId = itemId,
                deviceId = deviceId,
                payloadBytes = blob.size,
                totalChunks = chunks.size,
                nowMs = nowMs,
            )
            var batchAttemptSeq: Long? = null
            if (batchMeta != null) {
                batchAttemptSeq = ensureBatchSendState(
                    batchId = batchMeta.batchId,
                    deviceId = deviceId,
                    eventIds = batchMeta.eventIds,
                    createdAtMs = batchMeta.createdAtMs,
                    payloadBytes = blob.size,
                    totalChunks = chunks.size,
                    nowMs = nowMs,
                ).attemptSeq
                dropStaleBatchPacketsForAttempt(
                    batchId = batchMeta.batchId,
                    deviceId = deviceId,
                    currentAttemptSeq = batchAttemptSeq,
                )
                diagnosticsListener?.onBleBatchTargetQueued(
                    batchId = batchMeta.batchId,
                    deviceId = deviceId,
                    eventIds = batchMeta.eventIds,
                )
            }
            val packetsWithAttempt = if (batchAttemptSeq == null) {
                packets
            } else {
                packets.map { it.copy(batchAttemptSeq = batchAttemptSeq) }
            }
            val useDtnFastPath = preferDtnFastPath && isDtnControlPayload
            val holdUntilReady = !useDtnFastPath && !isAckPayload && !isDtnControlPayload && !isAddressReadyForHeldRelease(address, nowMs)
            if (useDtnFastPath) {
                val fastQueued = enqueueDtnControlPacketsAtPriority(address, packetsWithAttempt)
                if (!fastQueued) {
                    if (batchMeta != null) {
                        diagnosticsListener?.onBleBatchTargetPending(
                            batchId = batchMeta.batchId,
                            deviceId = deviceId,
                            eventIds = batchMeta.eventIds,
                            reason = "enqueue_fastpath_rejected",
                        )
                    }
                    return@forEach
                }
            } else {
                synchronized(schedulerLock) {
                    val targetQueue = if (holdUntilReady) {
                        heldEnqueueByAddress.getOrPut(address) { ArrayDeque() }
                    } else {
                        stagedEnqueueByAddress.getOrPut(address) { ArrayDeque() }
                    }
                    packetsWithAttempt.forEach { packet ->
                        targetQueue.addLast(packet)
                    }
                }
            }
            markItemEnqueued(address, itemId)
            enqueuedTargets.add(address)
            offerDeviceForRoundRobin(address)
            lastEnqueueLabelAtMsByAddress[address] = nowMs

            Log.i(
                TAG,
                "${if (useDtnFastPath) "Fast-path" else if (holdUntilReady) "Hold" else "Stage"} data enqueue addr=$address deviceId=${resolveStableDeviceIdForAddress(address) ?: "unknown"} bytes=${blob.size} chunks=${chunks.size} state=${connectState(address)} hasCharacteristic=${characteristicByDevice[address] != null} hasMtu=${mtuByDevice.containsKey(address)} pulledJob=${pulledDispatchJobByAddress[address]?.batchId ?: "none"}",
            )
            dtnControlType?.let { type ->
                traceDtnControlQueueState(
                    address = address,
                    type = type,
                    stage = when {
                        useDtnFastPath -> "enqueue_fast_pending"
                        holdUntilReady -> "enqueue_held"
                        else -> "enqueue_staged"
                    },
                )
            }

            if (useDtnFastPath) {
                postLabel(address, PipelineLabel.WRITE_NEXT)
            } else if (holdUntilReady) {
                postLabel(address, PipelineLabel.CONNECT)
            } else {
                postLabel(address, PipelineLabel.ENQUEUE)
            }
        }
        if (enqueuedTargets.isEmpty()) {
            Log.w(TAG, "No packet enqueued for any selected target")
            return false
        }
        BleTrafficStats.addUploaded(blob.size.toLong())
        Log.i(TAG, "Queued BLE send to devices=${enqueuedTargets.size}")
        diagnosticsListener?.onQueuedSend(enqueuedTargets.size)
        diagnosticsListener?.onQueuedSendTargets(sampleTargetAddresses(enqueuedTargets))
        return true
    }

    private fun enqueueDtnControlPacketsAtPriority(
        address: String,
        packets: List<PendingPacket>,
    ): Boolean {
        if (packets.isEmpty()) return false
        synchronized(pendingQueueLock) {
            val queue = pendingByDevice.getOrPut(address) { ArrayDeque() }
            val accepted = ArrayList<PendingPacket>(packets.size)
            for (packet in packets) {
                if (!enqueuePacket(address = address, queue = queue, packet = packet)) {
                    queue.removeAll(accepted.toSet())
                    return false
                }
                accepted.add(packet)
            }
            if (accepted.isEmpty()) return false
            val reordered = ArrayDeque<PendingPacket>()
            val leadingDtn = ArrayList<PendingPacket>()
            while (queue.isNotEmpty() && queue.first().dtnControlType != null) {
                leadingDtn.add(queue.removeFirst())
            }
            leadingDtn.forEach { reordered.addLast(it) }
            accepted.forEach { reordered.addLast(it) }
            queue.removeAll(accepted.toSet())
            while (queue.isNotEmpty()) {
                reordered.addLast(queue.removeFirst())
            }
            queue.addAll(reordered)
            return true
        }
    }

    private fun noteFreshEvents(batchMeta: BatchPayloadMeta, nowMs: Long) {
        pruneFreshWindow(nowMs)
        if (batchMeta.createdAtMs < nowMs - FRESH_WINDOW_MS) return
        batchMeta.eventIds.forEach { eventId ->
            freshEventCreatedAtMs[eventId] = batchMeta.createdAtMs
        }
    }

    private fun inferDtnControlDiagnosticType(blob: ByteArray): String? {
        val packet = DtnControlCodec.decode(blob) ?: return null
        return if (packet.type == DtnControlTypes.DATA && packet.carrierData.isNotEmpty()) {
            "carrier_data"
        } else {
            packet.type
        }
    }

    private fun inferDtnControlSessionMeta(blob: ByteArray): DtnControlSessionMeta? {
        val packet = DtnControlCodec.decode(blob) ?: return null
        return DtnControlSessionMeta(
            gid = packet.gid,
            sessionId = packet.sessionId,
        )
    }

    private fun traceDtnControlQueueState(
        address: String,
        type: String,
        stage: String,
        reason: String? = null,
    ) {
        val pending = synchronized(pendingQueueLock) {
            pendingByDevice[address]?.count { it.dtnControlType == type } ?: 0
        }
        val staged = synchronized(schedulerLock) {
            stagedEnqueueByAddress[address]?.count { it.dtnControlType == type } ?: 0
        }
        val held = synchronized(schedulerLock) {
            heldEnqueueByAddress[address]?.count { it.dtnControlType == type } ?: 0
        }
        val hasActive = activePendingPacketByAddress[address]?.dtnControlType == type
        diagnosticsListener?.onDtnControlQueueState(
            address = address,
            type = type,
            stage = stage,
            pending = pending,
            staged = staged,
            held = held,
            hasActive = hasActive,
            reason = reason,
        )
    }

    private fun shouldDeferUntilMtuKnown(address: String, payloadBytes: Int): Boolean {
        val configuredChunkBytes = BleConfig.defaultGattPayloadBytes
        val effectiveDataBytes =
            Chunking.payloadBytesFromChunkBytes(configuredChunkBytes).coerceAtLeast(1)
        val estimatedChunks =
            ((payloadBytes + effectiveDataBytes - 1) / effectiveDataBytes)
        val hasMtu = mtuByDevice.containsKey(address)
        val connected = connectState(address) == CONNECT_STATE_CONNECTED
        val defer =
            !hasMtu && connected && estimatedChunks > MAX_UNNEGOTIATED_CHUNKS_PER_DATA_SEND
        val reason = when {
            hasMtu -> "mtu_known"
            !connected -> "not_connected"
            defer -> "estimated_chunks_over_limit"
            else -> "estimated_chunks_within_limit"
        }
        Log.d(
            TAG,
            "MTU defer check addr=$address payloadBytes=$payloadBytes " +
                "configuredChunkBytes=$configuredChunkBytes effectiveDataBytes=$effectiveDataBytes " +
                "estimatedChunks=$estimatedChunks defer=$defer reason=$reason",
        )
        return defer
    }

    private fun shouldDeferFirstBatchWarmup(address: String, payloadBytes: Int, nowMs: Long): Boolean {
        val warmupUntilMs = dataWarmupUntilByAddress[address] ?: return false
        if (mtuByDevice.containsKey(address)) return false
        if (connectState(address) != CONNECT_STATE_CONNECTED) return false

        // Stronger first-data gate:
        // if this connection is still in warmup and has not negotiated MTU yet,
        // hold the first DATA item even when payload is small.
        val hasActiveDataWrite = activePendingPacketByAddress[address]?.isAck == false
        if (hasPendingDataForAddress(address) || hasActiveDataWrite) return false

        return nowMs < warmupUntilMs
    }

    private fun shouldDeferUntilRotationRecoveryReady(
        address: String,
        payloadBytes: Int,
        nowMs: Long,
    ): Boolean {
        val deviceId = lastSeenDeviceId[address] ?: return false
        if (!isRotationRecoveryCandidate(deviceId, address, nowMs)) return false
        if (connectState(address) != CONNECT_STATE_CONNECTED) return true

        val hasOldLaneStillWorking = candidateAddressesForDevice(deviceId, nowMs).any { candidate ->
            candidate != address && hasRecoverableActivePathForAddress(candidate)
        }
        if (hasOldLaneStillWorking) return true

        if (mtuByDevice.containsKey(address)) return false
        val estimatedChunks =
            ((payloadBytes + BleConfig.defaultGattPayloadBytes - 1) / BleConfig.defaultGattPayloadBytes)
        return estimatedChunks > ROTATION_RECOVERY_UNNEGOTIATED_CHUNK_LIMIT
    }

    private fun selectRelayCompensationTarget(
        nowMs: Long,
        sourceAddress: String,
        attemptedAddresses: Set<String>,
    ): String? {
        return selectTargetsForBroadcast(nowMs, applySelectionSideEffects = false).keys.firstOrNull { address ->
            address != sourceAddress && !attemptedAddresses.contains(address)
        }
    }

    private fun relayCandidateDeviceIds(
        nowMs: Long,
        sourceAddress: String,
        sourceDeviceId: String?,
        originDeviceIds: Set<String> = emptySet(),
    ): LinkedHashSet<String> {
        return selectTargetsForBroadcast(nowMs, applySelectionSideEffects = false)
            .keys
            .asSequence()
            .filter { address -> address != sourceAddress }
            .mapNotNull { address -> resolveStableDeviceIdForAddress(address) }
            .filter { deviceId -> deviceId != localDeviceId }
            .filter { deviceId -> deviceId != sourceDeviceId }
            .filter { deviceId -> !originDeviceIds.contains(deviceId) }
            .toCollection(LinkedHashSet())
    }

    private fun selectRelayTargetForDeviceIds(
        nowMs: Long,
        sourceAddress: String,
        sourceDeviceId: String?,
        originDeviceIds: Set<String>,
        targetDeviceIds: Set<String>,
        attemptedAddresses: Set<String>,
    ): String? {
        return selectTargetsForBroadcast(nowMs, applySelectionSideEffects = false).keys.firstOrNull { address ->
            address != sourceAddress &&
                !attemptedAddresses.contains(address) &&
                resolveStableDeviceIdForAddress(address) != localDeviceId &&
                resolveStableDeviceIdForAddress(address) != sourceDeviceId &&
                !originDeviceIds.contains(resolveStableDeviceIdForAddress(address)) &&
                targetDeviceIds.contains(resolveStableDeviceIdForAddress(address))
        }
    }

    private fun selectDelayedRelayTargetDeviceId(
        batchId: String,
        sourceAddress: String,
        sourceDeviceId: String?,
        originDeviceIds: Set<String>,
        nowMs: Long,
    ): String? {
        val candidateDeviceIds = relayCandidateDeviceIds(
            nowMs = nowMs,
            sourceAddress = sourceAddress,
            sourceDeviceId = sourceDeviceId,
            originDeviceIds = originDeviceIds,
        ).toList()
        if (candidateDeviceIds.isEmpty()) return null

        val pendingTargets = diagnosticsListener
            ?.selectRelayBatchTargetDeviceIds(batchId, candidateDeviceIds, nowMs)
            ?.toCollection(LinkedHashSet())
            ?: LinkedHashSet(candidateDeviceIds)

        if (pendingTargets.isEmpty()) return null

        return candidateDeviceIds.firstOrNull { pendingTargets.contains(it) }
    }

    private fun delayedRelayKey(batchId: String, targetDeviceId: String): String = "$batchId|$targetDeviceId"

    private fun shouldScheduleDelayedRelay(
        batch: AppBatchEnvelope,
        payload: ByteArray,
        sourceDeviceId: String?,
        nowMs: Long,
    ): Boolean {
        if (payload.isEmpty()) return false
        if (payload.size > DELAYED_RELAY_MAX_PAYLOAD_BYTES) return false
        if (nowMs - batch.ts > DELAYED_RELAY_MAX_AGE_MS) return false
        return true
    }

    private fun isMeshBusyForDelayedRelay(): Boolean {
        // "Connected" alone is not busy.
        // We only block delayed relay when there is real send pressure or in-flight work.
        if (activePendingPacketByAddress.isNotEmpty()) return true
        if (hasPendingDataTraffic()) return true
        if (totalPendingPackets() > 0) return true

        val hasConnectingWork = connectingClient.keys.any { address ->
            hasPendingDataForAddress(address) || hasHeldDataForAddress(address)
        }
        if (hasConnectingWork) return true

        return false
    }

    private fun handleTargetSelectionEmpty(
        nowMs: Long,
        candidateCount: Int,
        mainTargetCount: Int,
        penaltyFilteredCount: Int,
        nearbyCount: Int,
        reason: String,
    ) {
        consecutiveNoCandidateSelections += 1
        var finalReason = reason
        val shouldRecover =
            running &&
                consecutiveNoCandidateSelections >= NO_CANDIDATE_RECOVERY_THRESHOLD &&
                (nowMs - lastNoCandidateRecoveryAtMs >= NO_CANDIDATE_RECOVERY_COOLDOWN_MS)
        if (shouldRecover) {
            lastNoCandidateRecoveryAtMs = nowMs
            finalReason = "$reason|scan_restart"
            updateScanSettings()
        }
        diagnosticsListener?.onBleTargetSelectEmpty(
            candidateCount = candidateCount,
            mainTargetCount = mainTargetCount,
            penaltyFilteredCount = penaltyFilteredCount,
            nearbyCount = nearbyCount,
            reason = finalReason,
        )
    }

    private fun markTargetSelectionSuccess() {
        consecutiveNoCandidateSelections = 0
    }

    private fun selectTargetsForBroadcast(
        nowMs: Long,
        preferredDeviceIds: Set<String>? = null,
        applySelectionSideEffects: Boolean = true,
    ): Map<String, Long> {
        val candidates = buildFreshCandidates(nowMs)
        if (candidates.isEmpty()) {
            if (applySelectionSideEffects) {
                handleTargetSelectionEmpty(
                    nowMs = nowMs,
                    candidateCount = 0,
                    mainTargetCount = 0,
                    penaltyFilteredCount = 0,
                    nearbyCount = nearbyDeviceCount(nowMs),
                    reason = "no_candidates",
                )
            }
            return emptyMap()
        }
        if (!BleConfig.useManufacturerData) {
            val selected = candidates.values
                .sortedWith(compareByDescending<DeviceCandidate> { it.seenAtMs }.thenByDescending { it.rssi })
                .take(MAX_BROADCAST_TARGETS)
            if (selected.isEmpty()) {
                if (applySelectionSideEffects) {
                    handleTargetSelectionEmpty(
                        nowMs = nowMs,
                        candidateCount = candidates.size,
                        mainTargetCount = 0,
                        penaltyFilteredCount = 0,
                        nearbyCount = nearbyDeviceCount(nowMs),
                        reason = "legacy_selected_empty",
                    )
                }
                return emptyMap()
            }
            if (applySelectionSideEffects) {
                markTargetSelectionSuccess()
            }
            return selected.associate { it.address to it.seenAtMs }
        }

        syncTargetPools(candidates, nowMs)
        maybeLogCandidatePoolSnapshot(candidates, nowMs)
        val (ordered, mainCountSnapshot) = synchronized(targetPoolLock) {
            mainTargetDeviceIds.mapNotNull { candidates[it] } to mainTargetDeviceIds.size
        }
        val hasPendingDataTraffic = hasPendingDataTraffic()
        val preferredSet = canonicalDeviceIds(preferredDeviceIds)?.takeIf { it.isNotEmpty() }
        val selectionPool = if (preferredSet != null) {
            candidates.values.filter { candidate -> preferredSet.contains(candidate.deviceId) }
        } else {
            ordered
        }
        val rejectReasonByPeer = LinkedHashMap<String, MutableList<String>>()
        val noRelatedOutboundCandidates = ArrayList<DeviceCandidate>()
        fun rejectCandidate(deviceId: String, reason: String) {
            rejectReasonByPeer.getOrPut(deviceId) { mutableListOf() }.add(reason)
        }
        fun clearRejectReason(deviceId: String, reason: String) {
            val reasons = rejectReasonByPeer[deviceId] ?: return
            reasons.removeAll { it == reason }
            if (reasons.isEmpty()) {
                rejectReasonByPeer.remove(deviceId)
            }
        }

        val available = mutableListOf<DeviceCandidate>()
        selectionPool.forEach { candidate ->
            val rotationCandidate = isRotationRecoveryCandidate(candidate.deviceId, candidate.address, nowMs)
            if (!rotationCandidate && isPenaltyActive(candidate.deviceId, nowMs)) {
                rejectCandidate(candidate.deviceId, "penalty_active")
                return@forEach
            }
            if (!rotationCandidate && isDeviceIdSelectionLocked(candidate.deviceId, candidate.address, nowMs)) {
                rejectCandidate(candidate.deviceId, "selection_locked")
                return@forEach
            }
            if (!rotationCandidate && isDeviceIdQuietActive(candidate.deviceId, nowMs)) {
                rejectCandidate(candidate.deviceId, "quiet_active")
                return@forEach
            }
            if (hasPendingDataTraffic && isRepeatedNoJobDevice(candidate.deviceId, nowMs) && !hasStrictOutboundPathForCandidate(candidate)) {
                rejectCandidate(candidate.deviceId, "no_job_suppressed")
                return@forEach
            }
            if (hasPendingDataTraffic && !hasRelatedOutboundPathForCandidate(candidate, nowMs)) {
                rejectCandidate(candidate.deviceId, "no_related_outbound")
                noRelatedOutboundCandidates += candidate
                return@forEach
            }
            available += candidate
        }
        if (hasPendingDataTraffic && available.size in 1 until minOf(MAX_BROADCAST_TARGETS, selectionPool.size)) {
            val needed = minOf(MAX_BROADCAST_TARGETS, selectionPool.size) - available.size
            noRelatedOutboundCandidates
                .asSequence()
                .filter { candidate -> available.none { it.deviceId == candidate.deviceId } }
                .sortedWith(compareByDescending<DeviceCandidate> { it.seenAtMs }.thenByDescending { it.rssi })
                .take(needed)
                .forEach { candidate ->
                    available += candidate
                    clearRejectReason(candidate.deviceId, "no_related_outbound")
                    rejectCandidate(candidate.deviceId, "no_related_outbound_relaxed")
                }
        }
        val penaltyFilteredCount = rejectReasonByPeer.values.count { reasons -> reasons.contains("penalty_active") }
        var probeUsed = false
        if (available.isEmpty()) {
            val probePool = if (hasPendingDataTraffic) {
                selectionPool.filter { candidate ->
                    hasRelatedOutboundPathForCandidate(candidate, nowMs) &&
                        !isRepeatedNoJobDevice(candidate.deviceId, nowMs)
                }.associateBy { it.deviceId }
            } else {
                selectionPool.associateBy { it.deviceId }
            }
            chooseProbeCandidate(probePool, nowMs)?.let {
                available.add(it)
                probeUsed = true
            }
        }
        val freshMissingByDeviceId = available.associate { candidate ->
            candidate.deviceId to freshMissingCount(candidate.deviceId, nowMs)
        }
        val hasUnsatisfiedFreshPeer = freshMissingByDeviceId.values.any { it > 0 }
        val fairnessFiltered = if (hasUnsatisfiedFreshPeer) {
            available.filter { candidate -> (freshMissingByDeviceId[candidate.deviceId] ?: 0) > 0 }
        } else {
            available
        }
        val preserveFreshFanout =
            hasUnsatisfiedFreshPeer &&
                available.size > 1 &&
                fairnessFiltered.size == 1 &&
                MAX_BROADCAST_TARGETS > 1
        val effectiveAvailable = if (preserveFreshFanout) available else fairnessFiltered
        if (hasUnsatisfiedFreshPeer && !preserveFreshFanout) {
            available
                .filter { candidate -> (freshMissingByDeviceId[candidate.deviceId] ?: 0) <= 0 }
                .forEach { candidate -> rejectCandidate(candidate.deviceId, "fresh_missing_zero") }
        }
        val selected = effectiveAvailable
            .sortedWith(
                if (hasPendingDataTraffic) {
                    compareByDescending<DeviceCandidate> { freshMissingByDeviceId[it.deviceId] ?: 0 }
                        .thenByDescending { hasPendingDataForAddress(it.address) }
                        .thenBy { lastTargetSelectionAtByDeviceId[it.deviceId] ?: Long.MIN_VALUE }
                        .thenByDescending { it.seenAtMs }
                        .thenByDescending { it.rssi }
                } else {
                    compareByDescending<DeviceCandidate> { freshMissingByDeviceId[it.deviceId] ?: 0 }
                        .thenByDescending {
                        hasPendingDataForAddress(it.address) ||
                            lastTargetByDeviceId[it.deviceId] == it.address
                    }.thenByDescending { it.seenAtMs }
                        .thenByDescending { it.rssi }
                }
            )
            .take(MAX_BROADCAST_TARGETS)
        if (applySelectionSideEffects) {
            val selectedIds = selected.joinToString(",") { it.deviceId }.ifBlank { "none" }
            val rejectSummary = if (rejectReasonByPeer.isEmpty()) {
                "none"
            } else {
                rejectReasonByPeer.entries.joinToString("|") { (deviceId, reasons) ->
                    "$deviceId:${reasons.distinct().joinToString("+")}"
                }
            }
            maybeLogTargetSelectionDecision(
                available = available.size,
                effectiveAvailable = effectiveAvailable.size,
                probeUsed = probeUsed,
                selectedIds = selectedIds,
                rejectReasonByPeer = rejectSummary,
                nowMs = nowMs,
            )
        }
        if (selected.isEmpty()) {
            if (applySelectionSideEffects) {
                handleTargetSelectionEmpty(
                    nowMs = nowMs,
                    candidateCount = candidates.size,
                    mainTargetCount = mainCountSnapshot,
                    penaltyFilteredCount = penaltyFilteredCount,
                    nearbyCount = nearbyDeviceCount(nowMs),
                    reason = if (available.isEmpty()) "all_penalized_or_no_main" else "selected_empty",
                )
            }
            return emptyMap()
        }
        if (applySelectionSideEffects) {
            markTargetSelectionSuccess()
        }
        if (applySelectionSideEffects && nowMs - lastPeerFairnessLogAtMs >= PEER_FAIRNESS_LOG_COOLDOWN_MS) {
            lastPeerFairnessLogAtMs = nowMs
            diagnosticsListener?.onBlePeerFairness(
                candidateCount = candidates.size,
                mainTargetCount = mainCountSnapshot,
                availableCount = available.size,
                selected = selected.joinToString("|") { "id=${it.deviceId},addr=${it.address},rssi=${it.rssi}" },
                hasPendingDataTraffic = hasPendingDataTraffic,
            )
        }

        val result = LinkedHashMap<String, Long>()
        if (!applySelectionSideEffects) {
            selected.forEach { candidate ->
                result[candidate.address] = candidate.seenAtMs
            }
            return result
        }
        selected.forEach { candidate ->
            val previous = lastTargetByDeviceId.put(candidate.deviceId, candidate.address)
            val now = System.currentTimeMillis()
            lastTargetSelectionAtByDeviceId[candidate.deviceId] = now
            val dwellMs = if (hasPendingDataTraffic) DEVICEID_DWELL_MS_WITH_PENDING_DATA else DEVICEID_DWELL_MS
            setDeviceIdDwell(candidate.deviceId, dwellMs, now)
            if (previous != candidate.address) {
                if (previous != null) {
                    setCooldownForAddress(
                        address = previous,
                        durationMs = SELECTION_ROTATION_ADDR_COOLDOWN_MS,
                        nowMs = now,
                        sourceReason = "selection_rotation_previous_addr",
                        deviceId = candidate.deviceId,
                    )
                }
                setCooldownForDevice(candidate.deviceId, SELECTION_ROTATION_DEVICE_COOLDOWN_MS, now)
                setDeviceIdRotationCooldown(candidate.deviceId, DEVICEID_ROTATION_DEBOUNCE_MS, now)
                Log.i(
                    TAG,
                    "BLE target pick id=${candidate.deviceId} addr=${candidate.address} prev=${previous ?: "none"} rssi=${candidate.rssi} seenAt=${candidate.seenAtMs}",
                )
                diagnosticsListener?.onBleTargetSelected(
                    deviceId = candidate.deviceId,
                    address = candidate.address,
                    previousAddress = previous,
                    rssi = candidate.rssi,
                    seenAtMs = candidate.seenAtMs,
                )
            }
            result[candidate.address] = candidate.seenAtMs
        }
        return result
    }

    private fun maybeLogRxTargetMismatch(address: String) {
        if (!BleConfig.useManufacturerData) return
        val deviceId = lastSeenDeviceId[address] ?: return
        val picked = lastTargetByDeviceId[deviceId] ?: return
        if (picked == address) return
        val now = System.currentTimeMillis()
        val last = lastRxMismatchLogAtMs[address] ?: 0L
        if (now - last < 1_000L) return
        lastRxMismatchLogAtMs[address] = now
        Log.w(TAG, "BLE rx addr mismatch addr=$address id=$deviceId target=$picked")
        diagnosticsListener?.onBleRxTargetMismatch(address, deviceId, picked)
    }

    private fun maybeLogTxTargetMismatch(
        deviceId: String,
        address: String,
        rssi: Int,
        seenAtMs: Long,
    ) {
        val latest = lastSeenAddrByDeviceId[deviceId] ?: return
        if (latest == address) return
        val now = System.currentTimeMillis()
        val last = lastTxMismatchLogAtMs[deviceId] ?: 0L
        if (now - last < 1_000L) return
        lastTxMismatchLogAtMs[deviceId] = now
        Log.w(
            TAG,
            "BLE tx target mismatch id=$deviceId target=$address latest=$latest rssi=$rssi seenAt=$seenAtMs",
        )
        diagnosticsListener?.onBleTxTargetMismatch(deviceId, address, latest, rssi, seenAtMs)
    }

    private fun startGattServer() {
        if (gattServer != null) {
            diagnosticsListener?.onGattServerAlreadyRunning()
            return
        }
        val server = bluetoothManager.openGattServer(appContext, gattServerCallback)
        if (server == null) {
            Log.w(TAG, "Failed to open GATT server")
            diagnosticsListener?.onGattServerStartFailed()
            return
        }
        val service = BluetoothGattService(
            BleConfig.serviceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val characteristic = BluetoothGattCharacteristic(
            characteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristic)
        val added = server.addService(service)
        if (!added) {
            Log.w(TAG, "GATT addService failed")
            diagnosticsListener?.onGattAddServiceFailed()
        }
        gattServer = server
        lastServerStartedAtMs = System.currentTimeMillis()
        Log.i(TAG, "GATT server started")
        diagnosticsListener?.onGattServerStarted()
    }

    private fun stopGattServer() {
        gattServer?.close()
        gattServer = null
    }

    private fun maybeHardReset(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastHardResetAtMs < HARD_RESET_COOLDOWN_MS) return
        val nearby = nearbyDeviceCount()
        if (nearby <= 0) {
            diagnosticsListener?.onGattHardResetSkipped(nearby)
            return
        }
        lastHardResetAtMs = now
        Log.w(TAG, "GATT hard reset: $reason")
        diagnosticsListener?.onGattHardReset(reason)
        closeAllGatt()
        stopGattServer()
        startGattServer()
    }

    private fun startAdvertising(force: Boolean = false) {
        if (!force && meshMode != MeshMode.FULL_RELAY) return
        val adv = advertiser ?: return
        if (advertiseCallback != null) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(BleConfig.advertiseMode)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(BleConfig.advertiseTxPower)
            .build()
        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
        val scanResponseBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
        if (BleConfig.useManufacturerData) {
            val nearbyPayload = nearbyRoomAdvertisementProvider?.currentPayload()
            val manufacturerData = buildManufacturerData(nearbyPayload)
            diagnosticsListener?.onNearbyRoomAdvertisePayload(
                manufacturerDataBytes = manufacturerData.size,
                baseBytes = manufacturerDataBaseLength(),
                nearbyPayloadBytes = nearbyPayload?.size ?: 0,
                hasNearbyPayload = nearbyPayload != null && nearbyPayload.isNotEmpty(),
                manufacturerDataHex = manufacturerData.toHexString(),
                nearbyPayloadHex = nearbyPayload?.toHexString(),
            )
            dataBuilder.addManufacturerData(
                BleConfig.manufacturerId,
                manufacturerData,
            )
            diagnosticsListener?.onAdvertisedDeviceId(localDeviceId)
            scanResponseBuilder.addServiceUuid(serviceUuid)
        } else {
            dataBuilder.addServiceUuid(serviceUuid)
        }
        val data = dataBuilder.build()
        val scanResponse = scanResponseBuilder.build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                handleAdvertiseStartSuccess()
            }

            override fun onStartFailure(errorCode: Int) {
                handleAdvertiseStartFailure(errorCode)
            }
        }
        if (BleConfig.useManufacturerData) {
            adv.startAdvertising(settings, data, scanResponse, callback)
        } else {
            adv.startAdvertising(settings, data, callback)
        }
        advertiseCallback = callback
        Log.i(TAG, "BLE advertise start requested (serviceUuid=$serviceUuid)")
    }

    private fun buildManufacturerData(nearbyPayload: ByteArray?): ByteArray {
        val base = MFG_PREFIX + localDeviceIdBytes
        if (nearbyPayload == null || nearbyPayload.isEmpty()) return base
        return base + nearbyPayload
    }

    private fun handleAdvertiseStartSuccess() {
        workerHandler.post {
            cancelAdvertiseRetry()
            diagnosticsListener?.onAdvertiseStarted()
            if (meshMode == MeshMode.RECEIVE_ONLY) {
                restoreFullRelay("advertise_success")
            }
        }
    }

    private fun handleAdvertiseStartFailure(errorCode: Int) {
        workerHandler.post {
            Log.e(TAG, "BLE advertise failed code=$errorCode")
            diagnosticsListener?.onAdvertiseFailed(errorCode)
            advertiseCallback = null
            if (!running) return@post
            if (!isBluetoothEnabled()) {
                Log.w(TAG, "BLE advertise failed while bluetooth OFF; ignore receive-only switch")
                return@post
            }
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                Log.w(TAG, "BLE advertise already started; ignoring failure")
                return@post
            }
            if (meshMode == MeshMode.RECEIVE_ONLY) {
                scheduleAdvertiseRecheck()
                return@post
            }
            if (advertiser == null || errorCode == AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED) {
                switchToReceiveOnly("hard_failure:$errorCode")
                return@post
            }
            if (!scheduleAdvertiseRetry()) {
                switchToReceiveOnly("soft_failure:$errorCode")
            }
        }
    }

    private fun stopAdvertising() {
        val adv = advertiser ?: return
        val callback = advertiseCallback ?: return
        adv.stopAdvertising(callback)
        advertiseCallback = null
    }

    private fun switchToReceiveOnly(reason: String) {
        val alreadyReceiveOnly = synchronized(advertiseSupervisorLock) {
            if (meshMode == MeshMode.RECEIVE_ONLY) return@synchronized true
            meshMode = MeshMode.RECEIVE_ONLY
            advertiseRetryIndex = 0
            false
        }
        if (alreadyReceiveOnly) {
            scheduleAdvertiseRecheck()
            return
        }
        cancelAdvertiseRetry()
        stopAdvertising()
        Log.w(TAG, "mesh_mode_switch FULL_RELAY -> RECEIVE_ONLY reason=$reason")
        diagnosticsListener?.onMeshModeChanged("RECEIVE_ONLY", reason)
        scheduleAdvertiseRecheck()
    }

    private fun restoreFullRelay(reason: String) {
        val shouldRestore = synchronized(advertiseSupervisorLock) {
            if (meshMode == MeshMode.FULL_RELAY) return@synchronized false
            meshMode = MeshMode.FULL_RELAY
            advertiseRetryIndex = 0
            true
        }
        if (!shouldRestore) return
        cancelAdvertiseRecheck()
        Log.i(TAG, "mesh_mode_restore FULL_RELAY reason=$reason")
        diagnosticsListener?.onMeshModeChanged("FULL_RELAY", reason)
    }

    private fun scheduleAdvertiseRetry(): Boolean {
        val (attempt, delayMs) = synchronized(advertiseSupervisorLock) {
            if (advertiseRetryIndex >= ADV_SOFT_RETRY_DELAYS.size) return false
            val delay = ADV_SOFT_RETRY_DELAYS[advertiseRetryIndex]
            advertiseRetryIndex += 1
            val attemptNow = advertiseRetryIndex
            advertiseRetryRunnable?.let { workerHandler.removeCallbacks(it) }
            val runnable = Runnable {
                if (!running || meshMode != MeshMode.FULL_RELAY) return@Runnable
                Log.i(TAG, "mesh_mode_retry attempt=$attemptNow delay=${delay}ms")
                startAdvertising()
            }
            advertiseRetryRunnable = runnable
            workerHandler.postDelayed(runnable, delay)
            attemptNow to delay
        }
        Log.w(TAG, "mesh_mode_retry attempt=$attempt delay=${delayMs}ms")
        diagnosticsListener?.onAdvertiseRetry(attempt, delayMs)
        return true
    }

    private fun cancelAdvertiseRetry() {
        synchronized(advertiseSupervisorLock) {
            advertiseRetryRunnable?.let { workerHandler.removeCallbacks(it) }
            advertiseRetryRunnable = null
            advertiseRetryIndex = 0
        }
    }

    private fun scheduleAdvertiseRecheck() {
        synchronized(advertiseSupervisorLock) {
            if (advertiseRecheckRunnable != null) return
            val runnable = object : Runnable {
                override fun run() {
                    if (!running || meshMode != MeshMode.RECEIVE_ONLY) return
                    if (!isBluetoothEnabled()) {
                        workerHandler.postDelayed(this, ADV_RECHECK_INTERVAL_MS)
                        return
                    }
                    startAdvertising(force = true)
                    workerHandler.postDelayed(this, ADV_RECHECK_INTERVAL_MS)
                }
            }
            advertiseRecheckRunnable = runnable
            workerHandler.postDelayed(runnable, ADV_RECHECK_INTERVAL_MS)
        }
    }

    private fun cancelAdvertiseRecheck() {
        synchronized(advertiseSupervisorLock) {
            advertiseRecheckRunnable?.let { workerHandler.removeCallbacks(it) }
            advertiseRecheckRunnable = null
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        return adapter?.isEnabled == true
    }

    private fun updateAdvertisingSettings() {
        if (!running) return
        if (meshMode != MeshMode.FULL_RELAY) return
        stopAdvertising()
        startAdvertising()
    }

    private fun isScanStaleForConnect(nowMs: Long): Boolean {
        val baseAtMs = if (lastScanCallbackAtMs > 0L) lastScanCallbackAtMs else lastScanStartAtMs
        if (baseAtMs == 0L) return false
        return nowMs - baseAtMs >= CONNECT_GUARD_SCAN_STALE_MS
    }

    @SuppressLint("MissingPermission")
    private fun connectIfNeeded(requestedAddress: String) {
        val nowMs = System.currentTimeMillis()
        val deviceId = lastSeenDeviceId[requestedAddress] ?: "addr:$requestedAddress"
        val hasNearbyRoomPriorityWork = hasNearbyRoomPriorityWorkForDevice(deviceId)
        val hasNearbyRoomKeyBootstrapWork = hasNearbyRoomKeyBootstrapWorkForDevice(deviceId)
        if (
            isDeviceIdQuietActive(deviceId, nowMs) &&
            !BleNearbyTrafficPolicy.shouldBypassDeviceQuiet(hasNearbyRoomPriorityWork)
        ) {
            diagnosticsListener?.onGattConnectSkipped(
                requestedAddress = requestedAddress,
                selectedAddress = requestedAddress,
                reason = "device_quiet",
                state = CONNECT_STATE_DISCONNECTED,
                nearby = nearbyDeviceCount(nowMs),
            )
            Log.i(TAG, "connectIfNeeded skip reason=device_quiet requested=$requestedAddress")
            return
        }
        val connectAddress = candidateAddressesForDevice(deviceId, nowMs)
            .firstOrNull { address ->
                lastSeenDevices[address] != null &&
                    (!isAddressPenaltyActive(address, nowMs) || nearbyDeviceCount(nowMs) <= 1)
            } ?: requestedAddress
        fun skipConnect(reason: String, state: Int = connectState(connectAddress)) {
            val nearby = nearbyDeviceCount(nowMs)
            diagnosticsListener?.onGattConnectSkipped(
                requestedAddress = requestedAddress,
                selectedAddress = connectAddress,
                reason = reason,
                state = state,
                nearby = nearby,
            )
            Log.i(
                TAG,
                "connectIfNeeded skip reason=$reason requested=$requestedAddress selected=$connectAddress state=$state nearby=$nearby",
            )
        }
        val device = lastSeenDevices[connectAddress] ?: run {
            skipConnect("device_missing_in_cache", CONNECT_STATE_DISCONNECTED)
            return
        }
        if (
            isCooldownActiveForDevice(deviceId, nowMs) &&
            !BleNearbyTrafficPolicy.shouldBypassDeviceCooldown(hasNearbyRoomPriorityWork)
        ) {
            skipConnect("cooldown_device", CONNECT_STATE_DISCONNECTED)
            return
        }
        if (isCooldownActiveForAddress(connectAddress, nowMs) && !hasNearbyRoomKeyBootstrapWork) {
            skipConnect("cooldown_addr", CONNECT_STATE_DISCONNECTED)
            return
        }
        if (isScanStaleForConnect(nowMs)) {
            skipConnect("scan_stale", CONNECT_STATE_DISCONNECTED)
            return
        }
        if (connectAddress != requestedAddress) {
            migratePendingQueue(requestedAddress, connectAddress)
        }
        val state = connectState(connectAddress)
        if (state == CONNECT_STATE_CONNECTING || state == CONNECT_STATE_CONNECTED) {
            skipConnect("state_busy_connecting_or_connected", state)
            return
        }
        if (!canAttemptConnectNow(connectAddress, nowMs)) {
            skipConnect("connect_attempt_cooldown", state)
            return
        }
        if (clientByDevice.containsKey(connectAddress)) {
            skipConnect("client_exists", state)
            return
        }
        if (connecting.putIfAbsent(connectAddress, true) != null) {
            skipConnect("already_connecting_map", CONNECT_STATE_CONNECTING)
            return
        }
        if (!canOpenNewGatt()) {
            connecting.remove(connectAddress)
            maybeLogConnectCapBlocked(nowMs)
            skipConnect("max_gatt_reached", state)
            return
        }
        if (isAddressPenaltyActive(connectAddress, nowMs)) {
            if (nearbyDeviceCount(nowMs) <= 1) {
                val lastProbe = lastSingleProbeAttemptAtMsByAddress[connectAddress] ?: 0L
                if (nowMs - lastProbe < SINGLE_TARGET_PROBE_MS) {
                    connecting.remove(connectAddress)
                    skipConnect("penalty_probe_window", state)
                    return
                }
                lastSingleProbeAttemptAtMsByAddress[connectAddress] = nowMs
            } else {
                connecting.remove(connectAddress)
                skipConnect("penalty_active_multi_nearby", state)
                return
            }
        } else if (nearbyDeviceCount(nowMs) <= 1) {
            lastSingleProbeAttemptAtMsByAddress[connectAddress] = nowMs
        }
        connectStartedAtMs[connectAddress] = nowMs
        currentItemId(connectAddress)?.let { markItemConnecting(connectAddress, it) }
        setConnectState(connectAddress, CONNECT_STATE_CONNECTING, "connect_start")
        val connection = clientBackend.connect(appContext, device, gattCallback)
        if (connection == null) {
            connecting.remove(connectAddress)
            connectStartedAtMs.remove(connectAddress)
            connectingClient.remove(connectAddress)
            setConnectState(connectAddress, CONNECT_STATE_DISCONNECTED, "connect_null")
            recordConnectFailure(connectAddress, "connectGatt=null")
        } else {
            connectingClient[connectAddress] = connection
        }
    }

    private fun writeNextChunk(connection: BleClientConnection) {
        val address = connection.address
        if (connectState(address) != CONNECT_STATE_CONNECTED) {
            return
        }
        if (!tryAcquireWriteSlot(address)) return
        val (next, pendingCount) = synchronized(pendingQueueLock) {
            val queue = pendingByDevice[address] ?: return@synchronized (null to 0)
            val packet = pollNextPacketForWrite(address, queue)
            packet to queue.size
        }
        if (next == null) {
            releaseWriteSlot(address)
            activeItemKeyByAddress.remove(address)
            activeMeshEnvelopeItemIdByAddress.remove(address)
            val nowMs = System.currentTimeMillis()
            val hasStaged = synchronized(schedulerLock) { stagedEnqueueByAddress[address]?.isNotEmpty() == true }
            diagnosticsListener?.onGattWriteNextSkipped(
                address = address,
                reason = "queue_empty",
                state = connectState(address),
                pending = pendingCount,
                nowMs = nowMs,
                lastProgressMs = lastProgressAtMs[address],
                connectStartedMs = connectStartedAtMs[address],
            )
            if (hasStaged) {
                Log.i(TAG, "WRITE queue empty but staged remains addr=$address; requeue enqueue")
                postLabel(address, PipelineLabel.ENQUEUE)
            }
            return
        }
        val nowMs = System.currentTimeMillis()
        pausedAtMsByItemId.remove(next.itemId)?.let { pausedAtMs ->
            foregroundDiagnostic(
                NearbyForegroundDiagnosticLabels.QUEUE_RESUMED,
                "addr=$address item_id=${next.itemId} batch_id=${next.batchId ?: "none"} paused_duration_ms=${nowMs - pausedAtMs}",
            )
        }
        next.dtnControlType?.let { type ->
            traceDtnControlQueueState(address = address, type = type, stage = "poll_for_write")
        }

        if (!next.isAck) {
            val deferReason =
                when {
                    shouldDeferFirstBatchWarmup(address, next.payloadBytes, nowMs) ->
                        "deferred_first_batch_warmup"
                    shouldDeferUntilRotationRecoveryReady(address, next.payloadBytes, nowMs) ->
                        "deferred_rotation_recovery_not_ready"
                    shouldDeferUntilMtuKnown(address, next.payloadBytes) ->
                        "deferred_until_mtu_known"
                    else -> null
                }

            if (deferReason != null) {
                releaseWriteSlot(address)
                val moved = moveQueuedItemToHeld(address, next, deferReason)
                next.dtnControlType?.let { type ->
                    traceDtnControlQueueState(
                        address = address,
                        type = type,
                        stage = "defer_to_held",
                        reason = deferReason,
                    )
                }

                Log.i(
                    TAG,
                    "WRITE gate defer addr=$address item=${next.itemId} batch=${next.batchId ?: "none"} device=${next.deviceId} reason=$deferReason moved=$moved payloadBytes=${next.payloadBytes} totalChunks=${next.totalChunks}",
                )

                if (deferReason == "deferred_until_mtu_known") {
                    diagnosticsListener?.onGattWriteNextSkipped(
                        address = address,
                        reason = "gate_mtu_unknown moved=$moved",
                        state = connectState(address),
                        pending = synchronized(pendingQueueLock) { pendingByDevice[address]?.size ?: 0 },
                        nowMs = nowMs,
                        lastProgressMs = lastProgressAtMs[address],
                        connectStartedMs = connectStartedAtMs[address],
                    )
                } else {
                    diagnosticsListener?.onGattWriteNextSkipped(
                        address = address,
                        reason = "gate_not_ready:$deferReason moved=$moved",
                        state = connectState(address),
                        pending = synchronized(pendingQueueLock) { pendingByDevice[address]?.size ?: 0 },
                        nowMs = nowMs,
                        lastProgressMs = lastProgressAtMs[address],
                        connectStartedMs = connectStartedAtMs[address],
                    )
                }

                return
            }

            val rebuildForCurrentMtu = shouldRebuildPolledPacketForCurrentChunkSize(address, next)
            if (rebuildForCurrentMtu && next.dtnControlType != null) {
                val expectedChunks = expectedChunkCountFor(address, next.payloadBytes)
                traceDtnControlQueueState(
                    address = address,
                    type = next.dtnControlType,
                    stage = "rebuild_skip",
                    reason = "dtn_control old_chunks=${next.totalChunks} expected_chunks=$expectedChunks",
                )
            } else if (rebuildForCurrentMtu) {
                releaseWriteSlot(address)
                val expectedChunks = expectedChunkCountFor(address, next.payloadBytes)
                val moved = moveQueuedItemToHeld(address, next, "rebuild_for_current_mtu")
                val released = releaseHeldToStagedIfReady(address, nowMs)
                next.dtnControlType?.let { type ->
                    traceDtnControlQueueState(
                        address = address,
                        type = type,
                        stage = "rebuild_to_held",
                        reason = "released=$released expected_chunks=$expectedChunks",
                    )
                }

                Log.i(
                    TAG,
                    "WRITE gate rebuild addr=$address item=${next.itemId} batch=${next.batchId ?: "none"} device=${next.deviceId} oldChunks=${next.totalChunks} expectedChunks=$expectedChunks moved=$moved released=$released chunkBytes=${maxChunkSizeFor(address)}",
                )
                diagnosticsListener?.onGattWriteNextSkipped(
                    address = address,
                    reason = "rebuild_for_current_mtu moved=$moved released=$released old_chunks=${next.totalChunks} expected_chunks=$expectedChunks",
                    state = connectState(address),
                    pending = synchronized(pendingQueueLock) { pendingByDevice[address]?.size ?: 0 },
                    nowMs = nowMs,
                    lastProgressMs = lastProgressAtMs[address],
                    connectStartedMs = connectStartedAtMs[address],
                )

                postLabel(address, PipelineLabel.ENQUEUE)
                postLabel(address, PipelineLabel.WRITE_NEXT)
                return
            }
        }

        val totalPendingBytes = totalPendingBytes()
        updateBackpressure(totalPendingBytes)
        val singlePeer = nearbyDeviceCount(nowMs) == 1
        val delayMs = if (singlePeer) 0L else sendDelayMs(totalPendingBytes)
        if (delayMs > 0) {
            releaseWriteSlot(address)
            scheduleWrite(address, connection, delayMs)
            synchronized(pendingQueueLock) {
                pendingByDevice[address]?.addFirst(next)
            }
            return
        }
        val rxPauseMs = receiveWindowPauseMs(nowMs)
        if (rxPauseMs > 0L) {
            releaseWriteSlot(address)
            if (nowMs - lastRxWindowLogAtMs >= RX_WINDOW_LOG_INTERVAL_MS) {
                lastRxWindowLogAtMs = nowMs
                Log.i(TAG, "Pause TX for RX window pauseMs=$rxPauseMs")
            }
            scheduleWrite(address, connection, rxPauseMs)
            synchronized(pendingQueueLock) {
                pendingByDevice[address]?.addFirst(next)
            }
            return
        }
        if (!singlePeer && !canSendInCycle(address, nowMs)) {
            releaseWriteSlot(address)
            diagnosticsListener?.onBudgetExhausted(
                address = address,
                pauseMs = SEND_CYCLE_PAUSE_MS,
            )
            scheduleWrite(address, connection, SEND_CYCLE_PAUSE_MS)
            synchronized(pendingQueueLock) {
                pendingByDevice[address]?.addFirst(next)
            }
            return
        }
        val post133DelayMs = post133RecoveryWriteDelayMs(address, nowMs)
        if (post133DelayMs > 0L) {
            releaseWriteSlot(address)
            scheduleWrite(address, connection, post133DelayMs)
            synchronized(pendingQueueLock) {
                pendingByDevice[address]?.addFirst(next)
            }
            return
        }
        val characteristic = characteristicByDevice[address]
        if (characteristic == null) {
            releaseWriteSlot(address)
            cleanupConnection(address, connection, closeQueue = false, reason = "missing_characteristic")
            return
        }
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = next.payload
        if (next.dtnControlType == null && !next.isAck) {
            activeMeshEnvelopeItemIdByAddress[address] = next.itemId
        } else if (next.dtnControlType != null) {
            activeDtnControlItemIdByAddress[address] = next.itemId
        }
        activePendingPacketByAddress[address] = next
        next.dtnControlType?.let { type ->
            diagnosticsListener?.onDtnControlWireStart(
                address = address,
                type = type,
                payloadBytes = next.payloadBytes,
                totalChunks = next.totalChunks,
            )
        }
        val txNowMs = System.currentTimeMillis()
        lastProgressAtMs[address] = txNowMs
        lastTxChunkAtMs = txNowMs
        markSendPathStarted(next, txNowMs)
        diagnosticsListener?.onGattWriteAttempt(address, next.payload.size)
        val ok = clientBackend.write(connection, characteristic)
        diagnosticsListener?.onGattWriteStart(address, ok)
        if (!ok) {
            removeActivePendingPacket(address, reason = "write_start_failed")
            releaseWriteSlot(address)
            Log.w(TAG, "GATT writeCharacteristic failed for $address")
            onPacketWriteFailure(next, reason = "write_characteristic_failed", nowMs = System.currentTimeMillis())
            markItemBackoff(address, "write_failed")
            cleanupConnection(address, connection, closeQueue = false, reason = "write_characteristic_failed")
            return
        }
        markItemDoneIfDrained(address, next.itemId)
    }

    private fun cleanupConnection(address: String, connection: BleClientConnection, closeQueue: Boolean, reason: String) {
        val nowMs = System.currentTimeMillis()
        val deviceId = lastSeenDeviceId[address] ?: "addr:$address"
        if (reason.contains("transport_stop")) {
            dtnSyncEngine?.onPeerDisconnected(peerId = deviceId)
            dtnForegroundRetryAttemptsBySessionKey.keys.removeIf { it.startsWith("$deviceId|") }
            dtnForegroundRetryScheduledKeys.removeIf { it.startsWith("$deviceId|") }
            dtnDiagnostics?.log(DtnDiagnosticEvent(label = "DTN session invalidated", type = "session", direction = "invalidated", peerId = deviceId, reason = "transport_stop deferred_task_removed=true"))
        } else {
            dtnSyncEngine?.foregroundDeferredSessionTasks()
                ?.filter { it.peerId == deviceId }
                ?.forEach { task ->
                    dtnDiagnostics?.log(DtnDiagnosticEvent(label = "DTN session retained across GATT cleanup", type = task.packet.type, direction = "retained", peerId = task.peerId, gid = task.gid, sessionId = task.sessionId, reason = reason))
                }
        }
        dtnHelloSessionByDeviceAndGid.entries.removeIf { (_, context) ->
            context.deviceId == deviceId || context.address == address
        }
        if (reason.contains("transport_stop")) {
            setCooldownForAddress(
                address = address,
                durationMs = CLEANUP_ADDR_COOLDOWN_MS,
                nowMs = nowMs,
                sourceReason = "cleanup_transport_stop",
                deviceId = deviceId,
            )
            setCooldownForDevice(deviceId, CLEANUP_DEVICE_COOLDOWN_MS, nowMs)
        }
        failInterruptedActivePacket(address, reason, nowMs)
        diagnosticsListener?.onGattDisconnectReason(
            address = address,
            origin = "local_cleanup",
            reason = reason,
            status = null,
            newState = null,
        )
        markLocalDisconnectIntent(address, reason)
        try {
            connection.disconnect()
            connection.close()
        } catch (_: Exception) {
        }
        clientByDevice.remove(address)
        connectingClient.remove(address)
        characteristicByDevice.remove(address)
        connecting.remove(address)
        removeActivePendingPacket(address, reason = "cleanup_connection")
        releaseWriteSlot(address)
        setConnectState(address, CONNECT_STATE_DISCONNECTED, "cleanup_connection")
        connectStartedAtMs.remove(address)
        lastProgressAtMs.remove(address)
        dataWarmupUntilByAddress.remove(address)
        mtuByDevice.remove(address)
        maxChunkByDevice.remove(address)
        sendBudgetRemaining.remove(address)
        sendBudgetResetAtMs.remove(address)
        dataSinceAckByDevice.remove(address)
        val pulledJob = pulledDispatchJobByAddress[address]
        val keepPulledJob =
            !closeQueue &&
                pulledJob != null &&
                queuedPacketCountForItemDevice(
                    itemId = pulledJob.batchId,
                    deviceId = pulledJob.deviceId,
                ) > 0

        if (closeQueue) {
            synchronized(pendingQueueLock) {
                pendingByDevice.remove(address)
            }
        }
        if (closeQueue) {
            synchronized(schedulerLock) {
                stagedEnqueueByAddress.remove(address)
                heldEnqueueByAddress.remove(address)
            }
        } else {
            val stagedCount = synchronized(schedulerLock) { stagedEnqueueByAddress[address]?.size ?: 0 }
            val heldCount = synchronized(schedulerLock) { heldEnqueueByAddress[address]?.size ?: 0 }
            if (stagedCount > 0 || heldCount > 0) {
                Log.i(
                    TAG,
                    "Preserve deferred queue after cleanup addr=$address staged=$stagedCount held=$heldCount reason=$reason",
                )
                postLabel(address, PipelineLabel.ENQUEUE)
            }
        }
        if (keepPulledJob) {
            Log.i(
                TAG,
                "Preserve pulled dispatch job after cleanup addr=$address batch=${pulledJob?.batchId} device=${pulledJob?.deviceId} reason=$reason",
            )
        } else {
            pulledDispatchJobByAddress.remove(address)
        }
        if (running) {
            migrateRotatedAddressQueuesIfNeeded(address)
            requestMaybeConnectNext()
        }
    }

    private fun triggerWritePump(address: String) {
        if (!running) return
        workerHandler.post {
            val connection = clientByDevice[address] ?: return@post
            writeNextChunk(connection)
        }
    }

    private fun totalPendingBytes(): Int {
        return synchronized(pendingQueueLock) {
            var total = 0
            pendingByDevice.values.forEach { queue ->
                queue.forEach { total += it.payload.size }
            }
            total
        }
    }

    private fun totalPendingPackets(): Int {
        return synchronized(pendingQueueLock) {
            var total = 0
            pendingByDevice.values.forEach { queue -> total += queue.size }
            total
        }
    }

    private fun isAckPayload(blob: ByteArray): Boolean {
        val envelope = runCatching {
            CanonicalJson.json.decodeFromString(EventEnvelope.serializer(), blob.decodeToString())
        }.getOrNull() ?: return false
        return (envelope.dtn?.priority ?: Int.MIN_VALUE) >= ACK_PRIORITY_FLOOR
    }

    private fun isDtnControlPayload(blob: ByteArray): Boolean {
        return DtnControlCodec.decode(blob) != null
    }

    private fun enqueuePacket(
        address: String,
        queue: ArrayDeque<PendingPacket>,
        packet: PendingPacket,
    ): Boolean = synchronized(pendingQueueLock) {
        if (packet.nearbyForegroundProtected) {
            queue.add(packet)
            return true
        }
        if (!ensureGlobalQueueHeadroom(address, packet)) {
            Log.w(
                TAG,
                "Drop new ${if (packet.isAck) "ACK" else "DATA"} (global cap) addr=$address total=${totalPendingPackets()}",
            )
            return false
        }
        if (packet.isAck) {
            val ackCount = queue.count { it.isAck }
            if (ackCount >= MAX_ACK_QUEUE_PER_DEVICE) {
                val droppedAck = removeFirstMatching(queue) { it.isAck }
                if (droppedAck == null) {
                    Log.w(TAG, "Drop new ACK (peer ACK cap) addr=$address ackCount=$ackCount")
                    return false
                }
                Log.w(TAG, "Drop queued ACK (peer ACK cap) addr=$address ackCount=$ackCount")
            }
        } else {
            val dataCount = queue.count { !it.isAck }
            val dataLimit = dataQueueLimitFor(packet)
            if (dataCount >= dataLimit) {
                val dropped = evictionCandidate(queue, packet)
                if (dropped == null) {
                    Log.w(
                        TAG,
                        "Drop new DATA (peer DATA cap) addr=$address dataCount=$dataCount limit=$dataLimit batch=${packet.batchId ?: "none"} chunks=${packet.totalChunks}",
                    )
                    return false
                }
                Log.w(
                    TAG,
                    "Drop queued ${if (dropped.isAck) "ACK" else "DATA"} (peer DATA cap) addr=$address dataCount=$dataCount limit=$dataLimit batch=${packet.batchId ?: "none"} chunks=${packet.totalChunks}",
                )
            }
        }
        val hardLimit = hardQueueLimitFor(packet)
        if (queue.size >= hardLimit) {
            val dropped = evictionCandidate(queue, packet)
            if (dropped == null) {
                Log.w(
                    TAG,
                    "Drop new ${if (packet.isAck) "ACK" else "DATA"} (peer hard cap) addr=$address size=${queue.size} limit=$hardLimit batch=${packet.batchId ?: "none"} chunks=${packet.totalChunks}",
                )
                return false
            }
            Log.w(
                TAG,
                "Drop queued ${if (dropped.isAck) "ACK" else "DATA"} (peer hard cap) addr=$address size=${queue.size} limit=$hardLimit batch=${packet.batchId ?: "none"} chunks=${packet.totalChunks}",
            )
        }
        queue.add(packet)
        return true
    }

    private fun dataQueueLimitFor(packet: PendingPacket): Int {
        if (packet.dtnControlType != null) return MAX_DTN_CONTROL_QUEUE_PER_DEVICE
        if (packet.nearbyForegroundProtected) {
            return maxOf(MAX_DATA_QUEUE_PER_DEVICE, packet.totalChunks)
        }
        if (packet.isAck || packet.batchId == null) return MAX_DATA_QUEUE_PER_DEVICE
        return maxOf(MAX_DATA_QUEUE_PER_DEVICE, packet.totalChunks)
    }

    private fun hardQueueLimitFor(packet: PendingPacket): Int {
        if (packet.dtnControlType != null) return MAX_DTN_CONTROL_QUEUE_PER_DEVICE
        if (packet.nearbyForegroundProtected) {
            return maxOf(MAX_QUEUE_PER_DEVICE_HARD_CAP, packet.totalChunks + MAX_ACK_QUEUE_PER_DEVICE)
        }
        if (packet.isAck || packet.batchId == null) return MAX_QUEUE_PER_DEVICE_HARD_CAP
        return maxOf(MAX_QUEUE_PER_DEVICE_HARD_CAP, packet.totalChunks + MAX_ACK_QUEUE_PER_DEVICE)
    }

    private fun evictionCandidate(
        queue: ArrayDeque<PendingPacket>,
        incoming: PendingPacket,
    ): PendingPacket? {
        val ack = removeFirstMatching(queue) { it.isAck }
        if (ack != null) return ack
        return if (incoming.nearbyForegroundProtected) {
            removeFirstMatching(queue) {
                !it.isAck && !BleNearbyTrafficPolicy.isNearbyRoomTraffic(it.trafficHint)
            }
        } else {
            removeFirstMatching(queue) { !it.isAck && it.batchId == null }
        }
    }

    private fun ensureGlobalQueueHeadroom(address: String, incoming: PendingPacket): Boolean {
        if (totalPendingPackets() < MAX_GLOBAL_PENDING_PACKETS) return true
        val dropped = if (incoming.isAck) {
            val samePeerDropped = pendingByDevice[address]?.let { queue ->
                removeFirstMatching(queue) { it.isAck }
            }
            samePeerDropped ?: removeFirstGlobal { it.isAck }
        } else if (incoming.nearbyForegroundProtected) {
            removeFirstGlobal { it.isAck }
                ?: removeFirstGlobal {
                    !it.isAck && !BleNearbyTrafficPolicy.isNearbyRoomTraffic(it.trafficHint)
                }
        } else {
            removeFirstGlobal { it.isAck } ?: removeFirstGlobal { !it.isAck && it.batchId == null }
        }
        return dropped != null
    }

    private fun hasNearbyForegroundQueueHeadroom(address: String, incomingPackets: Int): Boolean {
        val pendingTotal = totalPendingPackets()
        val pendingForAddress = synchronized(pendingQueueLock) { pendingByDevice[address]?.size ?: 0 }
        val stagedTotal = synchronized(schedulerLock) {
            stagedEnqueueByAddress.values.sumOf { it.size } + heldEnqueueByAddress.values.sumOf { it.size }
        }
        val stagedForAddress = synchronized(schedulerLock) {
            (stagedEnqueueByAddress[address]?.size ?: 0) + (heldEnqueueByAddress[address]?.size ?: 0)
        }
        return BleNearbyTrafficPolicy.hasForegroundQueueHeadroom(
            currentGlobalPackets = pendingTotal + stagedTotal,
            currentPeerPackets = pendingForAddress + stagedForAddress,
            incomingPackets = incomingPackets,
            globalBaseLimit = MAX_GLOBAL_PENDING_PACKETS,
            peerBaseLimit = MAX_QUEUE_PER_DEVICE_HARD_CAP,
            reservePackets = NEARBY_FOREGROUND_QUEUE_RESERVE_PACKETS,
        )
    }

    private fun removeFirstGlobal(predicate: (PendingPacket) -> Boolean): PendingPacket? {
        synchronized(pendingQueueLock) {
            pendingByDevice.entries.forEach { (_, peerQueue) ->
                val removed = removeFirstMatching(peerQueue, predicate)
                if (removed != null) return removed
            }
            return null
        }
    }

    private fun pollNextPacketForWrite(address: String, queue: ArrayDeque<PendingPacket>): PendingPacket? {
        val queueDtnTypes = queue.asSequence()
            .mapNotNull { it.dtnControlType }
            .distinct()
            .take(4)
            .toList()
        val queueDtnSummary = if (queueDtnTypes.isEmpty()) "none" else queueDtnTypes.joinToString(",")
        val activeMeshItemId = activeMeshEnvelopeItemIdByAddress[address]
        if (activeMeshItemId != null) {
            val continuation = removeFirstMatching(queue) {
                it.itemId == activeMeshItemId &&
                    it.dtnControlType == null &&
                    !it.isAck &&
                    BleNearbyTrafficPolicy.allowsPacketDequeue(
                        foregroundMode = nearbyForegroundMode,
                        hint = it.trafficHint,
                        isTransportAck = false,
                        activeItemContinuation = true,
                    )
            }
            if (continuation != null) {
                if (queueDtnTypes.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "WRITE keep mesh envelope addr=$address item=${continuation.itemId} batch=${continuation.batchId ?: "none"} queue_dtn=$queueDtnSummary",
                    )
                }
                dataSinceAckByDevice[address] = (dataSinceAckByDevice[address] ?: 0) + 1
                return continuation
            }
            if (!hasMeshEnvelopeWorkForAddressItem(address, activeMeshItemId)) {
                activeMeshEnvelopeItemIdByAddress.remove(address, activeMeshItemId)
                foregroundDrainByAddress.remove(address)?.let { drain ->
                    foregroundDiagnostic(
                        NearbyForegroundDiagnosticLabels.DRAIN_COMPLETE,
                        "runtime_state=${foregroundRuntimeState()} addr=$address item_id=${drain.itemId} batch_id=${drain.batchId ?: "none"} duration_ms=${System.currentTimeMillis() - drain.startedAtMs} boundary=envelope next_non_nearby_blocked=true",
                    )
                }
            }
        }
        val activeDtnItemId = activeDtnControlItemIdByAddress[address]
        if (activeDtnItemId != null) {
            val continuation = removeFirstMatching(queue) {
                it.itemId == activeDtnItemId &&
                    it.dtnControlType != null &&
                    BleNearbyTrafficPolicy.allowsPacketDequeue(
                        foregroundMode = nearbyForegroundMode,
                        hint = it.trafficHint,
                        isTransportAck = it.isAck,
                        activeItemContinuation = true,
                    )
            }
            if (continuation != null) {
                if (!continuation.isAck) {
                    dataSinceAckByDevice[address] = (dataSinceAckByDevice[address] ?: 0) + 1
                }
                return continuation
            }
            activeDtnControlItemIdByAddress.remove(address, activeDtnItemId)
        }

        val nearbyRoomBootstrap = removeFirstMatching(queue) {
            BleNearbyTrafficPolicy.shouldPrioritizeBeforeNearbyRoomControl(it.trafficHint)
        }
        if (nearbyRoomBootstrap != null) {
            if (!nearbyRoomBootstrap.isAck) {
                dataSinceAckByDevice[address] = (dataSinceAckByDevice[address] ?: 0) + 1
            }
            return nearbyRoomBootstrap
        }
        val nearbyRoomControl = removeFirstMatching(queue) {
            BleNearbyTrafficPolicy.shouldPrioritizeBeforeMeshBatch(it.trafficHint)
        }
        if (nearbyRoomControl != null) {
            if (!nearbyRoomControl.isAck) {
                dataSinceAckByDevice[address] = (dataSinceAckByDevice[address] ?: 0) + 1
            }
            return nearbyRoomControl
        }
        if (nearbyForegroundMode) {
            val transportAck = removeFirstMatching(queue) { it.isAck }
            if (transportAck != null) {
                dataSinceAckByDevice[address] = 0
                return transportAck
            }
            val paused = queue.count { !it.isAck && !BleNearbyTrafficPolicy.isNearbyRoomTraffic(it.trafficHint) }
            val nowMs = System.currentTimeMillis()
            val lastLogged = lastForegroundDequeueLogAtMsByAddress[address] ?: 0L
            if (paused > 0 && nowMs - lastLogged >= 5_000L) {
                lastForegroundDequeueLogAtMsByAddress[address] = nowMs
                foregroundDiagnostic(NearbyForegroundDiagnosticLabels.DEQUEUE_BLOCKED, "runtime_state=${foregroundRuntimeState()} addr=$address blocked_count=$paused nearby_runnable_count=0 non_nearby_pending_count=$paused reason=foreground_policy")
                foregroundDiagnostic(NearbyForegroundDiagnosticLabels.SCHEDULER_IDLE_PAUSED, "runtime_state=${foregroundRuntimeState()} addr=$address non_nearby_pending_count=$paused nearby_pending_count=0 busy_loop_prevented=true")
            }
            Log.i(
                TAG,
                "NEARBY_FOREGROUND dequeue_blocked addr=$address paused=$paused",
            )
            return null
        }
        val meshBatch = removeFirstMatching(queue) {
            it.batchId != null && it.dtnControlType == null && !it.isAck
        }
        if (meshBatch != null) {
            if (queueDtnTypes.isNotEmpty()) {
                Log.i(
                    TAG,
                    "WRITE prefer mesh batch addr=$address item=${meshBatch.itemId} batch=${meshBatch.batchId} queue_dtn=$queueDtnSummary",
                )
                diagnosticsListener?.onDtnControlQueueDecision(
                    address = address,
                    selectedType = null,
                    queueDtnTypes = queueDtnSummary,
                    queueSize = queue.size + 1,
                    reason = "mesh_batch_first",
                )
            }
            dataSinceAckByDevice[address] = (dataSinceAckByDevice[address] ?: 0) + 1
            return meshBatch
        }
        val dtnCandidates = queue.asSequence()
            .filter { it.dtnControlType != null }
            .distinctBy { it.itemId }
            .map { packet ->
                DtnControlQueuePolicy.Candidate(
                    itemId = packet.itemId,
                    type = packet.dtnControlType!!,
                    gid = packet.dtnControlGid,
                    sessionId = packet.dtnControlSessionId,
                )
            }
            .toList()
        val selectedDtn = DtnControlQueuePolicy.select(dtnCandidates)
        val dtnControl = selectedDtn?.let { selected ->
            removeFirstMatching(queue) { it.itemId == selected.itemId && it.dtnControlType != null }
        }
        if (dtnControl != null) {
            diagnosticsListener?.onDtnControlQueueDecision(
                address = address,
                selectedType = dtnControl.dtnControlType,
                queueDtnTypes = queueDtnSummary,
                queueSize = queue.size + 1,
                reason = "selected_dtn_priority",
            )
            if (!dtnControl.isAck) {
                dataSinceAckByDevice[address] = (dataSinceAckByDevice[address] ?: 0) + 1
            }
            return dtnControl
        }
        val hasData = queue.any { !it.isAck }
        val hasAck = queue.any { it.isAck }
        if (!hasData) {
            dataSinceAckByDevice[address] = 0
            return queue.pollFirst()
        }
        if (hasAck) {
            val dataBurst = dataSinceAckByDevice[address] ?: 0
            if (dataBurst >= ACK_SEND_QUOTA_DATA_BURST) {
                val ack = removeFirstMatching(queue) { it.isAck }
                if (ack != null) {
                    dataSinceAckByDevice[address] = 0
                    return ack
                }
            }
        }
        val data = removeFirstMatching(queue) { !it.isAck } ?: queue.pollFirst()
        if (queueDtnTypes.isNotEmpty() && data != null) {
            diagnosticsListener?.onDtnControlQueueDecision(
                address = address,
                selectedType = data.dtnControlType,
                queueDtnTypes = queueDtnSummary,
                queueSize = queue.size + 1,
                reason = "dtn_not_selected",
            )
        }
        if (data != null && !data.isAck) {
            dataSinceAckByDevice[address] = (dataSinceAckByDevice[address] ?: 0) + 1
        }
        return data
    }

    private fun removeFirstMatching(
        queue: ArrayDeque<PendingPacket>,
        predicate: (PendingPacket) -> Boolean,
    ): PendingPacket? = synchronized(pendingQueueLock) {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (predicate(item)) {
                iterator.remove()
                return item
            }
        }
        return null
    }

    private fun hasNearbyRoomPriorityWorkForDevice(deviceId: String): Boolean {
        return hasTrafficHintWorkForDevice(deviceId) {
            BleNearbyTrafficPolicy.isNearbyRoomPriorityHint(it)
        }
    }

    private fun hasNearbyRoomKeyBootstrapWorkForDevice(deviceId: String): Boolean {
        return hasTrafficHintWorkForDevice(deviceId) {
            BleNearbyTrafficPolicy.shouldBypassAddressCooldown(it)
        }
    }

    private fun hasTrafficHintWorkForDevice(
        deviceId: String,
        predicate: (MeshTrafficHint) -> Boolean,
    ): Boolean {
        val canonicalDeviceId = canonicalDeviceIdOrSelf(deviceId)
        if (canonicalDeviceId.isBlank()) return false
        if (activePendingPacketByAddress.values.any { packet ->
                canonicalDeviceIdOrSelf(packet.deviceId) == canonicalDeviceId &&
                    predicate(packet.trafficHint)
            }
        ) {
            return true
        }
        synchronized(pendingQueueLock) {
            if (pendingByDevice.values.any { queue ->
                    queue.any { packet ->
                        canonicalDeviceIdOrSelf(packet.deviceId) == canonicalDeviceId &&
                            predicate(packet.trafficHint)
                    }
                }
            ) {
                return true
            }
        }
        synchronized(schedulerLock) {
            if (stagedEnqueueByAddress.values.any { queue ->
                    queue.any { packet ->
                        canonicalDeviceIdOrSelf(packet.deviceId) == canonicalDeviceId &&
                            predicate(packet.trafficHint)
                    }
                }
            ) {
                return true
            }
            if (heldEnqueueByAddress.values.any { queue ->
                    queue.any { packet ->
                        canonicalDeviceIdOrSelf(packet.deviceId) == canonicalDeviceId &&
                            predicate(packet.trafficHint)
                    }
                }
            ) {
                return true
            }
        }
        return false
    }

    private fun sendDelayMs(totalPendingBytes: Int): Long {
        if (totalPendingBytes < BACKPRESSURE_HIGH_WATERMARK_BYTES) return 0L
        val extraSteps = (totalPendingBytes - BACKPRESSURE_HIGH_WATERMARK_BYTES) / BACKPRESSURE_STEP_BYTES
        val extra = (extraSteps.coerceAtMost(3)) * 100L
        return (floodControl.jitterDelayMs() + extra).coerceAtMost(MAX_BACKPRESSURE_DELAY_MS)
    }

    private fun receiveWindowPauseMs(nowMs: Long): Long {
        val windowMs = RX_WINDOW_PERIOD_MS + RX_WINDOW_PAUSE_MS
        if (windowMs <= 0L) return 0L
        val phase = nowMs % windowMs
        return if (phase < RX_WINDOW_PAUSE_MS) {
            RX_WINDOW_PAUSE_MS - phase
        } else {
            0L
        }
    }

    private fun scheduleWrite(address: String, connection: BleClientConnection, delayMs: Long) {
        if (writeScheduled.putIfAbsent(address, true) != null) return
        workerHandler.postDelayed({
            writeScheduled.remove(address)
            if (clientByDevice[address] == connection) {
                postLabel(address, PipelineLabel.WRITE_NEXT)
            }
        }, delayMs)
    }

    private fun writePacingDelayMs(): Long {
        if (WRITE_PACING_DELAY_MS <= 0) return 0L
        if (WRITE_PACING_JITTER_MS <= 0) return WRITE_PACING_DELAY_MS
        val jitter = ThreadLocalRandom.current().nextLong(0, WRITE_PACING_JITTER_MS + 1)
        return WRITE_PACING_DELAY_MS + jitter
    }

    private fun canSendInCycle(address: String, nowMs: Long): Boolean {
        val lastReset = sendBudgetResetAtMs[address] ?: 0L
        if (nowMs - lastReset >= SEND_CYCLE_WINDOW_MS) {
            val prevRemaining = sendBudgetRemaining[address]
            if (prevRemaining != null) {
                val sent = (MAX_CHUNKS_PER_CYCLE - prevRemaining).coerceAtLeast(0)
                diagnosticsListener?.onBudgetSummary(
                    address = address,
                    sent = sent,
                    windowMs = nowMs - lastReset,
                    limit = MAX_CHUNKS_PER_CYCLE,
                )
                if (sent > MAX_CHUNKS_PER_CYCLE) {
                    diagnosticsListener?.onBudgetViolation(
                        address = address,
                        sent = sent,
                        limit = MAX_CHUNKS_PER_CYCLE,
                    )
                    Log.w(TAG, "GATT send budget violated addr=$address sent=$sent limit=$MAX_CHUNKS_PER_CYCLE")
                }
            }
            sendBudgetRemaining[address] = MAX_CHUNKS_PER_CYCLE
            sendBudgetResetAtMs[address] = nowMs
        }
        val remaining = sendBudgetRemaining[address] ?: MAX_CHUNKS_PER_CYCLE
        if (remaining <= 0) {
            return false
        }
        sendBudgetRemaining[address] = remaining - 1
        return true
    }

    private fun updateBackpressure(totalPendingBytes: Int) {
        if (!running) return
        if (!backpressureActive && totalPendingBytes >= BACKPRESSURE_HIGH_WATERMARK_BYTES) {
            backpressureActive = true
            BleConfig.scanMode = ScanSettings.SCAN_MODE_LOW_POWER
            BleConfig.scanReportDelayMs = BACKPRESSURE_SCAN_REPORT_DELAY_MS
            updateScanSettings()
            BleConfig.advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            BleConfig.advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_LOW
            updateAdvertisingSettings()
            adaptiveState = AdaptiveState.BASE
            Log.w(TAG, "Backpressure on: pendingBytes=$totalPendingBytes scanMode=LOW_POWER")
            val nearby = nearbyDeviceCount()
            diagnosticsListener?.onBackpressureTriggered(totalPendingBytes, nearby)
        } else if (backpressureActive && totalPendingBytes <= BACKPRESSURE_LOW_WATERMARK_BYTES) {
            backpressureActive = false
            BleConfig.scanMode = baseScanMode
            BleConfig.scanReportDelayMs = baseScanReportDelayMs
            updateScanSettings()
            BleConfig.advertiseMode = baseAdvertiseMode
            BleConfig.advertiseTxPower = baseAdvertiseTxPower
            updateAdvertisingSettings()
            Log.i(TAG, "Backpressure off: pendingBytes=$totalPendingBytes scanMode=base")
        }
    }

    private fun closeAllGatt() {
        clientByDevice.forEach { (address, connection) ->
            cleanupConnection(address, connection, closeQueue = false, reason = "transport_stop")
        }
        clientByDevice.clear()
        connectingClient.values.forEach { connection ->
            try {
                markLocalDisconnectIntent(connection.address, "transport_stop_connecting")
                connection.disconnect()
                connection.close()
            } catch (_: Exception) {
            }
        }
        connectingClient.clear()
        characteristicByDevice.clear()
        connecting.clear()
        connectStartedAtMs.clear()
        lastProgressAtMs.clear()
        dataWarmupUntilByAddress.clear()
    }

    private fun clearRuntimeState(skipInFlightFailure: Boolean = false) {
        if (!skipInFlightFailure) {
            failAllInFlightPackets(reason = "transport_restart", nowMs = System.currentTimeMillis())
        }
        synchronized(pendingQueueLock) {
            pendingByDevice.clear()
        }
        addressStateByAddress.clear()
        synchronized(schedulerLock) {
            pipelinePendingLabels.clear()
            stagedEnqueueByAddress.clear()
            heldEnqueueByAddress.clear()
            pendingCleanupModeByAddress.clear()
            pendingCleanupReasonByAddress.clear()
            pendingRetryReasonByAddress.clear()
            pipelineMainQueue.clear()
            pipelineInMainQueue.clear()
            pipelineBusy.clear()
            pipelineDrainScheduled = false
        }
        lastSeenDevices.clear()
        lastSeenAtMs.clear()
        lastSeenRssi.clear()
        lastSeenDeviceId.clear()
        lastSeenAddrByDeviceId.clear()
        lastSeenIdAtMs.clear()
        lastActiveAtMsByDeviceId.clear()
        lastGraceLogAtMsByDeviceId.clear()
        lastTargetByDeviceId.clear()
        lastTargetSelectionAtByDeviceId.clear()
        mtuByDevice.clear()
        maxChunkByDevice.clear()
        characteristicByDevice.clear()
        cooldownUntilByAddress.clear()
        cooldownUntilByDeviceId.clear()
        deviceIdDwellUntil.clear()
        deviceIdRotationCooldownUntil.clear()
        deviceIdRotationRecoverUntil.clear()
        deviceIdRotationRecoverAddress.clear()
        deviceIdQuietUntil.clear()
        last133AtByDeviceId.clear()
        lastWriteFailed133AtByAddress.clear()
        post133RecoveryUntilByDeviceId.clear()
        connectRetryScheduledByAddress.clear()
        itemStateByKey.clear()
        itemBackoffUntilByKey.clear()
        activeItemKeyByAddress.clear()
        activeMeshEnvelopeItemIdByAddress.clear()
        activeDtnControlItemIdByAddress.clear()
        localDisconnectReasonByAddress.clear()
        lastSingleProbeAttemptAtMsByAddress.clear()
        synchronized(targetPoolLock) {
            mainTargetDeviceIds.clear()
            potentialTargetDeviceIds.clear()
        }
        sendBudgetRemaining.clear()
        sendBudgetResetAtMs.clear()
        writeScheduled.clear()
        lastMfgLogAtMs.clear()
        lastMfgSkipLogAtMs.clear()
        lastRxMismatchLogAtMs.clear()
        lastTxMismatchLogAtMs.clear()
        relayCompensationStateByKey.clear()
        batchForwardStateById.clear()
        delayedRelayStateByKey.clear()
        lastInProgressStuckWatchdogLogAtMsByKey.clear()
        rrDeviceQueue = ArrayDeque()
        rrDeviceSet.clear()
        backpressureActive = false
        idleIntervals = 0
        adaptiveState = AdaptiveState.BASE
        consecutiveNoCandidateSelections = 0
        lastNoCandidateRecoveryAtMs = 0L
    }

    private fun startCleanupLoop() {
        if (cleanupRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                if (!running) return
                cleanupStalledConnections()
                workerHandler.postDelayed(this, CLEANUP_INTERVAL_MS)
            }
        }
        cleanupRunnable = runnable
        workerHandler.postDelayed(runnable, CLEANUP_INTERVAL_MS)
    }

    private fun stopCleanupLoop() {
        cleanupRunnable?.let { workerHandler.removeCallbacks(it) }
        cleanupRunnable = null
    }

    private fun cleanupStalledConnections() {
        val now = System.currentTimeMillis()
        val nowSeconds = now / 1000L
        cleanupQueuedWithoutProgress(now)
        connectStartedAtMs.forEach { (address, startedAt) ->
            postCleanup(address, CleanupMode.TIMEOUT_CHECK, reason = "connect_timeout_check", callSite = "cleanup_loop")
            if (now - startedAt > CONNECT_TIMEOUT_MS) {
                Log.w(TAG, "GATT connect timeout addr=$address")
                diagnosticsListener?.onGattConnectTimeout(address)
                if (TEST_DISABLE_CONNECT_TIMEOUT_CLEANUP) {
                    Log.w(TAG, "GATT connect timeout cleanup disabled (test) addr=$address")
                }
            }
        }
        clientByDevice.forEach { (address, _) ->
            val last = lastProgressAtMs[address] ?: return@forEach
            val queueSize = synchronized(pendingQueueLock) { pendingByDevice[address]?.size ?: 0 }
            if (queueSize > 0 && now - last > STALLED_WRITE_TIMEOUT_MS) {
                Log.w(TAG, "GATT stalled; cleanup addr=$address pending=$queueSize")
                diagnosticsListener?.onGattStalled(address, queueSize)
                postCleanup(address, CleanupMode.FORCE, reason = "stalled_write", callSite = "cleanup_loop")
                maybeHardReset("stalled addr=$address pending=$queueSize")
            }
        }
        reassembler.prune(nowSeconds)
        chunkDeduper.prune(nowSeconds)
        envelopeDeduper.prune(nowSeconds)
        inboundEventDeduper.prune(nowSeconds)
        forwardDeduper.prune(nowSeconds)
        relayCompensationStateByKey.entries.removeIf { now - it.value.firstAcceptedAtMs > RELAY_COMPENSATION_STATE_TTL_MS }
        batchForwardStateById.entries.removeIf { now - it.value.firstAcceptedAtMs > BATCH_FORWARD_STATE_TTL_MS }
        delayedRelayStateByKey.entries.removeIf { now - it.value.createdAtMs > DELAYED_RELAY_MAX_AGE_MS }
        maybeRestartIfServerStuck(now)
        updateAdaptiveDutyCycle()
        maybeAutoRecoverScan(now)
        pruneRoundRobinQueue(now)
        disconnectIdleIfNeeded(now)
        maybeConnectNext()
    }

    private fun maybeRestartIfServerStuck(nowMs: Long) {
        val nearby = nearbyDeviceCount(nowMs)
        if (nearby <= 0) return
        if (lastServerStartedAtMs == 0L) return
        if (nowMs - lastStartStuckResetAtMs < START_STUCK_COOLDOWN_MS) return
        if (nowMs - lastTxChunkAtMs < START_STUCK_TX_GRACE_MS) {
            Log.i(
                TAG,
                "Skip server reset; recent TX within ${START_STUCK_TX_GRACE_MS}ms",
            )
            return
        }
        val sinceStart = nowMs - lastServerStartedAtMs
        val noConnSinceStart = lastServerConnAtMs < lastServerStartedAtMs
        val noRecvSinceStart = lastRecvChunkAtMs < lastServerStartedAtMs
        if (sinceStart > START_STUCK_TIMEOUT_MS && noConnSinceStart) {
            lastStartStuckResetAtMs = nowMs
            maybeHardReset("start stuck nearby=$nearby noConn=$noConnSinceStart noRecv=$noRecvSinceStart")
        }
    }

    private fun currentGattCap(nowMs: Long = System.currentTimeMillis()): Int {
        return if (nowMs < reducedGattCapUntilMs) REDUCED_CONCURRENT_GATT else MAX_CONCURRENT_GATT
    }

    private fun canOpenNewGatt(): Boolean {
        val now = System.currentTimeMillis()
        val cap = currentGattCap(now)
        return clientByDevice.size + connectingClient.size < cap
    }

    private fun maybeLogConnectCapBlocked(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastConnectCapBlockedLogAtMs < CONNECT_CAP_BLOCKED_LOG_COOLDOWN_MS) return
        lastConnectCapBlockedLogAtMs = nowMs
        diagnosticsListener?.onBleConnectCapBlocked(
            cap = currentGattCap(nowMs),
            active = clientByDevice.size,
            connecting = connectingClient.size,
            waiting = waitingDevicesCount(),
        )
        diagnosticsListener?.onBleConnectCapBlockedSnapshot(
            summary = buildConnectCapBlockedSnapshot(nowMs),
        )
    }

    private fun disconnectIdleIfNeeded(nowMs: Long) {
        val cap = if (nowMs < reducedGattCapUntilMs) REDUCED_CONCURRENT_GATT else MAX_CONCURRENT_GATT
        val waitingCount = waitingDevicesCount()
        val hasPressure = waitingCount >= PENDING_TARGETS_BUSY_THRESHOLD
        val hasRecentDataActivity = nowMs - lastDataActivityAtMs < RECENT_DATA_ACTIVITY_WINDOW_MS
        val idleTimeoutMs = when {
            hasPressure && hasRecentDataActivity -> IDLE_DISCONNECT_MS_MESSAGE_ACTIVE
            hasPressure -> IDLE_DISCONNECT_MS_BUSY
            else -> IDLE_DISCONNECT_MS_IDLE
        }
        if (hasPressure && clientByDevice.size < cap) return
        val idleCandidates = clientByDevice.keys
            .filter { address ->
                !hasActiveSendWork(address)
            }
        if (idleCandidates.isEmpty()) return
        val target = idleCandidates.minByOrNull { address ->
            lastProgressAtMs[address] ?: Long.MAX_VALUE
        } ?: return
        val lastProgress = lastProgressAtMs[target] ?: return
        if (nowMs - lastProgress < idleTimeoutMs) return
        clientByDevice[target]?.let {
            Log.i(TAG, "GATT idle disconnect addr=$target idleMs=${nowMs - lastProgress}")
            postCleanup(target, CleanupMode.FORCE, reason = "idle_disconnect", callSite = "cleanup_loop")
        }
    }

    private fun waitingDevicesCount(): Int {
        val waitingAddresses = LinkedHashSet<String>()
        synchronized(pendingQueueLock) {
            pendingByDevice.forEach { (address, queue) ->
                if (queue.isNotEmpty()) {
                    waitingAddresses.add(address)
                }
            }
        }
        synchronized(schedulerLock) {
            stagedEnqueueByAddress.forEach { (address, queue) ->
                if (queue.isNotEmpty()) {
                    waitingAddresses.add(address)
                }
            }
            heldEnqueueByAddress.forEach { (address, queue) ->
                if (queue.isNotEmpty()) {
                    waitingAddresses.add(address)
                }
            }
        }
        return waitingAddresses.count { address ->
            !clientByDevice.containsKey(address) &&
                !connectingClient.containsKey(address) &&
                !connecting.containsKey(address)
        }
    }

    private fun hasQueuedPackets(address: String): Boolean {
        return synchronized(pendingQueueLock) {
            pendingByDevice[address]?.isNotEmpty() == true
        }
    }

    private fun hasActiveSendWork(address: String): Boolean {
        if (addressStateByAddress[address]?.writeInFlight == true) return true
        if (activePendingPacketByAddress.containsKey(address)) return true
        if (hasQueuedPackets(address)) return true
        return false
    }

    override fun hasPendingDataTraffic(): Boolean {
        if (activePendingPacketByAddress.values.any { !it.isAck }) return true
        if (addressStateByAddress.values.any { it.writeInFlight }) return true
        if (connectingClient.isNotEmpty()) return true
        synchronized(pendingQueueLock) {
            if (pendingByDevice.values.any { queue -> queue.any { !it.isAck } }) {
                return true
            }
        }
        synchronized(schedulerLock) {
            return stagedEnqueueByAddress.values.any { queue -> queue.any { !it.isAck } } ||
                heldEnqueueByAddress.values.any { queue -> queue.any { !it.isAck } }
        }
    }

    private fun hasPendingDataForAddress(address: String): Boolean {
        synchronized(pendingQueueLock) {
            if (pendingByDevice[address]?.any { !it.isAck } == true) {
                return true
            }
        }
        synchronized(schedulerLock) {
            return stagedEnqueueByAddress[address]?.any { !it.isAck } == true
        }
    }

    private fun hasHeldDataForAddress(address: String): Boolean {
        synchronized(schedulerLock) {
            return heldEnqueueByAddress[address]?.any { !it.isAck } == true
        }
    }

    private fun resolveAddressForDeviceId(deviceId: String): String? {
        lastSeenAddrByDeviceId[deviceId]?.let { return it }
        lastTargetByDeviceId[deviceId]?.let { return it }
        return null
    }

    private fun hasPulledDispatchJobForDeviceId(deviceId: String): Boolean {
        return pulledDispatchJobByAddress.values.any { it.deviceId == deviceId }
    }

    private fun findPulledDispatchJobEntry(
        deviceId: String,
        batchId: String?,
    ): Pair<String, MeshDispatchJob>? {
        val mappedAddress = resolveAddressForDeviceId(deviceId)
        if (mappedAddress != null) {
            val mappedJob = pulledDispatchJobByAddress[mappedAddress]
            if (mappedJob != null &&
                mappedJob.deviceId == deviceId &&
                (batchId == null || mappedJob.batchId == batchId)
            ) {
                return mappedAddress to mappedJob
            }
        }

        val fallback = pulledDispatchJobByAddress.entries.firstOrNull { (_, job) ->
            job.deviceId == deviceId && (batchId == null || job.batchId == batchId)
        } ?: return null
        return fallback.key to fallback.value
    }

    private fun hasQueuedOrHeldSendWork(address: String): Boolean {
        synchronized(pendingQueueLock) {
            if (pendingByDevice[address]?.isNotEmpty() == true) return true
        }
        synchronized(schedulerLock) {
            if (stagedEnqueueByAddress[address]?.isNotEmpty() == true) return true
            if (heldEnqueueByAddress[address]?.isNotEmpty() == true) return true
        }
        return false
    }

    private fun hasQueuedOrHeldSendWorkForDevice(deviceId: String): Boolean {
        if (activePendingPacketByAddress.values.any { it.deviceId == deviceId }) return true
        synchronized(pendingQueueLock) {
            if (pendingByDevice.values.any { queue -> queue.any { it.deviceId == deviceId } }) return true
        }
        synchronized(schedulerLock) {
            if (stagedEnqueueByAddress.values.any { queue -> queue.any { it.deviceId == deviceId } }) return true
            if (heldEnqueueByAddress.values.any { queue -> queue.any { it.deviceId == deviceId } }) return true
        }
        return false
    }

    private fun maybePullDispatchJobForAddress(
        address: String,
        reason: String,
    ) {
        if (!running) return
        if (nearbyForegroundMode) {
            foregroundDiagnostic(
                NearbyForegroundDiagnosticLabels.DISPATCH_BLOCKED,
                "runtime_state=${foregroundRuntimeState()} addr=$address reason=$reason source_queue_retained=true retry_consumed=false",
            )
            Log.i(TAG, "NEARBY_FOREGROUND dispatch_pull_blocked addr=$address reason=$reason")
            return
        }
        if (connectState(address) != CONNECT_STATE_CONNECTED) return
        if (!clientByDevice.containsKey(address)) return

        workerHandler.post {
            if (!running) return@post
            if (connectState(address) != CONNECT_STATE_CONNECTED) return@post
            if (!clientByDevice.containsKey(address)) return@post
            maybePullDispatchJobForAddressOnWorker(address, reason)
        }
    }

    private fun maybePullDispatchJobForAddressOnWorker(
        address: String,
        reason: String,
    ): Boolean {
        if (!running) return false
        if (nearbyForegroundMode) return false
        if (connectState(address) != CONNECT_STATE_CONNECTED) return false
        if (!clientByDevice.containsKey(address)) return false

        val resolvedDeviceIdAtEntry = resolveStableDeviceIdForAddress(address)
        diagnosticsListener?.onDispatchJobPullAttempt(
            address = address,
            deviceId = resolvedDeviceIdAtEntry,
            reason = reason,
        )

        if (!running) {
            diagnosticsListener?.onDispatchJobPullSkipped(address, null, "not_running:$reason")
            return false
        }

        val state = connectState(address)
        if (state != CONNECT_STATE_CONNECTED) {
            diagnosticsListener?.onDispatchJobPullSkipped(address, resolveStableDeviceIdForAddress(address), "not_connected:$reason:state=$state")
            return false
        }

        if (activePendingPacketByAddress[address] != null) {
            diagnosticsListener?.onDispatchJobPullSkipped(address, resolveStableDeviceIdForAddress(address), "active_pending:$reason")
            return false
        }

        if (hasQueuedOrHeldSendWork(address)) {
            diagnosticsListener?.onDispatchJobPullSkipped(address, resolveStableDeviceIdForAddress(address), "queued_or_held:$reason")
            return false
        }

        if (pulledDispatchJobByAddress.containsKey(address)) {
            diagnosticsListener?.onDispatchJobPullSkipped(address, resolveStableDeviceIdForAddress(address), "already_pulled:$reason")
            return false
        }

        val deviceId = resolveStableDeviceIdForAddress(address)
        if (deviceId == null) {
            diagnosticsListener?.onDispatchJobPullSkipped(address, null, "device_id_missing:$reason")
            return false
        }

        if (deviceId.startsWith("addr:")) {
            diagnosticsListener?.onDispatchJobPullSkipped(address, deviceId, "device_id_unstable:$reason")
            return false
        }

        if (hasPulledDispatchJobForDeviceId(deviceId)) {
            diagnosticsListener?.onDispatchJobPullSkipped(address, deviceId, "already_pulled_device:$reason")
            return false
        }

        if (hasQueuedOrHeldSendWorkForDevice(deviceId)) {
            diagnosticsListener?.onDispatchJobPullSkipped(address, deviceId, "device_queued_or_held:$reason")
            return false
        }

        val nowMs = System.currentTimeMillis()
        val candidate = DeviceCandidate(
            deviceId = deviceId,
            address = address,
            seenAtMs = lastSeenAtMs[address] ?: (lastSeenIdAtMs[deviceId] ?: nowMs),
            rssi = lastSeenRssi[address] ?: Int.MIN_VALUE,
        )
        val hasPendingData = hasPendingDataTraffic()
        if (hasPendingData) {
            if (!hasRelatedOutboundPathForCandidate(candidate, nowMs)) {
                diagnosticsListener?.onDispatchJobPullSkipped(address, deviceId, "no_outbound_path:$reason")
                return false
            }
        } else {
            val suppressIdleNoise =
                isRepeatedNoJobDevice(deviceId, nowMs) &&
                    !isDeviceInMainTargetPool(deviceId) &&
                    !hasStrictOutboundPathForCandidate(candidate)
            if (suppressIdleNoise) {
                diagnosticsListener?.onDispatchJobPullSkipped(address, deviceId, "idle_noise_suppressed:$reason")
                return false
            }
        }

        val source = dispatchJobSource
        if (source == null) {
            diagnosticsListener?.onDispatchJobPullSkipped(address, deviceId, "job_source_missing:$reason")
            return false
        }

        val job = source.claimNextDispatchJob(
            deviceId = deviceId,
            allowAck = !hasPendingData,
        )
        if (job == null) {
            noteNoJobSkip(deviceId, System.currentTimeMillis(), "no_job:$reason")
            diagnosticsListener?.onDispatchJobPullSkipped(address, deviceId, "no_job:$reason")
            return false
        }
        clearNoJobSkip(deviceId)

        val accepted = broadcastEnvelopeToDeviceIds(job.payload, setOf(job.deviceId))
        if (!accepted) {
            source.failDispatchJob(
                batchId = job.batchId,
                deviceId = job.deviceId,
                reason = "transport_pull_rejected:$reason",
                retryDelayMs = 2_000L,
            )
            diagnosticsListener?.onDispatchJobPullRejected(
                address = address,
                batchId = job.batchId,
                deviceId = job.deviceId,
                reason = reason,
            )
            return false
        }

        pulledDispatchJobByAddress[address] = job
        diagnosticsListener?.onDispatchJobPulled(
            address = address,
            batchId = job.batchId,
            deviceId = job.deviceId,
            reason = reason,
        )
        return true
    }

    private fun onPulledDispatchJobTerminal(
        deviceId: String,
        batchId: String?,
        reason: String,
    ) {
        val resolvedEntry = findPulledDispatchJobEntry(deviceId, batchId) ?: return
        val (address, job) = resolvedEntry

        pulledDispatchJobByAddress.remove(address)

        diagnosticsListener?.onDispatchJobTerminalState(
            address = address,
            batchId = job.batchId,
            deviceId = deviceId,
            result = "terminal",
            reason = reason,
            pulledBatchId = job.batchId,
            pulledDeviceId = job.deviceId,
        )

        diagnosticsListener?.onDispatchJobTerminal(
            address = address,
            batchId = job.batchId,
            deviceId = deviceId,
            reason = reason,
        )

        workerHandler.post {
            if (!running) return@post
            postLabel(address, PipelineLabel.ENQUEUE)
        }
    }

    private fun hasAnyQueuedOrHeldWorkForAddress(address: String): Boolean {
        synchronized(pendingQueueLock) {
            if (pendingByDevice[address]?.isNotEmpty() == true) {
                return true
            }
        }
        synchronized(schedulerLock) {
            if (stagedEnqueueByAddress[address]?.isNotEmpty() == true) {
                return true
            }
            if (heldEnqueueByAddress[address]?.isNotEmpty() == true) {
                return true
            }
        }
        return false
    }

    private fun hasRunnableQueuedOrHeldWorkForAddress(address: String): Boolean {
        fun isRunnable(packet: PendingPacket): Boolean =
            BleNearbyTrafficPolicy.allowsNewItemDequeue(
                foregroundMode = nearbyForegroundMode,
                hint = packet.trafficHint,
                isTransportAck = packet.isAck,
            )
        synchronized(pendingQueueLock) {
            if (pendingByDevice[address]?.any(::isRunnable) == true) return true
        }
        synchronized(schedulerLock) {
            if (stagedEnqueueByAddress[address]?.any(::isRunnable) == true) return true
            if (heldEnqueueByAddress[address]?.any(::isRunnable) == true) return true
        }
        return false
    }

    private fun hasRecoverableActivePathForAddress(address: String): Boolean {
        val nowMs = System.currentTimeMillis()
        if (activePendingPacketByAddress[address] != null) return true
        if (addressStateByAddress[address]?.writeInFlight == true) return true
        if (hasAnyQueuedOrHeldWorkForAddress(address)) return true
        val lastProgress = lastProgressAtMs[address] ?: 0L
        if (clientByDevice.containsKey(address) &&
            connectState(address) == CONNECT_STATE_CONNECTED &&
            lastProgress > 0L &&
            nowMs - lastProgress <= OLD_LANE_RECENT_PROGRESS_GRACE_MS
        ) {
            return true
        }
        return false
    }

    private fun hasActiveDrainPathForAddress(address: String): Boolean {
        if (activePendingPacketByAddress[address] != null) return true
        if (addressStateByAddress[address]?.writeInFlight == true) return true
        if (!hasAnyQueuedOrHeldWorkForAddress(address)) return false
        return clientByDevice.containsKey(address) ||
            connectingClient.containsKey(address) ||
            connecting.containsKey(address) ||
            connectState(address) == CONNECT_STATE_CONNECTED ||
            connectState(address) == CONNECT_STATE_CONNECTING
    }

    private fun cleanupStaleDuplicateDeviceLanes(nowMs: Long): Int {
        val laneAddresses = LinkedHashSet<String>()
        laneAddresses.addAll(clientByDevice.keys)
        laneAddresses.addAll(connectingClient.keys)
        if (laneAddresses.isEmpty()) return 0

        val byDeviceId = LinkedHashMap<String, MutableList<String>>()
        for (address in laneAddresses) {
            val deviceId = resolveStableDeviceIdForAddress(address) ?: continue
            byDeviceId.getOrPut(deviceId) { ArrayList() }.add(address)
        }

        var cleaned = 0
        for ((deviceId, addresses) in byDeviceId) {
            if (addresses.size <= 1) continue
            val keeper = addresses.maxByOrNull { address ->
                val activePacketScore = if (activePendingPacketByAddress[address] != null) 1_000 else 0
                val inFlightScore = if (addressStateByAddress[address]?.writeInFlight == true) 500 else 0
                val queuedScore = if (hasAnyQueuedOrHeldWorkForAddress(address)) 200 else 0
                val stateScore = when (connectState(address)) {
                    CONNECT_STATE_CONNECTING -> 100
                    CONNECT_STATE_CONNECTED -> 50
                    else -> 0
                }
                val lastProgress = lastProgressAtMs[address] ?: 0L
                val recentProgressScore = if (lastProgress > 0L) {
                    maxOf(0L, OLD_LANE_RECENT_PROGRESS_GRACE_MS - (nowMs - lastProgress)).toInt()
                } else {
                    0
                }
                activePacketScore + inFlightScore + queuedScore + stateScore + recentProgressScore
            } ?: continue

            for (address in addresses) {
                if (address == keeper) continue
                if (hasRecoverableActivePathForAddress(address)) continue

                val activeConn = clientByDevice[address]
                if (activeConn != null) {
                    Log.w(
                        TAG,
                        "connect cap stale lane cleanup id=$deviceId drop=$address keep=$keeper kind=active",
                    )
                    cleanupConnection(
                        address = address,
                        connection = activeConn,
                        closeQueue = false,
                        reason = "stale_duplicate_device_lane",
                    )
                    cleaned += 1
                    continue
                }

                val connectingConn = connectingClient[address]
                if (connectingConn != null) {
                    Log.w(
                        TAG,
                        "connect cap stale lane cleanup id=$deviceId drop=$address keep=$keeper kind=connecting",
                    )
                    try {
                        markLocalDisconnectIntent(address, "stale_duplicate_device_lane_connecting")
                        connectingConn.disconnect()
                        connectingConn.close()
                    } catch (_: Exception) {
                    }
                    connectingClient.remove(address)
                    connecting.remove(address)
                    connectStartedAtMs.remove(address)
                    setConnectState(address, CONNECT_STATE_DISCONNECTED, "stale_duplicate_device_lane")
                    cleaned += 1
                }
            }
        }

        return cleaned
    }

    private fun buildConnectCapBlockedSnapshot(nowMs: Long): String {
        val laneAddresses = LinkedHashSet<String>()
        laneAddresses.addAll(clientByDevice.keys)
        laneAddresses.addAll(connectingClient.keys)
        if (laneAddresses.isEmpty()) return "none"

        return laneAddresses.joinToString(separator = " | ") { address ->
            val deviceId = resolveStableDeviceIdForAddress(address) ?: "unknown"
            val state = connectState(address)
            val lastProgress = lastProgressAtMs[address] ?: 0L
            val progressAgeMs = if (lastProgress > 0L) nowMs - lastProgress else -1L
            val pendingCount = synchronized(pendingQueueLock) { pendingByDevice[address]?.size ?: 0 }
            val stagedCount = synchronized(schedulerLock) { stagedEnqueueByAddress[address]?.size ?: 0 }
            val heldCount = synchronized(schedulerLock) { heldEnqueueByAddress[address]?.size ?: 0 }
            val activePacket = activePendingPacketByAddress.containsKey(address)
            val writeInFlight = addressStateByAddress[address]?.writeInFlight == true
            "addr=$address id=$deviceId st=$state ageMs=$progressAgeMs pending=$pendingCount staged=$stagedCount held=$heldCount activePacket=$activePacket inFlight=$writeInFlight"
        }
    }

    private fun isRotationRecoveryActiveForDevice(deviceId: String, nowMs: Long): Boolean {
        val until = deviceIdRotationRecoverUntil[deviceId] ?: return false
        if (nowMs >= until) {
            deviceIdRotationRecoverUntil.remove(deviceId)
            deviceIdRotationRecoverAddress.remove(deviceId)
            return false
        }
        return true
    }

    private fun hasRecentSiblingProgressForDevice(deviceId: String, nowMs: Long): Boolean {
        val addresses = candidateAddressesForDevice(deviceId, nowMs)
        return addresses.any { address ->
            val lastProgress = lastProgressAtMs[address] ?: 0L
            val recentProgress = lastProgress > 0L && nowMs - lastProgress < (RECENT_CONNECT_GRACE_MS * 2)
            val connectedWithWork =
                connectState(address) == CONNECT_STATE_CONNECTED &&
                    (hasAnyQueuedOrHeldWorkForAddress(address) || activePendingPacketByAddress[address] != null)
            recentProgress || connectedWithWork
        }
    }

    private fun hasRecentOtherActiveLaneProgressForDevice(deviceId: String, nowMs: Long): Boolean {
        val canonicalDeviceId = canonicalDeviceIdOrSelf(deviceId)
        val recentProgressWindowMs = RECENT_CONNECT_GRACE_MS * 2

        val activePacketProgress = activePendingPacketByAddress.entries.any { (address, packet) ->
            if (canonicalDeviceIdOrSelf(packet.deviceId) == canonicalDeviceId) return@any false
            val lastProgress = lastProgressAtMs[address] ?: 0L
            val recentProgress = lastProgress > 0L && nowMs - lastProgress < recentProgressWindowMs
            recentProgress || addressStateByAddress[address]?.writeInFlight == true
        }
        if (activePacketProgress) return true

        return addressStateByAddress.entries.any { (address, state) ->
            if (!state.writeInFlight) return@any false
            val otherDeviceId = canonicalDeviceIdOrSelf(lastSeenDeviceId[address] ?: "")
            if (otherDeviceId.isBlank() || otherDeviceId == canonicalDeviceId) return@any false
            val lastProgress = lastProgressAtMs[address] ?: 0L
            lastProgress > 0L && nowMs - lastProgress < recentProgressWindowMs
        }
    }

    private fun stageHeldPackets(
        address: String,
        packets: List<PendingPacket>,
    ) {
        if (packets.isEmpty()) return
        synchronized(schedulerLock) {
            val held = heldEnqueueByAddress.getOrPut(address) { ArrayDeque() }
            packets.forEach { held.addLast(it) }
        }
    }

    private fun prependStagedPackets(
        address: String,
        packets: List<PendingPacket>,
    ) {
        if (packets.isEmpty()) return
        synchronized(schedulerLock) {
            val existing = stagedEnqueueByAddress.remove(address)
            val merged = ArrayDeque<PendingPacket>()
            packets.forEach { merged.addLast(it) }
            existing?.forEach { merged.addLast(it) }
            stagedEnqueueByAddress[address] = merged
        }
    }

    private fun rebuildPacketsForCurrentChunkSize(
        address: String,
        packets: List<PendingPacket>,
    ): List<PendingPacket> {
        if (packets.isEmpty()) return emptyList()

        val first = packets.first()
        val payloadBytes = first.payloadBytes
        if (payloadBytes <= 0) return packets

        val rawPayload =
            Chunking.reassemblePayload(
                chunks = packets.map { it.payload },
                senderKey = first.deviceId,
                nowSeconds = System.currentTimeMillis() / 1000,
            ) ?: return packets

        val chunkSize = maxChunkSizeFor(address)
        val rebuiltChunks = Chunking.split(rawPayload, chunkSize)
        if (rebuiltChunks.isEmpty()) return packets

        return rebuiltChunks.map { chunk ->
            PendingPacket(
                payload = chunk,
                isAck = first.isAck,
                itemId = first.itemId,
                deviceId = first.deviceId,
                trafficHint = first.trafficHint,
                nearbyForegroundProtected = first.nearbyForegroundProtected,
                payloadBytes = rawPayload.size,
                totalChunks = rebuiltChunks.size,
                chunkBytes = chunk.size,
                batchId = first.batchId,
                batchEventIds = first.batchEventIds,
                batchAttemptSeq = first.batchAttemptSeq,
            )
        }
    }

    private fun shouldRebuildPolledPacketForCurrentChunkSize(
        address: String,
        packet: PendingPacket,
    ): Boolean {
        if (packet.isAck) return false
        if (!mtuByDevice.containsKey(address)) return false
        if (packet.payloadBytes <= 0) return false

        val expectedChunks = expectedChunkCountFor(address, packet.payloadBytes)
        return expectedChunks > 0 && expectedChunks < packet.totalChunks
    }

    private fun expectedChunkCountFor(address: String, payloadBytes: Int): Int {
        if (payloadBytes <= 0) return 0
        val dataBytes = Chunking.payloadBytesFromChunkBytes(maxChunkSizeFor(address)).coerceAtLeast(1)
        return (payloadBytes + dataBytes - 1) / dataBytes
    }

    private fun rebuildQueueForCurrentChunkSize(
        address: String,
        queue: ArrayDeque<PendingPacket>,
    ): List<PendingPacket> {
        if (queue.isEmpty()) return emptyList()

        val rebuilt = ArrayList<PendingPacket>()
        val currentGroup = ArrayList<PendingPacket>()

        fun flushGroup() {
            if (currentGroup.isEmpty()) return
            rebuilt.addAll(rebuildPacketsForCurrentChunkSize(address, currentGroup))
            currentGroup.clear()
        }

        while (queue.isNotEmpty()) {
            val packet = queue.removeFirst()
            if (currentGroup.isEmpty()) {
                currentGroup.add(packet)
                continue
            }

            val first = currentGroup.first()
            val sameItem =
                first.itemId == packet.itemId &&
                    first.deviceId == packet.deviceId &&
                    first.batchId == packet.batchId

            if (sameItem) {
                currentGroup.add(packet)
            } else {
                flushGroup()
                currentGroup.add(packet)
            }
        }

        flushGroup()
        return rebuilt
    }

    private fun rebuildStagedQueueForCurrentChunkSize(address: String): Int {
        synchronized(schedulerLock) {
            val staged = stagedEnqueueByAddress[address] ?: return 0
            if (staged.isEmpty()) return 0

            val rebuiltPackets = rebuildQueueForCurrentChunkSize(address, staged)
            rebuiltPackets.forEach { packet ->
                staged.addLast(packet)
            }
            if (staged.isEmpty()) {
                stagedEnqueueByAddress.remove(address)
            }
            return rebuiltPackets.size
        }
    }

    private fun rebuildPendingQueueForCurrentChunkSizeIfIdle(address: String): Int {
        if (activePendingPacketByAddress[address] != null) return 0
        if (addressStateByAddress[address]?.writeInFlight == true) return 0

        synchronized(pendingQueueLock) {
            val pending = pendingByDevice[address] ?: return 0
            if (pending.isEmpty()) return 0

            val rebuiltPackets = rebuildQueueForCurrentChunkSize(address, pending)
            rebuiltPackets.forEach { packet ->
                pending.addLast(packet)
            }
            if (pending.isEmpty()) {
                pendingByDevice.remove(address)
            }
            return rebuiltPackets.size
        }
    }

    private fun isAddressReadyForHeldRelease(address: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (connectState(address) != CONNECT_STATE_CONNECTED) return false
        if (characteristicByDevice[address] == null) return false
        val warmupUntilMs = dataWarmupUntilByAddress[address] ?: 0L
        if (nowMs < warmupUntilMs) return false
        if (!mtuByDevice.containsKey(address)) return false
        return true
    }

    private fun releaseHeldToStagedIfReady(address: String, nowMs: Long = System.currentTimeMillis()): Int {
        if (!isAddressReadyForHeldRelease(address, nowMs)) return 0
        synchronized(schedulerLock) {
            val held = heldEnqueueByAddress[address] ?: return 0
            if (held.isEmpty()) return 0

            val rebuiltPackets = rebuildQueueForCurrentChunkSize(address, held)
            val staged = stagedEnqueueByAddress.getOrPut(address) { ArrayDeque() }

            rebuiltPackets.forEach { packet ->
                staged.addLast(packet)
            }

            heldEnqueueByAddress.remove(address)

            Log.i(
                TAG,
                "Rebuilt held packets after ready addr=$address rebuilt=${rebuiltPackets.size} chunkBytes=${maxChunkSizeFor(address)}",
            )

            return rebuiltPackets.size
        }
    }

    private fun maybeConnectNext() {
        if (!running) return
        val nowMs = System.currentTimeMillis()
        if (!canOpenNewGatt()) {
            val released = cleanupStaleDuplicateDeviceLanes(nowMs)
            if (released > 0 && canOpenNewGatt()) {
                Log.i(TAG, "GATT connect cap recovered released=$released active=${clientByDevice.size} connecting=${connectingClient.size}")
            } else {
                maybeLogConnectCapBlocked(nowMs)
                return
            }
        }
        if (!canOpenNewGatt()) {
            maybeLogConnectCapBlocked()
            return
        }
        val maxAttempts = rrDeviceQueue.size
        var attempts = 0
        var opened = 0
        while (attempts < maxAttempts && canOpenNewGatt()) {
            val address = rrDeviceQueue.pollFirst() ?: break
            rrDeviceQueue.addLast(address)
            attempts += 1
            val hasQueuedWork = hasRunnableQueuedOrHeldWorkForAddress(address)
            if (!hasQueuedWork) continue
            if (clientByDevice.containsKey(address) ||
                connectingClient.containsKey(address) ||
                connecting.containsKey(address)
            ) {
                continue
            }
            if (connectState(address) != CONNECT_STATE_DISCONNECTED) {
                continue
            }
            if (shouldSkipDueToRssi(address)) {
                continue
            }
            postLabel(address, PipelineLabel.CONNECT)
            opened += 1
        }
        if (opened > 0) {
            Log.i(TAG, "GATT connect cap: opened=$opened active=${clientByDevice.size} connecting=${connectingClient.size}")
        }
    }

    private fun shouldSkipDueToRssi(address: String): Boolean {
        if (!backpressureActive) return false
        val rssi = lastSeenRssi[address] ?: return false
        return rssi < RSSI_MIN_THRESHOLD_DBM
    }

    private fun offerDeviceForRoundRobin(address: String) {
        if (rrDeviceSet.add(address)) {
            rrDeviceQueue.addLast(address)
        }
    }

    private fun requestMaybeConnectNext() {
        if (!running) return
        workerHandler.post { maybeConnectNext() }
    }

    private fun labelPriority(label: PipelineLabel): Int {
        return when (label) {
            PipelineLabel.CONNECT -> 0
            PipelineLabel.WRITE_NEXT -> 1
            PipelineLabel.ENQUEUE -> 2
            PipelineLabel.RETRY -> 3
            PipelineLabel.CLEANUP -> 4
        }
    }

    private fun selectHighestPriorityLabel(labels: LinkedHashSet<PipelineLabel>): PipelineLabel? {
        return labels.minByOrNull { labelPriority(it) }
    }

    private fun formatPipelineLabels(labels: Set<PipelineLabel>?): String {
        if (labels.isNullOrEmpty()) return "none"
        return labels.joinToString(separator = ",") { it.name }
    }

    private fun postLabel(address: String, label: PipelineLabel) {
        if (label == PipelineLabel.CONNECT) {
            val state = connectState(address)
            if (state == CONNECT_STATE_CONNECTED || state == CONNECT_STATE_CONNECTING) {
                return
            }
            if (clientByDevice.containsKey(address) ||
                connectingClient.containsKey(address) ||
                connecting.containsKey(address)
            ) {
                return
            }
        }
        Log.i(TAG, "PIPELINE label_enqueue addr=$address label=$label")
        diagnosticsListener?.onPipelineLabelEnqueue(address, label.name)
        var shouldScheduleDrain = false
        synchronized(schedulerLock) {
            val labels = pipelinePendingLabels.getOrPut(address) { LinkedHashSet() }
            val added = labels.add(label)
            if (added) {
                diagnosticsListener?.onPipelineLabelsChanged(
                    address = address,
                    action = "add",
                    label = label.name,
                    labels = formatPipelineLabels(labels),
                )
            }
            if (!pipelineInMainQueue.contains(address) && !pipelineBusy.contains(address)) {
                val wasEmpty = pipelineMainQueue.isEmpty()
                pipelineMainQueue.addLast(address)
                pipelineInMainQueue.add(address)
                if (wasEmpty && !pipelineDrainScheduled) {
                    pipelineDrainScheduled = true
                    shouldScheduleDrain = true
                }
            } else if (!pipelineDrainScheduled) {
                pipelineDrainScheduled = true
                shouldScheduleDrain = true
            }
        }
        if (shouldScheduleDrain) {
            workerHandler.post { drainPipelineLoop() }
        }
    }

    private fun drainPipelineLoop() {
        while (true) {
            var taskAddress: String? = null
            var taskLabel: PipelineLabel? = null
            synchronized(schedulerLock) {
                val nextAddress = pipelineMainQueue.pollFirst()
                if (nextAddress == null) {
                    pipelineDrainScheduled = false
                    Log.i(TAG, "PIPELINE drain_idle")
                    diagnosticsListener?.onPipelineDrainIdle()
                    return
                }
                pipelineInMainQueue.remove(nextAddress)
                if (!pipelineBusy.contains(nextAddress)) {
                    val labels = pipelinePendingLabels[nextAddress]
                    val selected = if (labels.isNullOrEmpty()) null else selectHighestPriorityLabel(labels)
                    if (selected != null) {
                        labels?.remove(selected)
                        diagnosticsListener?.onPipelineLabelsChanged(
                            address = nextAddress,
                            action = "remove_for_execute",
                            label = selected.name,
                            labels = formatPipelineLabels(labels),
                        )
                        if (labels != null && labels.isEmpty()) {
                            pipelinePendingLabels.remove(nextAddress)
                        }
                        pipelineBusy.add(nextAddress)
                        taskAddress = nextAddress
                        taskLabel = selected
                    }
                }
            }

            val address = taskAddress
            val label = taskLabel
            if (address == null || label == null) {
                continue
            }

            Log.i(TAG, "PIPELINE label_execute addr=$address label=$label")
            diagnosticsListener?.onPipelineLabelExecute(address, label.name)
            executePipelineLabel(address, label)

            synchronized(schedulerLock) {
                pipelineBusy.remove(address)
                val hasMore = !(pipelinePendingLabels[address].isNullOrEmpty())
                if (hasMore && !pipelineInMainQueue.contains(address)) {
                    pipelineMainQueue.addLast(address)
                    pipelineInMainQueue.add(address)
                    Log.i(TAG, "PIPELINE label_requeue addr=$address")
                    diagnosticsListener?.onPipelineLabelRequeue(address)
                }
            }
        }
    }

    private fun executePipelineLabel(address: String, label: PipelineLabel) {
        when (label) {
            PipelineLabel.CONNECT -> {
                if (!hasRunnableQueuedOrHeldWorkForAddress(address)) {
                    if (nearbyForegroundMode && hasAnyQueuedOrHeldWorkForAddress(address)) {
                        Log.i(TAG, "NEARBY_FOREGROUND connect_blocked addr=$address")
                    }
                    return
                }
                val now = System.currentTimeMillis()
                val nextAllowed = addressStateByAddress[address]?.nextConnectAllowedAt ?: 0L
                if (now < nextAllowed) {
                    val delayMs = (nextAllowed - now).coerceAtLeast(200L)
                    Log.i(TAG, "PIPELINE connect_backoff_gate addr=$address delayMs=$delayMs")
                    diagnosticsListener?.onPipelineConnectBackoffGate(address, delayMs)
                    scheduleConnectRetry(address, delayMs)
                    return
                }
                connectIfNeeded(address)
            }
            PipelineLabel.WRITE_NEXT -> {
                triggerWritePump(address)
            }
            PipelineLabel.ENQUEUE -> {
                val staged = synchronized(schedulerLock) { stagedEnqueueByAddress.remove(address) }
                if (staged != null && staged.isNotEmpty()) {
                    val stagedCount = staged.size
                    var accepted = 0
                    var rejected = 0
                    var firstItemId: String? = null
                    val rejectedItems = LinkedHashSet<String>()
                    val rejectedPackets = ArrayList<PendingPacket>()
                    val pendingBefore = synchronized(pendingQueueLock) {
                        pendingByDevice[address]?.size ?: 0
                    }
                    while (staged.isNotEmpty()) {
                        val packet = staged.removeFirst()
                        if (firstItemId == null) firstItemId = packet.itemId
                        val queue = synchronized(pendingQueueLock) {
                            pendingByDevice.getOrPut(address) { ArrayDeque() }
                        }
                        if (enqueuePacket(address = address, queue = queue, packet = packet)) {
                            accepted += 1
                        } else {
                            rejected += 1
                            rejectedPackets.add(packet)
                            if (rejectedItems.size < 5) {
                                rejectedItems.add("${packet.itemId}:${packet.batchId ?: "none"}:${packet.deviceId}")
                            }
                        }
                    }
                    val pendingAfter = synchronized(pendingQueueLock) {
                        pendingByDevice[address]?.size ?: 0
                    }
                    Log.i(
                        TAG,
                        "PIPELINE staged_to_pending addr=$address staged=$stagedCount accepted=$accepted rejected=$rejected pendingBefore=$pendingBefore pendingAfter=$pendingAfter firstItem=${firstItemId ?: "unknown"} rejectedItems=${rejectedItems.joinToString(",")}",
                    )
                    diagnosticsListener?.onPipelineStagedToPending(
                        address = address,
                        deviceId = resolveStableDeviceIdForAddress(address),
                        staged = stagedCount,
                        accepted = accepted,
                        rejected = rejected,
                        pendingBefore = pendingBefore,
                        pendingAfter = pendingAfter,
                        firstItemId = firstItemId,
                        rejectedItems = rejectedItems.joinToString(","),
                    )
                    if (rejectedPackets.isNotEmpty()) {
                        prependStagedPackets(address, rejectedPackets)
                        Log.i(
                            TAG,
                            "PIPELINE staged_to_pending deferred addr=$address rejected=${rejectedPackets.size} pendingBefore=$pendingBefore pendingAfter=$pendingAfter firstRejected=${rejectedPackets.first().itemId}",
                        )
                    }
                    val dtnTypes = LinkedHashSet<String>()
                    rejectedPackets.forEach { packet ->
                        packet.dtnControlType?.let(dtnTypes::add)
                    }
                    synchronized(pendingQueueLock) {
                        pendingByDevice[address]?.forEach { packet ->
                            packet.dtnControlType?.let(dtnTypes::add)
                        }
                    }
                    dtnTypes.forEach { type ->
                        traceDtnControlQueueState(
                            address = address,
                            type = type,
                            stage = "staged_to_pending",
                            reason = "accepted=$accepted rejected=$rejected",
                        )
                    }
                    if (accepted == 0) {
                        Log.w(TAG, "PIPELINE enqueue_deferred_all addr=$address pendingAfter=$pendingAfter")
                        diagnosticsListener?.onPipelineEnqueueDropAll(address)
                        if (pendingAfter == 0) {
                            workerHandler.postDelayed({
                                if (!running) return@postDelayed
                                postLabel(address, PipelineLabel.ENQUEUE)
                            }, 250L)
                        }
                        return
                    }
                } else {
                    if (connectState(address) == CONNECT_STATE_CONNECTED && clientByDevice[address] != null) {
                        maybePullDispatchJobForAddress(address, reason = "pipeline_enqueue_idle")
                    }
                }
                when (connectState(address)) {
                    CONNECT_STATE_CONNECTED -> triggerWritePump(address)
                    CONNECT_STATE_CONNECTING -> {
                        // Keep queue staged while ongoing connect completes.
                    }
                    else -> postLabel(address, PipelineLabel.CONNECT)
                }
            }
            PipelineLabel.RETRY -> {
                pendingRetryReasonByAddress.remove(address)?.let { reason ->
                    recordConnectFailure(address, reason)
                }
                when (connectState(address)) {
                    CONNECT_STATE_CONNECTED -> triggerWritePump(address)
                    CONNECT_STATE_CONNECTING -> {
                        // Avoid reconnect spam while current attempt is still in-flight.
                    }
                    else -> postLabel(address, PipelineLabel.CONNECT)
                }
            }
            PipelineLabel.CLEANUP -> {
                val mode = pendingCleanupModeByAddress.remove(address) ?: CleanupMode.TIMEOUT_CHECK
                val cleanupReason = pendingCleanupReasonByAddress.remove(address)
                    ?: if (mode == CleanupMode.FORCE) "force_cleanup_unspecified" else "timeout_check"
                if (mode == CleanupMode.FORCE) {
                    val connection = clientByDevice[address] ?: connectingClient[address]
                    if (connection != null) {
                        cleanupConnection(address, connection, closeQueue = false, reason = cleanupReason)
                    } else {
                        connecting.remove(address)
                        connectStartedAtMs.remove(address)
                        setConnectState(address, CONNECT_STATE_DISCONNECTED, "cleanup_force_no_connection")
                        pulledDispatchJobByAddress.remove(address)
                        diagnosticsListener?.onGattDisconnectReason(
                            address = address,
                            origin = "local_cleanup_no_connection",
                            reason = cleanupReason,
                            status = null,
                            newState = null,
                        )
                        if (running) {
                            postLabel(address, PipelineLabel.ENQUEUE)
                        }
                    }
                    return
                }
                val now = System.currentTimeMillis()
                val startedAt = connectStartedAtMs[address] ?: return
                if (now - startedAt > CONNECT_TIMEOUT_MS && connectState(address) == CONNECT_STATE_CONNECTING) {
                    setConnectState(address, CONNECT_STATE_DISCONNECTED, "connect_timeout_cleanup")
                    markItemBackoff(address, "connect_timeout")
                    if (!TEST_DISABLE_CONNECT_TIMEOUT_CLEANUP) {
                        val connection = connectingClient[address] ?: clientByDevice[address]
                        if (connection != null) {
                            recordConnectFailure(address, "timeout")
                            cleanupConnection(address, connection, closeQueue = false, reason = "connect_timeout_cleanup")
                        }
                    }
                    postLabel(address, PipelineLabel.RETRY)
                }
            }
        }
    }

    private fun pruneRoundRobinQueue(nowMs: Long) {
        if (rrDeviceQueue.isEmpty()) return
        val fresh = ArrayDeque<String>()
        rrDeviceQueue.forEach { address ->
            val lastSeen = lastSeenAtMs[address]
            if (lastSeen != null && nowMs - lastSeen < DEVICE_TTL_MS) {
                fresh.addLast(address)
            } else {
                rrDeviceSet.remove(address)
                lastSeenRssi.remove(address)
            }
        }
        rrDeviceQueue = fresh
    }

    private fun updateAdaptiveDutyCycle() {
        if (backpressureActive) return
        val snapshot = BleTrafficStats.snapshot()
        val last = lastTrafficSnapshot
        lastTrafficSnapshot = snapshot
        if (last == null) return
        val uploadedDelta = snapshot.uploaded - last.uploaded
        val downloadedDelta = snapshot.downloaded - last.downloaded
        val receivedDelta = snapshot.receivedPackets - last.receivedPackets
        val traffic = uploadedDelta > 0 || downloadedDelta > 0 || receivedDelta > 0

        if (adaptiveState == AdaptiveState.BASE) {
            baseScanMode = BleConfig.scanMode
            baseScanReportDelayMs = BleConfig.scanReportDelayMs
            baseAdvertiseMode = BleConfig.advertiseMode
            baseAdvertiseTxPower = BleConfig.advertiseTxPower
        }

        if (traffic) {
            idleIntervals = 0
            if (adaptiveState != AdaptiveState.ACTIVE) {
                BleConfig.scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY
                BleConfig.scanReportDelayMs = 0L
                BleConfig.advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                BleConfig.advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
                updateScanSettings()
                updateAdvertisingSettings()
                adaptiveState = AdaptiveState.ACTIVE
                Log.i(TAG, "Adaptive duty: active traffic, low-latency scan/advertise")
            }
            return
        }

        idleIntervals += 1
        if (idleIntervals < IDLE_INTERVALS_THRESHOLD) return
        if (baseScanMode == ScanSettings.SCAN_MODE_LOW_LATENCY) {
            adaptiveState = AdaptiveState.BASE
            return
        }
        if (adaptiveState != AdaptiveState.IDLE) {
            BleConfig.scanMode = ScanSettings.SCAN_MODE_LOW_POWER
            BleConfig.scanReportDelayMs = IDLE_SCAN_REPORT_DELAY_MS
            BleConfig.advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            BleConfig.advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_LOW
            updateScanSettings()
            updateAdvertisingSettings()
            adaptiveState = AdaptiveState.IDLE
            Log.i(TAG, "Adaptive duty: idle, low-power scan/advertise")
            diagnosticsListener?.onAdaptiveDutyState(
                state = "idle",
                scanMode = BleConfig.scanMode,
                reportDelayMs = BleConfig.scanReportDelayMs,
                advertiseMode = BleConfig.advertiseMode,
                advertiseTxPower = BleConfig.advertiseTxPower,
            )
        }
    }

    private fun canForwardOpaque(rawJson: String, nowSeconds: Long): Boolean {
        val envelope = parseEnvelope(rawJson)
        if (envelope != null) {
            return hasLiveTtl(envelope.ts, envelope.ttl ?: envelope.dtn?.ttl_seconds, nowSeconds)
        }
        val batch = parseAppBatch(rawJson) ?: return false
        if (!AppBatchCrypto.verify(batch)) return false
        if (batch.gid.isNullOrBlank()) return false
        if (batch.items.isEmpty() && batch.items_v2.isEmpty()) return false
        return hasLiveTtl(batch.ts, batch.ttl ?: batch.dtn?.ttl_seconds, nowSeconds)
    }

    private fun hasLiveTtl(createdAtSeconds: Long, ttlSeconds: Int?, nowSeconds: Long): Boolean {
        val ttl = ttlSeconds ?: return false
        if (ttl <= 0) return false
        val expiresAt = createdAtSeconds + ttl
        return expiresAt > nowSeconds
    }

    private fun parseEnvelope(rawJson: String): EventEnvelope? {
        return try {
            CanonicalJson.json.decodeFromString(EventEnvelope.serializer(), rawJson)
        } catch (_: Exception) {
            null
        }
    }

    private fun maxChunkSizeFor(address: String): Int {
        val base = maxChunkByDevice[address] ?: BleConfig.defaultGattPayloadBytes
        val deviceId = resolveStableDeviceIdForAddress(address)
        val nowMs = System.currentTimeMillis()
        if (deviceId != null && isPost133RecoveryActive(deviceId, nowMs)) {
            return minOf(base, BleConfig.defaultGattPayloadBytes)
        }
        return base
    }

    private fun setMaxChunkSize(address: String, value: Int) {
        val safe = value.coerceAtLeast(BleConfig.defaultGattPayloadBytes)
        maxChunkByDevice[address] = safe
    }

    private fun requestMtuIfSupported(connection: BleClientConnection) {
        val requested = BleConfig.desiredMtu
        val ok = clientBackend.requestMtu(connection, requested)
        if (!ok) {
            Log.w(TAG, "GATT requestMtu failed addr=${connection.address} requested=$requested")
        }
    }

    private fun recordConnectFailure(address: String, reason: String) {
        val deviceId = lastSeenDeviceId[address] ?: "addr:$address"
        val state = stateFor(address)
        val failures = state.failureCount + 1
        state.failureCount = failures
        state.lastFailureReason = reason
        val now = System.currentTimeMillis()
        val baseDelay = CONNECT_RETRY_BASE_MS * (1L shl (failures - 1).coerceAtMost(5))
        val jitter = (Math.random() * CONNECT_RETRY_JITTER_MS).toLong()
        var delay = (baseDelay + jitter).coerceAtMost(CONNECT_RETRY_MAX_MS)
        if (reason.contains("status=133")) {
            applyQuietAfter133(deviceId, now)
        }
        if (failures >= CONNECT_FAIL_SKIP_THRESHOLD) {
            delay = maxOf(delay, CONNECT_FAIL_SKIP_MS)
        }
        val nextAllowed = now + delay
        state.nextConnectAllowedAt = maxOf(state.nextConnectAllowedAt, nextAllowed)
        val rotated = rotateAddressAfterFailure(deviceId, address, now)
        if (rotated == null && failures >= CONNECT_FAIL_SKIP_THRESHOLD) {
            synchronized(targetPoolLock) {
                if (mainTargetDeviceIds.remove(deviceId)) {
                    potentialTargetDeviceIds.remove(deviceId)
                    potentialTargetDeviceIds.add(deviceId)
                    prunePotentialTargetsLocked(now)
                }
            }
        }
        Log.w(TAG, "GATT connect backoff id=$deviceId addr=$address failures=$failures delayMs=$delay reason=$reason")
        diagnosticsListener?.onGattConnectFailed(address, reason, failures)
        diagnosticsListener?.onConnectBackoff(address, failures, delay, reason)
    }

    private fun resetConnectFailures(address: String) {
        val deviceId = lastSeenDeviceId[address] ?: "addr:$address"
        candidateAddressesForDevice(deviceId, System.currentTimeMillis()).forEach { candidateAddress ->
            addressStateByAddress[candidateAddress]?.apply {
                failureCount = 0
                nextConnectAllowedAt = 0L
                lastFailureReason = null
            }
            lastSingleProbeAttemptAtMsByAddress.remove(candidateAddress)
        }
        addressStateByAddress[address]?.apply {
            failureCount = 0
            nextConnectAllowedAt = 0L
            lastFailureReason = null
        }
        lastSingleProbeAttemptAtMsByAddress.remove(address)
        synchronized(targetPoolLock) {
            if (potentialTargetDeviceIds.remove(deviceId) || !mainTargetDeviceIds.contains(deviceId)) {
                if (mainTargetDeviceIds.size >= MTL_CAP) {
                    val oldest = mainTargetDeviceIds.firstOrNull()
                    if (oldest != null) {
                        mainTargetDeviceIds.remove(oldest)
                        potentialTargetDeviceIds.remove(oldest)
                        potentialTargetDeviceIds.add(oldest)
                    }
                }
                mainTargetDeviceIds.remove(deviceId)
                mainTargetDeviceIds.add(deviceId)
            } else {
                mainTargetDeviceIds.remove(deviceId)
                mainTargetDeviceIds.add(deviceId)
            }
        }
    }

    override fun setDiagnosticsListener(listener: MeshTransportDiagnosticsListener?) {
        diagnosticsListener = listener
    }

    override fun setNearbyRoomAdvertisementProvider(provider: NearbyRoomAdvertisementProvider?) {
        nearbyRoomAdvertisementProvider = provider
    }

    override fun setNearbyRoomDiscoveryListener(listener: NearbyRoomDiscoveryListener?) {
        nearbyRoomDiscoveryListener = listener
    }

    override fun setNearbyForegroundMode(enabled: Boolean, reason: String) {
        val changed = nearbyForegroundMode != enabled
        nearbyForegroundMode = enabled
        nearbyForegroundReason = reason
        val pausedCount = countPausedNonNearbyPackets()
        if (enabled && changed) {
            val nowMs = System.currentTimeMillis()
            activePendingPacketByAddress.entries.forEach { (address, packet) ->
                if (!packet.isAck && !BleNearbyTrafficPolicy.isNearbyRoomTraffic(packet.trafficHint)) {
                    foregroundDrainByAddress[address] = ForegroundDrain(packet.itemId, packet.batchId, nowMs)
                    foregroundDiagnostic(NearbyForegroundDiagnosticLabels.DRAIN_START, "runtime_state=${foregroundRuntimeState()} addr=$address active_item_id=${packet.itemId} active_batch_id=${packet.batchId ?: "none"} traffic_class=${trafficClass(packet)} chunks_completed=unknown chunks_total=${packet.totalChunks} pending_queue_count=$pausedCount")
                }
            }
            if (foregroundDrainByAddress.isEmpty()) {
                foregroundDiagnostic(NearbyForegroundDiagnosticLabels.DRAIN_COMPLETE, "runtime_state=${foregroundRuntimeState()} reason=no_active_non_nearby duration_ms=0")
            }
            synchronized(pendingQueueLock) {
                pendingByDevice.values.flatten().filter { !it.isAck && !BleNearbyTrafficPolicy.isNearbyRoomTraffic(it.trafficHint) }
                    .forEach { pausedAtMsByItemId.putIfAbsent(it.itemId, nowMs) }
            }
            foregroundDiagnostic(NearbyForegroundDiagnosticLabels.QUEUE_PRESERVED, "runtime_state=${foregroundRuntimeState()} pending_count=$pausedCount whole_envelope=true")
        }
        Log.i(
            TAG,
            "NEARBY_FOREGROUND mode=${if (enabled) "acquire" else "release"} changed=$changed reason=$reason paused=$pausedCount active=${activePendingPacketByAddress.size}",
        )
        if (!enabled && changed) {
            val addresses = LinkedHashSet<String>()
            synchronized(pendingQueueLock) { addresses.addAll(pendingByDevice.keys) }
            synchronized(schedulerLock) {
                addresses.addAll(stagedEnqueueByAddress.keys)
                addresses.addAll(heldEnqueueByAddress.keys)
            }
            addresses.forEach { address ->
                offerDeviceForRoundRobin(address)
                postLabel(address, PipelineLabel.ENQUEUE)
                postLabel(address, PipelineLabel.WRITE_NEXT)
            }
            requestMaybeConnectNext()
            resumeForegroundDeferredDtnTasks(reason = "nearby_foreground_release")
            foregroundDiagnostic(NearbyForegroundDiagnosticLabels.SCHEDULER_RESUME, "previous_state=${reason.substringBefore("_to_")} next_state=${reason.substringAfter("_to_").substringBefore(':')} reason=${reason.substringAfter(':', "unknown")} addresses_woken=${addresses.size} queue_count=$pausedCount labels_posted=ENQUEUE,WRITE_NEXT,CONNECT")
            Log.i(TAG, "NEARBY_FOREGROUND scheduler_resume addresses=${addresses.size}")
        }
    }

    private fun resumeForegroundDeferredDtnTasks(reason: String, peerId: String? = null) {
        if (nearbyForegroundMode) return
        val engine = dtnSyncEngine ?: return
        engine.foregroundDeferredSessionTasks()
            .filter { peerId == null || it.peerId == peerId }
            .forEach { task ->
                val key = "${task.peerId}|${task.gid}|${task.sessionId}|${task.taskKey}"
                val claim = engine.claimForegroundDeferredTask(task)
                if (claim != DtnSyncEngine.DeferredResumeClaimResult.CLAIMED) {
                    dtnDiagnostics?.log(
                        DtnDiagnosticEvent(
                            label = "DTN deferred resume ignored",
                            type = task.packet.type,
                            direction = "deferred_resume_ignored",
                            peerId = task.peerId,
                            gid = task.gid,
                            sessionId = task.sessionId,
                            reason = claim.name.lowercase(),
                        )
                    )
                    return@forEach
                }
                val attempt = (dtnForegroundRetryAttemptsBySessionKey[key] ?: 0) + 1
                dtnDiagnostics?.log(
                    DtnDiagnosticEvent(
                        label = "DTN deferred resume attempt",
                        type = task.packet.type,
                        direction = "deferred_resume_attempt",
                        peerId = task.peerId,
                        gid = task.gid,
                        sessionId = task.sessionId,
                        reason = "task_key=${task.taskKey} trigger=$reason attempt=$attempt",
                    )
                )
                val address = resolveAddressForDeviceId(task.peerId)
                if (address == null) {
                    engine.releaseForegroundDeferredTaskClaim(task)
                    logDeferredDtnResumePostponed(task.packet, task.peerId, task.gid, task.sessionId, "address_unavailable")
                    scheduleForegroundDeferredDtnRetry(key, task.peerId)
                    return@forEach
                }
                val queued = runCatching {
                    broadcastEnvelopeToTargets(
                        DtnControlCodec.encode(task.packet),
                        listOf(address),
                        preferDtnFastPath = true,
                    )
                }.getOrDefault(false)
                if (!queued) {
                    engine.releaseForegroundDeferredTaskClaim(task)
                    logDeferredDtnResumePostponed(task.packet, task.peerId, task.gid, task.sessionId, "enqueue_failed")
                    scheduleForegroundDeferredDtnRetry(key, task.peerId)
                    return@forEach
                }
                if (!engine.completeForegroundDeferredTaskQueued(task)) {
                    dtnDiagnostics?.log(DtnDiagnosticEvent(label = "DTN deferred resume ignored", type = task.packet.type, direction = "deferred_resume_ignored", peerId = task.peerId, gid = task.gid, sessionId = task.sessionId, reason = "task_changed_after_enqueue"))
                    return@forEach
                }
                dtnForegroundRetryAttemptsBySessionKey.remove(key)
                dtnForegroundRetryScheduledKeys.remove(key)
                dtnDiagnostics?.log(
                    DtnDiagnosticEvent(
                        label = "DTN ${task.packet.type} deferred_resume",
                        type = task.packet.type,
                        direction = "deferred_resume",
                        peerId = task.peerId,
                        gid = task.gid,
                        sessionId = task.sessionId,
                        reason = reason,
                    )
                )
            }
    }

    private fun scheduleForegroundDeferredDtnRetry(key: String, peerId: String) {
        val nextAttempt = (dtnForegroundRetryAttemptsBySessionKey[key] ?: 0) + 1
        if (nextAttempt > MAX_DTN_FOREGROUND_DEFERRED_RETRIES) {
            dtnDiagnostics?.log(DtnDiagnosticEvent(label = "DTN deferred resume retry exhausted", type = "session_task", direction = "deferred_resume_exhausted", peerId = peerId, reason = "attempts=$MAX_DTN_FOREGROUND_DEFERRED_RETRIES next_owner=peer_reappearance"))
            return
        }
        dtnForegroundRetryAttemptsBySessionKey[key] = nextAttempt
        if (!dtnForegroundRetryScheduledKeys.add(key)) return
        workerHandler.postDelayed(
            {
                dtnForegroundRetryScheduledKeys.remove(key)
                resumeForegroundDeferredDtnTasks(reason = "scheduled_retry", peerId = peerId)
            },
            DTN_FOREGROUND_DEFERRED_RETRY_MS,
        )
    }

    private fun logDeferredDtnResumePostponed(
        packet: DtnControlPacket,
        peerId: String,
        gid: String,
        sessionId: String,
        reason: String,
    ) {
        dtnDiagnostics?.log(
            DtnDiagnosticEvent(
                label = "DTN deferred resume postponed",
                type = packet.type,
                direction = "deferred_resume_postponed",
                peerId = peerId,
                gid = gid,
                sessionId = sessionId,
                reason = "$reason retry_consumed=false",
            )
        )
    }

    private fun countPausedNonNearbyPackets(): Int {
        fun count(queue: ArrayDeque<PendingPacket>): Int = queue.count {
            !it.isAck && !BleNearbyTrafficPolicy.isNearbyRoomTraffic(it.trafficHint)
        }
        var total = synchronized(pendingQueueLock) {
            pendingByDevice.values.sumOf(::count)
        }
        total += synchronized(schedulerLock) {
            stagedEnqueueByAddress.values.sumOf(::count) +
                heldEnqueueByAddress.values.sumOf(::count)
        }
        return total
    }

    override fun refreshNearbyRoomAdvertisement(): Boolean {
        if (!running || meshMode != MeshMode.FULL_RELAY) {
            diagnosticsListener?.onNearbyRoomAdvertisementRefreshRejected(
                running = running,
                meshMode = meshMode.name,
                reason = when {
                    !running -> "transport_not_running"
                    meshMode != MeshMode.FULL_RELAY -> "mesh_mode_not_full_relay"
                    else -> "unknown"
                },
            )
            return false
        }
        workerHandler.post {
            if (!running || meshMode != MeshMode.FULL_RELAY) return@post
            if (advertiseCallback != null) {
                stopAdvertising()
            }
            startAdvertising(force = true)
        }
        return true
    }

    override fun requestDiscoveryBurst(durationMs: Long) {
        workerHandler.post {
            val clampedDurationMs = durationMs.coerceAtLeast(1_000L)
            if (!running || backpressureActive) {
                diagnosticsListener?.onDiscoveryBurst(
                    stage = "skipped",
                    durationMs = clampedDurationMs,
                    scanMode = BleConfig.scanMode,
                    reportDelayMs = BleConfig.scanReportDelayMs,
                    token = discoveryBurstToken,
                    reason = if (!running) "transport_not_running" else "backpressure_active",
                )
                return@post
            }
            val previousScanMode = BleConfig.scanMode
            val previousReportDelayMs = BleConfig.scanReportDelayMs
            discoveryBurstToken += 1L
            val token = discoveryBurstToken
            BleConfig.scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY
            BleConfig.scanReportDelayMs = 0L
            diagnosticsListener?.onDiscoveryBurst(
                stage = "start",
                durationMs = clampedDurationMs,
                scanMode = BleConfig.scanMode,
                reportDelayMs = BleConfig.scanReportDelayMs,
                token = token,
            )
            updateScanSettings()
            workerHandler.postDelayed({
                if (discoveryBurstToken != token || backpressureActive) {
                    diagnosticsListener?.onDiscoveryBurst(
                        stage = "restore_skipped",
                        durationMs = clampedDurationMs,
                        scanMode = BleConfig.scanMode,
                        reportDelayMs = BleConfig.scanReportDelayMs,
                        token = token,
                        reason = if (discoveryBurstToken != token) "token_superseded" else "backpressure_active",
                    )
                    return@postDelayed
                }
                BleConfig.scanMode = previousScanMode
                BleConfig.scanReportDelayMs = previousReportDelayMs
                diagnosticsListener?.onDiscoveryBurst(
                    stage = "restore",
                    durationMs = clampedDurationMs,
                    scanMode = BleConfig.scanMode,
                    reportDelayMs = BleConfig.scanReportDelayMs,
                    token = token,
                )
                updateScanSettings()
            }, clampedDurationMs)
        }
    }

    private fun maybeLogScanStats() {
        val now = System.currentTimeMillis()
        if (now - lastScanLogAtMs < 2_000L) return
        lastScanLogAtMs = now
        diagnosticsListener?.onScanStats(scanCallbacks, scanResults, scanMatched)
    }

    companion object {
        private const val NEARBY_FOREGROUND_RELAY_RECHECK_MS = 5_000L
        private const val NEARBY_FOREGROUND_QUEUE_RESERVE_PACKETS = 192
        private const val TAG = "BleMeshTransport"
        private val ADV_SOFT_RETRY_DELAYS = listOf(1000L, 3000L, 10_000L)
        private const val ADV_RECHECK_INTERVAL_MS = 5 * 60 * 1000L
        private val MFG_PREFIX = byteArrayOf(0x43, 0x4C)
        private const val INVALID_BLE_ADDRESS = "02:00:00:00:00:00"
        private const val DEVICE_TTL_MS = 30_000L
        private const val MAX_BROADCAST_TARGETS = 2
        private const val MTL_CAP = 10
        private const val PTL_CAP_LOW = 24
        private const val PTL_CAP_MID = 48
        private const val PTL_CAP_HIGH = 96
        private const val PTL_CAP_MAX = 160
        private const val PTL_CAP_LOW_RAM_BYTES = 256L * 1024L * 1024L
        private const val PTL_CAP_MID_RAM_BYTES = 768L * 1024L * 1024L
        private const val PTL_CAP_HIGH_RAM_BYTES = 1536L * 1024L * 1024L
        private const val SINGLE_TARGET_PROBE_MS = 1_500L
        private const val DEVICEID_DWELL_MS = 10_000L
        private const val DEVICEID_DWELL_MS_WITH_PENDING_DATA = 4_000L
        private const val FRESH_WINDOW_MS = 15 * 60 * 1000L
        private const val DEVICEID_ROTATION_DEBOUNCE_MS = 6_000L
        private const val ROTATION_RECOVER_GRACE_MS = 8_000L
        private const val ROTATION_RECOVERY_TIMEOUT_GRACE_MS = 12_000L
        private const val SIBLING_PROGRESS_TIMEOUT_GRACE_MS = 60_000L
        private const val DEVICEID_MISSING_GRACE_MS = 2_500L
        private const val QUIET_AFTER_DISCONNECT_MS = 5_000L
        private const val QUIET_AFTER_133_MS = 20_000L
        private const val STATUS_133_DUPLICATE_RETRY_SUPPRESS_MS = 1_500L
        private const val POST_133_RECOVERY_MS = 30_000L
        private const val POST_133_MIN_WRITE_GAP_MS = 120L
        private const val POST_133_ORPHAN_GRACE_MS = 20_000L
        private const val POST_133_ORPHAN_GRACE_MIN_PROGRESS_PERCENT = 40
        private const val ACTIVE_PEER_GRACE_MS = 120_000L
        private const val ACTIVE_PEER_MAX_SEEN_AGE_MS = 10 * 60_000L
        private const val NO_CANDIDATE_RECOVERY_THRESHOLD = 3
        private const val NO_CANDIDATE_RECOVERY_COOLDOWN_MS = 5_000L
        private const val GRACE_LOG_INTERVAL_MS = 10_000L
        private const val DEDUPE_TTL_SECONDS = 600L
        private const val RELAY_COMPENSATION_WINDOW_MS = 15_000L
        private const val RELAY_COMPENSATION_MAX_ATTEMPTS = 1
        private const val RELAY_COMPENSATION_STATE_TTL_MS = 60_000L
        private const val BATCH_FORWARD_MAX_ATTEMPTS = 2
        private const val BATCH_FORWARD_RETRY_DELAY_MS = 6_000L
        private const val BATCH_FORWARD_STATE_TTL_MS = 30_000L
        private const val BATCH_FORWARD_SOURCE_RESOLVE_RETRY_DELAY_MS = 1_200L
        private const val BATCH_FORWARD_SOURCE_RESOLVE_RETRY_MAX = 3
        private const val DELAYED_RELAY_DELAY_MS = 10_000L
        private const val DELAYED_RELAY_JITTER_MS = 1_200L
        private const val DELAYED_RELAY_BUSY_DEFER_MS = 5_000L
        private const val DELAYED_RELAY_MAX_BUSY_DEFERS = 1
        private const val DELAYED_RELAY_MAX_AGE_MS = 30_000L
        private const val DELAYED_RELAY_MAX_PAYLOAD_BYTES = 2_048
        private const val ROTATION_RECOVERY_UNNEGOTIATED_CHUNK_LIMIT = 1
        private const val MAX_UNNEGOTIATED_CHUNKS_PER_DATA_SEND = 40
        private const val FIRST_BATCH_WARMUP_MS = 1_500L
        private const val STALE_SCAN_RESULT_MS = 10_000L
        private const val CLEANUP_INTERVAL_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 18_000L
        private const val CONNECT_STATE_DISCONNECTED = 0
        private const val CONNECT_STATE_CONNECTING = 1
        private const val CONNECT_STATE_CONNECTED = 2
        private const val CONNECT_RETRY_BASE_MS = 2_000L
        private const val CONNECT_RETRY_MAX_MS = 30_000L
        private const val CONNECT_RETRY_JITTER_MS = 500L
        private const val CONNECT_ATTEMPT_COOLDOWN_MS = 800L
        private const val CONNECT_FAIL_SKIP_THRESHOLD = 3
        private const val CONNECT_FAIL_SKIP_MS = 30_000L
        private const val ITEM_BACKOFF_MS = 8_000L
        private const val ITEM_SEND_STATE_TTL_MS = 15 * 60_000L
        private const val ITEM_SEND_STATE_STALE_MS = 5 * 60_000L
        private const val QUEUED_NO_WRITE_ATTEMPT_TIMEOUT_MS = 15_000L
        private const val QUEUED_NO_PROGRESS_TIMEOUT_MS = 30_000L
        private const val QUEUED_NO_WRITE_REBIND_MAX_ATTEMPTS = 3
        private const val IN_PROGRESS_STUCK_WATCHDOG_MS = 45_000L
        private const val IN_PROGRESS_STUCK_LOG_COOLDOWN_MS = 30_000L
        private const val RECENT_CONNECT_GRACE_MS = 10_000L
        private const val ENQUEUE_LABEL_COOLDOWN_MS = 300L
        private const val STALLED_WRITE_TIMEOUT_MS = 15_000L
        private const val HARD_RESET_COOLDOWN_MS = 20_000L
        private const val START_STUCK_TIMEOUT_MS = 15_000L
        private const val START_STUCK_COOLDOWN_MS = 30_000L
        private const val START_STUCK_TX_GRACE_MS = 5_000L
        private const val AUTO_RECOVER_SCAN_TIMEOUT_MS = 30_000L
        private const val AUTO_RECOVER_SCAN_TIMEOUT_LOW_POWER_MS = 60_000L
        private const val AUTO_RECOVER_SCAN_COOLDOWN_MS = 30_000L
        private const val AUTO_RECOVER_STREAK_LIMIT = 3
        private const val AUTO_RECOVER_STREAK_BACKOFF_MS = 60_000L
        private const val LOW_POWER_REPORT_DELAY_THRESHOLD_MS = 5_000L
        private const val CONNECT_GUARD_SCAN_STALE_MS = 45_000L
        private const val RSSI_MIN_THRESHOLD_DBM = -85
        private const val BACKPRESSURE_HIGH_WATERMARK_BYTES = 12_000
        private const val BACKPRESSURE_LOW_WATERMARK_BYTES = 4_000
        private const val BACKPRESSURE_STEP_BYTES = 4_000
        private const val MAX_BACKPRESSURE_DELAY_MS = 800L
        private const val BACKPRESSURE_SCAN_REPORT_DELAY_MS = 5_000L
        private const val POST_CONNECT_WRITE_DELAY_MS = 200L
        private const val WRITE_PACING_DELAY_MS = 15L
        private const val WRITE_PACING_JITTER_MS = 10L
        private const val MAX_DATA_QUEUE_PER_DEVICE = 48
        private const val MAX_DTN_CONTROL_QUEUE_PER_DEVICE = 160
        private const val MAX_ACK_QUEUE_PER_DEVICE = 24
        private const val MAX_QUEUE_PER_DEVICE_HARD_CAP = 72
        private const val MAX_GLOBAL_PENDING_PACKETS = 220
        private const val ACK_SEND_QUOTA_DATA_BURST = 6
        private const val ACK_PRIORITY_FLOOR = 9
        private const val MAX_CONCURRENT_GATT = 3
        private const val REDUCED_CONCURRENT_GATT = 1
        private const val REDUCED_GATT_WINDOW_MS = 30_000L
        private const val PEER_FAIRNESS_LOG_COOLDOWN_MS = 2_000L
        private const val CONNECT_CAP_BLOCKED_LOG_COOLDOWN_MS = 2_000L
        private const val CANDIDATE_SNAPSHOT_LOG_COOLDOWN_MS = 5_000L
        private const val TARGET_SELECTION_DECISION_LOG_COOLDOWN_MS = 5_000L
        private const val NEARBY_BREAKDOWN_LOG_COOLDOWN_MS = 5_000L
        private const val NO_JOB_SKIP_STREAK_THRESHOLD = 2
        private const val NO_JOB_SKIP_STREAK_WINDOW_MS = 30_000L
        private const val NO_JOB_SKIP_SUPPRESS_MS = 120_000L
        private const val OLD_LANE_RECENT_PROGRESS_GRACE_MS = 12_000L
        private const val IDLE_DISCONNECT_MS_BUSY = 30_000L
        private const val IDLE_DISCONNECT_MS_MESSAGE_ACTIVE = 60_000L
        private const val IDLE_DISCONNECT_MS_IDLE = 180_000L
        private const val PENDING_TARGETS_BUSY_THRESHOLD = 1
        private const val RECENT_DATA_ACTIVITY_WINDOW_MS = 60_000L
        private const val ROTATION_ADDR_COOLDOWN_MS = 12_000L
        private const val ROTATION_DEVICE_COOLDOWN_MS = 4_000L
        private const val CLEANUP_ADDR_COOLDOWN_MS = 8_000L
        private const val CLEANUP_DEVICE_COOLDOWN_MS = 3_000L
        private const val SELECTION_ROTATION_ADDR_COOLDOWN_MS = 6_000L
        private const val SELECTION_ROTATION_DEVICE_COOLDOWN_MS = 2_000L
        private const val MAX_CHUNKS_PER_CYCLE = 16
        private const val SEND_CYCLE_WINDOW_MS = 1_000L
        private const val SEND_CYCLE_PAUSE_MS = 50L
        private const val RX_WINDOW_PERIOD_MS = 5_000L
        private const val RX_WINDOW_PAUSE_MS = 1_500L
        private const val RX_WINDOW_LOG_INTERVAL_MS = 5_000L
        private const val IDLE_INTERVALS_THRESHOLD = 3
        private const val IDLE_SCAN_REPORT_DELAY_MS = 5_000L
        private const val DTN_HELLO_INTERVAL_MS = 5 * 60 * 1000L
        private const val DTN_HELLO_MAX_SCOPES = 8
        private const val DTN_FOREGROUND_DEFERRED_RETRY_MS = 2_000L
        private const val MAX_DTN_FOREGROUND_DEFERRED_RETRIES = 5
        private const val OPAQUE_RETRY_DRAIN_INTERVAL_MS = 3_000L
        private const val OPAQUE_RETRY_DRAIN_LIMIT = 16
        private const val OPAQUE_RETRY_PER_TARGET_LIMIT = 4
        private const val OPAQUE_RETRY_HOT_PER_TARGET_LIMIT = 12
        private const val OPAQUE_RETRY_HOT_RETRY_DELAY_MS = 1_000L
        private const val OPAQUE_RETRY_HOT_RETRY_SECOND_DELAY_MS = 8_000L
        private const val OPAQUE_RETRY_HOT_TTL_MS = 2 * 60 * 1000L
        private const val INBOUND_LOG_INTERVAL_MS = 2_000L
        private const val TEST_DISABLE_CONNECT_TIMEOUT_CLEANUP = false
    }

    private enum class AdaptiveState { BASE, ACTIVE, IDLE }

    private fun sampleTargetAddresses(addresses: Set<String>): String {
        if (addresses.isEmpty()) return ""
        return addresses.take(2).joinToString(separator = ",") { formatAddressWithId(it) }
    }

    private fun formatAddressWithId(address: String): String {
        val id = lastSeenDeviceId[address]
        return if (id.isNullOrBlank()) {
            address
        } else {
            "$address(id=$id)"
        }
    }

    private fun selfDeviceIdShort(): String? = localDeviceId

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
