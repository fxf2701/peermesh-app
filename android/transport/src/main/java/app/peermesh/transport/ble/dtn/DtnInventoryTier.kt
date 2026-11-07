package app.peermesh.transport.ble.dtn

import app.peermesh.core.types.EventTypes

enum class DtnInventoryTier(val wireValue: String) {
    HIGH("high"),
    NORMAL("normal"),
    LOW("low");

    companion object {
        const val HIGH_PRIORITY_WINDOW_SECONDS: Long = 60L * 60L

        val ordered = listOf(HIGH, NORMAL, LOW)

        fun fromWireValue(value: String?): DtnInventoryTier? {
            return ordered.firstOrNull { it.wireValue == value }
        }

        fun classify(item: DtnInventoryItem, nowSeconds: Long): DtnInventoryTier {
            return when {
                isHighPriorityMessage(item.type, item.createdAtSeconds, nowSeconds) -> HIGH
                item.type == EventTypes.SYS_ACK -> LOW
                else -> NORMAL
            }
        }

        fun isHighPriorityMessage(type: String, createdAtSeconds: Long, nowSeconds: Long): Boolean {
            if (createdAtSeconds > nowSeconds) return false
            if (nowSeconds - createdAtSeconds > HIGH_PRIORITY_WINDOW_SECONDS) return false
            return type == EventTypes.CHAT_MSG ||
                type == EventTypes.CHAT_REPLY ||
                type == EventTypes.CHAT_EDIT ||
                type == EventTypes.CHAT_DEL
        }
    }
}
