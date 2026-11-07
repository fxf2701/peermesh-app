package app.peermesh

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPrivateInvitationComposerArchitectureGI2E6Test {
    @Test
    fun composerUsesPipelineThroughAuthorityAndIdentityOnlyNavigation() {
        val source = File(
            "src/main/java/app/peermesh/NearbyPrivateInvitationComposerActivity.kt"
        ).readText()
        assertTrue(source.contains("NearbyPrivateGroupInvitationSendPipeline"))
        assertTrue(source.contains("NearbyPrivateInvitationComposerAuthority"))
        assertTrue(source.contains("EXTRA_ROOM_ID"))
        assertTrue(source.contains("EXTRA_TARGET_PARTICIPANT_ID"))
        assertTrue(source.contains("private_invitation_status"))
        assertFalse(source.contains("EXTRA_GID"))
        assertFalse(source.contains("GroupInvitationProtocol"))
        assertFalse(source.contains("JcaPrivateRecipientCrypto"))
        assertFalse(source.contains("AppDatabaseProvider"))
        assertFalse(source.contains("groupDao"))
        assertFalse(source.contains("nearbyRoomMessageDao"))
        assertFalse(source.contains("SecureKeyStore"))
        assertFalse(source.contains("InvitationCredential"))
        assertFalse(source.contains("buildInviteLink"))
        assertFalse(source.contains("group_key="))
    }

    @Test
    fun composerAuthorityDelegatesMaterialResolutionToGroupAuthority() {
        val source = File(
            "src/main/java/app/peermesh/nearby/NearbyPrivateInvitationComposerAuthority.kt"
        ).readText()
        assertTrue(source.contains("NearbyPrivateGroupInvitationSendPipeline"))
        assertTrue(source.contains("GroupInvitationMaterialAuthority"))
        assertTrue(source.contains("pipeline::send"))
        assertFalse(source.contains("AppDatabaseProvider"))
        assertFalse(source.contains("groupDao"))
        assertFalse(source.contains("SecureKeyStore"))
        assertFalse(source.contains("Base64Url"))
        assertFalse(source.contains("buildInviteLink"))
        assertFalse(source.contains("group_key="))
        assertFalse(source.contains("GroupInvitationProtocol"))
        assertFalse(source.contains("JcaPrivateRecipientCrypto"))
        assertFalse(source.contains("nearbyRoomMessageDao"))
    }

    @Test
    fun groupMaterialAuthorityOwnsInviteMaterialWithoutNearbyTransportAuthority() {
        val source = File(
            "src/main/java/app/peermesh/GroupInvitationMaterialAuthority.kt"
        ).readText()
        assertTrue(source.contains("AppDatabaseProvider"))
        assertTrue(source.contains("SecureKeyStore"))
        assertTrue(source.contains("InvitationCredential.of"))
        assertFalse(source.contains("NearbyPrivateGroupInvitationSendPipeline"))
        assertFalse(source.contains("NearbyRoomRepository"))
        assertFalse(source.contains("GroupInvitationProtocol"))
    }
}
