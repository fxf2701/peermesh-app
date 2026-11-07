package app.peermesh.map.application.detail

import app.peermesh.core.map.MapItemType

/**
 * Stable application identity for a map item.
 *
 * Unlike the M2 data-layer identity, this identity includes the group scope and
 * is safe to pass through future application/navigation boundaries.
 */
data class MapItemIdentity(
    val groupId: String,
    val itemType: MapItemType,
    val itemId: String,
)

internal enum class MapItemIdentityValidationFailure {
    BLANK_GROUP_ID,
    BLANK_ITEM_ID,
}

internal fun MapItemIdentity.validationFailure(): MapItemIdentityValidationFailure? = when {
    groupId.isBlank() -> MapItemIdentityValidationFailure.BLANK_GROUP_ID
    itemId.isBlank() -> MapItemIdentityValidationFailure.BLANK_ITEM_ID
    else -> null
}
