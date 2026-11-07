package app.peermesh.security.groupinvitation

import java.security.GeneralSecurityException

private typealias ResetReason = RecipientEncryptionIdentityStatus.Reason

/** Narrow failure-safe reset state machine shared by the Keystore adapter and JVM tests. */
internal object RecipientIdentityResetOrchestrator {
    fun activeRoomPrecondition(activeNearbyRoomCount: Int): RecipientIdentityResetResult? = when {
        activeNearbyRoomCount < 0 -> RecipientIdentityResetResult.InvalidActiveNearbyRoomCount
        activeNearbyRoomCount > 0 -> RecipientIdentityResetResult.BlockedByActiveNearbyRooms(activeNearbyRoomCount)
        else -> null
    }

    fun <T> reset(
        loadTarget: () -> T,
        persistResetIntent: () -> Boolean,
        clearIdentityMetadata: () -> Boolean,
        clearResetState: () -> Boolean,
        deleteIdentity: (T) -> Unit,
        identityExists: (T) -> Boolean,
        persistIndeterminateState: () -> Boolean,
        clearProbeCache: () -> Unit,
    ): RecipientIdentityResetResult {
        val target = securityOperationOrNull(loadTarget)
            ?: return temporary(ResetReason.KEYSTORE_UNAVAILABLE)
        if (!metadataOperation(persistResetIntent)) return temporary(ResetReason.METADATA_WRITE_FAILED)
        if (!metadataOperation(clearIdentityMetadata)) {
            metadataOperation(clearResetState)
            return temporary(ResetReason.METADATA_WRITE_FAILED)
        }
        return try {
            deleteIdentity(target)
            clearProbeCache()
            if (metadataOperation(clearResetState)) {
                RecipientIdentityResetResult.Success
            } else {
                temporary(ResetReason.METADATA_WRITE_FAILED)
            }
        } catch (_: GeneralSecurityException) {
            reconcileDeletionFailure(target, identityExists, persistIndeterminateState, clearResetState, clearProbeCache)
        } catch (_: RuntimeException) {
            reconcileDeletionFailure(target, identityExists, persistIndeterminateState, clearResetState, clearProbeCache)
        }
    }

    private fun <T> reconcileDeletionFailure(
        target: T,
        identityExists: (T) -> Boolean,
        persistIndeterminateState: () -> Boolean,
        clearResetState: () -> Boolean,
        clearProbeCache: () -> Unit,
    ): RecipientIdentityResetResult {
        clearProbeCache()
        val exists = securityOperationOrNull { identityExists(target) }
        return when (exists) {
            false -> if (metadataOperation(clearResetState)) {
                RecipientIdentityResetResult.Success
            } else {
                temporary(ResetReason.METADATA_WRITE_FAILED)
            }
            true, null -> {
                metadataOperation(persistIndeterminateState)
                temporary(ResetReason.KEYSTORE_UNAVAILABLE)
            }
        }
    }

    private fun metadataOperation(operation: () -> Boolean): Boolean = try {
        operation()
    } catch (_: RuntimeException) {
        false
    }

    private fun <T> securityOperationOrNull(operation: () -> T): T? = try {
        operation()
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun temporary(reason: ResetReason) = RecipientIdentityResetResult.TemporarilyUnavailable(reason)
}
