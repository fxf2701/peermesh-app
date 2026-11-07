package app.peermesh.core.map

/**
 * Single source of truth for the category of every canonical map kind.
 * Unknown kinds deliberately return null instead of receiving a guessed category.
 */
object MapItemKindCatalog {
    const val OBSTACLE = "obstacle"
    const val CROWDED = "crowded"
    const val RISK = "risk"
    const val HOT_ZONE = "hot_zone"
    const val INCIDENT = "incident"
    const val MEDICAL = "medical"
    const val RESOURCE = "resource"
    const val ROUTE_NOTE = "route_note"
    const val ENVIRONMENT = "environment"
    const val INFO = "info"
    const val CHECKPOINT = "checkpoint"
    const val ROAD_CLOSED = "road_closed"
    const val TRAFFIC_JAM = "traffic_jam"
    const val POWER_OUTAGE = "power_outage"
    const val CONNECTIVITY = "connectivity"
    const val STEEP = "steep"
    const val WATER_CROSSING = "water_crossing"
    const val WILDLIFE = "wildlife"
    const val CAMP = "camp"
    const val RESCUE = "rescue"
    const val WAYPOINT = "waypoint"
    const val MEETING_POINT = "meeting_point"
    const val ASSEMBLY_ZONE = "assembly_zone"
    const val TRAIL_SECTOR = "trail_sector"
    const val EVENT_ZONE = "event_zone"
    const val RESCUE_STAGING = "rescue_staging"

    private val categoryByCanonicalKind = mapOf(
        OBSTACLE to MapItemCategory.SITUATION,
        CROWDED to MapItemCategory.SITUATION,
        RISK to MapItemCategory.SITUATION,
        HOT_ZONE to MapItemCategory.SITUATION,
        INCIDENT to MapItemCategory.SITUATION,
        MEDICAL to MapItemCategory.SITUATION,
        RESOURCE to MapItemCategory.SITUATION,
        ROUTE_NOTE to MapItemCategory.SITUATION,
        ENVIRONMENT to MapItemCategory.SITUATION,
        INFO to MapItemCategory.SITUATION,
        CHECKPOINT to MapItemCategory.SITUATION,
        ROAD_CLOSED to MapItemCategory.SITUATION,
        TRAFFIC_JAM to MapItemCategory.SITUATION,
        POWER_OUTAGE to MapItemCategory.SITUATION,
        CONNECTIVITY to MapItemCategory.SITUATION,
        STEEP to MapItemCategory.SITUATION,
        WATER_CROSSING to MapItemCategory.SITUATION,
        WILDLIFE to MapItemCategory.SITUATION,
        CAMP to MapItemCategory.COORDINATION,
        RESCUE to MapItemCategory.SITUATION,
        WAYPOINT to MapItemCategory.COORDINATION,
        MEETING_POINT to MapItemCategory.COORDINATION,
        ASSEMBLY_ZONE to MapItemCategory.COORDINATION,
        TRAIL_SECTOR to MapItemCategory.COORDINATION,
        EVENT_ZONE to MapItemCategory.COORDINATION,
        RESCUE_STAGING to MapItemCategory.COORDINATION,
    )

    private val legacyAliasToCanonicalKind = mapOf(
        "danger" to RISK,
        "blocked_path" to OBSTACLE,
        "safe" to INFO,
        "food_water" to RESOURCE,
        "shelter" to RESOURCE,
    )

    val canonicalKinds: Set<String> = categoryByCanonicalKind.keys
    val legacyAliases: Set<String> = legacyAliasToCanonicalKind.keys

    private val markerOnlyKinds = setOf(ROUTE_NOTE, INFO, CHECKPOINT, CONNECTIVITY, CAMP, WAYPOINT, MEETING_POINT)

    fun normalize(kind: String?): String? {
        if (kind.isNullOrBlank()) return kind
        return legacyAliasToCanonicalKind[kind] ?: kind
    }

    fun categoryFor(kind: String?): MapItemCategory? =
        categoryByCanonicalKind[normalize(kind)]

    fun supportsMarker(kind: String?): Boolean = categoryFor(kind) != null

    fun supportsArea(kind: String?): Boolean = normalize(kind) in canonicalKinds - markerOnlyKinds
}
