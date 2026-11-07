package app.peermesh.security.groupinvitation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipientIdentityRecoveryStateTest {
    @Test
    fun processRestartWithIndeterminateMarkerAndMissingAliasRequiresExplicitRecovery() {
        assertEquals(
            RecipientEncryptionIdentityStatus.Invalidated(
                RecipientEncryptionIdentityStatus.Reason.RESET_RECOVERY_REQUIRED,
            ),
            recoveryStatus(RecipientResetRecoveryState.Indeterminate, aliasExists = false),
        )
    }

    @Test
    fun processRestartWithIndeterminateMarkerAndExistingAliasAllowsAdoption() {
        assertNull(recoveryStatus(RecipientResetRecoveryState.Indeterminate, aliasExists = true))
    }

    @Test
    fun malformedMarkerFailsConservativelyRegardlessOfAlias() {
        val expected = RecipientEncryptionIdentityStatus.Invalidated(
            RecipientEncryptionIdentityStatus.Reason.RESET_RECOVERY_REQUIRED,
        )
        assertEquals(expected, recoveryStatus(RecipientResetRecoveryState.Invalid, aliasExists = false))
        assertEquals(expected, recoveryStatus(RecipientResetRecoveryState.Invalid, aliasExists = true))
    }

    @Test
    fun absentMarkerAllowsFreshIdentityCreation() {
        assertNull(recoveryStatus(RecipientResetRecoveryState.Absent, aliasExists = false))
    }
}
