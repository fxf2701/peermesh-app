package app.peermesh.map.presentation.map.runtime

import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.presentation.map.resources.AndroidMapRenderModel

interface MapRuntimeRenderer : AutoCloseable {
    fun render(snapshot: List<AndroidMapRenderModel>)

    fun clear()

    override fun close()
}

fun interface MapRuntimeItemClickListener {
    fun onItemClicked(identity: MapItemIdentity)
}
