package app.peermesh.map.presentation.detail

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemDetailActivityM3E5Test {
    @Test fun detailUsesTheCatalogKindLabelIncludingItsEmoji() {
        val source = File(
            "src/main/java/app/peermesh/map/presentation/detail/MapItemDetailActivity.kt",
        ).readText()

        assertTrue(source.contains("MapKinds.labelForKey(kind) ?: kind"))
        assertTrue(source.contains("R.string.map_detail_kind"))
    }
}
