package app.peermesh.map.application.creation

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class MapCreationCompatibilityPolicyM3D3Test {
    private val policy = DefaultMapCreationCompatibilityPolicy()

    @Test fun `situation marker and area are accepted with required source`() {
        assertCompatible(input(MapItemCategory.SITUATION, MapCreationShape.MARKER, "risk"))
        assertCompatible(input(MapItemCategory.SITUATION, MapCreationShape.AREA, "risk"))
    }

    @Test fun `coordination marker and canonical coordination area are accepted`() {
        assertCompatible(input(
            MapItemCategory.COORDINATION,
            MapCreationShape.MARKER,
            "camp",
            source = null,
        ))
        assertCompatible(input(
            MapItemCategory.COORDINATION,
            MapCreationShape.AREA,
            "assembly_zone",
            source = null,
        ))
        assertIncompatible(
            input(MapItemCategory.COORDINATION, MapCreationShape.AREA, "camp", source = null),
            MapCreationCompatibilityFailure.KIND_SHAPE_MISMATCH,
        )
    }

    @Test fun `category kind and kind shape mismatches remain distinct`() {
        assertIncompatible(
            input(MapItemCategory.COORDINATION, MapCreationShape.MARKER, "risk", source = null),
            MapCreationCompatibilityFailure.CATEGORY_KIND_MISMATCH,
        )
        assertIncompatible(
            input(MapItemCategory.SITUATION, MapCreationShape.AREA, "info"),
            MapCreationCompatibilityFailure.KIND_SHAPE_MISMATCH,
        )
    }

    @Test fun `source rules unsupported kind and area radius are explicit`() {
        assertIncompatible(
            input(MapItemCategory.SITUATION, MapCreationShape.MARKER, "risk", source = null),
            MapCreationCompatibilityFailure.CREATOR_SOURCE_REQUIRED,
        )
        assertIncompatible(
            input(
                MapItemCategory.COORDINATION,
                MapCreationShape.MARKER,
                "camp",
                source = MapInformationSource.FIRSTHAND,
            ),
            MapCreationCompatibilityFailure.CREATOR_SOURCE_FORBIDDEN,
        )
        assertIncompatible(
            input(MapItemCategory.SITUATION, MapCreationShape.MARKER, "future_kind"),
            MapCreationCompatibilityFailure.UNSUPPORTED_COMBINATION,
        )
        assertIncompatible(
            input(MapItemCategory.SITUATION, MapCreationShape.AREA, "risk", radius = 5.0),
            MapCreationCompatibilityFailure.RADIUS_FORBIDDEN,
        )
    }

    @Test fun `evaluation is deterministic`() {
        val input = input(MapItemCategory.SITUATION, MapCreationShape.AREA, "risk")
        assertEquals(policy.evaluate(input), policy.evaluate(input))
    }

    private fun input(
        category: MapItemCategory,
        shape: MapCreationShape,
        kind: String,
        source: MapInformationSource? = MapInformationSource.FIRSTHAND,
        radius: Double? = null,
    ) = MapCreationCompatibilityInput(category, shape, kind, source, radius)

    private fun assertCompatible(input: MapCreationCompatibilityInput) =
        assertEquals(MapCreationCompatibilityResult.Compatible, policy.evaluate(input))

    private fun assertIncompatible(
        input: MapCreationCompatibilityInput,
        failure: MapCreationCompatibilityFailure,
    ) = assertEquals(MapCreationCompatibilityResult.Incompatible(failure), policy.evaluate(input))
}
