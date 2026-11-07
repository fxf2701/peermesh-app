package app.peermesh.transport.ble.dtn

import app.peermesh.core.types.EventTypes

object DtnForwardPolicy {
    const val PRIORITY_CRITICAL = 0
    const val PRIORITY_SAFETY = 1
    const val PRIORITY_MAP = 2
    const val PRIORITY_CHAT = 3
    const val PRIORITY_OTHER = 4

    const val DEFAULT_INVENTORY_LIMIT = 250
    const val DEFAULT_TRANSFER_LIMIT = 64

    fun priorityForEventType(type: String): Int {
        return when {
            type.startsWith("SYS.ALERT") -> PRIORITY_CRITICAL
            type.startsWith("SYS.SAFETY") -> PRIORITY_SAFETY
            EventTypes.isLiveTrail(type) -> PRIORITY_SAFETY
            EventTypes.isMap(type) -> PRIORITY_MAP
            EventTypes.isChat(type) -> PRIORITY_CHAT
            else -> PRIORITY_OTHER
        }
    }

    fun canForward(meta: DtnForwardMeta, nowSeconds: Long): Boolean {
        val expiresAt = meta.createdAtSeconds + meta.ttlSeconds
        return nowSeconds <= expiresAt && meta.hopCount < meta.maxHops
    }

    fun sortForTransfer(items: List<DtnInventoryItem>, tier: DtnInventoryTier? = null): List<DtnInventoryItem> {
        return when (tier) {
            DtnInventoryTier.HIGH -> items.sortedWith(
                compareByDescending<DtnInventoryItem> { it.createdAtSeconds }
                    .thenBy { it.eventId }
            )
            else -> items.sortedWith(
                compareBy<DtnInventoryItem> { it.priority }
                    .thenBy { it.createdAtSeconds }
                    .thenBy { it.eventId }
            )
        }
    }
}

data class DtnForwardMeta(
    val createdAtSeconds: Long,
    val ttlSeconds: Int,
    val hopCount: Int,
    val maxHops: Int,
)
