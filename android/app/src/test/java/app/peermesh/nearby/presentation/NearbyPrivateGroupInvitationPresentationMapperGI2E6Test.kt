package app.peermesh.nearby.presentation

import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.nearby.NearbyPrivateGroupInvitationReceiveResult
import app.peermesh.nearby.NearbyPrivateGroupInvitationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPrivateGroupInvitationPresentationMapperGI2E6Test {
    private val mapper = NearbyPrivateGroupInvitationPresentationMapper()

    @Test
    fun recipientInvitationMapsToImmutableVisibleCardWithoutCredentialOrExpiry() {
        val projection = mapper.map(
            context = context(
                audience = PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT,
                canMutePeer = true,
            ),
            result = invitationResult(),
        )

        val item = projection.requireItem<NearbyRoomTimelineItem.PrivateInvitation>()
        assertEquals(MESSAGE_ID, item.messageId)
        assertEquals(CREATED_AT_MS, item.createdAtMs)
        assertEquals(SENDER_ID, item.senderId)
        assertEquals("Sender", item.senderDisplayName)
        assertEquals("Emergency Group", item.title)
        assertEquals("Meet at checkpoint", item.shortDescription)
        assertEquals(PrivateInvitationAudienceLabel.RECEIVED_PRIVATELY, item.audienceLabel)
        assertEquals(
            setOf(
                PrivateInvitationAction.OPEN,
                PrivateInvitationAction.HIDE,
                PrivateInvitationAction.REPORT_INVITATION,
                PrivateInvitationAction.REPORT_PEER,
                PrivateInvitationAction.BLOCK_PEER,
                PrivateInvitationAction.MUTE_PEER,
            ),
            item.actions,
        )
        assertNoSensitivePresentationFields(item.javaClass)
    }

    @Test
    fun senderInvitationUsesSenderActionMatrixAndCannotReportSelfOrOwnInvitation() {
        val projection = mapper.map(
            context = context(PrivateInvitationAuthorizedAudience.LOCAL_SENDER),
            result = invitationResult(),
        )

        val item = projection.requireItem<NearbyRoomTimelineItem.PrivateInvitation>()
        assertEquals(PrivateInvitationAudienceLabel.SENT_PRIVATELY, item.audienceLabel)
        assertEquals(
            setOf(PrivateInvitationAction.OPEN, PrivateInvitationAction.HIDE),
            item.actions,
        )
        assertFalse(item.actions.contains(PrivateInvitationAction.REPORT_INVITATION))
        assertFalse(item.actions.contains(PrivateInvitationAction.REPORT_PEER))
    }

    @Test
    fun hiddenRecipientDoesNotCopyPlaintextAndKeepsModerationActions() {
        val projection = mapper.map(
            context = context(
                audience = PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT,
                hidden = true,
                canMutePeer = false,
            ),
            result = invitationResult(),
        )

        val item = projection.requireItem<NearbyRoomTimelineItem.HiddenPrivateInvitation>()
        assertEquals(MESSAGE_ID, item.messageId)
        assertEquals(CREATED_AT_MS, item.createdAtMs)
        assertEquals(
            setOf(
                PrivateInvitationAction.UNHIDE,
                PrivateInvitationAction.REPORT_INVITATION,
                PrivateInvitationAction.REPORT_PEER,
                PrivateInvitationAction.BLOCK_PEER,
            ),
            item.actions,
        )
        assertNoSensitivePresentationFields(item.javaClass)
        assertNull(item.javaClass.declaredFields.firstOrNull { it.name == "title" })
        assertNull(item.javaClass.declaredFields.firstOrNull { it.name == "shortDescription" })
    }

    @Test
    fun hiddenSenderOnlyOffersUnhide() {
        val projection = mapper.map(
            context = context(
                audience = PrivateInvitationAuthorizedAudience.LOCAL_SENDER,
                hidden = true,
            ),
            result = invitationResult(),
        )

        val item = projection.requireItem<NearbyRoomTimelineItem.HiddenPrivateInvitation>()
        assertEquals(setOf(PrivateInvitationAction.UNHIDE), item.actions)
    }

    @Test
    fun audienceHiddenExpiredAndMissingResultsAreAlwaysOmitted() {
        val hiddenContext = context(
            audience = PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT,
            hidden = true,
        )
        assertEquals(
            NearbyPrivateGroupInvitationTimelineProjection.Omit,
            mapper.map(hiddenContext, NearbyPrivateGroupInvitationReceiveResult.NotForLocalParticipant),
        )
        assertEquals(
            NearbyPrivateGroupInvitationTimelineProjection.Omit,
            mapper.map(hiddenContext, NearbyPrivateGroupInvitationReceiveResult.Expired),
        )
        assertEquals(
            NearbyPrivateGroupInvitationTimelineProjection.Omit,
            mapper.map(hiddenContext, NearbyPrivateGroupInvitationReceiveResult.MessageNotFound),
        )
    }

    @Test
    fun normalAndPublicCompatibilityResultsUseNormalMessagePath() {
        assertEquals(
            NearbyPrivateGroupInvitationTimelineProjection.UseNormalMessage,
            mapper.map(null, NearbyPrivateGroupInvitationReceiveResult.NotGroupInvitation),
        )
        assertEquals(
            NearbyPrivateGroupInvitationTimelineProjection.UseNormalMessage,
            mapper.map(null, NearbyPrivateGroupInvitationReceiveResult.NotPrivateInvitation),
        )
    }

    @Test
    fun failuresMapToSafePlaceholderKindsOnlyWithAuthoritativeAudienceContext() {
        val context = context(PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT)
        val unavailable = listOf(
            NearbyPrivateGroupInvitationReceiveResult.RecipientIdentityUnavailable,
            NearbyPrivateGroupInvitationReceiveResult.RecipientIdentityInvalidated,
            NearbyPrivateGroupInvitationReceiveResult.UnsupportedPlatform,
            NearbyPrivateGroupInvitationReceiveResult.CryptoUnavailable,
            NearbyPrivateGroupInvitationReceiveResult.KeyAgreementFailed,
        )
        unavailable.forEach { result ->
            assertPlaceholderKind(context, result, PrivateInvitationPlaceholderKind.UNAVAILABLE)
            assertEquals(
                NearbyPrivateGroupInvitationTimelineProjection.Omit,
                mapper.map(null, result),
            )
        }

        listOf(
            NearbyPrivateGroupInvitationReceiveResult.UnsupportedCryptoProtocol,
            NearbyPrivateGroupInvitationReceiveResult.UnsupportedCryptoAlgorithm,
        ).forEach { result ->
            assertPlaceholderKind(context, result, PrivateInvitationPlaceholderKind.UNSUPPORTED)
        }

        listOf(
            NearbyPrivateGroupInvitationReceiveResult.UnsupportedOrInvalidEnvelope,
            NearbyPrivateGroupInvitationReceiveResult.MessageIdentityMismatch,
            NearbyPrivateGroupInvitationReceiveResult.WrongRecipientKey,
            NearbyPrivateGroupInvitationReceiveResult.AuthenticationFailed,
            NearbyPrivateGroupInvitationReceiveResult.InvalidEncryptedPayload,
            NearbyPrivateGroupInvitationReceiveResult.InvalidCryptoContext,
            NearbyPrivateGroupInvitationReceiveResult.MalformedInnerPayload,
        ).forEach { result ->
            assertPlaceholderKind(context, result, PrivateInvitationPlaceholderKind.INVALID)
        }
    }

    @Test
    fun recipientPlaceholderCanReportVisibleContentAndSenderButSenderPlaceholderCannotReportSelf() {
        val recipient = mapper.map(
            context(PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT, canMutePeer = true),
            NearbyPrivateGroupInvitationReceiveResult.AuthenticationFailed,
        ).requireItem<NearbyRoomTimelineItem.PrivateInvitationPlaceholder>()
        assertEquals(
            setOf(
                PrivateInvitationAction.REPORT_INVITATION,
                PrivateInvitationAction.REPORT_PEER,
                PrivateInvitationAction.BLOCK_PEER,
                PrivateInvitationAction.MUTE_PEER,
            ),
            recipient.actions,
        )

        val sender = mapper.map(
            context(PrivateInvitationAuthorizedAudience.LOCAL_SENDER),
            NearbyPrivateGroupInvitationReceiveResult.MalformedInnerPayload,
        ).requireItem<NearbyRoomTimelineItem.PrivateInvitationPlaceholder>()
        assertTrue(sender.actions.isEmpty())
    }

    @Test
    fun mismatchedAuthoritativeContextAndOutOfContractContentFailSafeToInvalidPlaceholder() {
        val mismatched = mapper.map(
            context = context(PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT).copy(
                senderId = "different-sender",
            ),
            result = invitationResult(),
        ).requireItem<NearbyRoomTimelineItem.PrivateInvitationPlaceholder>()
        assertEquals(PrivateInvitationPlaceholderKind.INVALID, mismatched.kind)

        val longTitle = invitationResult(title = "x".repeat(41))
        val invalidTitle = mapper.map(
            context(PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT),
            longTitle,
        ).requireItem<NearbyRoomTimelineItem.PrivateInvitationPlaceholder>()
        assertEquals(PrivateInvitationPlaceholderKind.INVALID, invalidTitle.kind)

        val longDescription = invitationResult(description = "x".repeat(61))
        val invalidDescription = mapper.map(
            context(PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT),
            longDescription,
        ).requireItem<NearbyRoomTimelineItem.PrivateInvitationPlaceholder>()
        assertEquals(PrivateInvitationPlaceholderKind.INVALID, invalidDescription.kind)
    }

    @Test
    fun presentationEnumsDoNotContainProhibitedInvitationActionsOrSensitivePlaceholderReasons() {
        val actionNames = enumValues<PrivateInvitationAction>().map { it.name }.toSet()
        assertFalse(actionNames.contains("REPLY"))
        assertFalse(actionNames.contains("EDIT"))
        assertFalse(actionNames.contains("DELETE"))
        assertFalse(actionNames.contains("RECALL"))
        assertFalse(actionNames.contains("ACCEPT"))
        assertFalse(actionNames.contains("REJECT"))
        assertFalse(actionNames.contains("JOIN"))
        assertFalse(actionNames.contains("COPY_RAW_CONTENT"))
        assertFalse(actionNames.contains("COPY_CREDENTIAL"))

        val placeholderNames = enumValues<PrivateInvitationPlaceholderKind>().map { it.name }.toSet()
        assertEquals(setOf("UNAVAILABLE", "UNSUPPORTED", "INVALID"), placeholderNames)
    }

    private fun assertPlaceholderKind(
        context: NearbyPrivateGroupInvitationPresentationContext,
        result: NearbyPrivateGroupInvitationReceiveResult,
        expected: PrivateInvitationPlaceholderKind,
    ) {
        val item = mapper.map(context, result)
            .requireItem<NearbyRoomTimelineItem.PrivateInvitationPlaceholder>()
        assertEquals(expected, item.kind)
        assertNoSensitivePresentationFields(item.javaClass)
    }

    private fun invitationResult(
        title: String = "Emergency Group",
        description: String = "Meet at checkpoint",
    ) = NearbyPrivateGroupInvitationReceiveResult.Invitation(
        NearbyPrivateGroupInvitationView(
            roomId = ROOM_ID,
            messageId = MESSAGE_ID,
            cardId = MESSAGE_ID,
            logicalSenderId = SENDER_ID,
            targetParticipantId = TARGET_ID,
            title = title,
            shortDescription = description,
            invitationCredential = InvitationCredential.of("credential-secret"),
            createdAtMs = CREATED_AT_MS,
            expiresAtMs = 10_000L,
        )
    )

    private fun context(
        audience: PrivateInvitationAuthorizedAudience,
        hidden: Boolean = false,
        canMutePeer: Boolean = false,
    ) = NearbyPrivateGroupInvitationPresentationContext(
        messageId = MESSAGE_ID,
        createdAtMs = CREATED_AT_MS,
        isHiddenLocally = hidden,
        audience = audience,
        senderId = SENDER_ID,
        senderDisplayName = "Sender",
        canMutePeer = canMutePeer,
    )

    private fun assertNoSensitivePresentationFields(type: Class<*>) {
        val fieldNames = type.declaredFields.map { it.name }.toSet()
        assertFalse(fieldNames.contains("invitationCredential"))
        assertFalse(fieldNames.contains("expiresAtMs"))
        assertFalse(fieldNames.contains("body"))
        assertFalse(fieldNames.contains("localRenderSourceBody"))
        assertFalse(fieldNames.contains("source"))
    }

    private inline fun <reified T : NearbyRoomTimelineItem>
        NearbyPrivateGroupInvitationTimelineProjection.requireItem(): T {
        val item = (this as NearbyPrivateGroupInvitationTimelineProjection.Item).item
        assertTrue("Expected ${T::class.java.simpleName}, got ${item.javaClass.simpleName}", item is T)
        return item as T
    }

    private companion object {
        const val ROOM_ID = "room-1"
        const val MESSAGE_ID = "message-1"
        const val SENDER_ID = "sender-1"
        const val TARGET_ID = "recipient-1"
        const val CREATED_AT_MS = 1_000L
    }
}
