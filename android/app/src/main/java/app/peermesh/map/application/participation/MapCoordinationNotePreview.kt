package app.peermesh.map.application.participation

import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.timeline.MapItemActivityActor

/**
 * Optional historical preview selected only from already supplied M3B entries.
 *
 * This is not guaranteed to be the globally latest persisted note because the
 * supplied history may be partial or paginated.
 */
data class MapCoordinationNotePreview(
    val entryId: String,
    val itemIdentity: MapItemIdentity,
    val actor: MapItemActivityActor,
    val actionTimestampSeconds: Long,
    val note: String,
) {
    init {
        require(entryId.isNotBlank()) { "note preview entry identity must not be blank" }
        require(actionTimestampSeconds >= 0L) { "note preview timestamp must not be negative" }
        require(note.isNotBlank()) { "note preview content must not be blank" }
    }
}
