package app.peermesh.data.db

import app.peermesh.data.db.dao.EventsDao
import app.peermesh.data.db.dao.MapItemActionDao
import app.peermesh.data.db.dao.MapItemDomainStateDao

/** Dependencies only; M2C remains responsible for orchestration inside AppDatabase.runInTransaction. */
internal data class MapItemMaterializationDaos(
    val eventsDao: EventsDao,
    val actionDao: MapItemActionDao,
    val domainStateDao: MapItemDomainStateDao,
)

/** Builds the future M2C dependency set from one database instance. */
internal fun AppDatabase.mapItemMaterializationDaos(): MapItemMaterializationDaos =
    MapItemMaterializationDaos(
        eventsDao = eventsDao(),
        actionDao = mapItemActionDao(),
        domainStateDao = mapItemDomainStateDao(),
    )
