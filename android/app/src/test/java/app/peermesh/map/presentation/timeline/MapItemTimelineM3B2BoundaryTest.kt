package app.peermesh.map.presentation.timeline

import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.timeline.DefaultMapItemActivityMergePolicy
import app.peermesh.map.application.timeline.LoadMapItemActivity
import app.peermesh.map.application.timeline.MapItemActivityCursor
import app.peermesh.map.application.timeline.MapItemActivityLoadResult
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemTimelineM3B2BoundaryTest {
    @Test fun controllerRejectsInvalidConfiguredPageSize() {
        listOf(0, -1).forEach { pageSize ->
            assertThrows(IllegalArgumentException::class.java) {
                DefaultMapItemTimelineController(
                    identity = MapItemIdentity("group", MapItemType.MARKER, "item"),
                    pageSize = pageSize,
                    loadMapItemActivity = FailingLoader,
                    mergePolicy = DefaultMapItemActivityMergePolicy(),
                    execution = ImmediateExecution,
                    observer = MapItemTimelineStateObserver {},
                )
            }
        }
    }

    @Test fun presentationControllerHasNoDaoRoomRepositoryOrCursorImplementationDependency() {
        val source = projectRoot().resolve(
            "app/src/main/java/app/peermesh/map/presentation/timeline/MapItemTimelineController.kt",
        ).readText()
        listOf(
            "MapItemActionDao",
            "RoomMapItemActionQueryRepository",
            "MapActionHistoryCursor",
            "DataMapItemActivityCursor",
            "MapActionOrderKey",
            "AppDatabase",
            "sortedBy",
            "sortedWith",
            ".sorted(",
        ).forEach { forbidden ->
            assertFalse("Controller contains forbidden dependency/operation: $forbidden", source.contains(forbidden))
        }
        assertTrue(source.contains("loadMapItemActivity.load(identity, after, pageSize)"))
        assertTrue(source.contains("page.entries.size < page.requestedLimit"))
    }

    @Test fun factoryUsesExistingM3B1ReadBoundaryAndPassesConfiguredDependencies() {
        val source = projectRoot().resolve(
            "app/src/main/java/app/peermesh/map/composition/MapItemTimelineControllerFactory.kt",
        ).readText()
        assertTrue(source.contains("MapItemActivityReadFactory.create(database)"))
        assertTrue(source.contains("mergePolicy = DefaultMapItemActivityMergePolicy()"))
        assertTrue(source.contains("pageSize = pageSize"))
        assertTrue(source.contains("pageSize: Int = DEFAULT_PAGE_SIZE"))
        assertTrue(source.contains("private const val DEFAULT_PAGE_SIZE = 20"))
        assertTrue(source.contains("execution = execution"))
        assertFalse(source.contains("mapItemActionDao()"))
        assertFalse(source.contains("RoomMapItemActionQueryRepository"))
    }

    @Test fun M3B3IntegrationKeepsControllersIndependentAndCurrentRoomSchema() {
        val root = projectRoot()
        listOf(
            root.resolve("app/src/main/java/app/peermesh/map/presentation/detail/MapItemDetailShellController.kt"),
            root.resolve("app/src/main/java/app/peermesh/GroupMapActivity.kt"),
        ).forEach { file ->
            assertFalse("Timeline controller leaked into ${file.name}", file.readText().contains("MapItemTimelineController"))
        }
        val database = root.resolve("data/src/main/java/app/peermesh/data/db/AppDatabase.kt")
        assertTrue(database.readText().contains("version = 55"))
    }

    private fun projectRoot(): File {
        val current = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(current) { it.parentFile }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: error("Android project root not found from $current")
    }

    private data object ImmediateExecution : MapItemTimelineExecution {
        override fun execute(block: () -> Unit): MapItemTimelineTask {
            block()
            return MapItemTimelineTask {}
        }

        override fun deliver(block: () -> Unit) = block()
    }

    private data object FailingLoader : LoadMapItemActivity {
        override fun load(
            identity: MapItemIdentity,
            after: MapItemActivityCursor?,
            limit: Int,
        ) = MapItemActivityLoadResult.InternalReadFailure
    }
}
