package app.peermesh.nearby

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import app.peermesh.ble.BleMeshRuntime
import app.peermesh.data.debug.ActivityLog
import app.peermesh.transport.adapter.MeshTransport
import app.peermesh.transport.adapter.NearbyRoomAdvertisementProvider
import app.peermesh.transport.adapter.NearbyRoomDiscoveryListener

object NearbyRoomBleCoordinator {
    private const val DISCOVERY_UPSERT_LABEL = "Nearby room discovery burst upsert"
    @Volatile
    private var boundTransport: MeshTransport? = null
    private val discoveryWorkerThread = HandlerThread("NearbyRoomDiscoveryWorker").apply { start() }
    private val discoveryWorker = Handler(discoveryWorkerThread.looper)
    private val discoveryAccumulatorLock = Any()
    private val discoveryAccumulator = NearbyRoomDiscoveryAccumulator()
    private val advertiseStateLock = Any()
    @Volatile private var lastAdvertisedRoomId: String? = null
    @Volatile private var lastAdvertisedPayload: ByteArray? = null

    fun bind(context: Context, transport: MeshTransport) {
        if (boundTransport === transport) return
        val appContext = context.applicationContext
        boundTransport = transport

        transport.setNearbyRoomAdvertisementProvider(
            NearbyRoomAdvertisementProvider {
                val session = ActiveNearbyRoomSessionManager.get(appContext)
                val snapshot = NearbyRoomBleStateCache.snapshot(appContext)
                ActivityLog.add(
                    label = "Nearby room advertise provider",
                    source = "nearby",
                    message =
                        "active_room_id=${session.activeRoomId ?: "none"} cached_room_id=${snapshot.roomId ?: "none"} payload_bytes=${snapshot.payload?.size ?: 0}",
                )
                snapshot.payload
            }
        )

        transport.setNearbyRoomDiscoveryListener(
            NearbyRoomDiscoveryListener { sourceDeviceId, payload, seenAtMs, rssi ->
                val payloadCopy = payload.copyOf()
                discoveryWorker.post {
                    if (!BleModeController.shouldProcessDiscoveryMetadata(appContext)) {
                        ActivityLog.add(
                            label = "Nearby room discovery metadata skipped",
                            source = "nearby",
                            message = "source_device_id=$sourceDeviceId reason=discovery_disabled payload_bytes=${payloadCopy.size} rssi=$rssi",
                        )
                        return@post
                    }
                    ActivityLog.add(
                        label = "Nearby room discovery metadata recv",
                        source = "nearby",
                        message = "source_device_id=$sourceDeviceId payload_bytes=${payloadCopy.size} rssi=$rssi",
                    )
                    val advertisement = NearbyRoomBleCodec.decode(
                        bytes = payloadCopy,
                        ownerDeviceId = sourceDeviceId,
                        nowMs = seenAtMs,
                    )
                    if (advertisement == null) {
                        ActivityLog.add(
                            label = "Nearby room discovery metadata decode failed",
                            source = "nearby",
                            message = "source_device_id=$sourceDeviceId payload_bytes=${payloadCopy.size}",
                        )
                        return@post
                    }
                    ActivityLog.add(
                        label = "Nearby room discovery metadata decoded",
                        source = "nearby",
                        message = "source_device_id=$sourceDeviceId room_id=${advertisement.roomId} participants=${advertisement.participantCount ?: -1} rssi=$rssi",
                    )
                    ActivityLog.add(
                        label = "Nearby room discovery metadata buffered",
                        source = "nearby",
                        message = "source_device_id=$sourceDeviceId room_id=${advertisement.roomId} rssi=$rssi",
                    )
                    NearbyRoomPeerSnapshotStore.record(
                        deviceId = sourceDeviceId,
                        roomId = advertisement.roomId,
                        seenAtMs = seenAtMs,
                        rssi = rssi,
                    )
                    synchronized(discoveryAccumulatorLock) {
                        discoveryAccumulator.record(
                            NearbyRoomDiscoveryAccumulator.MetadataSighting(
                                sourceDeviceId = sourceDeviceId,
                                advertisement = advertisement,
                                seenAtMs = seenAtMs,
                                rssi = rssi,
                            )
                        )
                    }
                }
            }
        )
    }

    fun flushBufferedDiscovery(context: Context) {
        val appContext = context.applicationContext
        val repository = NearbyRoomRepository(appContext)
        val result = synchronized(discoveryAccumulatorLock) {
            val evaluated = discoveryAccumulator.evaluate(
                existingRoomsById = repository.listDiscoverySnapshots(),
                nowMs = System.currentTimeMillis(),
            )
            discoveryAccumulator.clear()
            evaluated
        }
        if (result.totalSightings == 0) {
            return
        }
        ActivityLog.add(
            label = "Nearby room discovery burst evaluated",
            source = "nearby",
            message =
                "sightings=${result.totalSightings} unique_rooms=${result.uniqueRoomSightings} accepted_peers=${result.acceptedMetadataPeers} accepted_rooms=${result.acceptedUniqueRooms}",
        )
        result.decisions.forEach { decision ->
            when (decision.type) {
                NearbyRoomDiscoveryAccumulator.DecisionType.INSERT,
                NearbyRoomDiscoveryAccumulator.DecisionType.REFRESH,
                -> {
                    val upserted = repository.upsertDiscoveredBleRoom(
                        advertisement = decision.advertisement,
                        sourceDeviceId = decision.sourceDeviceId,
                        seenAtMs = decision.seenAtMs,
                    )
                    ActivityLog.add(
                        label = if (upserted != null) {
                            DISCOVERY_UPSERT_LABEL
                        } else {
                            "Nearby room discovery burst ignored"
                        },
                        source = "nearby",
                        message =
                            "room_id=${decision.roomId} type=${decision.type} source_device_id=${decision.sourceDeviceId} advertisers=${decision.advertiserCount} rssi=${decision.rssi}",
                    )
                }
                NearbyRoomDiscoveryAccumulator.DecisionType.SKIP_THROTTLED,
                NearbyRoomDiscoveryAccumulator.DecisionType.SKIP_BUDGET,
                -> {
                    ActivityLog.add(
                        label = "Nearby room discovery burst skipped",
                        source = "nearby",
                        message =
                            "room_id=${decision.roomId} type=${decision.type} source_device_id=${decision.sourceDeviceId} advertisers=${decision.advertiserCount} rssi=${decision.rssi}",
                    )
                }
            }
        }
    }

    fun enqueueMetadataRefresh(context: Context, roomIds: Collection<String>) {
        NearbyRoomMetadataFetchCoordinator.enqueue(context.applicationContext, roomIds)
    }

    fun bindIfTransportAvailable(context: Context) {
        runCatching {
            bind(context.applicationContext, BleMeshRuntime.get(context.applicationContext))
        }
    }

    fun refreshActiveRoomAdvertisement(context: Context, roomId: String) {
        val appContext = context.applicationContext
        Thread {
            val room = NearbyRoomRepository(appContext).getRoom(roomId)
            val payload = if (room == null) {
                NearbyRoomBleStateCache.clear(appContext)
            } else {
                NearbyRoomBleStateCache.updateFromRoom(appContext, room)
            }
            val activeRoomId = ActiveNearbyRoomSessionManager.get(appContext).activeRoomId
            val snapshot = NearbyRoomBleStateCache.snapshot(appContext)
            ActivityLog.add(
                label = "Nearby room advertisement state",
                source = "nearby",
                message =
                    "trigger=refresh requested_room_id=$roomId active_room_id=${activeRoomId ?: "none"} updated_payload_bytes=${payload?.size ?: 0} cached_room_id=${snapshot.roomId ?: "none"} provider_payload_bytes=${snapshot.payload?.size ?: 0}",
            )
            if (!shouldRefreshAdvertisedState(snapshot.roomId, snapshot.payload)) {
                ActivityLog.add(
                    label = "Nearby room advertisement refresh skipped",
                    source = "nearby",
                    message =
                        "trigger=refresh requested_room_id=$roomId active_room_id=${activeRoomId ?: "none"} cached_room_id=${snapshot.roomId ?: "none"} reason=unchanged_payload",
                )
                return@Thread
            }
            val accepted = boundTransport?.refreshNearbyRoomAdvertisement() == true
            if (accepted) {
                rememberAdvertisedState(snapshot.roomId, snapshot.payload)
            } else {
                ActivityLog.add(
                    label = "Nearby room advertisement refresh deferred",
                    source = "nearby",
                    message =
                        "trigger=refresh requested_room_id=$roomId active_room_id=${activeRoomId ?: "none"} cached_room_id=${snapshot.roomId ?: "none"} reason=transport_unavailable",
                )
            }
        }.start()
    }

    fun clearActiveRoomAdvertisement(context: Context) {
        val appContext = context.applicationContext
        NearbyRoomBleStateCache.clear(appContext)
        val activeRoomId = ActiveNearbyRoomSessionManager.get(appContext).activeRoomId
        val snapshot = NearbyRoomBleStateCache.snapshot(appContext)
        ActivityLog.add(
            label = "Nearby room advertisement state",
            source = "nearby",
            message =
                "trigger=clear active_room_id=${activeRoomId ?: "none"} cached_room_id=${snapshot.roomId ?: "none"} provider_payload_bytes=${snapshot.payload?.size ?: 0}",
        )
        if (!shouldRefreshAdvertisedState(snapshot.roomId, snapshot.payload)) {
            ActivityLog.add(
                label = "Nearby room advertisement refresh skipped",
                source = "nearby",
                message =
                    "trigger=clear active_room_id=${activeRoomId ?: "none"} cached_room_id=${snapshot.roomId ?: "none"} reason=already_clear",
            )
            return
        }
        val accepted = boundTransport?.refreshNearbyRoomAdvertisement() == true
        if (accepted) {
            rememberAdvertisedState(snapshot.roomId, snapshot.payload)
        } else {
            ActivityLog.add(
                label = "Nearby room advertisement refresh deferred",
                source = "nearby",
                message =
                    "trigger=clear active_room_id=${activeRoomId ?: "none"} cached_room_id=${snapshot.roomId ?: "none"} reason=transport_unavailable",
            )
        }
    }

    private fun shouldRefreshAdvertisedState(roomId: String?, payload: ByteArray?): Boolean {
        synchronized(advertiseStateLock) {
            val roomChanged = lastAdvertisedRoomId != roomId
            val payloadChanged = !byteArraysEqual(lastAdvertisedPayload, payload)
            return roomChanged || payloadChanged
        }
    }

    private fun rememberAdvertisedState(roomId: String?, payload: ByteArray?) {
        synchronized(advertiseStateLock) {
            lastAdvertisedRoomId = roomId
            lastAdvertisedPayload = payload?.copyOf()
        }
    }

    private fun byteArraysEqual(left: ByteArray?, right: ByteArray?): Boolean {
        if (left == null || right == null) return left == null && right == null
        return left.contentEquals(right)
    }
}
