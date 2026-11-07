package app.peermesh.data.map.compatibility

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.AppBatchEnvelope
import app.peermesh.core.model.EventCore
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.dao.*
import app.peermesh.data.db.entity.EventDedupeEntity
import app.peermesh.data.ingest.EnvelopeReceiverImpl
import app.peermesh.data.ingest.EventIngestor
import app.peermesh.data.map.processing.MapEventProcessingRequest
import app.peermesh.data.map.processing.MapEventProcessingResult
import app.peermesh.security.crypto.AppBatchCrypto
import app.peermesh.security.crypto.EnvelopeCrypto
import app.peermesh.security.crypto.SecureKeyStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mockito.Mockito

internal object M2D3JvmHarness {
    const val GID = "m2d3-group"
    private val groupKey = ByteArray(32) { (it + 7).toByte() }
    val optionalDomainFields = setOf(
        "map_domain_v", "item_category", "information_source", "owner_edit_until", "interaction_until",
    )

    fun fixture(name: String): EventCore {
        val text = checkNotNull(javaClass.classLoader?.getResource("map/m2d3/$name")) { name }.readText()
        return CanonicalJson.json.decodeFromString(text)
    }

    fun knownFieldProjection(core: EventCore): EventCore = core.copy(
        data = JsonObject((core.data as JsonObject).filterKeys { it !in optionalDomainFields }),
    )

    /**
     * Contract-modeled subset of the pre-M2 validator at commit 3f0f4b8e. This is not execution
     * of the historical parser; provenance and its limits are recorded in FIXTURE_MANIFEST.md.
     */
    fun modeledPreM2ValidatorAccepts(core: EventCore): Boolean {
        val data = core.data as? JsonObject ?: return false
        return when (core.type) {
            EventTypes.MAP_MARKER_ADD, EventTypes.MAP_MARKER_UPD ->
                (data["lat"] as? JsonPrimitive)?.content?.toDoubleOrNull() != null &&
                    (data["lon"] as? JsonPrimitive)?.content?.toDoubleOrNull() != null
            EventTypes.MAP_MARKER_DEL, EventTypes.MAP_AREA_DEL -> true
            EventTypes.MAP_AREA_ADD, EventTypes.MAP_AREA_UPD -> data["geometry"] != null
            EventTypes.CHAT_MSG -> (data["text"] as? JsonPrimitive)?.content?.length?.let { it <= 200 } == true
            else -> false
        }
    }

    /** Contract-modeled, per-item old-peer batch premise; not historical APP-batch execution. */
    fun modeledPreM2BatchAcceptedItems(vararg cores: EventCore): List<EventCore> =
        cores.filter(::modeledPreM2ValidatorAccepts)

    fun receiver(processor: (MapEventProcessingRequest) -> MapEventProcessingResult): EnvelopeReceiverImpl {
        val db = Mockito.mock(AppDatabase::class.java)
        Mockito.`when`(db.eventsDao()).thenReturn(Mockito.mock(EventsDao::class.java))
        Mockito.`when`(db.eventDedupeDao()).thenReturn(NoOpDedupeDao())
        Mockito.`when`(db.chatDao()).thenReturn(Mockito.mock(ChatDao::class.java))
        Mockito.`when`(db.deletedEventDao()).thenReturn(Mockito.mock(DeletedEventDao::class.java))
        Mockito.`when`(db.groupDao()).thenReturn(Mockito.mock(GroupDao::class.java))
        Mockito.`when`(db.userProfileDao()).thenReturn(Mockito.mock(UserProfileDao::class.java))
        Mockito.`when`(db.removedMemberDao()).thenReturn(Mockito.mock(RemovedMemberDao::class.java))
        Mockito.`when`(db.liveTrailDao()).thenReturn(Mockito.mock(LiveTrailDao::class.java))
        val keys = Mockito.mock(SecureKeyStore::class.java)
        Mockito.`when`(keys.loadAllGroupKeys(GID)).thenReturn(listOf(groupKey))
        return EnvelopeReceiverImpl(EventIngestor(db, mapEventProcessor = processor), keys)
    }

    fun batch(vararg cores: EventCore): ByteArray {
        val unsigned = AppBatchEnvelope(v = 1, ts = 1_700_002_000L, items = cores.map(::encodedEnvelope))
        return CanonicalJson.json.encodeToString(AppBatchCrypto.sign(unsigned)).toByteArray()
    }

    private fun encodedEnvelope(core: EventCore): String = Base64Url.encode(
        CanonicalJson.json.encodeToString(
            EnvelopeCrypto.encryptAndSign(
                gid = GID, mid = core.id, ts = core.time.t, ttl = 7200,
                core = core, groupKey = groupKey,
            ),
        ).toByteArray(),
    )

    private class NoOpDedupeDao : EventDedupeDao {
        override fun upsert(entity: EventDedupeEntity) = Unit
        override fun countActive(gid: String, eventId: String, nowSeconds: Long) = 0
        override fun deleteExpired(nowSeconds: Long) = 0
        override fun deleteByGid(gid: String) = 0
    }
}
