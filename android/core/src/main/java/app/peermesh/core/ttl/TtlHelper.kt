package app.peermesh.core.ttl

import app.peermesh.core.types.EventTypes

object TtlHelper {
    const val DEFAULT_CHAT_TTL_S = 86400L
    const val DEFAULT_MAP_TTL_S = 86400L
    const val DEFAULT_MEMBERSHIP_CONTROL_TTL_S = 7 * 86400L

    fun resolveTtlSeconds(eventType: String, metaTtlSeconds: Long?): Long {
        if (metaTtlSeconds != null) return metaTtlSeconds
        return when {
            EventTypes.isPriorityControl(eventType) -> DEFAULT_MEMBERSHIP_CONTROL_TTL_S
            EventTypes.isChat(eventType) -> DEFAULT_CHAT_TTL_S
            EventTypes.isMap(eventType) -> DEFAULT_MAP_TTL_S
            EventTypes.isLiveTrail(eventType) -> DEFAULT_MAP_TTL_S
            else -> DEFAULT_CHAT_TTL_S
        }
    }

    fun expiresAt(createdSeconds: Long, ttlSeconds: Long): Long {
        return createdSeconds + ttlSeconds
    }
}
