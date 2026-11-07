package app.peermesh.nearby

import android.content.Context
import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.core.groupinvitation.PrivateGroupInvitationEnvelope
import app.peermesh.core.groupinvitation.PrivateGroupInvitationInnerPayload
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.security.groupinvitation.AndroidKeystoreRecipientEncryptionIdentityProvider
import app.peermesh.security.groupinvitation.JcaPrivateRecipientCrypto
import app.peermesh.security.groupinvitation.PrivateRecipientCryptoContext
import app.peermesh.security.groupinvitation.PrivateRecipientEncryptResult
import app.peermesh.security.groupinvitation.RecipientEncryptionIdentity

/** Semantic caller-owned input for one private group-invitation send attempt. */
data class NearbyPrivateGroupInvitationSendRequest(
    val roomId: String,
    val targetParticipantId: String,
    val title: String,
    val shortDescription: String,
    val invitationCredential: InvitationCredential,
)

sealed interface NearbyPrivateGroupInvitationSendResult {
    data class Sent(val message: NearbyRoomMessageEntity) : NearbyPrivateGroupInvitationSendResult
    data class PersistedForRetry(val message: NearbyRoomMessageEntity) : NearbyPrivateGroupInvitationSendResult
    data object PreparationUnavailable : NearbyPrivateGroupInvitationSendResult
    data object InvalidInvitationContent : NearbyPrivateGroupInvitationSendResult
    data object InvalidRecipientIdentity : NearbyPrivateGroupInvitationSendResult
    data object UnsupportedCryptoProtocol : NearbyPrivateGroupInvitationSendResult
    data object UnsupportedCryptoAlgorithm : NearbyPrivateGroupInvitationSendResult
    data object InvalidCryptoContext : NearbyPrivateGroupInvitationSendResult
    data object KeyAgreementFailed : NearbyPrivateGroupInvitationSendResult
    data object CryptoUnavailable : NearbyPrivateGroupInvitationSendResult
    data object InvalidPreparation : NearbyPrivateGroupInvitationSendResult
    data object StalePreparation : NearbyPrivateGroupInvitationSendResult
    data object RoomUnavailable : NearbyPrivateGroupInvitationSendResult
    data object SenderMismatch : NearbyPrivateGroupInvitationSendResult
    data object SenderMuted : NearbyPrivateGroupInvitationSendResult
    data object SendLimitReached : NearbyPrivateGroupInvitationSendResult
    data object MessageIdCollision : NearbyPrivateGroupInvitationSendResult
}

/**
 * Private-only GI2E4 orchestration boundary.
 *
 * The repository freezes all authoritative context, GI2D encrypts the canonical inner payload
 * exactly once, and transport receives only the persisted entity returned by prepared insertion.
 */
class NearbyPrivateGroupInvitationSendPipeline internal constructor(
    private val prepare: (String, NearbyGroupInvitationAudience) -> NearbyGroupInvitationPreparation?,
    private val encrypt: (
        ByteArray,
        RecipientEncryptionIdentity,
        PrivateRecipientCryptoContext,
    ) -> PrivateRecipientEncryptResult,
    private val insert: (
        NearbyGroupInvitationPreparation,
        String,
        String?,
    ) -> PreparedGroupInvitationInsertResult,
    private val submitPersistedMessage: (NearbyRoomMessageEntity) -> Boolean,
) {
    constructor(
        context: Context,
        repository: NearbyRoomRepository,
        transportManager: NearbyRoomTransportManager,
    ) : this(
        prepare = repository::prepareGroupInvitationContext,
        encrypt = JcaPrivateRecipientCrypto(
            AndroidKeystoreRecipientEncryptionIdentityProvider(context.applicationContext)
        )::encrypt,
        insert = repository::insertPreparedGroupInvitation,
        submitPersistedMessage = productionGroupInvitationSubmitter(
            context = context.applicationContext,
            repository = repository,
            transportManager = transportManager,
        ),
    )

    fun send(request: NearbyPrivateGroupInvitationSendRequest): NearbyPrivateGroupInvitationSendResult {
        if (!hasValidSemanticContent(request)) {
            return NearbyPrivateGroupInvitationSendResult.InvalidInvitationContent
        }

        val audience = try {
            NearbyGroupInvitationAudience.Private(request.targetParticipantId)
        } catch (_: IllegalArgumentException) {
            return NearbyPrivateGroupInvitationSendResult.InvalidInvitationContent
        }
        val preparation = prepare(request.roomId, audience)
            ?: return NearbyPrivateGroupInvitationSendResult.PreparationUnavailable
        val preparedAudience = preparation.audience as? NearbyGroupInvitationAudience.Private
            ?: return NearbyPrivateGroupInvitationSendResult.InvalidPreparation
        if (preparedAudience.targetParticipantId != request.targetParticipantId) {
            return NearbyPrivateGroupInvitationSendResult.InvalidPreparation
        }
        val preparedIdentity = preparation.recipientEncryptionIdentity
            ?: return NearbyPrivateGroupInvitationSendResult.InvalidRecipientIdentity

        val innerPayload = try {
            PrivateGroupInvitationInnerPayload(
                title = request.title,
                shortDescription = request.shortDescription,
                invitationCredential = request.invitationCredential,
            )
        } catch (_: IllegalArgumentException) {
            return NearbyPrivateGroupInvitationSendResult.InvalidInvitationContent
        }
        val canonicalInnerBody = try {
            GroupInvitationProtocol.encodePrivateInnerPayload(innerPayload)
        } catch (_: IllegalArgumentException) {
            return NearbyPrivateGroupInvitationSendResult.InvalidInvitationContent
        }
        val recipientIdentity = try {
            RecipientEncryptionIdentity(
                algorithm = preparedIdentity.algorithm,
                keyId = preparedIdentity.keyId,
                publicKeySec1 = preparedIdentity.publicKeySec1,
            )
        } catch (_: IllegalArgumentException) {
            return NearbyPrivateGroupInvitationSendResult.InvalidRecipientIdentity
        }
        val cryptoContext = PrivateRecipientCryptoContext(
            protocolVersion = GroupInvitationProtocol.VERSION,
            algorithmWireValue = preparedIdentity.algorithm.wireValue,
            roomId = preparation.roomId,
            messageId = preparation.messageId,
            cardId = preparation.messageId,
            logicalSenderId = preparation.logicalSenderId,
            targetParticipantId = preparedAudience.targetParticipantId,
            targetKeyId = preparedIdentity.keyId,
            effectiveRoomExpiryEpochMs = preparation.effectiveExpiryAtMs,
        )

        val plaintextBytes = canonicalInnerBody.toByteArray(Charsets.UTF_8)
        val encryptionResult = try {
            encrypt(plaintextBytes, recipientIdentity, cryptoContext)
        } finally {
            plaintextBytes.fill(0)
        }
        val encrypted = when (encryptionResult) {
            is PrivateRecipientEncryptResult.Success -> encryptionResult.payload
            PrivateRecipientEncryptResult.UnsupportedProtocolVersion ->
                return NearbyPrivateGroupInvitationSendResult.UnsupportedCryptoProtocol
            PrivateRecipientEncryptResult.UnsupportedAlgorithm ->
                return NearbyPrivateGroupInvitationSendResult.UnsupportedCryptoAlgorithm
            PrivateRecipientEncryptResult.InvalidContext ->
                return NearbyPrivateGroupInvitationSendResult.InvalidCryptoContext
            PrivateRecipientEncryptResult.InvalidRecipientIdentity ->
                return NearbyPrivateGroupInvitationSendResult.InvalidRecipientIdentity
            PrivateRecipientEncryptResult.KeyAgreementFailed ->
                return NearbyPrivateGroupInvitationSendResult.KeyAgreementFailed
            PrivateRecipientEncryptResult.CryptoUnavailable ->
                return NearbyPrivateGroupInvitationSendResult.CryptoUnavailable
        }

        if (
            encrypted.algorithmWireValue != preparedIdentity.algorithm.wireValue ||
            encrypted.targetKeyId != preparedIdentity.keyId
        ) {
            return NearbyPrivateGroupInvitationSendResult.InvalidCryptoContext
        }

        val finalizedOuterBody = try {
            GroupInvitationProtocol.encodePrivateEnvelope(
                PrivateGroupInvitationEnvelope(
                    cardId = preparation.messageId,
                    targetParticipantId = preparedAudience.targetParticipantId,
                    algorithm = preparedIdentity.algorithm,
                    targetKeyId = encrypted.targetKeyId,
                    ephemeralPublicKey = Base64Url.encode(encrypted.ephemeralPublicKeySec1),
                    nonce = Base64Url.encode(encrypted.nonce),
                    ciphertext = Base64Url.encode(encrypted.ciphertextAndTag),
                )
            )
        } catch (_: IllegalArgumentException) {
            return NearbyPrivateGroupInvitationSendResult.InvalidInvitationContent
        }

        return when (val inserted = insert(preparation, finalizedOuterBody, canonicalInnerBody)) {
            is PreparedGroupInvitationInsertResult.Inserted -> {
                if (submitPersistedMessage(inserted.message)) {
                    NearbyPrivateGroupInvitationSendResult.Sent(inserted.message)
                } else {
                    NearbyPrivateGroupInvitationSendResult.PersistedForRetry(inserted.message)
                }
            }
            PreparedGroupInvitationInsertResult.InvalidPreparation ->
                NearbyPrivateGroupInvitationSendResult.InvalidPreparation
            PreparedGroupInvitationInsertResult.InvalidPreparedContent ->
                NearbyPrivateGroupInvitationSendResult.InvalidInvitationContent
            PreparedGroupInvitationInsertResult.StalePreparation ->
                NearbyPrivateGroupInvitationSendResult.StalePreparation
            PreparedGroupInvitationInsertResult.RoomUnavailable ->
                NearbyPrivateGroupInvitationSendResult.RoomUnavailable
            PreparedGroupInvitationInsertResult.SenderMismatch ->
                NearbyPrivateGroupInvitationSendResult.SenderMismatch
            PreparedGroupInvitationInsertResult.SenderMuted ->
                NearbyPrivateGroupInvitationSendResult.SenderMuted
            PreparedGroupInvitationInsertResult.SendLimitReached ->
                NearbyPrivateGroupInvitationSendResult.SendLimitReached
            PreparedGroupInvitationInsertResult.MessageIdCollision ->
                NearbyPrivateGroupInvitationSendResult.MessageIdCollision
        }
    }

    private fun hasValidSemanticContent(request: NearbyPrivateGroupInvitationSendRequest): Boolean =
        try {
            require(request.roomId.isNotBlank())
            require(request.targetParticipantId.isNotBlank())
            PrivateGroupInvitationInnerPayload(
                title = request.title,
                shortDescription = request.shortDescription,
                invitationCredential = request.invitationCredential,
            )
            true
        } catch (_: IllegalArgumentException) {
            false
        }
}
