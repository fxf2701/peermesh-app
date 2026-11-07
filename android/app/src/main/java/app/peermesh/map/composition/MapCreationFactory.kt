package app.peermesh.map.composition

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.peermesh.DeviceIdProvider
import app.peermesh.KeyIdHelper
import app.peermesh.LocalMapPostCommitTransport
import app.peermesh.UserOutbox
import app.peermesh.ble.BlePermissionHelper
import app.peermesh.core.id.EntityIdFactory
import app.peermesh.core.id.EventIdFactory
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.map.local.LocalMapEventWriterFactory
import app.peermesh.map.application.creation.DefaultMapCreationEventFactoryAdapter
import app.peermesh.map.application.creation.DefaultMapCreationCompatibilityPolicy
import app.peermesh.map.application.creation.DefaultSubmitMapCreation
import app.peermesh.map.application.creation.DefaultValidateMapCreationIntent
import app.peermesh.map.application.creation.MapCreationConstructionContext
import app.peermesh.map.application.creation.MapCreationConstructionContextProvider
import app.peermesh.map.application.creation.MapCreationDeliveryState
import app.peermesh.map.application.creation.MapCreationKeyContextAttacher
import app.peermesh.map.application.creation.MapCreationPostCommitTransport
import app.peermesh.map.application.creation.MapCreationShape
import app.peermesh.map.application.creation.SubmitMapCreation
import app.peermesh.map.presentation.creation.DefaultMapCreationController
import app.peermesh.map.presentation.creation.MapCreationController
import app.peermesh.security.crypto.SecureKeyStore
import java.util.concurrent.Executor

/** Narrow M3D3 composition seam over the accepted writer and post-commit transport. */
object MapCreationFactory {
    fun createSubmitter(context: Context): SubmitMapCreation {
        val appContext = context.applicationContext
        return DefaultSubmitMapCreation(
            validator = DefaultValidateMapCreationIntent(DefaultMapCreationCompatibilityPolicy()),
            constructionContextProvider = constructionContextProvider(appContext),
            eventFactory = DefaultMapCreationEventFactoryAdapter(),
            keyContextAttacher = MapCreationKeyContextAttacher { groupId, core ->
                KeyIdHelper.attachKeyId(appContext, groupId, core)
            },
            localWriter = LocalMapEventWriterFactory.lazy(AppDatabaseProvider.get(appContext)),
            postCommitTransport = postCommitTransport(appContext),
        )
    }

    fun createController(context: Context, groupId: String): MapCreationController {
        require(groupId.isNotBlank()) { "groupId must not be blank" }
        val handler = Handler(Looper.getMainLooper())
        return DefaultMapCreationController(
            submitter = createSubmitter(context),
            backgroundExecutor = Executor { command ->
                Thread(command, "map-creation-submit").apply { isDaemon = true }.start()
            },
            publicationExecutor = Executor(handler::post),
        )
    }

    private fun constructionContextProvider(context: Context) =
        MapCreationConstructionContextProvider { groupId, shape ->
            val actorDid = DeviceIdProvider.get(context)
            val nowMillis = System.currentTimeMillis()
            val nowSeconds = nowMillis / 1000L
            MapCreationConstructionContext(
                actorDid = actorDid,
                eventId = EventIdFactory.generate(
                    groupId,
                    actorDid,
                    nowSeconds,
                    EventIdFactory.generateNonce(),
                ),
                itemId = when (shape) {
                    MapCreationShape.MARKER -> EntityIdFactory.markerId(groupId, actorDid, nowSeconds)
                    MapCreationShape.AREA -> EntityIdFactory.areaId(groupId, actorDid, nowSeconds)
                },
                createdSeconds = nowSeconds,
                lamport = nowMillis,
                insertedAtSeconds = nowSeconds,
            )
        }

    private fun postCommitTransport(context: Context) =
        MapCreationPostCommitTransport { groupId, outcome, core ->
            LocalMapPostCommitTransport.dispatch(
                outcome = outcome,
                core = core,
                hasGroupKey = { SecureKeyStore(context).loadGroupKey(groupId) != null },
                hasBlePermission = {
                    BlePermissionHelper.requiredPermissions().all { permission ->
                        ContextCompat.checkSelfPermission(context, permission) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                },
                isBleReady = {
                    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val adapter = manager?.adapter
                    adapter?.isEnabled == true && adapter.bluetoothLeAdvertiser != null
                },
                enqueue = { exactCore ->
                    UserOutbox.enqueue(
                        context = context,
                        gid = groupId,
                        core = exactCore,
                        source = "mesh",
                        successLabel = "Sent map creation",
                        successMessage = exactCore.type,
                    )
                },
            ).toCreationDeliveryState()
        }

    internal fun LocalMapPostCommitTransport.Result.toCreationDeliveryState(): MapCreationDeliveryState = when (this) {
        LocalMapPostCommitTransport.Result.NotEligible -> MapCreationDeliveryState.TRANSPORT_FAILURE
        LocalMapPostCommitTransport.Result.MissingGroupKey -> MapCreationDeliveryState.MISSING_GROUP_KEY
        is LocalMapPostCommitTransport.Result.Enqueued ->
            if (enqueueResult) MapCreationDeliveryState.ENQUEUED else MapCreationDeliveryState.ENQUEUE_REJECTED
        is LocalMapPostCommitTransport.Result.EnqueueFailed -> MapCreationDeliveryState.TRANSPORT_FAILURE
    }
}
