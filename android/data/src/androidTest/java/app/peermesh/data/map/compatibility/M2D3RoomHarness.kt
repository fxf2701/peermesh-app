package app.peermesh.data.map.compatibility

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.*
import app.peermesh.core.model.EventCore
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.entity.UserProfileEntity
import app.peermesh.data.map.materialization.RoomMapItemActionProjectionDeriver
import app.peermesh.data.map.processing.*
import app.peermesh.data.reducer.*
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add

internal class M2D3RoomHarness : AutoCloseable {
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val processor: RoomMapEventProcessor

    init {
        listOf("owner", "peer", "peer2").forEach { did ->
            db.userProfileDao().upsert(UserProfileEntity("g", did, did, null, 1, "profile-$did", 1, 0, did))
        }
        val projection = RoomMapItemActionProjectionDeriver(db.mapItemActionDao())
        processor = RoomMapEventProcessor(db, DefaultCanonicalMapEventPolicy(), MapEventReducerDispatcher(
            RoomCanonicalMapEventReducer(db.mapDao(), db.mapItemVoteDao()),
            RoomDedicatedMapActionReducer(
                db.mapItemActionDao(), DefaultDedicatedMapActionPolicy, Sha256MapEventFingerprint,
                DefaultMapItemActionEntityFactory, projection, db.mapItemDomainStateDao(),
            ),
        ))
    }

    fun process(core: EventCore, now: Long = core.time.t): MapEventProcessingResult =
        processor.process(MapEventProcessingRequest("g", core, "m2d3", now))

    fun marker(id: String, kind: String = "risk", confidence: Int = 2, ttl: Long? = null,
               removalRequestedT: Long? = null, removalCancelT: Long? = null): EventCore =
        EventFactory.mapMarkerAdd(
            "add-$id", "owner", 100, 1, id, "layer", 10.0, 106.0, 20.0,
            "label-$id", "owner note", kind, confidence, ttl,
            removalRequestedT, removalRequestedT?.let { "peer" }, removalRequestedT?.let { "duplicate" }, removalCancelT,
        )

    fun area(id: String, kind: String = "risk", confidence: Int = 1, ttl: Long? = null): EventCore =
        EventFactory.mapAreaAdd(
            "add-$id", "owner", 100, 1, id, "layer", geometry(), "label-$id", "owner note",
            kind, confidence, ttl,
        )

    fun contribution(itemId: String, area: Boolean = false, confidence: Int = 2, note: String = "legacy contribution", kind: String = "risk"): EventCore =
        if (area) EventFactory.mapAreaUpdate("vote-$itemId", "peer", 110, 2, itemId, "layer", geometry(), null, note, kind, confidence)
        else EventFactory.mapMarkerUpdate("vote-$itemId", "peer", 110, 2, itemId, "layer", 11.0, 107.0, null, null, note, kind, confidence)

    fun confirm(id: String, target: String, actor: String = "peer", t: Long = 120) = EventFactory.mapConfirmation(
        MapItemType.MARKER, MapConfirmation(MapActionMetadata(id, target, actor, t, 3), MapInformationSource.FIRSTHAND, "confirmed"),
    )
    fun confirmArea(id: String, target: String, actor: String = "peer", t: Long = 120) = EventFactory.mapConfirmation(
        MapItemType.AREA, MapConfirmation(MapActionMetadata(id, target, actor, t, 3), MapInformationSource.FIRSTHAND, "confirmed"),
    )
    fun ack(id: String, target: String, actor: String = "peer", area: Boolean = false, t: Long = 120) = EventFactory.mapAcknowledgement(
        if (area) MapItemType.AREA else MapItemType.MARKER,
        MapCoordinationAcknowledgement(MapActionMetadata(id, target, actor, t, 3)),
    )
    fun coordinationNote(id: String, target: String, area: Boolean = false, t: Long = 121) = EventFactory.mapCoordinationNote(
        if (area) MapItemType.AREA else MapItemType.MARKER,
        MapCoordinationNote(MapActionMetadata(id, target, "peer", t, 4), "coordination note"),
    )
    fun closeStatus(id: String, target: String, t: Long = 130) = EventFactory.mapSituationStatus(
        MapItemType.MARKER,
        MapSituationStatusUpdate(MapActionMetadata(id, target, "peer", t, 5), MapSituationLifecycleStatus.NO_LONGER_ACTIVE, "ended", MapInformationSource.FIRSTHAND),
    )

    override fun close() = db.close()
    private fun geometry() = buildJsonArray { add(buildJsonArray { add(106.0); add(10.0) }) }
}
