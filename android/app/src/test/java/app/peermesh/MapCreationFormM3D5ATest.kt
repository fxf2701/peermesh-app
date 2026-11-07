package app.peermesh

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCreationFormM3D5ATest {
    @Test
    fun `creation source copy uses accepted wording and includes uncertain`() {
        val strings = source("res/values/strings.xml")

        assertTrue(strings.contains("""name="map_creation_source_firsthand">Seen firsthand<"""))
        assertTrue(strings.contains("""name="map_creation_source_reported">Reported by others<"""))
        assertTrue(strings.contains("""name="map_creation_source_uncertain">Source uncertain<"""))
        assertTrue(strings.contains("""name="map_creation_note_hint">Note (optional)<"""))
        assertFalse(strings.contains("""name="map_creation_source_firsthand">Observed<"""))
    }

    @Test
    fun `marker and area forms contain a creation uncertain source control`() {
        val marker = source("res/layout/dialog_map_marker.xml")
        val area = source("res/layout/dialog_map_area.xml")

        assertTrue(marker.contains("""android:id="@+id/marker_conf_uncertain""""))
        assertTrue(area.contains("""android:id="@+id/area_conf_uncertain""""))
    }

    @Test
    fun `activity maps all creation source choices to typed domain values`() {
        val activity = source("java/app/peermesh/GroupMapActivity.kt")

        assertTrue(activity.contains("R.id.marker_conf_uncertain -> MapInformationSource.UNCERTAIN"))
        assertTrue(activity.contains("R.id.area_conf_uncertain -> MapInformationSource.UNCERTAIN"))
        assertTrue(activity.contains("mapCreationController.updateCreatorInformationSource(creatorInformationSource)"))
        assertTrue(activity.contains("MapKinds.markerCreationOptions(groupProfile, category)"))
        assertTrue(activity.contains("MapKinds.areaCreationOptions(groupProfile, category)"))
        assertTrue(activity.contains("R.string.map_creation_no_compatible_kinds"))
        assertTrue(activity.contains("kindSpinner.isEnabled = creationKindAvailable"))
        assertTrue(activity.contains("button.isEnabled = creationKindAvailable"))
    }

    private fun source(relative: String): String = File("src/main", relative).readText()
}
