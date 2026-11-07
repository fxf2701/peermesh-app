package app.peermesh.relay

interface ManagedRelayAccessProvider {
    fun isConfigured(): Boolean

    fun acquire(gids: List<String>): ManagedRelayAccessBindingResult

    fun clearCachedBinding(relayUrl: String)
}

data class ManagedRelayCycleResult(
    val report: RelaySyncRunReport,
    val authFailureCode: String? = null,
)

fun interface ManagedRelayCycleExecutor {
    fun run(binding: ManagedRelayAccessBinding, gids: List<String>): ManagedRelayCycleResult
}

class ManagedRelaySyncRuntime(
    private val accessProvider: ManagedRelayAccessProvider,
    private val cycleExecutor: ManagedRelayCycleExecutor,
) {
    fun isConfigured(): Boolean = accessProvider.isConfigured()

    fun runOnce(gids: List<String>): RelaySyncRunReport {
        val firstBindingResult = accessProvider.acquire(gids)
        val firstBinding = firstBindingResult.binding
        if (!firstBindingResult.success || firstBinding == null) {
            return RelaySyncRunReport.empty().copy(
                success = false,
                errorSummary = firstBindingResult.errorMessage
                    ?: firstBindingResult.errorCode
                    ?: "managed_relay_bootstrap_failed",
                relayCount = 0,
                gidCount = gids.size,
            )
        }

        val firstCycle = cycleExecutor.run(firstBinding, gids)
        if (!shouldRetry(firstCycle.authFailureCode)) {
            return firstCycle.report
        }

        accessProvider.clearCachedBinding(firstBinding.relayUrl)
        val secondBindingResult = accessProvider.acquire(gids)
        val secondBinding = secondBindingResult.binding
        if (!secondBindingResult.success || secondBinding == null) {
            return firstCycle.report.withRetryNote(
                "managed_reauth_failed:${secondBindingResult.errorCode ?: secondBindingResult.errorMessage ?: "unknown"}",
            )
        }

        return cycleExecutor.run(secondBinding, gids)
            .report
            .withRetryNote("managed_reauth_retry")
    }

    private fun shouldRetry(authFailureCode: String?): Boolean {
        return authFailureCode != null && authFailureCode in RECOVERABLE_AUTH_FAILURE_CODES
    }

    private fun RelaySyncRunReport.withRetryNote(extra: String): RelaySyncRunReport {
        val merged = listOfNotNull(note, extra)
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")
            .ifBlank { null }
        return copy(note = merged)
    }

    companion object {
        val RECOVERABLE_AUTH_FAILURE_CODES = setOf(
            "grant_mismatch",
            "client_session_expired",
            "client_session_token_mismatch",
        )
    }
}
