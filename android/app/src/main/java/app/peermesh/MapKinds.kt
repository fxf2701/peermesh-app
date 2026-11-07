package app.peermesh

import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemKindCatalog

object MapKinds {
    const val PROFILE_URBAN = "urban"
    const val PROFILE_OUTDOOR = "outdoor"

    data class KindOption(val key: String, val label: String) {
        override fun toString(): String = label
    }

    private val core = listOf(
        KindOption("obstacle", "\ud83d\udea7 Blocked path"),
        KindOption("crowded", "\ud83d\udc65 Crowded area"),
        KindOption("risk", "\u26a0\ufe0f Potential risk"),
        KindOption("hot_zone", "\ud83d\udd25 Hot zone"),
        KindOption("incident", "\ud83d\udea8 Ongoing disruption"),
        KindOption("medical", "\ud83d\ude91 Medical help needed"),
        KindOption("resource", "\ud83d\udca7 Utility point"),
        KindOption("route_note", "\ud83e\udded Route observation"),
        KindOption("environment", "\ud83c\udf27 Environmental conditions"),
        KindOption("info", "\ud83d\udcdd Observation note"),
    )

    private val urban = listOf(
        KindOption("checkpoint", "\ud83d\udec2 Checkpoint"),
        KindOption("road_closed", "\u26d4 Road closed"),
        KindOption("traffic_jam", "\ud83d\ude97 Traffic congestion"),
        KindOption("power_outage", "\ud83d\udd0c Power outage"),
        KindOption("connectivity", "\ud83d\udcf6 Connectivity point"),
        KindOption("meeting_point", "\ud83d\udccc Meeting point"),
        KindOption("waypoint", "\ud83d\udccd Waypoint"),
    )

    private val outdoor = listOf(
        KindOption("steep", "\u26f0 Steep terrain"),
        KindOption("water_crossing", "\ud83c\udf0a Water crossing"),
        KindOption("wildlife", "\ud83d\udc3e Wildlife presence"),
        KindOption("camp", "\ud83c\udfd5 Campsite"),
        KindOption("rescue", "\ud83c\udd98 Rescue needed"),
        KindOption("waypoint", "\ud83d\udccd Waypoint"),
    )

    private val areaCore = listOf(
        KindOption("obstacle", "\ud83d\udea7 Blocked path"),
        KindOption("crowded", "\ud83d\udc65 Crowded area"),
        KindOption("risk", "\u26a0\ufe0f Potential risk"),
        KindOption("hot_zone", "\ud83d\udd25 Hot zone"),
        KindOption("incident", "\ud83d\udea8 Ongoing disruption"),
        KindOption("medical", "\ud83d\ude91 Medical help needed"),
        KindOption("resource", "\ud83d\udca7 Utility point"),
        KindOption("environment", "\ud83c\udf27 Environmental conditions"),
        KindOption("assembly_zone", "\ud83e\uddeD Assembly zone"),
    )

    private val areaUrban = listOf(
        KindOption("road_closed", "\u26d4 Road closed"),
        KindOption("traffic_jam", "\ud83d\ude97 Traffic congestion"),
        KindOption("power_outage", "\ud83d\udd0c Power outage"),
        KindOption("event_zone", "\ud83c\udfaa Event zone"),
    )

    private val areaOutdoor = listOf(
        KindOption("steep", "\u26f0 Steep terrain"),
        KindOption("water_crossing", "\ud83c\udf0a Water crossing"),
        KindOption("wildlife", "\ud83d\udc3e Wildlife presence"),
        KindOption("rescue", "\ud83c\udd98 Rescue needed"),
        KindOption("trail_sector", "\ud83e\udd7e Trail sector"),
        KindOption("event_zone", "\ud83c\udfaa Event zone"),
        KindOption("rescue_staging", "\ud83d\ude91 Rescue staging"),
    )

    private val labelByKey: Map<String, String> =
        (core + urban + outdoor + areaCore + areaUrban + areaOutdoor)
            .distinctBy(KindOption::key)
            .associate { it.key to it.label }

    private val allMarkerOptions: List<KindOption> =
        (core + urban + outdoor).distinctBy(KindOption::key)

    private val allAreaOptions: List<KindOption> =
        (areaCore + areaUrban + areaOutdoor).distinctBy(KindOption::key)

    fun kindOptions(profile: String?): List<KindOption> {
        return when (profile) {
            PROFILE_OUTDOOR -> core + outdoor
            PROFILE_URBAN -> core + urban
            else -> core + urban
        }
    }

    fun areaKindOptions(profile: String?): List<KindOption> {
        return when (profile) {
            PROFILE_OUTDOOR -> areaCore + areaOutdoor
            PROFILE_URBAN -> areaCore + areaUrban
            else -> areaCore + areaUrban
        }
    }

    /**
     * Category/shape-safe creation options.
     *
     * A profile remains a preference, not a semantic authority. If it contains no
     * option for an accepted category, fall back to all catalog-backed options for
     * that shape instead of publishing an unexplained empty selector.
     */
    fun markerCreationOptions(
        profile: String?,
        category: MapItemCategory,
    ): List<KindOption> = compatibleCreationOptions(
        preferred = kindOptions(profile),
        fallback = allMarkerOptions,
        category = category,
        shapeSupported = MapItemKindCatalog::supportsMarker,
    )

    fun areaCreationOptions(
        profile: String?,
        category: MapItemCategory,
    ): List<KindOption> = compatibleCreationOptions(
        preferred = areaKindOptions(profile),
        fallback = allAreaOptions,
        category = category,
        shapeSupported = MapItemKindCatalog::supportsArea,
    )

    fun labelForKey(key: String?): String? {
        if (key.isNullOrBlank()) return null
        val normalized = MapItemKindCatalog.normalize(key)
        return labelByKey[normalized]
    }

    fun normalizeKey(key: String?): String? {
        return MapItemKindCatalog.normalize(key)
    }

    fun categoryForKey(key: String?): MapItemCategory? = MapItemKindCatalog.categoryFor(key)

    fun allMarkerKindKeys(): Set<String> = (core + urban + outdoor).mapTo(linkedSetOf()) { it.key }

    fun allAreaKindKeys(): Set<String> = (areaCore + areaUrban + areaOutdoor).mapTo(linkedSetOf()) { it.key }

    private fun compatibleCreationOptions(
        preferred: List<KindOption>,
        fallback: List<KindOption>,
        category: MapItemCategory,
        shapeSupported: (String?) -> Boolean,
    ): List<KindOption> {
        fun List<KindOption>.compatible(): List<KindOption> = asSequence()
            .filter { categoryForKey(it.key) == category }
            .filter { shapeSupported(it.key) }
            .distinctBy(KindOption::key)
            .toList()

        return preferred.compatible().ifEmpty { fallback.compatible() }
    }
}
