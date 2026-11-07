package app.peermesh.map.presentation.list

/**
 * Presentation-only owner identity projection for map rows. The map event is
 * intentionally not changed when a user updates either of these names.
 */
data class MapOwnerNameProjection(
    val groupNickname: String? = null,
    val profileName: String? = null,
)

sealed interface MapOwnerDisplayName {
    data object You : MapOwnerDisplayName
    data class Named(val value: String) : MapOwnerDisplayName
    data object GroupMember : MapOwnerDisplayName
}

/** Resolves owner names without exposing persistence or Settings to row views. */
object MapOwnerDisplayNameResolver {
    fun resolve(
        ownerActorDid: String?,
        activeActorDid: String?,
        projections: Map<String, MapOwnerNameProjection>,
    ): MapOwnerDisplayName {
        if (!ownerActorDid.isNullOrBlank() && ownerActorDid == activeActorDid) {
            return MapOwnerDisplayName.You
        }

        val projection = ownerActorDid?.let(projections::get)
        return projection?.groupNickname.normalizedName()?.let(MapOwnerDisplayName::Named)
            ?: projection?.profileName.normalizedName()?.let(MapOwnerDisplayName::Named)
            ?: MapOwnerDisplayName.GroupMember
    }

    private fun String?.normalizedName(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
