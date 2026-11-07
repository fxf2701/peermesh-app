package app.peermesh.map.composition

import app.peermesh.map.application.rendering.DefaultGroupMapController
import app.peermesh.map.application.rendering.DefaultMapItemVisualPolicy
import app.peermesh.map.application.rendering.GroupMapController
import app.peermesh.map.application.scene.LoadMapSceneItems
import java.util.concurrent.Executor

object GroupMapRenderingFactory {
    fun createController(
        loadMapSceneItems: LoadMapSceneItems,
        backgroundExecutor: Executor,
        presentationExecutor: Executor,
    ): GroupMapController = DefaultGroupMapController(
        loadMapSceneItems = loadMapSceneItems,
        visualPolicy = DefaultMapItemVisualPolicy(),
        backgroundExecutor = backgroundExecutor,
        presentationExecutor = presentationExecutor,
    )
}
