package app.peermesh.ble

import android.content.Context
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.util.Log
import app.peermesh.ActiveGroupManager
import app.peermesh.DeviceIdProvider
import app.peermesh.OutboundBatchDispatcher
import app.peermesh.PeerMeshCapabilities
import app.peermesh.PeerMeshPrefs
import app.peermesh.R
import app.peermesh.core.transport.EnvelopeReceiver
import app.peermesh.core.transport.EnvelopeReceiveResult
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.dtn.RoomDtnCarrierDataReceiver
import app.peermesh.data.dtn.RoomDtnCarrierDataSource
import app.peermesh.data.dtn.RoomDtnCarrierManifestKeyResolver
import app.peermesh.data.dtn.RoomDtnCarrierManifestOfferSource
import app.peermesh.data.dtn.RoomDtnCarrierManifestStore
import app.peermesh.data.dtn.RoomDtnInventoryStore
import app.peermesh.data.dtn.RoomOpaqueRelayStore
import app.peermesh.data.dtn.RoomDtnPeerManifestKeyStore
import app.peermesh.data.dtn.RoomDtnSyncStateStore
import app.peermesh.relay.RelaySyncSettings
import app.peermesh.security.crypto.SecureKeyStore
import app.peermesh.transport.ble.BleConfig
import app.peermesh.transport.adapter.MeshTransport
import app.peermesh.transport.ble.BleMeshTransport
import app.peermesh.transport.ble.dtn.DtnDataReceiver
import app.peermesh.transport.ble.dtn.DtnDiagnosticEvent
import app.peermesh.transport.ble.dtn.DtnDiagnosticsSink
import app.peermesh.transport.ble.dtn.DtnCloudStatus
import app.peermesh.transport.ble.dtn.DtnCloudStates
import app.peermesh.transport.ble.dtn.DtnLocalManifestKeyProvider
import app.peermesh.transport.ble.dtn.DtnManifestKeyAlgorithms
import app.peermesh.transport.ble.dtn.DtnPeerManifestKey
import app.peermesh.transport.ble.dtn.DtnPeerHelloObserver
import app.peermesh.transport.ble.dtn.InMemoryDtnRepairSuppressionStore
import app.peermesh.transport.ble.dtn.DtnSyncEngine
import app.peermesh.transport.ble.dtn.DtnCarrierManifestCrypto
import app.peermesh.transport.ble.dtn.DtnCarrierManifestSigner
import app.peermesh.transport.ble.dtn.EcdsaDtnCarrierManifestVerifier
import app.peermesh.transport.ble.nordic.NordicMeshTransport
import app.peermesh.ble.DispatcherJobSource
import app.peermesh.nearby.NearbyRoomBleCoordinator
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object BleMeshRuntime {
    @Volatile
    private var transport: MeshTransport? = null
    @Volatile
    private var receiver: EnvelopeReceiver? = null
    @Volatile
    private var delegating: DelegatingReceiver? = null
    @Volatile
    private var nearbyForegroundMode: Boolean = false
    @Volatile
    private var cachedOpaqueDtnGids: List<String> = emptyList()
    @Volatile
    private var lastOpaqueDtnGidRefreshAtMs: Long = 0L
    private val opaqueDtnGidRefreshInFlight = AtomicBoolean(false)
    private val opaqueDtnGidExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dtn-sync-gid-cache").apply { isDaemon = true }
    }
    private val dtnRepairSuppressionStore = InMemoryDtnRepairSuppressionStore()

    fun init(context: Context, envelopeReceiver: EnvelopeReceiver): MeshTransport {
        synchronized(this) {
            handleBleAvailability(context)
            PeerMeshPrefs.applyBleForwardingConfig(context)
            receiver = envelopeReceiver
            val delegate = delegating ?: DelegatingReceiver().also { delegating = it }
            delegate.setDelegate(envelopeReceiver)
            if (transport == null) {
                ensureAdvertisedDeviceId(context)
                transport = buildTransport(context.applicationContext, delegate)
                transport?.setNearbyForegroundMode(nearbyForegroundMode, "runtime_init")
                transport?.setDiagnosticsListener(BleMeshDiagnosticsLogger(context.applicationContext))
                transport?.let { NearbyRoomBleCoordinator.bind(context.applicationContext, it) }
                OutboundBatchDispatcher.bindContext(context.applicationContext)
                Log.i(TAG, "BleMeshRuntime initialized backend=${transportBackendLabel()}")
            }
            return transport!!
        }
    }

    fun get(context: Context): MeshTransport {
        synchronized(this) {
            handleBleAvailability(context)
            PeerMeshPrefs.applyBleForwardingConfig(context)
            val current = transport
            if (current != null) return current
            val delegate = delegating ?: DelegatingReceiver().also { delegating = it }
            receiver?.let { delegate.setDelegate(it) }
            ensureAdvertisedDeviceId(context)
            transport = buildTransport(context.applicationContext, delegate)
            transport?.setNearbyForegroundMode(nearbyForegroundMode, "runtime_get")
            transport?.setDiagnosticsListener(BleMeshDiagnosticsLogger(context.applicationContext))
            transport?.let { NearbyRoomBleCoordinator.bind(context.applicationContext, it) }
            OutboundBatchDispatcher.bindContext(context.applicationContext)
            if (receiver == null) {
                Log.w(TAG, "BleMeshRuntime initialized without ingest receiver")
            }
            return transport!!
        }
    }

    fun updateNearbyForegroundMode(enabled: Boolean, reason: String) {
        synchronized(this) {
            nearbyForegroundMode = enabled
            transport?.setNearbyForegroundMode(enabled, reason)
        }
    }

    fun resetForActorSwitch(context: Context) {
        synchronized(this) {
            transport?.stop()
            transport = null
            BleConfig.advertisedDeviceId = null
            ActivityLog.add(
                label = "BLE actor switch",
                source = "mesh",
                message = "advertised device_id reset",
            )
            // Re-init will happen on next get/init call.
        }
    }

    fun reinitialize(context: Context, envelopeReceiver: EnvelopeReceiver): MeshTransport {
        synchronized(this) {
            transport?.stop()
            transport = null
            PeerMeshPrefs.applyBleForwardingConfig(context)
            receiver = envelopeReceiver
            val delegate = delegating ?: DelegatingReceiver().also { delegating = it }
            delegate.setDelegate(envelopeReceiver)
            ensureAdvertisedDeviceId(context)
            transport = buildTransport(context.applicationContext, delegate)
            transport?.setNearbyForegroundMode(nearbyForegroundMode, "runtime_reinitialize")
            transport?.setDiagnosticsListener(BleMeshDiagnosticsLogger(context.applicationContext))
            transport?.let { NearbyRoomBleCoordinator.bind(context.applicationContext, it) }
            OutboundBatchDispatcher.bindContext(context.applicationContext)
            Log.i(TAG, "BleMeshRuntime reinitialized backend=${transportBackendLabel()}")
            return transport!!
        }
    }

    private fun ensureAdvertisedDeviceId(context: Context) {
        val appContext = context.applicationContext
        val canonicalDeviceId = canonicalDtnPeerId(appContext)
        val canonicalBytes = deviceIdHexToBytes(canonicalDeviceId)
        val previousAdvertised = BleConfig.advertisedDeviceId
            ?.joinToString("") { byte -> "%02X".format(byte) }

        if (previousAdvertised != canonicalDeviceId) {
            BleConfig.advertisedDeviceId = canonicalBytes
            ActivityLog.add(
                label = "BLE identity canonicalized",
                source = "mesh",
                message = "field=advertised_device_id previous=${previousAdvertised ?: "unknown"} canonical=$canonicalDeviceId reason=runtime_init",
            )
        }

        persistBleLocalDeviceId(appContext, canonicalDeviceId)

        val shortId = canonicalDeviceId.take(8)
        Log.i(TAG, "BLE advertised device_id initialized id=$shortId")
        ActivityLog.add(
            label = "BLE advertised device_id",
            source = "mesh",
            message = "id=$shortId stableDeviceId=$canonicalDeviceId advertisedDeviceId=$canonicalDeviceId helloPeerId=$canonicalDeviceId",
        )
    }

    private fun canonicalDtnPeerId(context: Context): String {
        val deviceId = DeviceIdProvider.get(context.applicationContext)
        val digest = MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray())
        return digest
            .copyOf(BLE_DEVICE_ID_BYTES)
            .joinToString("") { byte -> "%02X".format(byte) }
    }

    private fun deviceIdHexToBytes(deviceId: String): ByteArray {
        return deviceId.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun normalizeBleDeviceId(value: String?): String? {
        return value
            ?.trim()
            ?.uppercase()
            ?.takeIf { candidate ->
                candidate.length == BLE_DEVICE_ID_BYTES * 2 &&
                    candidate.all { ch -> ch in '0'..'9' || ch in 'A'..'F' }
            }
    }

    private fun persistBleLocalDeviceId(context: Context, canonicalDeviceId: String) {
        val normalized = normalizeBleDeviceId(canonicalDeviceId) ?: return
        val prefs = context.applicationContext.getSharedPreferences(
            BLE_ID_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val previous = normalizeBleDeviceId(prefs.getString(KEY_LOCAL_DEVICE_ID, null))
        if (previous == normalized) return

        prefs.edit()
            .putString(KEY_LOCAL_DEVICE_ID, normalized)
            .apply()

        ActivityLog.add(
            label = "BLE identity canonicalized",
            source = "mesh",
            message = "field=local_device_id previous=${previous ?: "unknown"} canonical=$normalized reason=runtime_init",
        )
    }

    private fun buildTransport(
        context: Context,
        receiver: EnvelopeReceiver,
    ): MeshTransport {
        val db = AppDatabaseProvider.get(context.applicationContext)
        val opaqueRelayStore = RoomOpaqueRelayStore(db.opaqueDtnCapsuleDao())
        val dtnDiagnostics = buildDtnDiagnostics()
        val dtnSyncEngine = buildDtnSyncEngine(
            context.applicationContext,
            receiver,
            dtnDiagnostics,
            dtnRepairSuppressionStore,
        )
        return if (BleConfig.useNordicBackend) {
            NordicMeshTransport(
                context = context,
                receiver = receiver,
                dispatchJobSource = DispatcherJobSource(context.applicationContext),
                dtnSyncEngine = dtnSyncEngine,
                dtnActiveGidProvider = { ActiveGroupManager.getActiveGroup(context.applicationContext) },
                dtnSyncGidProvider = { dtnSyncGids(context.applicationContext) },
                dtnDiagnostics = dtnDiagnostics,
                opaqueRelayStore = opaqueRelayStore,
                dtnRepairSuppressionStore = dtnRepairSuppressionStore,
            )
        } else {
            BleMeshTransport(
                context = context,
                receiver = receiver,
                dispatchJobSource = DispatcherJobSource(context.applicationContext),
                dtnSyncEngine = dtnSyncEngine,
                dtnActiveGidProvider = { ActiveGroupManager.getActiveGroup(context.applicationContext) },
                dtnSyncGidProvider = { dtnSyncGids(context.applicationContext) },
                dtnDiagnostics = dtnDiagnostics,
                opaqueRelayStore = opaqueRelayStore,
                dtnRepairSuppressionStore = dtnRepairSuppressionStore,
            )
        }
    }

    private fun dtnSyncGids(context: Context): List<String> {
        refreshOpaqueDtnGidsAsync(context.applicationContext)
        return buildList {
            ActiveGroupManager.getActiveGroup(context)?.takeIf { it.isNotBlank() }?.let { add(it) }
            cachedOpaqueDtnGids.forEach { add(it) }
        }.distinct().take(DTN_MAX_SYNC_SCOPES)
    }

    private fun refreshOpaqueDtnGidsAsync(context: Context) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastOpaqueDtnGidRefreshAtMs < DTN_SYNC_GID_REFRESH_INTERVAL_MS) return
        if (!opaqueDtnGidRefreshInFlight.compareAndSet(false, true)) return
        lastOpaqueDtnGidRefreshAtMs = nowMs
        opaqueDtnGidExecutor.execute {
            try {
                val nowSeconds = System.currentTimeMillis() / 1000L
                cachedOpaqueDtnGids = AppDatabaseProvider.get(context)
                    .opaqueDtnCapsuleDao()
                    .listActiveGids(nowSeconds)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(DTN_MAX_SYNC_SCOPES)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to refresh DTN sync gids", t)
            } finally {
                opaqueDtnGidRefreshInFlight.set(false)
            }
        }
    }

    private fun buildDtnSyncEngine(
        context: Context,
        receiver: EnvelopeReceiver,
        dtnDiagnostics: DtnDiagnosticsSink,
        repairSuppressionStore: InMemoryDtnRepairSuppressionStore,
    ): DtnSyncEngine {
        val db = AppDatabaseProvider.get(context)
        val keyStore = SecureKeyStore(context)
        val store = RoomDtnInventoryStore(
            eventsDao = db.eventsDao(),
            pendingCanonicalMapEventDao = db.pendingCanonicalMapEventDao(),
            groupKeyLoader = keyStore::loadGroupKey,
            diagnostics = dtnDiagnostics,
        )
        val syncStateStore = RoomDtnSyncStateStore(
            peerStateDao = db.dtnPeerStateDao(),
            syncCursorDao = db.dtnSyncCursorDao(),
        )
        val carrierManifestStore = RoomDtnCarrierManifestStore(
            manifestDao = db.dtnCarrierManifestDao(),
        )
        val carrierManifestOfferSource = RoomDtnCarrierManifestOfferSource(
            outboundBatchDao = db.outboundBatchDao(),
            opaqueCapsuleDao = db.opaqueDtnCapsuleDao(),
        )
        val carrierDataSource = RoomDtnCarrierDataSource(
            outboundBatchDao = db.outboundBatchDao(),
            opaqueCapsuleDao = db.opaqueDtnCapsuleDao(),
        )
        val carrierDataReceiver = RoomDtnCarrierDataReceiver(
            manifestDao = db.dtnCarrierManifestDao(),
            capsuleDao = db.opaqueDtnCapsuleDao(),
        )
        val carrierManifestKeyResolver = RoomDtnCarrierManifestKeyResolver(
            keyDao = db.dtnPeerManifestKeyDao(),
        )
        val peerManifestKeyStore = RoomDtnPeerManifestKeyStore(
            keyDao = db.dtnPeerManifestKeyDao(),
        )
        val localPeerId = localDtnPeerId(context)
        val manifestIdentity = keyStore.loadOrCreateDtnManifestIdentity()
        return DtnSyncEngine(
            localPeerId = localPeerId,
            store = store,
            diagnostics = dtnDiagnostics,
            dataReceiver = DtnDataReceiver { _, item ->
                receiver.onDtnDataReceived(item.envelope.toByteArray(Charsets.UTF_8)).accepted
            },
            syncStateStore = syncStateStore,
            localCloudStatusProvider = { nowSeconds -> localCloudStatus(context, nowSeconds) },
            peerHelloObserver = DtnPeerHelloObserver { peerId, gid, _, cloudStatus, seenAtSeconds ->
                BlePeerCloudStateRegistry.update(peerId, gid, cloudStatus, seenAtSeconds)
            },
            carrierManifestStore = carrierManifestStore,
            carrierManifestVerifier = EcdsaDtnCarrierManifestVerifier(carrierManifestKeyResolver),
            localManifestKeyProvider = DtnLocalManifestKeyProvider {
                DtnPeerManifestKey(
                    peerId = localPeerId,
                    algorithm = DtnManifestKeyAlgorithms.ECDSA_P256_SHA256,
                    publicKey = manifestIdentity.publicKey,
                )
            },
            peerManifestKeyStore = peerManifestKeyStore,
            carrierManifestOfferSource = carrierManifestOfferSource,
            carrierManifestSigner = DtnCarrierManifestSigner { packet, manifest ->
                DtnCarrierManifestCrypto.signEcdsaP256(packet, manifest, manifestIdentity.privateKey)
            },
            carrierDataSource = carrierDataSource,
            carrierDataReceiver = carrierDataReceiver,
            repairSuppressionStore = repairSuppressionStore,
        )
    }

    private fun localCloudStatus(context: Context, nowSeconds: Long): DtnCloudStatus {
        if (!PeerMeshCapabilities.canUseRelaySync || !RelaySyncSettings.isEnabled(context)) {
            return DtnCloudStatus(
                state = DtnCloudStates.DISABLED,
                lastSuccessAtSeconds = null,
            )
        }
        val lastSuccessMs = RelaySyncSettings.lastSuccessMs(context)
        if (lastSuccessMs <= 0L) {
            return DtnCloudStatus(
                state = DtnCloudStates.STALE,
                lastSuccessAtSeconds = null,
            )
        }
        val lastSuccessSeconds = lastSuccessMs / 1000L
        val ageSeconds = nowSeconds - lastSuccessSeconds
        val state = if (ageSeconds > CLOUD_STATUS_STALE_AFTER_SECONDS) {
            DtnCloudStates.STALE
        } else {
            DtnCloudStates.OK
        }
        return DtnCloudStatus(
            state = state,
            lastSuccessAtSeconds = lastSuccessSeconds,
        )
    }

    private fun localDtnPeerId(context: Context): String {
        return BleConfig.advertisedDeviceId
            ?.joinToString("") { byte -> "%02X".format(byte) }
            ?: DeviceIdProvider.get(context)
    }

    private fun buildDtnDiagnostics(): DtnDiagnosticsSink {
        return DtnDiagnosticsSink { event ->
            ActivityLog.add(
                label = event.label,
                source = "dtn",
                message = dtnDiagnosticMessage(event),
                gid = event.gid,
            )
        }
    }

    private fun dtnDiagnosticMessage(event: DtnDiagnosticEvent): String {
        return buildString {
            event.type?.let { append("type=").append(it).append(' ') }
            event.direction?.let { append("direction=").append(it).append(' ') }
            event.peerId?.let { append("peer_id=").append(it).append(' ') }
            event.sessionId?.let { append("session_id=").append(it).append(' ') }
            append("ids=").append(event.idCount).append(' ')
            append("data=").append(event.dataCount).append(' ')
            append("responses=").append(event.responseCount).append(' ')
            append("sent=").append(event.sentCount)
            if (event.idSample.isNotEmpty()) {
                append(" id_sample=").append(event.idSample.joinToString(","))
            }
            if (event.dataIdSample.isNotEmpty()) {
                append(" data_id_sample=").append(event.dataIdSample.joinToString(","))
            }
            event.reason?.let { append(" reason=").append(it) }
        }.trim()
    }

    private fun transportBackendLabel(): String {
        return if (BleConfig.useNordicBackend) "nordic" else "current"
    }

    private class DelegatingReceiver : EnvelopeReceiver {
        @Volatile
        private var delegate: EnvelopeReceiver? = null

        fun setDelegate(newDelegate: EnvelopeReceiver) {
            delegate = newDelegate
        }

        override fun onEnvelopeReceived(blob: ByteArray): Boolean {
            return delegate?.onEnvelopeReceived(blob) ?: false
        }

        override fun onEnvelopeReceivedWithMeta(blob: ByteArray): EnvelopeReceiveResult {
            return delegate?.onEnvelopeReceivedWithMeta(blob)
                ?: EnvelopeReceiveResult(accepted = false)
        }

        override fun onDtnDataReceived(blob: ByteArray): EnvelopeReceiveResult {
            return delegate?.onDtnDataReceived(blob)
                ?: EnvelopeReceiveResult(accepted = false)
        }
    }

    private const val TAG = "BleMeshRuntime"
    private const val BLE_ID_PREFS_NAME = "ble_mesh_identity"
    private const val KEY_LOCAL_DEVICE_ID = "local_device_id"
    private const val BLE_DEVICE_ID_BYTES = 8
    private const val DTN_MAX_SYNC_SCOPES = 8
    private const val DTN_SYNC_GID_REFRESH_INTERVAL_MS = 5_000L
    private const val CLOUD_STATUS_STALE_AFTER_SECONDS = 10 * 60L

    private fun handleBleAvailability(context: Context) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        val hasBleFeature =
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val available = try {
            val advertiserReady = adapter?.bluetoothLeAdvertiser != null
            val bleSupported = hasBleFeature && adapter != null && advertiserReady
            val bleEnabled = adapter?.isEnabled == true
            bleSupported && bleEnabled
        } catch (se: SecurityException) {
            false
        }

        if (available) return

        // BLE availability must never override the user's Cloud Sync preference.
        // RelaySyncLifecycleCoordinator independently manages an allowed Cloud Sync service.
        Log.w(TAG, "BLE unavailable; relay sync userEnabled=${RelaySyncSettings.isEnabled(appContext)}")
    }
}
