package app.peermesh.map.application.submission

/** One trusted immutable construction snapshot per submission invocation. */
data class MapEventConstructionContext(
    val actorDid: String,
    val eventId: String,
    val createdSeconds: Long,
    val lamport: Long,
    val insertedAtSeconds: Long,
)

fun interface MapEventConstructionContextProvider {
    fun create(groupId: String): MapEventConstructionContext
}
