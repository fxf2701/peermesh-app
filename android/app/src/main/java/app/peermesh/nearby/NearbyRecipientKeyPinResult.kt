package app.peermesh.nearby

sealed interface NearbyRecipientKeyPinResult {
    data object NoValidIdentity : NearbyRecipientKeyPinResult
    data object Pinned : NearbyRecipientKeyPinResult
    data object Matched : NearbyRecipientKeyPinResult
    data object Conflict : NearbyRecipientKeyPinResult
    data object RoomUnavailable : NearbyRecipientKeyPinResult
    data object StoredPinInvalid : NearbyRecipientKeyPinResult
}

data class NearbyParticipantSelfInfoResult(
    val participantInserted: Boolean,
    val recipientKeyPinResult: NearbyRecipientKeyPinResult,
)

internal fun classifyStoredRecipientKeyPin(
    storedAlgorithm: String?,
    storedKeyId: String?,
    storedPublicKey: ByteArray?,
    incomingIdentity: NearbyRecipientEncryptionPublicIdentity,
): NearbyRecipientKeyPinResult {
    if (storedAlgorithm == null && storedKeyId == null && storedPublicKey == null) {
        return NearbyRecipientKeyPinResult.RoomUnavailable
    }
    if (storedAlgorithm == null || storedKeyId == null || storedPublicKey == null) {
        return NearbyRecipientKeyPinResult.StoredPinInvalid
    }
    val algorithm = app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm.entries
        .firstOrNull { it.wireValue == storedAlgorithm }
        ?: return NearbyRecipientKeyPinResult.StoredPinInvalid
    val storedIdentity = NearbyRecipientEncryptionPublicIdentity(
        algorithm = algorithm,
        keyId = storedKeyId,
        publicKeySec1 = storedPublicKey,
    )
    if (!NearbySelfInfoProtocol.isValidRecipientEncryptionIdentity(storedIdentity)) {
        return NearbyRecipientKeyPinResult.StoredPinInvalid
    }
    return if (storedIdentity == incomingIdentity) {
        NearbyRecipientKeyPinResult.Matched
    } else {
        NearbyRecipientKeyPinResult.Conflict
    }
}
