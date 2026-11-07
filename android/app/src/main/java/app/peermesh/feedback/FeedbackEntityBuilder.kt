package app.peermesh.feedback

import app.peermesh.data.db.entity.PendingFeedbackEntity
import java.util.UUID

data class FeedbackDraft(
    val category: String,
    val feedbackType: String,
    val message: String,
    val createdAtMs: Long,
    val appVersion: String?,
    val buildNumber: String?,
    val androidVersion: String?,
    val deviceModel: String?,
    val localDeviceId: String?,
    val source: String,
)

object FeedbackEntityBuilder {
    fun build(
        existing: PendingFeedbackEntity?,
        draft: FeedbackDraft,
    ): PendingFeedbackEntity {
        return PendingFeedbackEntity(
            feedbackId = existing?.feedbackId ?: UUID.randomUUID().toString(),
            category = draft.category,
            feedbackType = draft.feedbackType,
            message = draft.message,
            createdAtMs = existing?.createdAtMs ?: draft.createdAtMs,
            appVersion = draft.appVersion,
            buildNumber = draft.buildNumber,
            androidVersion = draft.androidVersion,
            deviceModel = draft.deviceModel,
            localDeviceId = draft.localDeviceId,
            source = draft.source,
            deliveryState = existing?.deliveryState ?: FeedbackSyncPolicy.DELIVERY_PENDING,
            attemptCount = existing?.attemptCount ?: 0,
            lastAttemptAtMs = existing?.lastAttemptAtMs,
            deliveredAtMs = existing?.deliveredAtMs,
        )
    }
}
