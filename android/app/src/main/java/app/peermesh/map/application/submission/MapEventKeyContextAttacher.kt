package app.peermesh.map.application.submission

import app.peermesh.core.model.EventCore

/** Attaches the current accepted group key ID without exposing Android key storage to the use case. */
fun interface MapEventKeyContextAttacher {
    fun attach(groupId: String, core: EventCore): EventCore
}

/** Injectable function adapter used by app composition to bind the accepted KeyIdHelper path. */
class DefaultMapEventKeyContextAttacher(
    private val attachKeyId: (groupId: String, core: EventCore) -> EventCore,
) : MapEventKeyContextAttacher {
    override fun attach(groupId: String, core: EventCore): EventCore = attachKeyId(groupId, core)
}
