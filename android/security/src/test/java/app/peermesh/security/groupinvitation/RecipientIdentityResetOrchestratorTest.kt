package app.peermesh.security.groupinvitation

import java.security.KeyStoreException
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipientIdentityResetOrchestratorTest {
    @Test
    fun resetIntentFailurePreventsEveryLaterMutation() {
        val operations = mutableListOf<String>()
        val result = reset(operations, persistIntent = false)

        assertTemporary(result, RecipientEncryptionIdentityStatus.Reason.METADATA_WRITE_FAILED)
        assertEquals(listOf("load", "intent"), operations)
    }

    @Test
    fun identityMetadataFailureAttemptsMarkerCleanupAndPreservesProbe() {
        val operations = mutableListOf<String>()
        val result = reset(operations, clearIdentity = false)

        assertTemporary(result, RecipientEncryptionIdentityStatus.Reason.METADATA_WRITE_FAILED)
        assertEquals(listOf("load", "intent", "metadata", "clear-reset"), operations)
    }

    @Test
    fun deleteFailureWithAliasPresentPersistsIndeterminateState() {
        val operations = mutableListOf<String>()
        val result = reset(operations, deleteFailure = true, aliasExists = true)

        assertTemporary(result, RecipientEncryptionIdentityStatus.Reason.KEYSTORE_UNAVAILABLE)
        assertEquals(listOf("load", "intent", "metadata", "delete", "probe", "exists", "indeterminate"), operations)
    }

    @Test
    fun deleteThenThrowWithAliasAbsentClearsMarkerAndSucceeds() {
        val operations = mutableListOf<String>()
        val result = reset(operations, deleteFailure = true, aliasExists = false)

        assertEquals(RecipientIdentityResetResult.Success, result)
        assertEquals(listOf("load", "intent", "metadata", "delete", "probe", "exists", "clear-reset"), operations)
    }

    @Test
    fun unverifiablePostconditionPersistsIndeterminateState() {
        val operations = mutableListOf<String>()
        val result = reset(operations, deleteFailure = true, aliasCheckFailure = true)

        assertTemporary(result, RecipientEncryptionIdentityStatus.Reason.KEYSTORE_UNAVAILABLE)
        assertEquals(listOf("load", "intent", "metadata", "delete", "probe", "exists", "indeterminate"), operations)
    }

    @Test
    fun successfulOrderingClearsDurableMarkerLast() {
        val operations = mutableListOf<String>()
        val result = reset(operations)

        assertEquals(RecipientIdentityResetResult.Success, result)
        assertEquals(listOf("load", "intent", "metadata", "delete", "probe", "clear-reset"), operations)
    }

    @Test
    fun activeRoomPreconditionRemainsMutationFree() {
        assertEquals(
            RecipientIdentityResetResult.BlockedByActiveNearbyRooms(1),
            RecipientIdentityResetOrchestrator.activeRoomPrecondition(1),
        )
        assertEquals(
            RecipientIdentityResetResult.InvalidActiveNearbyRoomCount,
            RecipientIdentityResetOrchestrator.activeRoomPrecondition(-1),
        )
    }

    private fun reset(
        operations: MutableList<String>,
        persistIntent: Boolean = true,
        clearIdentity: Boolean = true,
        deleteFailure: Boolean = false,
        aliasExists: Boolean = true,
        aliasCheckFailure: Boolean = false,
    ): RecipientIdentityResetResult = RecipientIdentityResetOrchestrator.reset(
        loadTarget = { operations += "load"; Any() },
        persistResetIntent = { operations += "intent"; persistIntent },
        clearIdentityMetadata = { operations += "metadata"; clearIdentity },
        clearResetState = { operations += "clear-reset"; true },
        deleteIdentity = {
            operations += "delete"
            if (deleteFailure) throw KeyStoreException("delete")
        },
        identityExists = {
            operations += "exists"
            if (aliasCheckFailure) throw KeyStoreException("exists")
            aliasExists
        },
        persistIndeterminateState = { operations += "indeterminate"; true },
        clearProbeCache = { operations += "probe" },
    )

    private fun assertTemporary(result: RecipientIdentityResetResult, reason: RecipientEncryptionIdentityStatus.Reason) {
        assertEquals(RecipientIdentityResetResult.TemporarilyUnavailable(reason), result)
    }
}
