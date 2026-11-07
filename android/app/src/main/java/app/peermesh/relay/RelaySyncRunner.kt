package app.peermesh.relay

import android.content.Context
import android.util.Log
import app.peermesh.DeviceIdProvider
import app.peermesh.PeerMeshCapabilities
import app.peermesh.config.EnvironmentLoader
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.ingest.EnvelopeReceiverImpl
import app.peermesh.data.ingest.EventIngestor
import app.peermesh.data.membership.GroupPeerRouteStore
import app.peermesh.data.relay.RelayEventMapper
import app.peermesh.data.relay.RelayPullManager
import app.peermesh.data.relay.RelayPushManager
import app.peermesh.data.relay.RoomRelayPullStateStore
import app.peermesh.data.relay.RoomRelayPushStateStore
import app.peermesh.moderation.ModerationRepository
import app.peermesh.security.crypto.SecureKeyStore
import app.peermesh.transport.relay.RelayHttpClient

class RelaySyncRunner(context: Context) {
    private val appContext = context.applicationContext
    private val db by lazy { AppDatabaseProvider.get(appContext) }
    private val keyStore by lazy { SecureKeyStore(appContext) }
    private val moderationRepository by lazy { ModerationRepository(appContext) }
    private val ingestor by lazy {
        EventIngestor(
            db = db,
            localActorProvider = {
                DeviceIdProvider.getActiveActor(appContext)
            },
            hiddenMessagePeerIdsProvider = { gid ->
                moderationRepository.hiddenMessagePeerIds(gid)
            },
        )
    }
    private val allowLegacyRelayPlaintext = RelaySyncSettings.isDeveloperMode(appContext)
    private val receiver by lazy {
        EnvelopeReceiverImpl(
            ingestor = ingestor,
            keyStore = keyStore,
            groupPeerRouteStore = GroupPeerRouteStore(db.groupPeerRouteDao()),
            allowLegacyRelayPlaintext = allowLegacyRelayPlaintext,
        )
    }

    fun runOnce(): RelaySyncRunReport {
        if (!PeerMeshCapabilities.canUseRelaySync) {
            return RelaySyncRunReport.empty().copy(
                success = true,
                errorSummary = "relay_sync_unavailable",
            )
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            PENDING.set(true)
            return RelaySyncRunReport.empty().copy(
                success = false,
                errorSummary = "sync_in_progress",
            )
        }
        try {
            return runOnceInternal()
        } finally {
            IN_FLIGHT.set(false)
            if (PENDING.compareAndSet(true, false)) {
                if (IN_FLIGHT.compareAndSet(false, true)) {
                    try {
                        runOnceInternal()
                    } finally {
                        IN_FLIGHT.set(false)
                    }
                }
            }
        }
    }

    private fun runOnceInternal(): RelaySyncRunReport {
        val startMs = System.currentTimeMillis()
        val environment = EnvironmentLoader.load()
        val flushedSeenAcks = SeenAckCloudBuffer.flushToDb(appContext)
        if (flushedSeenAcks > 0) {
            ActivityLog.add(
                label = "Cloud seen ACK flush",
                source = "relay",
                message = "inserted=$flushedSeenAcks",
            )
        }
        val managedAccessCoordinator = ManagedRelayAccessCoordinator(appContext, environment)
        val managedRuntime = ManagedRelaySyncRuntime(
            accessProvider = object : ManagedRelayAccessProvider {
                override fun isConfigured(): Boolean = managedAccessCoordinator.isConfigured()

                override fun acquire(gids: List<String>): ManagedRelayAccessBindingResult =
                    managedAccessCoordinator.acquire(gids)

                override fun clearCachedBinding(relayUrl: String) {
                    ManagedRelayAccessStore.load(appContext)
                        ?.takeIf { it.relayUrl == relayUrl }
                        ?.also { ManagedRelayAccessStore.clear(appContext) }
                }
            },
            cycleExecutor = ManagedRelayCycleExecutor { binding, gids ->
                val relayClient = RelayHttpClient(
                    baseUrl = binding.relayUrl,
                    managedAccessContext = binding.authContext,
                )
                runRelayCycle(
                    startMs = startMs,
                    relayUrl = binding.relayUrl,
                    gids = gids,
                    relayClient = relayClient,
                    relayCount = 1,
                )
            },
        )
        RelaySyncSettings.markSyncAttempt(appContext)
        val relayUrls = RelaySyncSettings.resolveRelayBaseUrls(
            appContext,
            environment.relayBaseUrl,
        )
        val knownRelayUrls = RelaySyncSettings.filterRelayBaseUrls(
            appContext,
            KnownRelayStore.activeRelayUrls(appContext),
        )
        ActivityLog.add(
            label = "Relay sync run",
            source = "relay",
            message = "relays=${relayUrls.size} known=${knownRelayUrls.size} managed=${managedRuntime.isConfigured()}",
        )
        if (relayUrls.isEmpty() && !managedRuntime.isConfigured()) {
            ActivityLog.add(
                label = "Relay sync error",
                source = "relay",
                message = "no relay urls configured",
            )
            return RelaySyncRunReport.empty().copy(
                success = false,
                errorSummary = "no relay urls configured",
            )
        }
        val gids = db.groupDao().listGids()
        ActivityLog.add(
            label = "Relay sync gids",
            source = "relay",
            message = "gidCount=${gids.size}",
        )

        if (managedRuntime.isConfigured()) {
            val managedReport = managedRuntime.runOnce(gids)
            if (managedReport.success) {
                return managedReport
            }
            if (
                environment.relayRequestSignatureSecret.isNotBlank() &&
                    ManagedRelayFallbackPolicy.shouldUseKnownRelays(managedReport) &&
                    knownRelayUrls.isNotEmpty()
            ) {
                ActivityLog.add(
                    label = "Managed relay fallback",
                    source = "relay",
                    message = "knownRelays=${knownRelayUrls.size} reason=${managedReport.errorSummary ?: managedReport.note ?: "unknown"}",
                )
                return runDirectSync(
                    startMs = startMs,
                    relayUrls = knownRelayUrls,
                    gids = gids,
                    relayRequestSignatureSecret = environment.relayRequestSignatureSecret,
                    notes = mutableListOf("known_relay_fallback"),
                )
            }
            return managedReport
        }

        return runDirectSync(
            startMs = startMs,
            relayUrls = relayUrls,
            gids = gids,
            relayRequestSignatureSecret = environment.relayRequestSignatureSecret,
            notes = mutableListOf(),
        )
    }

    private fun runDirectSync(
        startMs: Long,
        relayUrls: List<String>,
        gids: List<String>,
        relayRequestSignatureSecret: String,
        notes: MutableList<String>,
    ): RelaySyncRunReport {
        var totalPushAttempted = 0
        var totalPushAccepted = 0
        var totalPullReceived = 0
        var totalPullAccepted = 0
        var ok = true
        var pushOk = true
        var pullOk = true
        val errors = mutableListOf<String>()
        val rejectedReasons = mutableListOf<String>()
        if (gids.isEmpty()) {
            notes.add("no gids for pull")
        }

        relayUrls.forEach { relayUrl ->
            if (relayUrl.isBlank()) return@forEach
            val relayClient = RelayHttpClient(
                baseUrl = relayUrl,
                requestSignatureSecret = relayRequestSignatureSecret,
            )
            val cycle = runRelayCycle(
                startMs = startMs,
                relayUrl = relayUrl,
                gids = gids,
                relayClient = relayClient,
                relayCount = 1,
            ).report
            totalPushAttempted += cycle.pushAttempted
            totalPushAccepted += cycle.pushAccepted
            totalPullReceived += cycle.pullReceived
            totalPullAccepted += cycle.pullAccepted
            ok = ok && cycle.success
            if (cycle.errorSummary?.contains("push ") == true) pushOk = false
            if (cycle.errorSummary?.contains("pull ") == true) pullOk = false
            cycle.errorSummary?.takeIf { it.isNotBlank() }?.let { errors.add(it) }
            cycle.rejectedSummary?.takeIf { it.isNotBlank() }?.let { rejectedReasons.add(it) }
        }

        return finalizeReport(
            startMs = startMs,
            pushOk = pushOk,
            pullOk = pullOk,
            totalPushAttempted = totalPushAttempted,
            totalPushAccepted = totalPushAccepted,
            totalPullReceived = totalPullReceived,
            totalPullAccepted = totalPullAccepted,
            ok = ok,
            errors = errors,
            rejectedReasons = rejectedReasons,
            relayCount = relayUrls.size,
            gidCount = gids.size,
            notes = notes,
        )
    }

    private fun runRelayCycle(
        startMs: Long,
        relayUrl: String,
        gids: List<String>,
        relayClient: RelayHttpClient,
        relayCount: Int,
    ): ManagedRelayCycleResult {
        val pushManager = RelayPushManager(
            relayUrl = relayUrl,
            eventsDao = db.eventsDao(),
            chatDao = db.chatDao(),
            outboundBatchDao = db.outboundBatchDao(),
            relayClient = relayClient,
            mapper = RelayEventMapper(keyStore::loadGroupKey),
            stateStore = RoomRelayPushStateStore(db.relayPushStateDao()),
        )
        val pullManager = RelayPullManager(
            relayUrl = relayUrl,
            relayClient = relayClient,
            receiver = receiver,
            stateStore = RoomRelayPullStateStore(db.relayCursorDao()),
            allowLegacyRelayPlaintext = allowLegacyRelayPlaintext,
        )
        var totalPushAttempted = 0
        var totalPushAccepted = 0
        var totalPullReceived = 0
        var totalPullAccepted = 0
        var ok = true
        var pushOk = true
        var pullOk = true
        var authFailureCode: String? = null
        val errors = mutableListOf<String>()
        val rejectedReasons = mutableListOf<String>()
        val notes = mutableListOf<String>()
        if (gids.isEmpty()) {
            notes.add("no gids for pull")
        }

        val pushReport = pushManager.pushNewEvents()
        ActivityLog.add(
            label = "Relay push report",
            source = "relay",
            message = "relay=$relayUrl attempted=${pushReport.attempted} accepted=${pushReport.accepted} rejected=${pushReport.rejected} ok=${pushReport.success}",
        )
        totalPushAttempted += pushReport.attempted
        totalPushAccepted += pushReport.accepted
        rejectedReasons.addAll(pushReport.rejectedReasons)
        if (pushReport.success && pushReport.attempted > 0) {
            RelaySyncSettings.markSyncSuccess(appContext)
        }
        if (!pushReport.success) {
            ok = false
            pushOk = false
            errors.add("push $relayUrl: ${pushReport.errorMessage ?: "unknown error"}")
            authFailureCode = authFailureCode ?: pushReport.errorCode
            maybeBlockRelayForAbuse(
                relayUrl = relayUrl,
                operation = "push",
                statusCode = pushReport.statusCode,
                errorCode = pushReport.errorCode,
            )
        }

        gids.forEach { gid ->
            val pullReport = pullManager.pullByCursor(gid)
            ActivityLog.add(
                label = "Relay pull report",
                source = "relay",
                message = "relay=$relayUrl gid=$gid received=${pullReport.received} accepted=${pullReport.accepted} ok=${pullReport.success}",
            )
            totalPullReceived += pullReport.received
            totalPullAccepted += pullReport.accepted
            if (pullReport.success) {
                RelaySyncSettings.markSyncSuccess(appContext)
            }
            if (!pullReport.success) {
                ok = false
                pullOk = false
                errors.add("pull $relayUrl: ${pullReport.errorMessage ?: "unknown error"}")
                authFailureCode = authFailureCode ?: pullReport.errorCode
                maybeBlockRelayForAbuse(
                    relayUrl = relayUrl,
                    operation = "pull",
                    statusCode = pullReport.statusCode,
                    errorCode = pullReport.errorCode,
                )
            }
            val missingRoots = (
                db.pendingCanonicalMapEventDao()
                    .listPendingRootEventIds(gid, MAX_CANONICAL_REPAIR_ROOTS_PER_CYCLE) +
                    db.eventsDao()
                        .listPendingMapActionRootEventIds(gid, MAX_CANONICAL_REPAIR_ROOTS_PER_CYCLE)
                )
                .distinct()
                .take(MAX_CANONICAL_REPAIR_ROOTS_PER_CYCLE)
            if (missingRoots.isNotEmpty()) {
                ActivityLog.add(
                    label = "Map canonical repair pull started",
                    source = "relay",
                    message = "gid=$gid roots=${missingRoots.joinToString(",")}",
                    gid = gid,
                )
                val repairReport = pullManager.repairByEventIds(gid, missingRoots)
                ActivityLog.add(
                    label = "Map canonical repair pull finished",
                    source = "relay",
                    message = "gid=$gid requested=${missingRoots.size} received=${repairReport.received} " +
                        "accepted=${repairReport.accepted} ok=${repairReport.success}",
                    gid = gid,
                )
                totalPullReceived += repairReport.received
                totalPullAccepted += repairReport.accepted
                if (!repairReport.success) {
                    ok = false
                    pullOk = false
                    errors.add("repair $relayUrl: ${repairReport.errorMessage ?: "unknown error"}")
                    authFailureCode = authFailureCode ?: repairReport.errorCode
                }
            }
        }

        val report = finalizeReport(
                startMs = startMs,
                pushOk = pushOk,
                pullOk = pullOk,
                totalPushAttempted = totalPushAttempted,
                totalPushAccepted = totalPushAccepted,
                totalPullReceived = totalPullReceived,
                totalPullAccepted = totalPullAccepted,
                ok = ok,
                errors = errors,
                rejectedReasons = rejectedReasons,
                relayCount = relayCount,
                gidCount = gids.size,
                notes = notes,
            )
        if (report.success) {
            KnownRelayStore.recordSuccess(appContext, relayUrl)
        }
        return ManagedRelayCycleResult(
            report = report,
            authFailureCode = authFailureCode,
        )
    }

    private fun finalizeReport(
        startMs: Long,
        pushOk: Boolean,
        pullOk: Boolean,
        totalPushAttempted: Int,
        totalPushAccepted: Int,
        totalPullReceived: Int,
        totalPullAccepted: Int,
        ok: Boolean,
        errors: List<String>,
        rejectedReasons: List<String>,
        relayCount: Int,
        gidCount: Int,
        notes: List<String>,
    ): RelaySyncRunReport {
        RelaySyncSettings.markSyncOutcome(appContext, pushOk, pullOk)
        if (pushOk != pullOk) {
            Log.w(TAG, "Relay sync partial: pushOk=$pushOk pullOk=$pullOk")
        } else {
            Log.i(TAG, "Relay sync outcome: pushOk=$pushOk pullOk=$pullOk")
        }
        ActivityLog.add(
            label = "Relay sync outcome",
            source = "relay",
            message = "pushOk=$pushOk pullOk=$pullOk elapsedMs=${System.currentTimeMillis() - startMs}",
        )
        return RelaySyncRunReport(
            pushAttempted = totalPushAttempted,
            pushAccepted = totalPushAccepted,
            pullReceived = totalPullReceived,
            pullAccepted = totalPullAccepted,
            success = ok,
            errorSummary = errors.distinct().joinToString(" | "),
            rejectedSummary = rejectedReasons
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(3)
                .joinToString(", ")
                .ifBlank { null },
            relayCount = relayCount,
            gidCount = gidCount,
            note = notes.distinct().joinToString(", ").ifBlank { null },
        )
    }

    private fun maybeBlockRelayForAbuse(
        relayUrl: String,
        operation: String,
        statusCode: Int,
        errorCode: String?,
    ) {
        val reason = RelayLocalBlockPolicy.blockReasonFor(statusCode, errorCode) ?: return
        val entry = RelayLocalBlockStore.block(appContext, relayUrl, reason) ?: return
        ActivityLog.add(
            label = "Relay blocked",
            source = "relay",
            message = "relay=$relayUrl operation=$operation status=$statusCode code=${errorCode ?: "none"} until=${entry.expiresAtMs}",
        )
        ManagedRelayAccessStore.load(appContext)
            ?.takeIf { it.relayUrl == relayUrl }
            ?.also { ManagedRelayAccessStore.clear(appContext) }
        RelayRegistryAbuseReporter.submitBestEffort(
            context = appContext,
            relayUrl = relayUrl,
            blockReason = reason,
            errorCode = errorCode,
            counters = mapOf(
                "status_code" to statusCode,
            ),
        )
    }

    companion object {
        private const val MAX_CANONICAL_REPAIR_ROOTS_PER_CYCLE = 32
        private const val TAG = "RelaySyncRunner"
        private val IN_FLIGHT = java.util.concurrent.atomic.AtomicBoolean(false)
        private val PENDING = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

data class RelaySyncRunReport(
    val pushAttempted: Int,
    val pushAccepted: Int,
    val pullReceived: Int,
    val pullAccepted: Int,
    val success: Boolean,
    val errorSummary: String?,
    val rejectedSummary: String?,
    val relayCount: Int,
    val gidCount: Int,
    val note: String?,
) {
    companion object {
        fun empty() = RelaySyncRunReport(0, 0, 0, 0, true, null, null, 0, 0, null)
    }
}
