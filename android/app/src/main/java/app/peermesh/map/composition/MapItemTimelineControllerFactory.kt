package app.peermesh.map.composition

import app.peermesh.data.db.AppDatabase
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.timeline.DefaultMapItemActivityMergePolicy
import app.peermesh.map.presentation.timeline.AndroidMapItemTimelineExecution
import app.peermesh.map.presentation.timeline.DefaultMapItemTimelineController
import app.peermesh.map.presentation.timeline.MapItemTimelineController
import app.peermesh.map.presentation.timeline.MapItemTimelineExecution
import app.peermesh.map.presentation.timeline.MapItemTimelineStateObserver

/** Narrow M3B2 composition boundary. No DAO or Room entity reaches presentation code. */
object MapItemTimelineControllerFactory {
    fun create(
        database: AppDatabase,
        identity: MapItemIdentity,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        observer: MapItemTimelineStateObserver,
        execution: MapItemTimelineExecution = AndroidMapItemTimelineExecution(),
    ): MapItemTimelineController = DefaultMapItemTimelineController(
        identity = identity,
        pageSize = pageSize,
        loadMapItemActivity = MapItemActivityReadFactory.create(database),
        mergePolicy = DefaultMapItemActivityMergePolicy(),
        execution = execution,
        observer = observer,
    )

    private const val DEFAULT_PAGE_SIZE = 20
}
