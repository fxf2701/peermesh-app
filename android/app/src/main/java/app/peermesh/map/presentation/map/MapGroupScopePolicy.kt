package app.peermesh.map.presentation.map

internal data class MapGroupScopeToken(
    val groupId: String,
    val generation: Long,
)

internal data class ScopedTrailIdentity(
    val groupId: String,
    val trailId: String,
)

/** Android-free ownership policy shared by Activity callbacks and deterministic JVM tests. */
internal object MapGroupScopePolicy {
    fun isCurrent(
        token: MapGroupScopeToken,
        currentGroupId: String,
        currentGeneration: Long,
    ): Boolean = token.groupId == currentGroupId && token.generation == currentGeneration

    fun isCurrentTrail(
        token: MapGroupScopeToken,
        trailId: String,
        currentGroupId: String,
        currentGeneration: Long,
        selectedTrailId: String?,
        currentTrails: Collection<ScopedTrailIdentity>,
    ): Boolean = isCurrent(token, currentGroupId, currentGeneration) &&
        selectedTrailId == trailId &&
        ScopedTrailIdentity(token.groupId, trailId) in currentTrails
}

internal object MapRefreshTimerPolicy {
    fun mayLoadAndReschedule(
        token: MapGroupScopeToken,
        currentGroupId: String,
        currentGeneration: Long,
        paused: Boolean,
    ): Boolean = !paused && MapGroupScopePolicy.isCurrent(
        token,
        currentGroupId,
        currentGeneration,
    )
}

internal class ScopedRetryOwnership<K, T : Any>(
    private val cancelTask: (T) -> Unit,
) {
    private val tasks = mutableMapOf<K, T>()

    fun replace(key: K, task: T) {
        tasks.put(key, task)?.let(cancelTask)
    }

    fun isOwned(key: K, task: T): Boolean = tasks[key] === task

    fun removeIfOwned(key: K, task: T): Boolean =
        if (isOwned(key, task)) {
            tasks.remove(key)
            true
        } else {
            false
        }

    fun cancel(key: K) {
        tasks.remove(key)?.let(cancelTask)
    }

    fun cancelAll() {
        tasks.values.toList().forEach(cancelTask)
        tasks.clear()
    }

    fun size(): Int = tasks.size
}

internal sealed interface RetryAttemptDecision {
    data class Schedule(val attempt: Int) : RetryAttemptDecision
    data object Exhausted : RetryAttemptDecision
}

internal class ScopedRetryAttemptState<K>(
    private val maxDelayedRetries: Int,
) {
    private val attempts = mutableMapOf<K, Int>()

    init {
        require(maxDelayedRetries >= 0)
    }

    fun onEmpty(key: K): RetryAttemptDecision {
        val completed = attempts[key] ?: 0
        if (completed >= maxDelayedRetries) {
            attempts.remove(key)
            return RetryAttemptDecision.Exhausted
        }
        val next = completed + 1
        attempts[key] = next
        return RetryAttemptDecision.Schedule(next)
    }

    fun reset(key: K) {
        attempts.remove(key)
    }

    fun resetAll() {
        attempts.clear()
    }

    fun attempts(key: K): Int = attempts[key] ?: 0
}
