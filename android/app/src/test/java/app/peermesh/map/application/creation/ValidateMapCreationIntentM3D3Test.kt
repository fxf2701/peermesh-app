package app.peermesh.map.application.creation

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.map.application.scene.MapSceneAreaGeometry
import app.peermesh.map.application.scene.MapSceneCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidateMapCreationIntentM3D3Test {
    private val validator = DefaultValidateMapCreationIntent(DefaultMapCreationCompatibilityPolicy())

    @Test fun `valid marker and area pass`() {
        assertEquals(MapCreationValidationResult.Valid, validator.validate(marker()))
        assertEquals(MapCreationValidationResult.Valid, validator.validate(area()))
    }

    @Test fun `all non finite and out of range coordinates fail`() {
        listOf(
            Double.NaN to 1.0,
            Double.POSITIVE_INFINITY to 1.0,
            Double.NEGATIVE_INFINITY to 1.0,
            91.0 to 1.0,
            1.0 to 181.0,
        ).forEach { (lat, lon) ->
            assertInvalid(marker(latitude = lat, longitude = lon), MapCreationInputFailure.INVALID_COORDINATES)
        }
    }

    @Test fun `blank fields ttl and radius fail locally`() {
        assertInvalid(marker(groupId = " "), MapCreationInputFailure.BLANK_GROUP_ID)
        assertInvalid(marker(kind = "\t"), MapCreationInputFailure.BLANK_KIND)
        assertInvalid(marker(label = " "), MapCreationInputFailure.BLANK_LABEL)
        assertInvalid(marker(ttl = 0L), MapCreationInputFailure.INVALID_TTL)
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertInvalid(marker(radius = it), MapCreationInputFailure.INVALID_RADIUS)
        }
    }

    @Test fun `too few or malformed area vertices fail without mutating caller list`() {
        val mutable = mutableListOf(c(0.0, 0.0), c(0.0, 1.0))
        val intent = area(geometry = MapSceneAreaGeometry.Polygon(listOf(mutable)))
        mutable += c(1.0, 1.0)
        assertInvalid(intent, MapCreationInputFailure.INVALID_AREA_GEOMETRY)
        assertEquals(2, (intent.geometry as MapSceneAreaGeometry.Polygon).rings.single().size)
    }

    @Test fun `compatibility and creator source failures are typed`() {
        assertInvalid(
            marker(category = MapItemCategory.COORDINATION, kind = "risk", source = null),
            MapCreationInputFailure.INCOMPATIBLE_CATEGORY_KIND_SHAPE,
        )
        assertInvalid(marker(source = null), MapCreationInputFailure.INVALID_CREATOR_SOURCE)
    }

    @Test fun `all accepted situation sources validate and coordination forbids source`() {
        MapInformationSource.entries.forEach { source ->
            assertEquals(
                source.name,
                MapCreationValidationResult.Valid,
                validator.validate(marker(source = source)),
            )
        }
        assertEquals(
            MapCreationValidationResult.Valid,
            validator.validate(
                marker(
                    category = MapItemCategory.COORDINATION,
                    kind = "camp",
                    source = null,
                ),
            ),
        )
        assertInvalid(
            marker(
                category = MapItemCategory.COORDINATION,
                kind = "camp",
                source = MapInformationSource.UNCERTAIN,
            ),
            MapCreationInputFailure.INVALID_CREATOR_SOURCE,
        )
    }

    private fun marker(
        groupId: String = "group",
        category: MapItemCategory = MapItemCategory.SITUATION,
        kind: String = "risk",
        label: String? = "R",
        ttl: Long? = 60L,
        source: MapInformationSource? = MapInformationSource.FIRSTHAND,
        latitude: Double = 10.0,
        longitude: Double = 20.0,
        radius: Double? = null,
    ) = MapCreationIntent.Marker(
        groupId, category, kind, label, "note", ttl, source, latitude, longitude, radius,
    )

    private fun area(
        geometry: MapSceneAreaGeometry = MapSceneAreaGeometry.Polygon(
            listOf(listOf(c(0.0, 0.0), c(0.0, 1.0), c(1.0, 1.0))),
        ),
    ) = MapCreationIntent.Area(
        "group", MapItemCategory.SITUATION, "risk", "R", "note", 60L,
        MapInformationSource.REPORTED_BY_OTHERS, geometry,
    )

    private fun c(lat: Double, lon: Double) = MapSceneCoordinate(lat, lon)

    private fun assertInvalid(intent: MapCreationIntent, failure: MapCreationInputFailure) {
        val result = validator.validate(intent)
        assertTrue(result is MapCreationValidationResult.Invalid)
        assertEquals(failure, (result as MapCreationValidationResult.Invalid).failure)
    }
}
