package app.peermesh.map.application.creation

/** One trusted immutable construction snapshot per creation submission. */
data class MapCreationConstructionContext(
    val actorDid: String,
    val eventId: String,
    val itemId: String,
    val createdSeconds: Long,
    val lamport: Long,
    val insertedAtSeconds: Long,
)

internal fun MapCreationConstructionContext.isValid(): Boolean =
    actorDid.isNotBlank() && eventId.isNotBlank() && itemId.isNotBlank() &&
        createdSeconds > 0L && lamport >= 0L && insertedAtSeconds > 0L

fun interface MapCreationConstructionContextProvider {
    fun create(groupId: String, shape: MapCreationShape): MapCreationConstructionContext
}
