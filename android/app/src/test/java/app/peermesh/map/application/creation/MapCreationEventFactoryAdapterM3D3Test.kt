package app.peermesh.map.application.creation

import app.peermesh.core.map.MapDomainPayloadContract
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.types.EventTypes
import app.peermesh.core.validation.EventValidator
import app.peermesh.map.application.scene.MapSceneAreaGeometry
import app.peermesh.map.application.scene.MapSceneCoordinate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCreationEventFactoryAdapterM3D3Test {
    private val adapter = DefaultMapCreationEventFactoryAdapter()
    private val context = MapCreationConstructionContext("actor", "event", "item", 10L, 11L, 12L)

    @Test fun `marker uses canonical add and exact trusted context`() {
        val core = adapter.create(
            MapCreationIntent.Marker(
                "group", MapItemCategory.SITUATION, "risk", "R", "note", 60L,
                MapInformationSource.FIRSTHAND, 10.25, 20.5, 25.0,
            ),
            context,
        )
        assertEquals(EventTypes.MAP_MARKER_ADD, core.type)
        assertEquals(context.eventId, core.id)
        assertEquals(context.actorDid, core.actor.did)
        assertEquals(context.itemId, core.ref.target)
        assertEquals(context.createdSeconds, core.time.t)
        assertEquals(context.lamport, core.time.lt)
        assertEquals("risk", core.data.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("situation", core.data.jsonObject[
            MapDomainPayloadContract.CATEGORY_FIELD]!!.jsonPrimitive.content)
        assertTrue(EventValidator.validate(core))
    }

    @Test fun `area preserves lon lat order closes ring and does not mutate input`() {
        val points = mutableListOf(c(1.0, 2.0), c(3.0, 4.0), c(5.0, 6.0))
        val intent = MapCreationIntent.Area(
            "group", MapItemCategory.SITUATION, "risk", "R", null, null,
            MapInformationSource.UNCERTAIN,
            MapSceneAreaGeometry.Polygon(listOf(points)),
        )
        points.reverse()
        val core = adapter.create(intent, context)
        val ring = core.data.jsonObject["geometry"]!!.jsonObject["coordinates"]!!
            .jsonArray[0].jsonArray
        assertEquals(EventTypes.MAP_AREA_ADD, core.type)
        assertEquals(4, ring.size)
        assertEquals(2.0, ring[0].jsonArray[0].jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(1.0, ring[0].jsonArray[1].jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(ring.first(), ring.last())
        assertTrue(EventValidator.validate(core))
    }

    private fun c(lat: Double, lon: Double) = MapSceneCoordinate(lat, lon)
}
