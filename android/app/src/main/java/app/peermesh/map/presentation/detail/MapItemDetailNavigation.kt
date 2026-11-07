package app.peermesh.map.presentation.detail

import android.content.Intent
import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.MapItemIdentity

/** UI integration seam. Navigation must carry identity only, never Room entities or snapshots. */
fun interface OpenMapItemDetail {
    fun open(identity: MapItemIdentity)
}

/**
 * Platform argument codec contract for exact group/type/item identity.
 * Android Intent implementation belongs in M3A4 production UI integration.
 */
interface MapItemDetailNavigationArguments {
    fun encode(identity: MapItemIdentity): Map<String, String>

    fun decode(arguments: Map<String, String>): MapItemIdentity?
}

object DefaultMapItemDetailNavigationArguments : MapItemDetailNavigationArguments {
    const val EXTRA_GROUP_ID = "map_detail_group_id"
    const val EXTRA_ITEM_TYPE = "map_detail_item_type"
    const val EXTRA_ITEM_ID = "map_detail_item_id"

    override fun encode(identity: MapItemIdentity): Map<String, String> = mapOf(
        EXTRA_GROUP_ID to identity.groupId,
        EXTRA_ITEM_TYPE to identity.itemType.wireValue,
        EXTRA_ITEM_ID to identity.itemId,
    )

    override fun decode(arguments: Map<String, String>): MapItemIdentity? {
        val groupId = arguments[EXTRA_GROUP_ID]?.takeIf { it.isNotBlank() } ?: return null
        val itemId = arguments[EXTRA_ITEM_ID]?.takeIf { it.isNotBlank() } ?: return null
        val itemType = MapItemType.fromWireValue(arguments[EXTRA_ITEM_TYPE]) ?: return null
        return MapItemIdentity(groupId, itemType, itemId)
    }

    fun put(intent: Intent, identity: MapItemIdentity): Intent = intent.apply {
        encode(identity).forEach { (key, value) -> putExtra(key, value) }
    }

    fun decode(intent: Intent): MapItemIdentity? = decode(
        listOf(EXTRA_GROUP_ID, EXTRA_ITEM_TYPE, EXTRA_ITEM_ID).mapNotNull { key ->
            intent.getStringExtra(key)?.let { key to it }
        }.toMap(),
    )
}
