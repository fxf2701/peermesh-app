package app.peermesh.map.presentation.map.runtime

import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.MapItemIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

class MapRuntimeRenderRegistryM3D2Test {
    @Test fun putGetReplacementRemoveAndClearAreExactIdentityOperations() {
        val registry = MapRuntimeRenderRegistry()
        val markerId = id(MapItemType.MARKER)
        val areaId = id(MapItemType.AREA)
        val marker = markerBundle(markerId)
        val replacement = markerBundle(markerId)
        val area = areaBundle(areaId, polygonCount = 2)

        assertNull(registry.put(markerId, marker))
        assertSame(marker, registry.get(markerId))
        assertSame(marker, registry.put(markerId, replacement))
        registry.put(areaId, area)
        assertEquals(2, registry.size())
        assertEquals(setOf(markerId, areaId), registry.identities())
        assertEquals(2, (registry.get(areaId) as MapRuntimeArtifactBundle.AreaArtifacts).polygons.size)
        assertSame(replacement, registry.remove(markerId))
        assertNull(registry.get(markerId))
        assertEquals(listOf(area), registry.clear())
        assertTrue(registry.clear().isEmpty())
        assertEquals(0, registry.size())
    }

    @Test(expected = IllegalArgumentException::class)
    fun registryRejectsKeyBundleIdentityMismatch() {
        val registry = MapRuntimeRenderRegistry()
        registry.put(id(MapItemType.MARKER), areaBundle(id(MapItemType.AREA), 1))
    }

    private fun markerBundle(identity: MapItemIdentity) =
        MapRuntimeArtifactBundle.MarkerArtifacts(identity, allocate(), null, null)

    private fun areaBundle(identity: MapItemIdentity, polygonCount: Int) =
        MapRuntimeArtifactBundle.AreaArtifacts(
            identity,
            List(polygonCount) { allocate<Polygon>() },
            allocate<Marker>(),
            null,
        )

    private inline fun <reified T> allocate(): T =
        allocateInstance.invoke(unsafe, T::class.java) as T

    companion object {
        private val unsafeClass = Class.forName("sun.misc.Unsafe")
        private val unsafe: Any = unsafeClass.getDeclaredField("theUnsafe").let { field ->
            field.isAccessible = true
            field.get(null) ?: error("Unsafe instance is unavailable")
        }
        private val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)

        private fun id(type: MapItemType) = MapItemIdentity("g", type, "same")
    }
}
