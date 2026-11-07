package app.peermesh.core.map

/** Legacy values remain readable but are never reinterpreted as a new information source. */
enum class LegacyMapConfidence(val storedValue: Int) {
    UNCERTAIN(0),
    REPORTED(1),
    OBSERVED(2);

    companion object {
        fun fromStoredValue(value: Int?): LegacyMapConfidence? =
            entries.firstOrNull { it.storedValue == value }
    }
}

enum class LegacyMapSourcePresentation {
    UNSPECIFIED_LEGACY_SOURCE,
}

data class LegacyMapContribution(
    val actorDid: String,
    val confidence: LegacyMapConfidence?,
    val note: String?,
    val createdAtSeconds: Long,
    val updatedAtSeconds: Long,
)

data class LegacyMapRemovalState(
    val requestedAtSeconds: Long?,
    val requestedByActor: String?,
    val reason: String?,
    val cancelledAtSeconds: Long?,
) {
    val isRequested: Boolean
        get() = requestedAtSeconds != null &&
            (cancelledAtSeconds == null || cancelledAtSeconds < requestedAtSeconds)
}

object MapItemCompatibilityPolicy {
    fun legacySourcePresentation(@Suppress("UNUSED_PARAMETER") confidence: LegacyMapConfidence?):
        LegacyMapSourcePresentation = LegacyMapSourcePresentation.UNSPECIFIED_LEGACY_SOURCE
}

/**
 * Optional payload extension names reserved for M2. M1 does not write these fields.
 * Legacy peers can continue reading the existing event shape and ignore these additions.
 */
object MapDomainPayloadContract {
    const val EXTENSION_VERSION_FIELD = "map_domain_v"
    const val EXTENSION_VERSION_1 = 1
    const val CATEGORY_FIELD = "item_category"
    const val SOURCE_FIELD = "information_source"
    const val ACTION_FIELD = "map_action"
    const val LIFECYCLE_STATUS_FIELD = "situation_lifecycle"
    const val UPDATE_TYPE_FIELD = "situation_update_type"
    const val ACTION_ID_FIELD = "action_id"
    const val ACTION_TIMESTAMP_FIELD = "action_t"
    /** Wire content for confirmation/status notes and update/coordination-note content. */
    const val ACTION_NOTE_FIELD = "action_note"
    const val ITEM_TYPE_FIELD = "item_type"
}
