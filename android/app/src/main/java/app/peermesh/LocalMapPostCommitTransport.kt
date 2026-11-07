package app.peermesh

import app.peermesh.data.map.local.LocalMapWriteOutcome
import app.peermesh.core.model.EventCore

/** Small post-commit compatibility seam; it has no persistence or semantic-policy authority. */
internal object LocalMapPostCommitTransport {
    sealed interface Result {
        data object NotEligible : Result
        data object MissingGroupKey : Result
        data class Enqueued(val hasBlePermission: Boolean, val bleReady: Boolean, val enqueueResult: Boolean) : Result
        data class EnqueueFailed(val hasBlePermission: Boolean, val bleReady: Boolean, val cause: Throwable) : Result
    }

    fun dispatch(
        outcome: LocalMapWriteOutcome,
        core: EventCore,
        hasGroupKey: () -> Boolean,
        hasBlePermission: () -> Boolean,
        isBleReady: () -> Boolean,
        enqueue: (EventCore) -> Boolean,
    ): Result {
        if (outcome !is LocalMapWriteOutcome.AcceptedNew) return Result.NotEligible
        if (!hasGroupKey()) return Result.MissingGroupKey
        val permission = hasBlePermission()
        val ready = isBleReady() && permission
        return try {
            Result.Enqueued(permission, ready, enqueue(core))
        } catch (cause: Exception) {
            Result.EnqueueFailed(permission, ready, cause)
        }
    }
}
