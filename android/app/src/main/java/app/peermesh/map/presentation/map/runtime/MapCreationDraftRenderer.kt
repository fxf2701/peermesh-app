package app.peermesh.map.presentation.map.runtime

import app.peermesh.map.presentation.creation.MapCreationDraft

/**
 * Renderer-owned temporary SDK artifacts for one creation draft.
 * Implementations must never submit events or become canonical state authority.
 */
interface MapCreationDraftRenderer : AutoCloseable {
    fun render(draft: MapCreationDraft?)
    fun clear()
    override fun close()
}
