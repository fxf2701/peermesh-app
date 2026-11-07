package app.peermesh.core.map

/** Stable domain category for a marker or area kind. */
enum class MapItemCategory(val wireValue: String) {
    SITUATION("situation"),
    COORDINATION("coordination");

    companion object {
        fun fromWireValue(value: String?): MapItemCategory? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** Answers how a factual Situation item or update is known. */
enum class MapInformationSource(val wireValue: String) {
    FIRSTHAND("firsthand"),
    REPORTED_BY_OTHERS("reported_by_others"),
    UNCERTAIN("uncertain");

    companion object {
        fun fromWireValue(value: String?): MapInformationSource? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class MapItemAction(val wireValue: String) {
    CONFIRM("confirm"),
    ADD_UPDATE("add_update"),
    UPDATE_STATUS("update_status"),
    ACKNOWLEDGE("acknowledge"),
    ADD_NOTE("add_note");

    companion object {
        fun fromWireValue(value: String?): MapItemAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** Persisted lifecycle: whether a Situation is active or has ended. */
enum class MapSituationLifecycleStatus(val wireValue: String) {
    ACTIVE("active"),
    NO_LONGER_ACTIVE("no_longer_active");

    companion object {
        fun fromWireValue(value: String?): MapSituationLifecycleStatus? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** Disposition of an ADD_UPDATE action; it is not item lifecycle state. */
enum class MapSituationUpdateType(val wireValue: String) {
    STILL_ACTIVE("still_active"),
    SITUATION_CHANGED("situation_changed");

    companion object {
        fun fromWireValue(value: String?): MapSituationUpdateType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** Stable action identity. M2 may use the event ID directly as actionId. */
data class MapActionMetadata(
    val actionId: String,
    val itemId: String,
    val actorDid: String,
    val timestampSeconds: Long,
    val eventLamport: Long,
)

data class MapConfirmation(
    val metadata: MapActionMetadata,
    val source: MapInformationSource?,
    val note: String?,
)

data class MapSituationUpdate(
    val metadata: MapActionMetadata,
    val content: String,
    val source: MapInformationSource?,
    val updateType: MapSituationUpdateType,
)

data class MapSituationStatusUpdate(
    val metadata: MapActionMetadata,
    val status: MapSituationLifecycleStatus,
    val note: String?,
    val source: MapInformationSource?,
)

data class MapCoordinationAcknowledgement(
    val metadata: MapActionMetadata,
)

data class MapCoordinationNote(
    val metadata: MapActionMetadata,
    val content: String,
)

enum class MapCommunityVerification {
    UNVERIFIED,
    PARTIALLY_VERIFIED,
    CONFIRMED,
    CONFLICTING_UPDATES,
}

data class MapCommunityVerificationInputs(
    val confirmationActorCount: Int,
    val hasConflictingUpdates: Boolean,
) {
    init {
        require(confirmationActorCount >= 0) { "confirmationActorCount must not be negative" }
    }
}

object MapCommunityVerificationPolicy {
    fun derive(inputs: MapCommunityVerificationInputs): MapCommunityVerification = when {
        inputs.hasConflictingUpdates -> MapCommunityVerification.CONFLICTING_UPDATES
        inputs.confirmationActorCount == 0 -> MapCommunityVerification.UNVERIFIED
        inputs.confirmationActorCount == 1 -> MapCommunityVerification.PARTIALLY_VERIFIED
        else -> MapCommunityVerification.CONFIRMED
    }
}

object MapItemActionPolicy {
    private val situationActions = setOf(
        MapItemAction.CONFIRM,
        MapItemAction.ADD_UPDATE,
        MapItemAction.UPDATE_STATUS,
    )
    private val coordinationActions = setOf(
        MapItemAction.ACKNOWLEDGE,
        MapItemAction.ADD_NOTE,
    )

    fun actionsFor(category: MapItemCategory): Set<MapItemAction> = when (category) {
        MapItemCategory.SITUATION -> situationActions
        MapItemCategory.COORDINATION -> coordinationActions
    }

    fun allows(category: MapItemCategory, action: MapItemAction): Boolean =
        action in actionsFor(category)
}

object MapSituationLifecyclePolicy {
    fun canTransition(
        from: MapSituationLifecycleStatus,
        to: MapSituationLifecycleStatus,
    ): Boolean = when (from) {
        MapSituationLifecycleStatus.ACTIVE ->
            to == MapSituationLifecycleStatus.ACTIVE ||
                to == MapSituationLifecycleStatus.NO_LONGER_ACTIVE
        MapSituationLifecycleStatus.NO_LONGER_ACTIVE ->
            to == MapSituationLifecycleStatus.NO_LONGER_ACTIVE
    }
}

/** User-created UPDATE_STATUS actions only resolve an active Situation. */
object MapSituationStatusActionPolicy {
    fun isValidTarget(status: MapSituationLifecycleStatus): Boolean =
        status == MapSituationLifecycleStatus.NO_LONGER_ACTIVE
}

enum class MapItemOperation {
    CREATE,
    CONFIRM,
    ADD_UPDATE,
    UPDATE_STATUS,
    ACKNOWLEDGE,
    ADD_NOTE,
}

enum class MapSourceRequirement {
    REQUIRED,
    OPTIONAL,
    FORBIDDEN,
}

object MapInformationSourcePolicy {
    fun requirementFor(
        category: MapItemCategory,
        operation: MapItemOperation,
    ): MapSourceRequirement? = when (category) {
        MapItemCategory.SITUATION -> when (operation) {
            MapItemOperation.CREATE -> MapSourceRequirement.REQUIRED
            MapItemOperation.CONFIRM,
            MapItemOperation.ADD_UPDATE,
            MapItemOperation.UPDATE_STATUS,
            -> MapSourceRequirement.OPTIONAL
            MapItemOperation.ACKNOWLEDGE,
            MapItemOperation.ADD_NOTE,
            -> null
        }
        MapItemCategory.COORDINATION -> when (operation) {
            MapItemOperation.CREATE,
            MapItemOperation.ACKNOWLEDGE,
            MapItemOperation.ADD_NOTE,
            -> MapSourceRequirement.FORBIDDEN
            MapItemOperation.CONFIRM,
            MapItemOperation.ADD_UPDATE,
            MapItemOperation.UPDATE_STATUS,
            -> null
        }
    }

    fun isValid(
        category: MapItemCategory,
        operation: MapItemOperation,
        source: MapInformationSource?,
    ): Boolean = when (requirementFor(category, operation)) {
        MapSourceRequirement.REQUIRED -> source != null
        MapSourceRequirement.OPTIONAL -> true
        MapSourceRequirement.FORBIDDEN -> source == null
        null -> false
    }
}

object MapActionInvariantPolicy {
    fun isMetadataValid(metadata: MapActionMetadata): Boolean =
        metadata.actionId.isNotBlank() &&
            metadata.itemId.isNotBlank() &&
            metadata.actorDid.isNotBlank() &&
            metadata.timestampSeconds > 0L &&
            metadata.eventLamport >= 0L

    fun isStructurallyValid(confirmation: MapConfirmation): Boolean =
        isMetadataValid(confirmation.metadata)

    fun isStructurallyValid(update: MapSituationUpdate): Boolean =
        isMetadataValid(update.metadata) && update.content.isNotBlank()

    fun isStructurallyValid(statusUpdate: MapSituationStatusUpdate): Boolean =
        isMetadataValid(statusUpdate.metadata)

    fun isStructurallyValid(acknowledgement: MapCoordinationAcknowledgement): Boolean =
        isMetadataValid(acknowledgement.metadata)

    fun isStructurallyValid(note: MapCoordinationNote): Boolean =
        isMetadataValid(note.metadata) && note.content.isNotBlank()
}
