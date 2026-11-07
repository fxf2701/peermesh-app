package app.peermesh.relay

object RelayLocalBlockPolicy {
    private val MANAGED_SESSION_ABUSE_CODES = setOf(
        "grant_mismatch",
        "client_session_token_mismatch",
        "client_session_expired",
    )

    fun blockReasonFor(statusCode: Int, errorCode: String?): String? {
        if (statusCode !in setOf(401, 403, 409)) return null
        val code = errorCode?.trim().orEmpty()
        if (code.isEmpty()) return null
        if (code in MANAGED_SESSION_ABUSE_CODES) {
            return "managed_pairwise_session_abuse:$code"
        }
        return null
    }

    fun registryReportReasonFor(blockReason: String): String? {
        return when {
            blockReason.startsWith("managed_pairwise_session_abuse:") -> "invalid_session_behavior"
            else -> null
        }
    }
}
