package app.peermesh.map.composition

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.core.content.ContextCompat
import app.peermesh.DeviceIdProvider
import app.peermesh.KeyIdHelper
import app.peermesh.LocalMapPostCommitTransport
import app.peermesh.UserOutbox
import app.peermesh.ble.BlePermissionHelper
import app.peermesh.core.id.EventIdFactory
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.map.local.LocalMapEventWriterFactory
import app.peermesh.map.application.submission.DefaultMapEventKeyContextAttacher
import app.peermesh.map.application.submission.DefaultMapInteractionEventFactoryAdapter
import app.peermesh.map.application.submission.DefaultMapInteractionInputValidator
import app.peermesh.map.application.submission.DefaultMapInteractionOutcomeMapper
import app.peermesh.map.application.submission.DefaultSubmitMapInteraction
import app.peermesh.map.application.submission.MapEventConstructionContext
import app.peermesh.map.application.submission.MapEventConstructionContextProvider
import app.peermesh.map.application.submission.MapInteractionPostCommitResult
import app.peermesh.map.application.submission.MapInteractionPostCommitTransport
import app.peermesh.map.application.submission.MapCanonicalRootResolver
import app.peermesh.map.application.submission.SubmitMapInteraction
import app.peermesh.core.map.MapItemType
import app.peermesh.core.types.EventTypes
import app.peermesh.security.crypto.SecureKeyStore

/** App composition seam. Must reuse the accepted LocalMapEventWriter and post-commit path. */
object MapInteractionSubmissionFactory {
    fun create(
        applicationContext: Context,
        database: AppDatabase,
    ): SubmitMapInteraction {
        val appContext = applicationContext.applicationContext
        return DefaultSubmitMapInteraction(
            inputValidator = DefaultMapInteractionInputValidator(),
            constructionContextProvider = constructionContextProvider(appContext),
            eventFactory = DefaultMapInteractionEventFactoryAdapter(),
            keyContextAttacher = DefaultMapEventKeyContextAttacher { groupId, core ->
                KeyIdHelper.attachKeyId(appContext, groupId, core)
            },
            localWriter = LocalMapEventWriterFactory.lazy(database),
            postCommitTransport = postCommitTransport(appContext),
            outcomeMapper = DefaultMapInteractionOutcomeMapper(),
            canonicalRootResolver = MapCanonicalRootResolver { target ->
                val itemType = if (target.itemType == MapItemType.MARKER) "marker" else "area"
                database.mapItemDomainStateDao()
                    .find(target.groupId, itemType, target.itemId)
                    ?.canonical_add_event_id
                    ?: database.eventsDao()
                        .listByTypeAndTarget(
                            target.groupId,
                            if (target.itemType == MapItemType.MARKER) {
                                EventTypes.MAP_MARKER_ADD
                            } else {
                                EventTypes.MAP_AREA_ADD
                            },
                            target.itemId,
                        )
                        .firstOrNull()
                        ?.event_id
            },
        )
    }

    private fun constructionContextProvider(context: Context) = MapEventConstructionContextProvider { groupId ->
        val actorDid = DeviceIdProvider.get(context)
        val nowMillis = System.currentTimeMillis()
        val nowSeconds = nowMillis / 1000L
        val eventId = EventIdFactory.generate(groupId, actorDid, nowSeconds, EventIdFactory.generateNonce())
        MapEventConstructionContext(
            actorDid = actorDid,
            eventId = eventId,
            createdSeconds = nowSeconds,
            lamport = nowMillis,
            insertedAtSeconds = nowSeconds,
        )
    }

    private fun postCommitTransport(context: Context) = MapInteractionPostCommitTransport { groupId, outcome, core ->
        try {
            LocalMapPostCommitTransport.dispatch(
                outcome = outcome,
                core = core,
                hasGroupKey = { SecureKeyStore(context).loadGroupKey(groupId) != null },
                hasBlePermission = { hasBlePermissions(context) },
                isBleReady = { isBleReady(context) },
                enqueue = { exactCore ->
                    UserOutbox.enqueue(
                        context = context,
                        gid = groupId,
                        core = exactCore,
                        source = "mesh",
                        successLabel = "Sent map interaction",
                        successMessage = exactCore.type,
                    )
                },
            ).toApplicationResult()
        } catch (_: Exception) {
            MapInteractionPostCommitResult.EnqueueFailed
        }
    }

    private fun LocalMapPostCommitTransport.Result.toApplicationResult(): MapInteractionPostCommitResult = when (this) {
        LocalMapPostCommitTransport.Result.NotEligible -> MapInteractionPostCommitResult.NotEligible
        LocalMapPostCommitTransport.Result.MissingGroupKey -> MapInteractionPostCommitResult.MissingGroupKey
        is LocalMapPostCommitTransport.Result.Enqueued -> if (enqueueResult) {
            MapInteractionPostCommitResult.Enqueued
        } else {
            MapInteractionPostCommitResult.EnqueueUnavailable
        }
        is LocalMapPostCommitTransport.Result.EnqueueFailed -> MapInteractionPostCommitResult.EnqueueFailed
    }

    private fun hasBlePermissions(context: Context): Boolean =
        BlePermissionHelper.requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun isBleReady(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return false
        val adapter = manager.adapter ?: return false
        return adapter.isEnabled && adapter.bluetoothLeAdvertiser != null
    }
}
