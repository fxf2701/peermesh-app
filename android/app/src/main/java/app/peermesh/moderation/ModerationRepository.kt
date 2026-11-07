package app.peermesh.moderation

import android.content.Context
import app.peermesh.BuildConfig
import app.peermesh.DeviceIdProvider
import app.peermesh.config.EnvironmentLoader
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.BlockedPeerEntity
import app.peermesh.data.db.entity.ChatMessageEntity
import app.peermesh.data.db.entity.ModerationActionEntity
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.data.db.entity.PendingModerationReportEntity
import app.peermesh.data.debug.ActivityLog
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.net.RequestSigning
import app.peermesh.moderation.BlockedPeerScopes.GLOBAL_LOCAL
import app.peermesh.moderation.BlockedPeerScopes.GROUP_LOCAL
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ModerationRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val db by lazy { AppDatabaseProvider.get(appContext) }

    fun hiddenMessagePeerIds(groupId: String): Set<String> {
        val nowMs = System.currentTimeMillis()
        return buildSet {
            addAll(db.blockedPeerDao().listGlobalMessageHiddenPeerIds(nowMs, scope = GLOBAL_LOCAL))
            addAll(db.blockedPeerDao().listMessageHiddenPeerIds(groupId, nowMs, scope = GROUP_LOCAL))
        }
    }

    fun hiddenLocationPeerIds(groupId: String): Set<String> {
        val nowMs = System.currentTimeMillis()
        return buildSet {
            addAll(db.blockedPeerDao().listGlobalLocationHiddenPeerIds(nowMs, scope = GLOBAL_LOCAL))
            addAll(db.blockedPeerDao().listLocationHiddenPeerIds(groupId, nowMs, scope = GROUP_LOCAL))
        }
    }

    fun listBlockedPeers(groupId: String): List<BlockedPeerSummary> {
        val nowMs = System.currentTimeMillis()
        return mergeBlockedPeerSummaries(db.blockedPeerDao().listAllActive(nowMs))
    }

    fun unblockPeer(groupId: String, targetPeerId: String) {
        db.blockedPeerDao().deleteAllScopes(targetPeerId)
    }

    fun blockPeerLocally(
        groupId: String,
        targetPeerId: String,
        reasonCode: String? = null,
        hideMessages: Boolean = true,
        hideLocation: Boolean = true,
        hideTrail: Boolean = true,
    ) {
        val nowMs = System.currentTimeMillis()
        val actorPeerId = DeviceIdProvider.get(appContext)
        db.runInTransaction {
            upsertBlockRecord(
                sourceGroupId = groupId,
                targetPeerId = targetPeerId,
                reasonCode = reasonCode,
                hideMessages = hideMessages,
                hideLocation = hideLocation,
                hideTrail = hideTrail,
                actorPeerId = actorPeerId,
                nowMs = nowMs,
                actionType = ACTION_LOCAL_BLOCK,
                blockedGroupId = null,
                scope = GLOBAL_LOCAL,
            )
        }
    }

    fun hidePeerLocation(groupId: String, targetPeerId: String) {
        val nowMs = System.currentTimeMillis()
        val actorPeerId = DeviceIdProvider.get(appContext)
        db.runInTransaction {
            upsertBlockRecord(
                sourceGroupId = groupId,
                targetPeerId = targetPeerId,
                reasonCode = null,
                hideMessages = false,
                hideLocation = true,
                hideTrail = false,
                actorPeerId = actorPeerId,
                nowMs = nowMs,
                actionType = ACTION_HIDE_LOCATION,
                blockedGroupId = groupId,
                scope = GROUP_LOCAL,
            )
        }
    }

    fun hidePeerTrail(groupId: String, targetPeerId: String) {
        val nowMs = System.currentTimeMillis()
        val actorPeerId = DeviceIdProvider.get(appContext)
        db.runInTransaction {
            upsertBlockRecord(
                sourceGroupId = groupId,
                targetPeerId = targetPeerId,
                reasonCode = null,
                hideMessages = false,
                hideLocation = false,
                hideTrail = true,
                actorPeerId = actorPeerId,
                nowMs = nowMs,
                actionType = ACTION_HIDE_TRAIL,
                blockedGroupId = groupId,
                scope = GROUP_LOCAL,
            )
        }
    }

    fun createPeerReport(
        groupId: String,
        reportedPeerId: String,
        reasonCode: String,
        userNote: String?,
        alsoBlock: Boolean,
    ): String {
        val reportId = UUID.randomUUID().toString()
        val nowMs = System.currentTimeMillis()
        val actorPeerId = DeviceIdProvider.get(appContext)
        val entity = PendingModerationReportEntity(
            reportId = reportId,
            groupId = groupId,
            reporterPeerId = actorPeerId,
            reportedPeerId = reportedPeerId,
            targetType = TARGET_TYPE_PEER,
            messageId = null,
            eventId = null,
            reasonCode = reasonCode,
            userNote = userNote?.trim()?.ifBlank { null },
            includePlaintext = false,
            plaintextExcerpt = null,
            ciphertextRef = null,
            deliveryState = DELIVERY_PENDING,
            attemptCount = 0,
            createdAtMs = nowMs,
            lastAttemptAtMs = null,
            deliveredAtMs = null,
        )
        db.runInTransaction {
            db.moderationReportDao().upsert(entity)
            db.moderationActionDao().upsert(
                ModerationActionEntity(
                    actionId = UUID.randomUUID().toString(),
                    groupId = groupId,
                    actorPeerId = actorPeerId,
                    targetPeerId = reportedPeerId,
                    actionType = ACTION_REPORT_PEER,
                    createdAtMs = nowMs,
                    metadataJson = CanonicalJson.json.encodeToString(
                        ReportActionMetadata(
                            reportId = reportId,
                            reasonCode = reasonCode,
                            includePlaintext = false,
                        )
                    ),
                )
            )
            if (alsoBlock) {
                upsertBlockRecord(
                    sourceGroupId = groupId,
                    targetPeerId = reportedPeerId,
                    reasonCode = reasonCode,
                    hideMessages = true,
                    hideLocation = true,
                    hideTrail = true,
                    actorPeerId = actorPeerId,
                    nowMs = nowMs,
                    actionType = ACTION_LOCAL_BLOCK,
                    blockedGroupId = null,
                    scope = GLOBAL_LOCAL,
                )
            }
        }
        ModerationSyncWorkManager.enqueue(appContext)
        return reportId
    }

    fun createMessageReport(
        groupId: String,
        message: ChatMessageEntity,
        reasonCode: String,
        userNote: String?,
        includePlaintext: Boolean,
        alsoBlock: Boolean,
    ): String {
        val reportId = UUID.randomUUID().toString()
        val nowMs = System.currentTimeMillis()
        val actorPeerId = DeviceIdProvider.get(appContext)
        val entity = PendingModerationReportEntity(
            reportId = reportId,
            groupId = groupId,
            reporterPeerId = actorPeerId,
            reportedPeerId = message.actor_did,
            targetType = TARGET_TYPE_MESSAGE,
            messageId = message.message_id,
            eventId = message.last_event_id,
            reasonCode = reasonCode,
            userNote = userNote?.trim()?.ifBlank { null },
            includePlaintext = includePlaintext,
            plaintextExcerpt = message.text.take(MAX_PLAINTEXT_EXCERPT).takeIf { includePlaintext },
            ciphertextRef = message.last_event_id,
            deliveryState = DELIVERY_PENDING,
            attemptCount = 0,
            createdAtMs = nowMs,
            lastAttemptAtMs = null,
            deliveredAtMs = null,
        )
        db.runInTransaction {
            db.moderationReportDao().upsert(entity)
            db.moderationActionDao().upsert(
                ModerationActionEntity(
                    actionId = UUID.randomUUID().toString(),
                    groupId = groupId,
                    actorPeerId = actorPeerId,
                    targetPeerId = message.actor_did,
                    actionType = ACTION_REPORT_MESSAGE,
                    createdAtMs = nowMs,
                    metadataJson = CanonicalJson.json.encodeToString(
                        ReportActionMetadata(
                            reportId = reportId,
                            reasonCode = reasonCode,
                            includePlaintext = includePlaintext,
                        )
                    ),
                )
            )
            if (alsoBlock) {
                upsertBlockRecord(
                    sourceGroupId = groupId,
                    targetPeerId = message.actor_did,
                    reasonCode = reasonCode,
                    hideMessages = true,
                    hideLocation = true,
                    hideTrail = true,
                    actorPeerId = actorPeerId,
                    nowMs = nowMs,
                    actionType = ACTION_LOCAL_BLOCK,
                    blockedGroupId = null,
                    scope = GLOBAL_LOCAL,
                )
            }
        }
        ModerationSyncWorkManager.enqueue(appContext)
        return reportId
    }

    fun createNearbyRoomPrivateInvitationReport(
        roomId: String,
        messageId: String,
        reportedPeerId: String,
        reasonCode: String,
        userNote: String?,
        alsoBlock: Boolean,
    ): String? {
        val message = db.nearbyRoomMessageDao().findMessage(roomId, messageId) ?: return null
        val localId = DeviceIdProvider.get(appContext)
        val logicalSenderId = NearbyPrivateGroupInvitationReportValidator.validatedReportedOwnerId(
            message = message,
            reportedPeerId = reportedPeerId,
            localParticipantId = localId,
        ) ?: return null

        val reportId = UUID.randomUUID().toString()
        val nowMs = System.currentTimeMillis()
        val entity = PendingModerationReportEntity(
            reportId = reportId,
            groupId = roomId,
            reporterPeerId = localId,
            reportedPeerId = logicalSenderId,
            targetType = TARGET_TYPE_MESSAGE,
            messageId = messageId,
            eventId = null,
            reasonCode = reasonCode,
            userNote = userNote?.trim()?.ifBlank { null },
            includePlaintext = false,
            plaintextExcerpt = null,
            ciphertextRef = messageId,
            deliveryState = DELIVERY_PENDING,
            attemptCount = 0,
            createdAtMs = nowMs,
            lastAttemptAtMs = null,
            deliveredAtMs = null,
        )
        db.runInTransaction {
            db.moderationReportDao().upsert(entity)
            db.moderationActionDao().upsert(
                ModerationActionEntity(
                    actionId = UUID.randomUUID().toString(),
                    groupId = roomId,
                    actorPeerId = localId,
                    targetPeerId = logicalSenderId,
                    actionType = ACTION_REPORT_MESSAGE,
                    createdAtMs = nowMs,
                    metadataJson = CanonicalJson.json.encodeToString(
                        ReportActionMetadata(reportId, reasonCode, false)
                    ),
                )
            )
            if (alsoBlock) {
                upsertBlockRecord(
                    sourceGroupId = roomId, targetPeerId = logicalSenderId, reasonCode = reasonCode,
                    hideMessages = true, hideLocation = true, hideTrail = true, actorPeerId = localId,
                    nowMs = nowMs, actionType = ACTION_LOCAL_BLOCK, blockedGroupId = null, scope = GLOBAL_LOCAL,
                )
            }
        }
        ModerationSyncWorkManager.enqueue(appContext)
        return reportId
    }

    fun createNearbyRoomMessageReport(
        roomId: String,
        message: NearbyRoomMessageEntity,
        reasonCode: String,
        userNote: String?,
        includePlaintext: Boolean,
        alsoBlock: Boolean,
    ): String {
        val reportId = UUID.randomUUID().toString()
        val nowMs = System.currentTimeMillis()
        val actorPeerId = DeviceIdProvider.get(appContext)
        val reportedPeerId = message.owner_device_id ?: message.sender_device_id
        val mayIncludeBody = includePlaintext &&
            !GroupInvitationProtocol.isExactGroupInvitationCandidate(message.body)
        val entity = PendingModerationReportEntity(
            reportId = reportId,
            groupId = roomId,
            reporterPeerId = actorPeerId,
            reportedPeerId = reportedPeerId,
            targetType = TARGET_TYPE_MESSAGE,
            messageId = message.message_id,
            eventId = null,
            reasonCode = reasonCode,
            userNote = userNote?.trim()?.ifBlank { null },
            includePlaintext = mayIncludeBody,
            plaintextExcerpt = message.body.take(MAX_PLAINTEXT_EXCERPT).takeIf { mayIncludeBody },
            ciphertextRef = message.message_id,
            deliveryState = DELIVERY_PENDING,
            attemptCount = 0,
            createdAtMs = nowMs,
            lastAttemptAtMs = null,
            deliveredAtMs = null,
        )
        db.runInTransaction {
            db.moderationReportDao().upsert(entity)
            db.moderationActionDao().upsert(
                ModerationActionEntity(
                    actionId = UUID.randomUUID().toString(),
                    groupId = roomId,
                    actorPeerId = actorPeerId,
                    targetPeerId = reportedPeerId,
                    actionType = ACTION_REPORT_MESSAGE,
                    createdAtMs = nowMs,
                    metadataJson = CanonicalJson.json.encodeToString(
                        ReportActionMetadata(
                            reportId = reportId,
                            reasonCode = reasonCode,
                            includePlaintext = includePlaintext,
                        )
                    ),
                )
            )
            if (alsoBlock) {
                upsertBlockRecord(
                    sourceGroupId = roomId,
                    targetPeerId = reportedPeerId,
                    reasonCode = reasonCode,
                    hideMessages = true,
                    hideLocation = true,
                    hideTrail = true,
                    actorPeerId = actorPeerId,
                    nowMs = nowMs,
                    actionType = ACTION_LOCAL_BLOCK,
                    blockedGroupId = null,
                    scope = GLOBAL_LOCAL,
                )
            }
        }
        ModerationSyncWorkManager.enqueue(appContext)
        return reportId
    }

    fun syncPendingReports(limit: Int = 10): SyncSummary {
        val endpointBaseUrl = EnvironmentLoader.load().registryBaseUrl.trim().trimEnd('/')
        if (endpointBaseUrl.isBlank()) {
            return SyncSummary(
                sent = 0,
                pending = db.moderationReportDao().listPending(limit).size,
                endpointConfigured = false,
                retryPending = false,
            )
        }
        val nowMs = System.currentTimeMillis()
        val pending = db.moderationReportDao().listPending(limit)
        val eligible = pending.filter { report -> isRetryEligible(report, nowMs) }
        var sent = 0
        eligible.forEach { report ->
            val attemptNowMs = System.currentTimeMillis()
            val nextAttempt = report.attemptCount + 1
            val result = submitReport(endpointBaseUrl, report)
            when {
                result.success -> {
                    db.moderationReportDao().updateDeliveryState(
                        reportId = report.reportId,
                        deliveryState = DELIVERY_DELIVERED,
                        attemptCount = nextAttempt,
                        lastAttemptAtMs = attemptNowMs,
                        deliveredAtMs = attemptNowMs,
                    )
                    sent += 1
                }
                result.permanentFailure -> {
                    db.moderationReportDao().updateDeliveryState(
                        reportId = report.reportId,
                        deliveryState = DELIVERY_FAILED_PERMANENT,
                        attemptCount = nextAttempt,
                        lastAttemptAtMs = attemptNowMs,
                        deliveredAtMs = null,
                    )
                }
                else -> {
                    db.moderationReportDao().updateDeliveryState(
                        reportId = report.reportId,
                        deliveryState = DELIVERY_RETRY_WAITING,
                        attemptCount = nextAttempt,
                        lastAttemptAtMs = attemptNowMs,
                        deliveredAtMs = null,
                    )
                }
            }
            ActivityLog.add(
                label = if (result.success) "Moderation report sent" else "Moderation report pending",
                source = "moderation",
                message = buildString {
                    append("reportId=${report.reportId}")
                    append(" status=${result.statusCode}")
                    append(" permanent=${result.permanentFailure}")
                    result.reportStatus?.let { append(" reportStatus=$it") }
                    result.duplicateCollapsed?.let { append(" duplicate=$it") }
                    result.errorCode?.let { append(" errorCode=$it") }
                },
                gid = report.groupId,
                eventId = report.eventId,
            )
        }
        val remainingPendingCount = db.moderationReportDao().listPending(limit).size
        return SyncSummary(
            sent = sent,
            pending = remainingPendingCount,
            endpointConfigured = true,
            retryPending = remainingPendingCount > 0,
        )
    }

    private fun isRetryEligible(
        report: PendingModerationReportEntity,
        nowMs: Long,
    ): Boolean {
        return ModerationReportSyncPolicy.isRetryEligible(
            deliveryState = report.deliveryState,
            attemptCount = report.attemptCount,
            lastAttemptAtMs = report.lastAttemptAtMs,
            nowMs = nowMs,
        )
    }

    private fun upsertBlockRecord(
        sourceGroupId: String,
        targetPeerId: String,
        reasonCode: String?,
        hideMessages: Boolean,
        hideLocation: Boolean,
        hideTrail: Boolean,
        actorPeerId: String,
        nowMs: Long,
        actionType: String,
        blockedGroupId: String?,
        scope: String,
    ) {
        val existing = if (blockedGroupId == null) {
            db.blockedPeerDao().findGlobal(targetPeerId, nowMs, scope = scope)
        } else {
            db.blockedPeerDao().findScoped(targetPeerId, blockedGroupId, nowMs, scope = scope)
        }
        val entity = BlockedPeerEntity(
            id = existing?.id ?: 0L,
            blockedPeerId = targetPeerId,
            scope = scope,
            groupId = blockedGroupId,
            hideMessages = existing?.hideMessages == true || hideMessages,
            hideLocation = existing?.hideLocation == true || hideLocation,
            hideTrail = existing?.hideTrail == true || hideTrail,
            reasonCode = reasonCode ?: existing?.reasonCode,
            createdAtMs = existing?.createdAtMs ?: nowMs,
            updatedAtMs = nowMs,
            expiresAtMs = null,
        )
        db.blockedPeerDao().upsert(entity)
        db.moderationActionDao().upsert(
            ModerationActionEntity(
                actionId = UUID.randomUUID().toString(),
                groupId = sourceGroupId,
                actorPeerId = actorPeerId,
                targetPeerId = targetPeerId,
                actionType = actionType,
                createdAtMs = nowMs,
                metadataJson = CanonicalJson.json.encodeToString(
                    ActionMetadata(
                        hideMessages = entity.hideMessages,
                        hideLocation = entity.hideLocation,
                        hideTrail = entity.hideTrail,
                        reasonCode = entity.reasonCode,
                    )
                ),
            )
        )
    }

    private fun submitReport(
        endpointBaseUrl: String,
        report: PendingModerationReportEntity,
    ): SubmitResult {
        val payload = CanonicalJson.json.encodeToString(
            ModerationReportPayload(
                report_id = report.reportId,
                group_id = report.groupId,
                reporter_peer_id = report.reporterPeerId,
                reported_peer_id = report.reportedPeerId,
                target_type = report.targetType,
                message_id = report.messageId,
                event_id = report.eventId,
                reason_code = report.reasonCode,
                user_note = report.userNote,
                include_plaintext = report.includePlaintext,
                plaintext_excerpt = report.plaintextExcerpt,
                ciphertext_ref = report.ciphertextRef,
                created_at_ms = report.createdAtMs,
                app_version = BuildConfig.VERSION_NAME,
                protocol_version = PROTOCOL_VERSION,
            )
        )
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val requestTarget = REPORT_PATH
        val url = URL(endpointBaseUrl + requestTarget)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            applyRegistryRequestSignature(requestTarget, payloadBytes)
        }
        return try {
            connection.outputStream.use { it.write(payloadBytes) }
            val status = connection.responseCode
            val responseText = readResponseText(connection, status)
            val response = parseRegistryResponse(responseText)
            if (status in 200..299) {
                SubmitResult(
                    success = response?.ok != false,
                    permanentFailure = false,
                    statusCode = status,
                    reportStatus = response?.report?.status,
                    duplicateCollapsed = response?.duplicate_collapsed,
                    errorCode = response?.error?.code,
                )
            } else {
                SubmitResult(
                    success = false,
                    permanentFailure = ModerationReportSyncPolicy.isPermanentFailureStatus(status),
                    statusCode = status,
                    reportStatus = response?.report?.status,
                    duplicateCollapsed = response?.duplicate_collapsed,
                    errorCode = response?.error?.code,
                )
            }
        } catch (_: Exception) {
            SubmitResult(success = false, permanentFailure = false, statusCode = 0)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponseText(connection: HttpURLConnection, status: Int): String? {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.use { BufferedReader(InputStreamReader(it)).readText() }?.ifBlank { null }
    }

    private fun parseRegistryResponse(responseText: String?): RegistryModerationSubmitResponse? {
        if (responseText.isNullOrBlank()) return null
        return runCatching {
            CanonicalJson.json.decodeFromString<RegistryModerationSubmitResponse>(responseText)
        }.getOrNull()
    }

    private fun HttpURLConnection.applyRegistryRequestSignature(
        requestTarget: String,
        payloadBytes: ByteArray,
    ) {
        val secret = EnvironmentLoader.load().registryRequestSignatureSecret.trim()
        if (secret.isBlank()) return
        val timestampSeconds = (System.currentTimeMillis() / 1000L).toString()
        val signature = RequestSigning.signHmacSha256Hex(
            secret = secret,
            method = "POST",
            requestTarget = requestTarget,
            timestampSeconds = timestampSeconds,
            bodyBytes = payloadBytes,
        )
        setRequestProperty("X-Registry-Timestamp", timestampSeconds)
        setRequestProperty("X-Registry-Signature", signature)
    }

    @Serializable
    private data class ModerationReportPayload(
        val report_id: String,
        val group_id: String,
        val reporter_peer_id: String,
        val reported_peer_id: String,
        val target_type: String,
        val message_id: String?,
        val event_id: String?,
        val reason_code: String,
        val user_note: String?,
        val include_plaintext: Boolean,
        val plaintext_excerpt: String?,
        val ciphertext_ref: String?,
        val created_at_ms: Long,
        val app_version: String,
        val protocol_version: Int,
    )

    @Serializable
    private data class ActionMetadata(
        val hideMessages: Boolean,
        val hideLocation: Boolean,
        val hideTrail: Boolean,
        val reasonCode: String?,
    )

    @Serializable
    private data class ReportActionMetadata(
        val reportId: String,
        val reasonCode: String,
        val includePlaintext: Boolean,
    )

    data class SyncSummary(
        val sent: Int,
        val pending: Int,
        val endpointConfigured: Boolean,
        val retryPending: Boolean,
    )

    data class BlockedPeerSummary(
        val peerId: String,
        val hideMessages: Boolean,
        val hideLocation: Boolean,
        val hideTrail: Boolean,
        val updatedAtMs: Long,
    )

    private fun mergeBlockedPeerSummaries(
        entities: List<BlockedPeerEntity>,
    ): List<BlockedPeerSummary> {
        return entities
            .groupBy { it.blockedPeerId }
            .map { (peerId, rows) ->
                BlockedPeerSummary(
                    peerId = peerId,
                    hideMessages = rows.any { it.hideMessages },
                    hideLocation = rows.any { it.hideLocation },
                    hideTrail = rows.any { it.hideTrail },
                    updatedAtMs = rows.maxOf { it.updatedAtMs },
                )
            }
            .sortedByDescending { it.updatedAtMs }
    }

    private data class SubmitResult(
        val success: Boolean,
        val permanentFailure: Boolean,
        val statusCode: Int,
        val reportStatus: String? = null,
        val duplicateCollapsed: Boolean? = null,
        val errorCode: String? = null,
    )

    @Serializable
    private data class RegistryModerationSubmitResponse(
        val ok: Boolean? = null,
        val report: RegistryModerationSubmitReport? = null,
        val duplicate_collapsed: Boolean? = null,
        val error: RegistryModerationSubmitError? = null,
    )

    @Serializable
    private data class RegistryModerationSubmitReport(
        val report_id: String? = null,
        val status: String? = null,
    )

    @Serializable
    private data class RegistryModerationSubmitError(
        val code: String? = null,
        val message: String? = null,
    )

    companion object {
        private const val ACTION_LOCAL_BLOCK = "local_block"
        private const val ACTION_HIDE_LOCATION = "hide_location"
        private const val ACTION_HIDE_TRAIL = "hide_trail"
        private const val ACTION_REPORT_PEER = "report_peer"
        private const val ACTION_REPORT_MESSAGE = "report_message"
        private const val TARGET_TYPE_PEER = "peer"
        private const val TARGET_TYPE_MESSAGE = "message"
        private const val DELIVERY_PENDING = "pending"
        private const val DELIVERY_RETRY_WAITING = "retry_waiting"
        private const val DELIVERY_DELIVERED = "delivered"
        private const val DELIVERY_FAILED_PERMANENT = "failed_permanent"
        private const val REPORT_PATH = "/v1/registry/client/moderation-reports"
        private const val PROTOCOL_VERSION = 1
        private const val MAX_PLAINTEXT_EXCERPT = 280
    }
}
