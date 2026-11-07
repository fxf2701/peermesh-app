package app.peermesh.data.map.processing

import app.peermesh.data.db.AppDatabase
import app.peermesh.data.map.materialization.RoomMapItemActionProjectionDeriver
import app.peermesh.data.reducer.MapEventReducerDispatcher
import app.peermesh.data.reducer.RoomCanonicalMapEventReducer
import app.peermesh.data.reducer.RoomDedicatedMapActionReducer

/** Data-layer-only production graph for the shared map transaction owner. */
internal object RoomMapEventProcessorFactory {
    fun lazy(db: AppDatabase): MapEventProcessor {
        val processor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { create(db) }
        return MapEventProcessor { request -> processor.process(request) }
    }

    private fun create(db: AppDatabase): MapEventProcessor = RoomMapEventProcessor(
        db = db,
        canonicalPolicy = DefaultCanonicalMapEventPolicy(),
        dispatcher = MapEventReducerDispatcher(
            canonicalReducer = RoomCanonicalMapEventReducer(db.mapDao(), db.mapItemVoteDao()),
            actionReducer = RoomDedicatedMapActionReducer(
                actionDao = db.mapItemActionDao(),
                policy = DefaultDedicatedMapActionPolicy,
                fingerprint = Sha256MapEventFingerprint,
                entityFactory = DefaultMapItemActionEntityFactory,
                projectionDeriver = RoomMapItemActionProjectionDeriver(db.mapItemActionDao()),
                domainStateDao = db.mapItemDomainStateDao(),
            ),
        ),
    )
}
