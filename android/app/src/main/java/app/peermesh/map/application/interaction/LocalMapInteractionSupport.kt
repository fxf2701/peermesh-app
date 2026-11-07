package app.peermesh.map.application.interaction

data class LocalMapInteractionSupport(
    val supportedInteractions: Set<MapInteractionType>,
) {
    fun supports(interaction: MapInteractionType): Boolean = interaction in supportedInteractions

    companion object {
        fun allSupported(): LocalMapInteractionSupport =
            LocalMapInteractionSupport(MapInteractionType.all.toSet())
    }
}

fun interface LocalMapInteractionSupportProvider {
    fun supportFor(groupId: String): LocalMapInteractionSupport
}
