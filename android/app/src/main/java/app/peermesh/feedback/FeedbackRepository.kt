package app.peermesh.feedback

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import app.peermesh.BuildConfig
import app.peermesh.DeviceIdProvider
import app.peermesh.config.EnvironmentLoader
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.net.RequestSigning
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.PendingFeedbackEntity
import app.peermesh.data.debug.ActivityLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class FeedbackRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val db by lazy { AppDatabaseProvider.get(appContext) }

    fun submitFeedback(
        message: String,
        category: String,
        feedbackId: String? = null,
        source: String = SOURCE_ANDROID_BETA,
        feedbackType: String = TYPE_USER_FEEDBACK,
    ): SubmitUiResult {
        val normalizedMessage = message.trim()
        val normalizedCategory = category.trim().ifBlank { CATEGORY_GENERAL }
        val normalizedSource = source.trim().ifBlank { SOURCE_ANDROID_BETA }
        val normalizedFeedbackType = feedbackType.trim().ifBlank { TYPE_USER_FEEDBACK }
        require(normalizedMessage.isNotBlank()) { "Feedback message must not be blank." }
        val existing = feedbackId?.let { db.feedbackDao().findById(it) }
        val nowMs = System.currentTimeMillis()
        val entity = FeedbackEntityBuilder.build(
            existing = existing,
            draft = FeedbackDraft(
                category = normalizedCategory,
                feedbackType = normalizedFeedbackType,
                message = normalizedMessage,
                createdAtMs = nowMs,
                appVersion = BuildConfig.VERSION_NAME.takeIf { it.isNotBlank() },
                buildNumber = loadBuildNumber(),
                androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                localDeviceId = DeviceIdProvider.get(appContext).takeIf { it.isNotBlank() },
                source = normalizedSource,
            ),
        )
        db.feedbackDao().upsert(entity)
        val result = syncFeedback(entity.feedbackId)
        FeedbackSyncWorkManager.enqueue(appContext)
        return result
    }

    fun syncPendingFeedback(limit: Int = 10): SyncSummary {
        val endpointBaseUrl = EnvironmentLoader.load().registryBaseUrl.trim().trimEnd('/')
        if (endpointBaseUrl.isBlank()) {
            return SyncSummary(
                sent = 0,
                pending = db.feedbackDao().listPending(limit).size,
                endpointConfigured = false,
                retryPending = false,
            )
        }
        val nowMs = System.currentTimeMillis()
        val pending = db.feedbackDao().listPending(limit)
        val eligible = pending.filter {
            FeedbackSyncPolicy.isRetryEligible(
                deliveryState = it.deliveryState,
                attemptCount = it.attemptCount,
                lastAttemptAtMs = it.lastAttemptAtMs,
                nowMs = nowMs,
            )
        }
        var sent = 0
        eligible.forEach { feedback ->
            val result = syncFeedback(feedback.feedbackId)
            if (result.success) sent += 1
        }
        val remaining = db.feedbackDao().listPending(limit).size
        return SyncSummary(
            sent = sent,
            pending = remaining,
            endpointConfigured = true,
            retryPending = remaining > 0,
        )
    }

    private fun syncFeedback(feedbackId: String): SubmitUiResult {
        val feedback = db.feedbackDao().findById(feedbackId)
            ?: return SubmitUiResult(
                feedbackId = feedbackId,
                success = false,
                queuedForRetry = false,
                errorCode = "feedback_not_found",
            )
        val endpointBaseUrl = EnvironmentLoader.load().registryBaseUrl.trim().trimEnd('/')
        if (endpointBaseUrl.isBlank()) {
            return SubmitUiResult(
                feedbackId = feedback.feedbackId,
                success = false,
                queuedForRetry = true,
                errorCode = "registry_base_url_missing",
            )
        }
        val attemptNowMs = System.currentTimeMillis()
        val nextAttempt = feedback.attemptCount + 1
        val result = submitFeedbackToRegistry(endpointBaseUrl, feedback)
        when {
            result.success -> {
                db.feedbackDao().updateDeliveryState(
                    feedbackId = feedback.feedbackId,
                    deliveryState = FeedbackSyncPolicy.DELIVERY_DELIVERED,
                    attemptCount = nextAttempt,
                    lastAttemptAtMs = attemptNowMs,
                    deliveredAtMs = attemptNowMs,
                )
            }
            result.permanentFailure -> {
                db.feedbackDao().updateDeliveryState(
                    feedbackId = feedback.feedbackId,
                    deliveryState = FeedbackSyncPolicy.DELIVERY_FAILED_PERMANENT,
                    attemptCount = nextAttempt,
                    lastAttemptAtMs = attemptNowMs,
                    deliveredAtMs = null,
                )
            }
            else -> {
                db.feedbackDao().updateDeliveryState(
                    feedbackId = feedback.feedbackId,
                    deliveryState = FeedbackSyncPolicy.DELIVERY_RETRY_WAITING,
                    attemptCount = nextAttempt,
                    lastAttemptAtMs = attemptNowMs,
                    deliveredAtMs = null,
                )
            }
        }
        ActivityLog.add(
            label = if (result.success) "Feedback sent" else "Feedback pending",
            source = "feedback",
            message = buildString {
                append("feedbackId=${feedback.feedbackId}")
                append(" status=${result.statusCode}")
                append(" permanent=${result.permanentFailure}")
                result.errorCode?.let { append(" errorCode=$it") }
            },
        )
        return SubmitUiResult(
            feedbackId = feedback.feedbackId,
            success = result.success,
            queuedForRetry = !result.success && !result.permanentFailure,
            errorCode = result.errorCode,
        )
    }

    private fun submitFeedbackToRegistry(
        endpointBaseUrl: String,
        feedback: PendingFeedbackEntity,
    ): SubmitResult {
        val payload = CanonicalJson.json.encodeToString(
            FeedbackPayload(
                feedback_id = feedback.feedbackId,
                category = feedback.category,
                feedback_type = feedback.feedbackType,
                message = feedback.message,
                created_at = feedback.createdAtMs,
                app_version = feedback.appVersion,
                build_number = feedback.buildNumber,
                android_version = feedback.androidVersion,
                device_model = feedback.deviceModel,
                local_device_id = feedback.localDeviceId,
                source = feedback.source,
            )
        )
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val url = URL(endpointBaseUrl + FEEDBACK_PATH)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            applyRegistryRequestSignature(FEEDBACK_PATH, payloadBytes)
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
                    errorCode = response?.error?.code,
                )
            } else {
                SubmitResult(
                    success = false,
                    permanentFailure = FeedbackSyncPolicy.isPermanentFailureStatus(status),
                    statusCode = status,
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

    private fun parseRegistryResponse(responseText: String?): RegistryFeedbackResponse? {
        if (responseText.isNullOrBlank()) return null
        return runCatching {
            CanonicalJson.json.decodeFromString<RegistryFeedbackResponse>(responseText)
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

    private fun loadBuildNumber(): String? {
        return runCatching {
            val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            PackageInfoCompat.getLongVersionCode(packageInfo).toString()
        }.getOrNull()
    }

    @Serializable
    private data class FeedbackPayload(
        val feedback_id: String,
        val category: String,
        val feedback_type: String,
        val message: String,
        val created_at: Long,
        val app_version: String?,
        val build_number: String?,
        val android_version: String?,
        val device_model: String?,
        val local_device_id: String?,
        val source: String,
    )

    @Serializable
    private data class RegistryFeedbackResponse(
        val ok: Boolean? = null,
        val feedback: RegistryFeedbackItem? = null,
        val duplicate_collapsed: Boolean? = null,
        val error: RegistryFeedbackError? = null,
    )

    @Serializable
    private data class RegistryFeedbackItem(
        val feedback_id: String? = null,
        val status: String? = null,
    )

    @Serializable
    private data class RegistryFeedbackError(
        val code: String? = null,
        val message: String? = null,
    )

    data class SubmitUiResult(
        val feedbackId: String,
        val success: Boolean,
        val queuedForRetry: Boolean,
        val errorCode: String? = null,
    )

    data class SyncSummary(
        val sent: Int,
        val pending: Int,
        val endpointConfigured: Boolean,
        val retryPending: Boolean,
    )

    private data class SubmitResult(
        val success: Boolean,
        val permanentFailure: Boolean,
        val statusCode: Int,
        val errorCode: String? = null,
    )

    companion object {
        const val CATEGORY_GENERAL = "general"
        const val CATEGORY_BUG = "bug"
        const val CATEGORY_FEATURE_REQUEST = "feature_request"
        const val CATEGORY_NEARBY_ROOMS = "nearby_rooms"
        const val CATEGORY_GROUPS = "groups"
        const val CATEGORY_MAPS_AWARENESS = "maps_awareness"

        const val TYPE_USER_FEEDBACK = "user_feedback"
        const val TYPE_CRASH_REPORT = "crash_report"
        const val SOURCE_ANDROID_BETA = "android_beta"
        private const val FEEDBACK_PATH = "/v1/registry/client/feedback"
    }
}
