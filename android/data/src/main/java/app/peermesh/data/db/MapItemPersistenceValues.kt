package app.peermesh.data.db

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemAction
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.core.map.MapSituationUpdateType

/** A persistence parse never guesses and never throws for malformed database content. */
sealed interface PersistenceParseResult<out T> {
    data class Success<T>(val value: T) : PersistenceParseResult<T>
    data class MissingRequiredValue(val field: String) : PersistenceParseResult<Nothing>
    data class UnknownPersistedValue(val field: String, val value: String) : PersistenceParseResult<Nothing>
    data class InvalidRecordShape(val reason: String) : PersistenceParseResult<Nothing>
}

enum class MapItemCategoryOrigin(val persistedValue: String) {
    EXPLICIT_DOMAIN_V1("explicit_domain_v1"),
    LEGACY_KIND_DERIVED("legacy_kind_derived"),
    UNRESOLVED("unresolved"),
}

enum class MapItemStateOrigin(val persistedValue: String) {
    EXPLICIT_DOMAIN_V1("explicit_domain_v1"),
    LEGACY_ROW_BACKFILL("legacy_row_backfill"),
}

enum class MapItemTemporalOrigin(val persistedValue: String) {
    EXPLICIT_DOMAIN_V1("explicit_domain_v1"),
    LEGACY_TTL_DERIVED("legacy_ttl_derived"),
    LEGACY_NO_EXPIRY("legacy_no_expiry"),
    LEGACY_UNRESOLVED("legacy_unresolved"),
}

enum class MapItemMaterializationState(val persistedValue: String) {
    APPLIED("applied"),
    PENDING_TARGET("pending_target"),
    REJECTED("rejected"),
}

/** Stable SQLite values and strict bidirectional mappings. Never persist enum.name. */
object MapItemPersistenceValues {
    object ItemType {
        const val MARKER = "marker"
        const val AREA = "area"
        val ALL: Set<String> = setOf(MARKER, AREA)
        fun valueOf(value: MapItemType): String = when (value) {
            MapItemType.MARKER -> MARKER
            MapItemType.AREA -> AREA
        }
        fun parse(value: String?): PersistenceParseResult<MapItemType> = required("item_type", value, mapOf(
            MARKER to MapItemType.MARKER, AREA to MapItemType.AREA,
        ))
    }

    object CategoryOrigin {
        const val EXPLICIT_DOMAIN_V1 = "explicit_domain_v1"
        const val LEGACY_KIND_DERIVED = "legacy_kind_derived"
        const val UNRESOLVED = "unresolved"
        val ALL: Set<String> = MapItemCategoryOrigin.entries.mapTo(linkedSetOf()) { it.persistedValue }
        fun valueOf(value: MapItemCategoryOrigin): String = value.persistedValue
        fun parse(value: String?): PersistenceParseResult<MapItemCategoryOrigin> =
            required("category_origin", value, MapItemCategoryOrigin.entries.associateBy { it.persistedValue })
    }

    object Category {
        const val SITUATION = "situation"
        const val COORDINATION = "coordination"
        val ALL: Set<String> = setOf(SITUATION, COORDINATION)
        fun valueOf(value: MapItemCategory): String = when (value) {
            MapItemCategory.SITUATION -> SITUATION
            MapItemCategory.COORDINATION -> COORDINATION
        }
        fun parse(value: String?): PersistenceParseResult<MapItemCategory> = required("category", value, mapOf(
            SITUATION to MapItemCategory.SITUATION, COORDINATION to MapItemCategory.COORDINATION,
        ))
        fun parseNullable(value: String?): PersistenceParseResult<MapItemCategory?> = nullable("category", value, mapOf(
            SITUATION to MapItemCategory.SITUATION, COORDINATION to MapItemCategory.COORDINATION,
        ))
    }

    object InformationSource {
        const val FIRSTHAND = "firsthand"
        const val REPORTED_BY_OTHERS = "reported_by_others"
        const val UNCERTAIN = "uncertain"
        val ALL: Set<String> = setOf(FIRSTHAND, REPORTED_BY_OTHERS, UNCERTAIN)
        fun valueOf(value: MapInformationSource): String = when (value) {
            MapInformationSource.FIRSTHAND -> FIRSTHAND
            MapInformationSource.REPORTED_BY_OTHERS -> REPORTED_BY_OTHERS
            MapInformationSource.UNCERTAIN -> UNCERTAIN
        }
        fun parseNullable(value: String?): PersistenceParseResult<MapInformationSource?> = nullable(
            "information_source", value, mapOf(FIRSTHAND to MapInformationSource.FIRSTHAND,
                REPORTED_BY_OTHERS to MapInformationSource.REPORTED_BY_OTHERS, UNCERTAIN to MapInformationSource.UNCERTAIN),
        )
    }

    object ActionType {
        const val CONFIRM = "confirm"
        const val ADD_UPDATE = "add_update"
        const val UPDATE_STATUS = "update_status"
        const val ACKNOWLEDGE = "acknowledge"
        const val ADD_NOTE = "add_note"
        val ALL: Set<String> = setOf(CONFIRM, ADD_UPDATE, UPDATE_STATUS, ACKNOWLEDGE, ADD_NOTE)
        fun valueOf(value: MapItemAction): String = when (value) {
            MapItemAction.CONFIRM -> CONFIRM
            MapItemAction.ADD_UPDATE -> ADD_UPDATE
            MapItemAction.UPDATE_STATUS -> UPDATE_STATUS
            MapItemAction.ACKNOWLEDGE -> ACKNOWLEDGE
            MapItemAction.ADD_NOTE -> ADD_NOTE
        }
        fun parse(value: String?): PersistenceParseResult<MapItemAction> = required("action_type", value, mapOf(
            CONFIRM to MapItemAction.CONFIRM, ADD_UPDATE to MapItemAction.ADD_UPDATE,
            UPDATE_STATUS to MapItemAction.UPDATE_STATUS, ACKNOWLEDGE to MapItemAction.ACKNOWLEDGE,
            ADD_NOTE to MapItemAction.ADD_NOTE,
        ))
    }

    object SituationLifecycle {
        const val ACTIVE = "active"
        const val NO_LONGER_ACTIVE = "no_longer_active"
        val ALL: Set<String> = setOf(ACTIVE, NO_LONGER_ACTIVE)
        fun valueOf(value: MapSituationLifecycleStatus): String = when (value) {
            MapSituationLifecycleStatus.ACTIVE -> ACTIVE
            MapSituationLifecycleStatus.NO_LONGER_ACTIVE -> NO_LONGER_ACTIVE
        }
        fun parseNullable(value: String?): PersistenceParseResult<MapSituationLifecycleStatus?> = nullable(
            "situation_lifecycle", value, mapOf(ACTIVE to MapSituationLifecycleStatus.ACTIVE,
                NO_LONGER_ACTIVE to MapSituationLifecycleStatus.NO_LONGER_ACTIVE),
        )
    }

    object UpdateDisposition {
        const val STILL_ACTIVE = "still_active"
        const val SITUATION_CHANGED = "situation_changed"
        val ALL: Set<String> = setOf(STILL_ACTIVE, SITUATION_CHANGED)
        fun valueOf(value: MapSituationUpdateType): String = when (value) {
            MapSituationUpdateType.STILL_ACTIVE -> STILL_ACTIVE
            MapSituationUpdateType.SITUATION_CHANGED -> SITUATION_CHANGED
        }
        fun parseNullable(value: String?): PersistenceParseResult<MapSituationUpdateType?> = nullable(
            "update_disposition", value, mapOf(STILL_ACTIVE to MapSituationUpdateType.STILL_ACTIVE,
                SITUATION_CHANGED to MapSituationUpdateType.SITUATION_CHANGED),
        )
    }

    object StateOrigin {
        const val EXPLICIT_DOMAIN_V1 = "explicit_domain_v1"
        const val LEGACY_ROW_BACKFILL = "legacy_row_backfill"
        val ALL: Set<String> = MapItemStateOrigin.entries.mapTo(linkedSetOf()) { it.persistedValue }
        fun valueOf(value: MapItemStateOrigin): String = value.persistedValue
        fun parse(value: String?): PersistenceParseResult<MapItemStateOrigin> =
            required("state_origin", value, MapItemStateOrigin.entries.associateBy { it.persistedValue })
    }

    object TemporalOrigin {
        const val EXPLICIT_DOMAIN_V1 = "explicit_domain_v1"
        const val LEGACY_TTL_DERIVED = "legacy_ttl_derived"
        const val LEGACY_NO_EXPIRY = "legacy_no_expiry"
        const val LEGACY_UNRESOLVED = "legacy_unresolved"
        val ALL: Set<String> = MapItemTemporalOrigin.entries.mapTo(linkedSetOf()) { it.persistedValue }
        fun valueOf(value: MapItemTemporalOrigin): String = value.persistedValue
        fun parse(value: String?): PersistenceParseResult<MapItemTemporalOrigin> =
            required("temporal_origin", value, MapItemTemporalOrigin.entries.associateBy { it.persistedValue })
    }

    object MaterializationState {
        const val APPLIED = "applied"
        const val PENDING_TARGET = "pending_target"
        const val REJECTED = "rejected"
        val ALL: Set<String> = MapItemMaterializationState.entries.mapTo(linkedSetOf()) { it.persistedValue }
        fun valueOf(value: MapItemMaterializationState): String = value.persistedValue
        fun parse(value: String?): PersistenceParseResult<MapItemMaterializationState> =
            required("materialization_state", value, MapItemMaterializationState.entries.associateBy { it.persistedValue })
    }

    private fun <T> required(field: String, value: String?, values: Map<String, T>): PersistenceParseResult<T> = when {
        value == null || value.isBlank() -> PersistenceParseResult.MissingRequiredValue(field)
        else -> values[value]?.let { PersistenceParseResult.Success(it) }
            ?: PersistenceParseResult.UnknownPersistedValue(field, value)
    }

    private fun <T> nullable(field: String, value: String?, values: Map<String, T>): PersistenceParseResult<T?> = when {
        value == null -> PersistenceParseResult.Success(null)
        else -> values[value]?.let { PersistenceParseResult.Success(it) }
            ?: PersistenceParseResult.UnknownPersistedValue(field, value)
    }
}
