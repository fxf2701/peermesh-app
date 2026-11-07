package app.peermesh.map.composition

import app.peermesh.data.db.AppDatabase
import app.peermesh.data.map.RoomMapItemActionQueryRepository
import app.peermesh.map.application.timeline.DefaultLoadMapItemActivity
import app.peermesh.map.application.timeline.DefaultMapItemActivityProjector
import app.peermesh.map.application.timeline.LoadMapItemActivity

/** App-level M3B1 composition boundary; callers receive no DAO or Room entity. */
object MapItemActivityReadFactory {
    fun create(database: AppDatabase): LoadMapItemActivity = DefaultLoadMapItemActivity(
        repository = RoomMapItemActionQueryRepository(database.mapItemActionDao()),
        projector = DefaultMapItemActivityProjector(),
    )
}
