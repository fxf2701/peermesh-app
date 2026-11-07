package app.peermesh.map.application.rendering

import app.peermesh.map.application.detail.MapItemIdentity

interface GroupMapController : AutoCloseable {
    fun currentState(): GroupMapState

    fun refresh(request: GroupMapViewportRequest)

    fun select(identity: MapItemIdentity?)

    fun focus(identity: MapItemIdentity?)

    /** Drops all accepted scope state and supersedes every in-flight scene request. */
    fun reset()

    fun addListener(listener: GroupMapStateListener)

    fun removeListener(listener: GroupMapStateListener)

    override fun close()
}

fun interface GroupMapStateListener {
    fun onStateChanged(state: GroupMapState)
}
