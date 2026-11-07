package app.peermesh.map.application.interaction

enum class MapPeerCapabilityLevel {
    SUPPORTED,
    MIXED,
    UNKNOWN,
    UNAVAILABLE,
}

data class MapPeerCapabilitySummary(
    val level: MapPeerCapabilityLevel,
)

/** Presentation-only compatibility evidence. Never implies delivery certainty. */
fun interface MapPeerCapabilityProvider {
    fun capabilityFor(groupId: String): MapPeerCapabilitySummary
}

object UnknownMapPeerCapabilityProvider : MapPeerCapabilityProvider {
    override fun capabilityFor(groupId: String): MapPeerCapabilitySummary =
        MapPeerCapabilitySummary(MapPeerCapabilityLevel.UNKNOWN)
}
