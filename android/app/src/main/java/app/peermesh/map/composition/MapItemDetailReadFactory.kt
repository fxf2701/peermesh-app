package app.peermesh.map.composition

import app.peermesh.data.db.AppDatabase
import app.peermesh.data.map.RoomMapItemDomainStateRepository
import app.peermesh.data.map.canonical.RoomMapItemCanonicalReadRepository
import app.peermesh.map.application.detail.DefaultMapItemDetailComposer
import app.peermesh.map.application.detail.LoadMapItemDetail

/** App-level dependency composition; callers receive no DAO or Room entity. */
object MapItemDetailReadFactory {
    fun create(database: AppDatabase): LoadMapItemDetail = DefaultMapItemDetailComposer(
        canonicalRepository = RoomMapItemCanonicalReadRepository(database.mapDao()),
        domainStateRepository = RoomMapItemDomainStateRepository(database.mapItemDomainStateDao()),
    )
}
