package app.peermesh.nearby

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyRecipientKeyPinReceiveBoundaryTest {
    @Test
    fun receiverPinsOnlyValidatedIdentityAndKeepsAckAndRelayIndependentOfPinResult() {
        val source = nearbySource("NearbyRoomTransportReceiver.kt").readText()
        val selfInfoStart = source.indexOf("NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1 ->")
        val ackBranchStart = source.indexOf("NearbyRoomTransportEnvelope.KIND_SELF_INFO_ACK_V1 ->")
        val selfInfoBranch = source.substring(selfInfoStart, ackBranchStart)

        assertTrue(selfInfoBranch.contains("val originDeviceId = envelope.origin_device_id ?: envelope.sender_device_id"))
        assertTrue(selfInfoBranch.contains("val validatedSnapshot = NearbySelfInfoProtocol.validateReceived(envelope)"))
        assertTrue(
            selfInfoBranch.contains(
                "recipientEncryptionIdentity = validatedSnapshot?.recipientEncryptionIdentity"
            )
        )
        assertFalse(selfInfoBranch.contains("recipientEncryptionIdentity = envelope."))
        assertTrue(
            selfInfoBranch.indexOf("repository.upsertParticipantSelfInfo(") <
                selfInfoBranch.indexOf("transportManager.sendSelfInfoAck(")
        )
        assertTrue(
            selfInfoBranch.indexOf("transportManager.sendSelfInfoAck(") <
                selfInfoBranch.lastIndexOf("transportManager.relaySelfInfo(envelope)")
        )
        assertFalse(selfInfoBranch.contains("if (selfInfoResult.recipientKeyPinResult"))
        assertFalse(selfInfoBranch.contains("when (selfInfoResult.recipientKeyPinResult"))
    }

    private fun nearbySource(fileName: String): File =
        projectRoot().resolve("app/src/main/java/app/peermesh/nearby/$fileName")

    private fun projectRoot(): File {
        val current = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(current) { it.parentFile }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: error("Android project root not found from $current")
    }
}
