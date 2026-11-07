package app.peermesh.map.application.timeline

import app.peermesh.data.map.MapItemActionQueryRepository
import app.peermesh.map.application.detail.MapItemIdentity
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemActivityM3B1BoundaryTest {
    @Test fun publicPresentationContractsExposeNoRoomOrM2OrderingInternals() {
        val contracts = listOf(
            MapItemActivityEntry::class.java,
            MapItemActivityActor::class.java,
            MapItemActivityType::class.java,
            MapItemActivityCursor::class.java,
            MapItemActivityPage::class.java,
            MapItemActivityLoadResult::class.java,
            MapItemActivityLoadResult.Loaded::class.java,
            MapItemActivityLoadResult.InvalidIdentity::class.java,
            InvalidMapItemActivityIdentityReason::class.java,
            LoadMapItemActivity::class.java,
        )
        val forbidden = listOf(
            ".data.db.entity.",
            ".data.db.dao.",
            "MapActionOrderKey",
            "MapActionHistoryCursor",
            "Reducer",
            "RoomMapEventProcessor",
            "LocalMapEventWriter",
            ".transport.",
            ".ble.",
            ".relay.",
            "Envelope",
        )

        contracts.forEach { contract ->
            val exposedTypes = buildList {
                contract.declaredFields.filter { Modifier.isPublic(it.modifiers) }.forEach { add(it.type.name) }
                contract.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.forEach { method ->
                    add(method.returnType.name)
                    addAll(method.parameterTypes.map { it.name })
                }
                contract.declaredConstructors.filter { Modifier.isPublic(it.modifiers) }.forEach { constructor ->
                    addAll(constructor.parameterTypes.map { it.name })
                }
            }
            forbidden.forEach { token ->
                assertFalse("$contract publicly exposes forbidden type token $token in $exposedTypes",
                    exposedTypes.any { it.contains(token) })
            }
        }

        assertTrue(MapItemActivityCursor::class.java.declaredFields.isEmpty())
        assertTrue(MapItemActivityCursor::class.java.declaredMethods.isEmpty())
        assertEquals(MapItemActivityCursor::class.java, MapItemActivityPage::class.java.getMethod("getNextCursor").returnType)
    }

    @Test fun timelineProjectionContainsOnlyHistoricalFieldsAndNoCurrentState() {
        assertEquals(
            setOf(
                "entryId", "itemIdentity", "type", "actor", "actionTimestampSeconds",
                "informationSource", "note", "updateDisposition", "targetLifecycle",
            ),
            MapItemActivityEntry::class.java.declaredFields.map { it.name }.toSet(),
        )
        val forbiddenNames = listOf(
            "confirmationCount", "acknowledgementCount", "currentLifecycle", "availability",
            "verification", "freshness", "score", "lamport", "order", "materialization", "rejection",
        )
        val publicNames = listOf(
            MapItemActivityEntry::class.java,
            MapItemActivityPage::class.java,
        ).flatMap { type ->
            type.declaredFields.map { it.name } + type.declaredMethods.map { it.name }
        }
        forbiddenNames.forEach { forbidden ->
            assertFalse("Current-state/order field leaked: $forbidden in $publicNames",
                publicNames.any { it.contains(forbidden, ignoreCase = true) })
        }
    }

    @Test fun loaderConstructorHasExactlyRepositoryAndProjectorDependencies() {
        assertEquals(
            setOf(MapItemActionQueryRepository::class.java, MapItemActivityProjector::class.java),
            DefaultLoadMapItemActivity::class.java.declaredConstructors.single().parameterTypes.toSet(),
        )
    }

    @Test fun productionMappingPreservesOrderWithoutSortingOrReversing() {
        val source = projectRoot().resolve(
            "app/src/main/java/app/peermesh/map/application/timeline/DefaultLoadMapItemActivity.kt",
        ).readText()
        listOf("sortedBy", "sortedWith", ".sorted(", ".reversed(", "reverse()", "sortBy", "sortWith").forEach { token ->
            assertFalse("Loader reconstructs repository order with $token", source.contains(token))
        }
        assertTrue(source.contains("for (action in result.value.actions)"))
    }

    @Test fun applicationScopeAddsNoTimelineUiSubmissionOrPersistenceWiring() {
        val root = projectRoot()
        val timelineSources = root.resolve("app/src/main/java/app/peermesh/map/application/timeline")
            .walkTopDown().filter { it.extension == "kt" }.toList()
        val combined = timelineSources.joinToString("\n") { it.readText() }
        listOf(
            "android.app.Activity", "androidx.fragment", "androidx.compose", "@Entity", "@Dao",
            "RoomMapEventProcessor", "LocalMapEventWriter", "EventCore", ".ble.", ".relay.", "Envelope",
        ).forEach { token -> assertFalse("Out-of-scope dependency $token", combined.contains(token)) }

        val detailController = root.resolve(
            "app/src/main/java/app/peermesh/map/presentation/detail/MapItemDetailShellController.kt",
        ).readText()
        assertFalse(detailController.contains("MapItemActivity"))
        assertFalse(detailController.contains("LoadMapItemActivity"))
    }

    @Test fun roomSchemaUsesCurrentVersion() {
        val databaseSource = projectRoot().resolve("data/src/main/java/app/peermesh/data/db/AppDatabase.kt").readText()
        assertTrue(databaseSource.contains("version = 55"))
    }

    @Test fun identityContractRemainsExactGroupTypeAndItemId() {
        assertEquals(
            setOf("groupId", "itemType", "itemId"),
            MapItemIdentity::class.java.declaredFields.map { it.name }.toSet(),
        )
    }

    private fun projectRoot(): File {
        val current = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(current) { it.parentFile }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: error("Android project root not found from $current")
    }
}
