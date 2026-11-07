package app.peermesh

import android.content.Context
import app.peermesh.core.id.EventIdFactory
import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventMeta
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.types.EventTypes
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

object LiveTrailShareManager {
    const val SCOPE_GROUP = "group"
    const val SCOPE_MEMBERS = "members"

    private const val SHARE_KIND_MANUAL = "manual"
    private const val SHARE_KIND_AUTOMATIC = "automatic"
    private const val LIVE_TRAIL_TTL_S = 7L * 24L * 60L * 60L

    private var activeShareTrailId: String? = null
    private var activeShareGid: String? = null
    private var shareStartIndex: Int = 0
    private val pendingPoints = mutableListOf<SharedTrailPoint>()
    private var recordingActive: Boolean = false
    private var recordingShareKind: String? = null
    private var recordingTrailName: String? = null
    private var recordingStartT: Long = 0L
    private var pendingManualShare: PendingManualShare? = null
    private var firstPointBroadcastLogged: Boolean = false

    data class ShareConfig(
        val enabled: Boolean,
        val scope: String,
        val members: List<String>,
    )

    data class SharedTrailPoint(
        val lat: Double,
        val lon: Double,
        val t: Long,
        val acc: Float?,
        val spd: Float?,
        val mm: Int,
    )

    private data class PendingManualShare(
        val gid: String,
        val config: ShareConfig,
    )

    fun prepareManualTrailShare(context: Context, gid: String, config: ShareConfig): Boolean {
        pendingManualShare = PendingManualShare(gid, config.copy(enabled = true))
        app.peermesh.data.debug.ActivityLog.add(
            label = "Live trail share",
            source = "trail",
            message = "manual share prepared gid=$gid scope=${config.scope} members=${config.members.size}",
            gid = gid,
        )
        return true
    }

    fun onRecordingStarted(
        context: Context,
        trailName: String?,
        startT: Long,
        recordingMode: String,
    ) {
        recordingActive = true
        recordingTrailName = trailName
        recordingStartT = startT
        app.peermesh.data.debug.ActivityLog.add(
            label = "Live trail share",
            source = "trail",
            message = "recording started mode=$recordingMode trailName=${trailName ?: "Live trail"} startT=$startT",
        )
        val manualShare = consumeManualShare(recordingMode)
        val shareKind = if (recordingMode == TrailRecordingMode.MANUAL) {
            SHARE_KIND_MANUAL
        } else {
            SHARE_KIND_AUTOMATIC
        }
        if (shareKind == SHARE_KIND_AUTOMATIC) {
            app.peermesh.data.debug.ActivityLog.add(
                label = "Live trail share",
                source = "trail",
                message = "auto live share skipped local_only",
            )
            return
        }
        val gid = when (shareKind) {
            SHARE_KIND_MANUAL -> manualShare?.gid ?: run {
                app.peermesh.data.debug.ActivityLog.add(
                    label = "Live trail share",
                    source = "trail",
                    message = "manual live share skipped no pending audience",
                )
                return
            }
            else -> ActiveGroupManager.getActiveGroup(context) ?: run {
                app.peermesh.data.debug.ActivityLog.add(
                    label = "Live trail share",
                    source = "trail",
                    message = "auto live share skipped no active group",
                )
                return
            }
        }

        recordingShareKind = shareKind
        val config = when (shareKind) {
            SHARE_KIND_MANUAL -> manualShare?.config ?: run {
                app.peermesh.data.debug.ActivityLog.add(
                    label = "Live trail share",
                    source = "trail",
                    message = "manual live share skipped no pending config",
                    gid = gid,
                )
                return
            }
            else -> getAutomaticShareConfig(context, gid)
        }
        if (!config.enabled) {
            val message = if (shareKind == SHARE_KIND_AUTOMATIC) {
                "auto live share skipped no trusted contacts"
            } else {
                "manual live share skipped disabled config"
            }
            app.peermesh.data.debug.ActivityLog.add(
                label = "Live trail share",
                source = "trail",
                message = message,
                gid = gid,
            )
            return
        }
        app.peermesh.data.debug.ActivityLog.add(
            label = "Live trail share",
            source = "trail",
            message = if (shareKind == SHARE_KIND_AUTOMATIC) {
                "auto live share starting trusted_contacts=${config.members.size}"
            } else {
                "manual live share starting scope=${config.scope} targets=${config.members.size}"
            },
            gid = gid,
        )
        startShare(context, gid, trailName, startT, config, shareKind)
    }

    fun onRecordingStopped(context: Context) {
        val hadActiveShare = activeShareTrailId != null
        app.peermesh.data.debug.ActivityLog.add(
            label = "Live trail share",
            source = "trail",
            message = "recording stopped activeShare=$hadActiveShare",
            gid = activeShareGid,
        )
        recordingActive = false
        recordingShareKind = null
        recordingTrailName = null
        recordingStartT = 0L
        stopShare(context, sendEnd = true)
    }

    fun onPoint(context: Context, point: SharedTrailPoint) {
        if (activeShareTrailId == null) return
        pendingPoints.add(point)
        flushBatch(context)
    }

    private fun consumeManualShare(recordingMode: String): PendingManualShare? {
        if (recordingMode != TrailRecordingMode.MANUAL) return null
        val manualShare = pendingManualShare
        pendingManualShare = null
        return manualShare
    }

    private fun getAutomaticShareConfig(context: Context, gid: String): ShareConfig {
        val contacts = TrustedContactsStore.getContacts(context, gid)
        return ShareConfig(
            enabled = contacts.isNotEmpty(),
            scope = SCOPE_MEMBERS,
            members = contacts,
        )
    }

    private fun startShare(
        context: Context,
        gid: String,
        trailName: String?,
        startT: Long,
        config: ShareConfig,
        shareKind: String,
    ) {
        if (!PeerMeshCapabilities.canUseSharedLiveTrail) {
            app.peermesh.data.debug.ActivityLog.add(
                label = "Live trail share",
                source = "trail",
                message = "live share skipped capability disabled kind=$shareKind",
                gid = gid,
            )
            return
        }
        stopShare(context, sendEnd = false)
        activeShareTrailId = UUID.randomUUID().toString()
        activeShareGid = gid
        shareStartIndex = 0
        firstPointBroadcastLogged = false
        pendingPoints.clear()
        app.peermesh.data.debug.ActivityLog.add(
            label = "Live trail share",
            source = "trail",
            message = "start kind=$shareKind trailId=$activeShareTrailId gid=$gid recipients=${config.members.size}",
            gid = gid,
        )
        app.peermesh.data.debug.ActivityLog.add(
            label = "Live trail share",
            source = "trail",
            message = if (shareKind == SHARE_KIND_AUTOMATIC) {
                "auto live share started trusted_contacts=${config.members.size}"
            } else {
                "manual live share started scope=${config.scope} targets=${config.members.size}"
            },
            gid = gid,
        )

        val nowSeconds = System.currentTimeMillis() / 1000L
        val actorDid = DeviceIdProvider.get(context)
        val eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce())
        val data = buildJsonObject {
            put("trail_id", JsonPrimitive(activeShareTrailId!!))
            put("name", JsonPrimitive(trailName ?: "Live trail"))
            put("start_t", JsonPrimitive(startT))
            put("share_scope", JsonPrimitive(config.scope))
            put("share_to", buildJsonArray {
                config.members.forEach { add(JsonPrimitive(it)) }
            })
        }
        val core = EventCore(
            ev = 1,
            id = eventId,
            type = EventTypes.LIVE_TRAIL_START,
            actor = Actor(actorDid),
            time = EventTime(nowSeconds, System.currentTimeMillis()),
            ref = EventRef(target = activeShareTrailId),
            meta = EventMeta(ttl_s = LIVE_TRAIL_TTL_S, prio = "low"),
            data = data,
        )
        runAsync {
            LiveTrailEventWriter.storeAndBroadcast(context, gid, core)
        }
    }

    private fun stopShare(context: Context, sendEnd: Boolean) {
        val gid = activeShareGid ?: run {
            activeShareTrailId = null
            pendingPoints.clear()
            return
        }
        app.peermesh.data.debug.ActivityLog.add(
            label = "Live trail share",
            source = "trail",
            message = "stop sendEnd=$sendEnd trailId=$activeShareTrailId gid=$gid",
            gid = gid,
        )
        if (sendEnd && PeerMeshCapabilities.canUseSharedLiveTrail) {
            flushBatch(context)
            val nowSeconds = System.currentTimeMillis() / 1000L
            val actorDid = DeviceIdProvider.get(context)
            val eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce())
            val data = buildJsonObject {
                put("trail_id", JsonPrimitive(activeShareTrailId!!))
                put("end_t", JsonPrimitive(nowSeconds))
            }
            val core = EventCore(
                ev = 1,
                id = eventId,
                type = EventTypes.LIVE_TRAIL_END,
                actor = Actor(actorDid),
                time = EventTime(nowSeconds, System.currentTimeMillis()),
                ref = EventRef(target = activeShareTrailId),
                meta = EventMeta(ttl_s = LIVE_TRAIL_TTL_S, prio = "low"),
                data = data,
            )
            runAsync {
                LiveTrailEventWriter.storeAndBroadcast(context, gid, core)
            }
        }
        activeShareTrailId = null
        activeShareGid = null
        pendingPoints.clear()
        shareStartIndex = 0
        firstPointBroadcastLogged = false
    }

    private fun flushBatch(context: Context) {
        if (!PeerMeshCapabilities.canUseSharedLiveTrail) {
            pendingPoints.clear()
            return
        }
        val gid = activeShareGid ?: return
        val trailId = activeShareTrailId ?: return
        if (pendingPoints.isEmpty()) return
        val batch = pendingPoints.toList()
        pendingPoints.clear()

        val nowSeconds = System.currentTimeMillis() / 1000L
        val actorDid = DeviceIdProvider.get(context)
        val eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce())
        val data = buildJsonObject {
            put("trail_id", JsonPrimitive(trailId))
            put("start_index", JsonPrimitive(shareStartIndex))
            put("points", buildJsonArray {
                batch.forEach { pt ->
                    add(buildJsonObject {
                        put("lat", JsonPrimitive(pt.lat))
                        put("lon", JsonPrimitive(pt.lon))
                        put("t", JsonPrimitive(pt.t))
                        pt.acc?.let { put("acc", JsonPrimitive(it)) }
                        pt.spd?.let { put("spd", JsonPrimitive(it)) }
                        put("mm", JsonPrimitive(pt.mm))
                    })
                }
            })
        }
        val core = EventCore(
            ev = 1,
            id = eventId,
            type = EventTypes.LIVE_TRAIL_POINT_BATCH,
            actor = Actor(actorDid),
            time = EventTime(nowSeconds, System.currentTimeMillis()),
            ref = EventRef(target = trailId),
            meta = EventMeta(ttl_s = LIVE_TRAIL_TTL_S, prio = "low"),
            data = data,
        )
        if (!firstPointBroadcastLogged) {
            firstPointBroadcastLogged = true
            app.peermesh.data.debug.ActivityLog.add(
                label = "Live trail share",
                source = "trail",
                message = "live trail first point broadcast trailId=$trailId batchSize=${batch.size}",
                gid = gid,
            )
        }
        runAsync {
            LiveTrailEventWriter.storeAndBroadcast(context, gid, core)
        }
        shareStartIndex += batch.size
    }

    private fun runAsync(block: () -> Unit) {
        Thread {
            runCatching { block() }
        }.start()
    }
}
