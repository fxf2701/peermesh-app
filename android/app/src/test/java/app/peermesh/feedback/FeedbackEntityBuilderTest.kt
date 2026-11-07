package app.peermesh.feedback

import app.peermesh.data.db.entity.PendingFeedbackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FeedbackEntityBuilderTest {
    @Test
    fun `reuses feedback id for duplicate draft`() {
        val existing = PendingFeedbackEntity(
            feedbackId = "feedback-1",
            category = "general",
            feedbackType = "user_feedback",
            message = "original",
            createdAtMs = 10L,
            appVersion = "0.1.0",
            buildNumber = "1",
            androidVersion = "Android 15",
            deviceModel = "Pixel",
            localDeviceId = "device-1",
            source = "android_beta",
            deliveryState = "retry_waiting",
            attemptCount = 2,
            lastAttemptAtMs = 20L,
            deliveredAtMs = null,
        )

        val merged = FeedbackEntityBuilder.build(
            existing = existing,
            draft = FeedbackDraft(
                category = "bug",
                feedbackType = "crash_report",
                message = "updated",
                createdAtMs = 30L,
                appVersion = "0.1.1",
                buildNumber = "2",
                androidVersion = "Android 15",
                deviceModel = "Pixel",
                localDeviceId = "device-1",
                source = "android_beta",
            ),
        )

        assertEquals("feedback-1", merged.feedbackId)
        assertEquals(10L, merged.createdAtMs)
        assertEquals("crash_report", merged.feedbackType)
        assertEquals("retry_waiting", merged.deliveryState)
        assertEquals(2, merged.attemptCount)
    }

    @Test
    fun `creates new feedback id when no existing item`() {
        val created = FeedbackEntityBuilder.build(
            existing = null,
            draft = FeedbackDraft(
                category = "general",
                feedbackType = "user_feedback",
                message = "new",
                createdAtMs = 30L,
                appVersion = "0.1.1",
                buildNumber = "2",
                androidVersion = "Android 15",
                deviceModel = "Pixel",
                localDeviceId = "device-1",
                source = "android_beta",
            ),
        )

        assertNotEquals("", created.feedbackId)
        assertEquals("user_feedback", created.feedbackType)
        assertEquals("pending", created.deliveryState)
        assertEquals(0, created.attemptCount)
    }
}
