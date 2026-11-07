package app.peermesh.core.types

object EventTypes {
    const val CHAT_MSG = "CHAT.MSG"
    const val CHAT_REPLY = "CHAT.REPLY"
    const val CHAT_EDIT = "CHAT.EDIT"
    const val CHAT_DEL = "CHAT.DEL"

    const val MAP_MARKER_ADD = "MAP.MARKER.ADD"
    const val MAP_MARKER_UPD = "MAP.MARKER.UPD"
    const val MAP_MARKER_DEL = "MAP.MARKER.DEL"

    const val MAP_AREA_ADD = "MAP.AREA.ADD"
    const val MAP_AREA_UPD = "MAP.AREA.UPD"
    const val MAP_AREA_DEL = "MAP.AREA.DEL"

    const val MAP_ITEM_CONFIRM = "MAP.ITEM.CONFIRM"
    const val MAP_ITEM_SITUATION_UPDATE = "MAP.ITEM.SITUATION.UPDATE"
    const val MAP_ITEM_SITUATION_STATUS = "MAP.ITEM.SITUATION.STATUS"
    const val MAP_ITEM_ACKNOWLEDGE = "MAP.ITEM.ACKNOWLEDGE"
    const val MAP_ITEM_NOTE = "MAP.ITEM.NOTE"

    const val SYS_PRESENCE = "SYS.PRESENCE"
    const val SYS_ALERT = "SYS.ALERT"
    const val SYS_GROUP = "SYS.GROUP"
    const val SYS_USER = "SYS.USER"
    const val SYS_GROUP_UPD = "SYS.GROUP.UPD"
    const val SYS_USER_UPD = "SYS.USER.UPD"
    const val SYS_MEMBER_REMOVED = "SYS.MEMBER.REMOVED"
    const val SYS_GROUP_REKEY = "SYS.GROUP.REKEY"
    const val SYS_GROUP_KEY_SHARE = "SYS.GROUP.KEY.SHARE"
    const val SYS_ACK = "SYS.ACK"

    const val LIVE_TRAIL_START = "LIVE_TRAIL_START"
    const val LIVE_TRAIL_POINT_BATCH = "LIVE_TRAIL_POINT_BATCH"
    const val LIVE_TRAIL_END = "LIVE_TRAIL_END"

    fun isChat(type: String): Boolean = type.startsWith("CHAT.")
    fun isMap(type: String): Boolean = type.startsWith("MAP.")
    fun isSys(type: String): Boolean = type.startsWith("SYS.")
    fun isLiveTrail(type: String): Boolean = type.startsWith("LIVE_TRAIL_")
    fun isMembershipControl(type: String): Boolean =
        type == SYS_MEMBER_REMOVED || type == SYS_GROUP_REKEY
    fun isPriorityControl(type: String): Boolean =
        isMembershipControl(type) || type == SYS_GROUP_KEY_SHARE
}
