package app.peermesh.nearby

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.GroupInvitationDecodeResult
import app.peermesh.core.groupinvitation.GroupInvitationDecodedMessage
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.core.groupinvitation.PrivateGroupInvitationEnvelope
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.security.groupinvitation.PrivateRecipientCryptoContext
import app.peermesh.security.groupinvitation.PrivateRecipientDecryptResult
import app.peermesh.security.groupinvitation.PrivateRecipientEncryptedPayload
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Immutable, validated private invitation data safe for later presentation consumption. */
data class NearbyPrivateGroupInvitationView(
    val roomId: String,
    val messageId: String,
    val cardId: String,
    val logicalSenderId: String,
    val targetParticipantId: String,
    val title: String,
    val shortDescription: String,
    val invitationCredential: InvitationCredential,
    val createdAtMs: Long,
    val expiresAtMs: Long,
) {
    init {
        require(roomId.isNotBlank())
        require(messageId.isNotBlank())
        require(messageId == cardId)
        require(logicalSenderId.isNotBlank())
        require(targetParticipantId.isNotBlank())
        require(expiresAtMs > createdAtMs)
    }
}



enum class NearbyPrivateGroupInvitationLocalAudienceRole {
    LOCAL_SENDER,
    INTENDED_RECIPIENT,
}

data class NearbyPrivateGroupInvitationModerationCapabilities(
    val canReportInvitation: Boolean,
    val canReportPeer: Boolean,
    val canBlockPeer: Boolean,
    val canMutePeer: Boolean,
)

data class NearbyPrivateGroupInvitationTimelineContext(
    val messageId: String,
    val createdAtMs: Long,
    val logicalSenderId: String,
    val senderDisplayName: String,
    val audienceRole: NearbyPrivateGroupInvitationLocalAudienceRole,
    val hiddenLocally: Boolean,
    val moderationCapabilities: NearbyPrivateGroupInvitationModerationCapabilities,
)

data class NearbyPrivateGroupInvitationTimelineInspection(
    val receiveResult: NearbyPrivateGroupInvitationReceiveResult,
    val context: NearbyPrivateGroupInvitationTimelineContext?,
)

sealed interface NearbyPrivateGroupInvitationReceiveResult {
    data class Invitation(
        val invitation: NearbyPrivateGroupInvitationView,
    ) : NearbyPrivateGroupInvitationReceiveResult

    data object MessageNotFound : NearbyPrivateGroupInvitationReceiveResult
    data object NotGroupInvitation : NearbyPrivateGroupInvitationReceiveResult
    data object NotPrivateInvitation : NearbyPrivateGroupInvitationReceiveResult
    data object UnsupportedOrInvalidEnvelope : NearbyPrivateGroupInvitationReceiveResult
    data object MessageIdentityMismatch : NearbyPrivateGroupInvitationReceiveResult
    data object NotForLocalParticipant : NearbyPrivateGroupInvitationReceiveResult
    data object Expired : NearbyPrivateGroupInvitationReceiveResult
    data object WrongRecipientKey : NearbyPrivateGroupInvitationReceiveResult
    data object RecipientIdentityUnavailable : NearbyPrivateGroupInvitationReceiveResult
    data object RecipientIdentityInvalidated : NearbyPrivateGroupInvitationReceiveResult
    data object UnsupportedPlatform : NearbyPrivateGroupInvitationReceiveResult
    data object AuthenticationFailed : NearbyPrivateGroupInvitationReceiveResult
    data object InvalidEncryptedPayload : NearbyPrivateGroupInvitationReceiveResult
    data object InvalidCryptoContext : NearbyPrivateGroupInvitationReceiveResult
    data object UnsupportedCryptoProtocol : NearbyPrivateGroupInvitationReceiveResult
    data object UnsupportedCryptoAlgorithm : NearbyPrivateGroupInvitationReceiveResult
    data object KeyAgreementFailed : NearbyPrivateGroupInvitationReceiveResult
    data object CryptoUnavailable : NearbyPrivateGroupInvitationReceiveResult
    data object MalformedInnerPayload : NearbyPrivateGroupInvitationReceiveResult
}

/** Repository-owned, read-only GI2E5 receive orchestration. */
internal class NearbyPrivateGroupInvitationInspector(
    private val decrypt: (
        PrivateRecipientEncryptedPayload,
        PrivateRecipientCryptoContext,
        Long,
    ) -> PrivateRecipientDecryptResult,
) {
    fun inspectPersisted(
        roomId: String,
        messageId: String,
        messageLoader: (String, String) -> NearbyRoomMessageEntity?,
        localParticipantId: String,
        nowMs: Long,
    ): NearbyPrivateGroupInvitationReceiveResult {
        if (roomId.isBlank() || messageId.isBlank()) {
            return NearbyPrivateGroupInvitationReceiveResult.MessageNotFound
        }
        val message = messageLoader(roomId, messageId)
            ?: return NearbyPrivateGroupInvitationReceiveResult.MessageNotFound
        return inspect(
            message = message,
            localParticipantId = localParticipantId,
            nowMs = nowMs,
        )
    }
    fun inspect(
        message: NearbyRoomMessageEntity,
        localParticipantId: String,
        nowMs: Long,
    ): NearbyPrivateGroupInvitationReceiveResult {
        if (!GroupInvitationProtocol.isExactGroupInvitationCandidate(message.body)) {
            return NearbyPrivateGroupInvitationReceiveResult.NotGroupInvitation
        }

        val decoded = when (val result = GroupInvitationProtocol.decode(message.body)) {
            GroupInvitationDecodeResult.NotGroupInvitation ->
                return NearbyPrivateGroupInvitationReceiveResult.NotGroupInvitation
            GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation ->
                return NearbyPrivateGroupInvitationReceiveResult.UnsupportedOrInvalidEnvelope
            is GroupInvitationDecodeResult.Invitation -> result.message
        }
        val envelope = when (decoded) {
            is GroupInvitationDecodedMessage.PublicInvitation ->
                return NearbyPrivateGroupInvitationReceiveResult.NotPrivateInvitation
            is GroupInvitationDecodedMessage.PrivateEncryptedInvitation -> decoded.envelope
        }
        if (GroupInvitationProtocol.encodePrivateEnvelope(envelope) != message.body) {
            return NearbyPrivateGroupInvitationReceiveResult.UnsupportedOrInvalidEnvelope
        }

        if (message.room_id.isBlank() || message.message_id.isBlank()) {
            return NearbyPrivateGroupInvitationReceiveResult.InvalidCryptoContext
        }
        if (envelope.cardId != message.message_id) {
            return NearbyPrivateGroupInvitationReceiveResult.MessageIdentityMismatch
        }
        val logicalSenderId = message.owner_device_id ?: message.sender_device_id
        if (
            logicalSenderId.isBlank() ||
            message.ttl_expires_at_ms <= message.created_at_ms
        ) {
            return NearbyPrivateGroupInvitationReceiveResult.InvalidCryptoContext
        }
        if (localParticipantId.isBlank()) {
            return NearbyPrivateGroupInvitationReceiveResult.NotForLocalParticipant
        }

        if (localParticipantId == logicalSenderId) {
            if (nowMs >= message.ttl_expires_at_ms) {
                return NearbyPrivateGroupInvitationReceiveResult.Expired
            }
            return inspectSenderLocal(
                message = message,
                envelope = envelope,
                logicalSenderId = logicalSenderId,
            )
        }
        if (envelope.targetParticipantId != localParticipantId) {
            return NearbyPrivateGroupInvitationReceiveResult.NotForLocalParticipant
        }

        val encrypted = try {
            PrivateRecipientEncryptedPayload(
                algorithmWireValue = envelope.algorithm.wireValue,
                targetKeyId = envelope.targetKeyId,
                ephemeralPublicKeySec1 = Base64Url.decode(envelope.ephemeralPublicKey),
                nonce = Base64Url.decode(envelope.nonce),
                ciphertextAndTag = Base64Url.decode(envelope.ciphertext),
            )
        } catch (_: IllegalArgumentException) {
            return NearbyPrivateGroupInvitationReceiveResult.InvalidEncryptedPayload
        }
        val context = PrivateRecipientCryptoContext(
            protocolVersion = GroupInvitationProtocol.VERSION,
            algorithmWireValue = envelope.algorithm.wireValue,
            roomId = message.room_id,
            messageId = message.message_id,
            cardId = envelope.cardId,
            logicalSenderId = logicalSenderId,
            targetParticipantId = envelope.targetParticipantId,
            targetKeyId = envelope.targetKeyId,
            effectiveRoomExpiryEpochMs = message.ttl_expires_at_ms,
        )

        val plaintext = when (val result = decrypt(encrypted, context, nowMs)) {
            is PrivateRecipientDecryptResult.Success -> result.plaintext
            PrivateRecipientDecryptResult.Expired -> return NearbyPrivateGroupInvitationReceiveResult.Expired
            PrivateRecipientDecryptResult.UnsupportedProtocolVersion -> return NearbyPrivateGroupInvitationReceiveResult.UnsupportedCryptoProtocol
            PrivateRecipientDecryptResult.UnsupportedAlgorithm -> return NearbyPrivateGroupInvitationReceiveResult.UnsupportedCryptoAlgorithm
            PrivateRecipientDecryptResult.InvalidContext -> return NearbyPrivateGroupInvitationReceiveResult.InvalidCryptoContext
            PrivateRecipientDecryptResult.InvalidEncryptedPayload -> return NearbyPrivateGroupInvitationReceiveResult.InvalidEncryptedPayload
            PrivateRecipientDecryptResult.NotForLocalRecipientKey -> return NearbyPrivateGroupInvitationReceiveResult.WrongRecipientKey
            is PrivateRecipientDecryptResult.RecipientIdentityUnavailable -> return NearbyPrivateGroupInvitationReceiveResult.RecipientIdentityUnavailable
            is PrivateRecipientDecryptResult.RecipientIdentityInvalidated -> return NearbyPrivateGroupInvitationReceiveResult.RecipientIdentityInvalidated
            PrivateRecipientDecryptResult.UnsupportedPlatform -> return NearbyPrivateGroupInvitationReceiveResult.UnsupportedPlatform
            PrivateRecipientDecryptResult.AuthenticationFailed -> return NearbyPrivateGroupInvitationReceiveResult.AuthenticationFailed
            PrivateRecipientDecryptResult.KeyAgreementFailed -> return NearbyPrivateGroupInvitationReceiveResult.KeyAgreementFailed
            PrivateRecipientDecryptResult.CryptoUnavailable -> return NearbyPrivateGroupInvitationReceiveResult.CryptoUnavailable
        }

        return try {
            val canonicalInner = decodeStrictUtf8(plaintext)
                ?: return NearbyPrivateGroupInvitationReceiveResult.MalformedInnerPayload
            materializeInvitation(
                message = message,
                envelope = envelope,
                logicalSenderId = logicalSenderId,
                canonicalInner = canonicalInner,
            )
        } finally {
            plaintext.fill(0)
        }
    }

    private fun inspectSenderLocal(
        message: NearbyRoomMessageEntity,
        envelope: PrivateGroupInvitationEnvelope,
        logicalSenderId: String,
    ): NearbyPrivateGroupInvitationReceiveResult {
        val canonicalInner = message.local_render_source_body
            ?: return NearbyPrivateGroupInvitationReceiveResult.MalformedInnerPayload
        return materializeInvitation(
            message = message,
            envelope = envelope,
            logicalSenderId = logicalSenderId,
            canonicalInner = canonicalInner,
        )
    }

    private fun materializeInvitation(
        message: NearbyRoomMessageEntity,
        envelope: PrivateGroupInvitationEnvelope,
        logicalSenderId: String,
        canonicalInner: String,
    ): NearbyPrivateGroupInvitationReceiveResult {
        val inner = GroupInvitationProtocol.decodePrivateInnerPayload(canonicalInner)
            ?: return NearbyPrivateGroupInvitationReceiveResult.MalformedInnerPayload
        if (GroupInvitationProtocol.encodePrivateInnerPayload(inner) != canonicalInner) {
            return NearbyPrivateGroupInvitationReceiveResult.MalformedInnerPayload
        }
        return NearbyPrivateGroupInvitationReceiveResult.Invitation(
            NearbyPrivateGroupInvitationView(
                roomId = message.room_id,
                messageId = message.message_id,
                cardId = envelope.cardId,
                logicalSenderId = logicalSenderId,
                targetParticipantId = envelope.targetParticipantId,
                title = inner.title,
                shortDescription = inner.shortDescription,
                invitationCredential = inner.invitationCredential,
                createdAtMs = message.created_at_ms,
                expiresAtMs = message.ttl_expires_at_ms,
            )
        )
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
}
