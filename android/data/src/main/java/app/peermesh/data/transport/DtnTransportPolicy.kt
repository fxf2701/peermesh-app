package app.peermesh.data.transport

import app.peermesh.core.types.EventTypes

object DtnTransportPolicy {
    const val DEFAULT_TTL_SECONDS: Int = 3600
    const val DEFAULT_MAX_HOPS: Int = 20
    const val PRIORITY_MEMBERSHIP_CONTROL: Int = 0
    const val PRIORITY_CRITICAL: Int = 0
    const val PRIORITY_SAFETY: Int = 1
    const val PRIORITY_MAP: Int = 2
    const val PRIORITY_CHAT: Int = 3
    const val PRIORITY_OTHER: Int = 4
    const val PRIORITY_ACK_BACKGROUND: Int = 9

    fun priorityForEventType(type: String): Int {
        return when {
            EventTypes.isPriorityControl(type) -> PRIORITY_MEMBERSHIP_CONTROL
            type.startsWith("SYS.ALERT") -> PRIORITY_CRITICAL
            type.startsWith("SYS.SAFETY") -> PRIORITY_SAFETY
            EventTypes.SYS_ACK == type -> PRIORITY_ACK_BACKGROUND
            EventTypes.isLiveTrail(type) -> PRIORITY_SAFETY
            EventTypes.isMap(type) -> PRIORITY_MAP
            EventTypes.isChat(type) -> PRIORITY_CHAT
            else -> PRIORITY_OTHER
        }
    }
}
