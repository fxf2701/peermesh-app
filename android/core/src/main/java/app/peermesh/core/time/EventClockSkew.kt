package app.peermesh.core.time

import app.peermesh.core.types.EventTypes
import kotlin.math.abs

object EventClockSkew {
    const val MAX_SKEW_SECONDS: Long = 600L
    const val CHAT_MAX_SKEW_SECONDS: Long = 3_600L

    fun resolveEventTimeSeconds(
        eventTimeSeconds: Long,
        arrivalSeconds: Long,
        maxSkewSeconds: Long = MAX_SKEW_SECONDS,
    ): Long {
        val skew = abs(eventTimeSeconds - arrivalSeconds)
        return if (skew > maxSkewSeconds) arrivalSeconds else eventTimeSeconds
    }

    fun isSkewed(
        eventTimeSeconds: Long,
        arrivalSeconds: Long,
        maxSkewSeconds: Long = MAX_SKEW_SECONDS,
    ): Boolean {
        return abs(eventTimeSeconds - arrivalSeconds) > maxSkewSeconds
    }

    fun maxSkewSecondsForType(type: String): Long? {
        return when {
            EventTypes.isMap(type) -> null
            EventTypes.isLiveTrail(type) -> null
            type == EventTypes.CHAT_MSG ||
                type == EventTypes.CHAT_REPLY ||
                type == EventTypes.CHAT_EDIT ||
                type == EventTypes.CHAT_DEL -> CHAT_MAX_SKEW_SECONDS
            else -> MAX_SKEW_SECONDS
        }
    }
}
