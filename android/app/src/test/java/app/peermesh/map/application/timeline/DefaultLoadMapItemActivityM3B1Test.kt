package app.peermesh.map.application.timeline

import app.peermesh.core.map.MapItemAction
import app.peermesh.core.map.MapItemType
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.map.MapActionHistoryCursor
import app.peermesh.data.map.MapActionHistoryPage
import app.peermesh.data.map.MapItemActionQueryRepository
import app.peermesh.data.map.MapItemActionRead
import app.peermesh.data.map.MapItemIdentity as DataMapItemIdentity
import app.peermesh.data.map.MapQueryResult
import app.peermesh.map.application.detail.MapItemIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultLoadMapItemActivityM3B1Test {
    @Test fun queriesExactGroupTypeItemIdOnceAndPreservesRepositoryOrder() {
        val rows = listOf(row("third", 30), row("first", 10), row("second", 20))
        val repository = FakeRepository(success(rows))
        val result = loader(repository).load(IDENTITY, limit = 3) as MapItemActivityLoadResult.Loaded

        assertEquals(1, repository.calls.size)
        assertEquals("group-1", repository.calls.single().gid)
        assertEquals(DataMapItemIdentity(MapItemType.MARKER, "same-id"), repository.calls.single().identity)
        assertNull(repository.calls.single().after)
        assertEquals(3, repository.calls.single().limit)
        assertEquals(listOf("third", "first", "second"), result.page.entries.map { it.entryId })
    }

    @Test fun preservesCompleteIncomingAndOutgoingCursorAcrossCalls() {
        val incoming = MapActionHistoryCursor(MapActionOrderKey(500, 41, "did:cursor", "cursor-event"))
        val outgoing = MapActionHistoryCursor(MapActionOrderKey(400, 39, "did:next", "next-event"))
        val repository = FakeRepository(success(listOf(row("event", 450)), outgoing))
        val useCase = loader(repository)

        val first = useCase.load(IDENTITY, DataMapItemActivityCursor(incoming), 1) as MapItemActivityLoadResult.Loaded
        assertSame(incoming, repository.calls.single().after)
        val publicCursor = checkNotNull(first.page.nextCursor)
        assertTrue(publicCursor is DataMapItemActivityCursor)
        assertSame(outgoing, (publicCursor as DataMapItemActivityCursor).value)

        repository.result = success(emptyList())
        useCase.load(IDENTITY, publicCursor, 1)
        assertSame(outgoing, repository.calls[1].after)
    }

    @Test fun successfulEmptyPageIsLoaded() {
        val result = loader(FakeRepository(success(emptyList()))).load(IDENTITY, limit = 20)
            as MapItemActivityLoadResult.Loaded
        assertTrue(result.page.entries.isEmpty())
        assertNull(result.page.nextCursor)
        assertEquals(20, result.page.requestedLimit)
        assertTrue(!result.page.mayHaveMore)
    }

    @Test fun malformedRowFailsWholePageWithoutSkipping() {
        val repository = FakeRepository(success(listOf(row("valid", 20), row("bad", 0), row("later", 10))))
        assertEquals(MapItemActivityLoadResult.InvalidPersistedHistory, loader(repository).load(IDENTITY, limit = 3))
        assertEquals(1, repository.calls.size)
    }

    @Test fun invalidHistoryForOneTargetDoesNotCorruptASeparateLoad() {
        val repository = FakeRepository(success(listOf(row("wrong", 10).copy(gid = "other-group"))))
        val useCase = loader(repository)
        assertEquals(MapItemActivityLoadResult.InvalidPersistedHistory, useCase.load(IDENTITY, limit = 1))

        val areaIdentity = MapItemIdentity("group-1", MapItemType.AREA, "same-id")
        repository.result = success(listOf(row("area-ok", 20).copy(
            identity = DataMapItemIdentity(MapItemType.AREA, "same-id"),
        )))
        val areaResult = useCase.load(areaIdentity, limit = 1) as MapItemActivityLoadResult.Loaded
        assertEquals(listOf("area-ok"), areaResult.page.entries.map { it.entryId })
        assertEquals(DataMapItemIdentity(MapItemType.AREA, "same-id"), repository.calls.last().identity)
    }

    @Test fun invalidApplicationInputAndForeignCursorFailBeforeRepositoryRead() {
        val cases = listOf(
            Triple(IDENTITY.copy(groupId = "  "), null, 10) to InvalidMapItemActivityIdentityReason.BLANK_GROUP_ID,
            Triple(IDENTITY.copy(itemId = "\t"), null, 10) to InvalidMapItemActivityIdentityReason.BLANK_ITEM_ID,
            Triple(IDENTITY, null, 0) to InvalidMapItemActivityIdentityReason.NON_POSITIVE_LIMIT,
            Triple(IDENTITY, null, -1) to InvalidMapItemActivityIdentityReason.NON_POSITIVE_LIMIT,
            Triple(IDENTITY, ForeignCursor, 10) to InvalidMapItemActivityIdentityReason.INVALID_CURSOR,
        )
        cases.forEach { (input, expected) ->
            val repository = FakeRepository(success(emptyList()))
            val result = loader(repository).load(input.first, input.second, input.third)
            assertEquals(MapItemActivityLoadResult.InvalidIdentity(expected), result)
            assertTrue(repository.calls.isEmpty())
        }
    }

    @Test fun validationOrderPrefersIdentityThenLimitThenCursor() {
        val repository = FakeRepository(success(emptyList()))
        assertEquals(
            MapItemActivityLoadResult.InvalidIdentity(InvalidMapItemActivityIdentityReason.BLANK_GROUP_ID),
            loader(repository).load(IDENTITY.copy(groupId = ""), ForeignCursor, 0),
        )
        assertEquals(
            MapItemActivityLoadResult.InvalidIdentity(InvalidMapItemActivityIdentityReason.NON_POSITIVE_LIMIT),
            loader(repository).load(IDENTITY, ForeignCursor, 0),
        )
        assertTrue(repository.calls.isEmpty())
    }

    @Test fun mapsRepositoryInvalidRecordAndInvalidInputDistinctly() {
        val failure = PersistenceParseResult.InvalidRecordShape("bad persisted row")
        val invalidRecord = FakeRepository(MapQueryResult.InvalidRecord(failure))
        assertEquals(
            MapItemActivityLoadResult.InvalidPersistedHistory,
            loader(invalidRecord).load(IDENTITY, limit = 10),
        )

        val invalidInput = FakeRepository(MapQueryResult.InvalidInput(failure))
        assertEquals(
            MapItemActivityLoadResult.InternalReadFailure,
            loader(invalidInput).load(IDENTITY, limit = 10),
        )
    }

    @Test fun mapsOrdinaryRepositoryAndProjectorExceptionsToInternalReadFailure() {
        val repositoryFailure = FakeRepository(success(emptyList()), thrown = IllegalStateException("unavailable"))
        assertEquals(
            MapItemActivityLoadResult.InternalReadFailure,
            loader(repositoryFailure).load(IDENTITY, limit = 10),
        )

        val projectorFailure = DefaultLoadMapItemActivity(
            FakeRepository(success(listOf(row("event", 10)))),
            MapItemActivityProjector { _, _ -> throw IllegalArgumentException("bad projection") },
        )
        assertEquals(MapItemActivityLoadResult.InternalReadFailure, projectorFailure.load(IDENTITY, limit = 10))
    }

    @Test fun fatalJvmErrorsPropagate() {
        listOf(AssertionError("assertion"), OutOfMemoryError("oom")).forEach { fatal ->
            val repository = FakeRepository(success(emptyList()), thrown = fatal)
            val thrown = assertThrows(fatal::class.java) { loader(repository).load(IDENTITY, limit = 10) }
            assertSame(fatal, thrown)
        }
    }

    private fun loader(repository: MapItemActionQueryRepository) =
        DefaultLoadMapItemActivity(repository, DefaultMapItemActivityProjector())

    private class FakeRepository(
        var result: MapQueryResult<MapActionHistoryPage>,
        private val thrown: Throwable? = null,
    ) : MapItemActionQueryRepository {
        val calls = mutableListOf<Call>()

        override fun appliedHistory(
            gid: String,
            identity: DataMapItemIdentity,
            after: MapActionHistoryCursor?,
            limit: Int,
        ): MapQueryResult<MapActionHistoryPage> {
            calls += Call(gid, identity, after, limit)
            thrown?.let { throw it }
            return result
        }

        override fun pendingTarget(gid: String, identity: DataMapItemIdentity) = unused<List<MapItemActionRead>>()
        override fun scanPending(gid: String, after: MapActionOrderKey?, limit: Int) = unused<List<MapItemActionRead>>()
        override fun latestApplied(gid: String, identity: DataMapItemIdentity) = unused<MapItemActionRead?>()
        override fun latestApplied(gid: String, identity: DataMapItemIdentity, action: MapItemAction) = unused<MapItemActionRead?>()
        override fun latestMaterialChange(gid: String, identity: DataMapItemIdentity) = unused<MapItemActionRead?>()
        override fun confirmationActorCount(gid: String, identity: DataMapItemIdentity, after: MapActionOrderKey?) = unused<Int>()
        override fun acknowledgementActorCount(gid: String, identity: DataMapItemIdentity) = unused<Int>()

        private fun <T> unused(): MapQueryResult<T> = error("unexpected repository method")
    }

    private data class Call(
        val gid: String,
        val identity: DataMapItemIdentity,
        val after: MapActionHistoryCursor?,
        val limit: Int,
    )

    private data object ForeignCursor : MapItemActivityCursor

    companion object {
        private val IDENTITY = MapItemIdentity("group-1", MapItemType.MARKER, "same-id")

        private fun row(eventId: String, timestamp: Long) = MapItemActionRead(
            gid = "group-1",
            identity = DataMapItemIdentity(MapItemType.MARKER, "same-id"),
            action = MapItemAction.CONFIRM,
            order = MapActionOrderKey(timestamp, 1, "did:$eventId", eventId),
            informationSource = null,
            note = null,
            updateDisposition = null,
            targetLifecycle = null,
        )

        private fun success(
            rows: List<MapItemActionRead>,
            nextCursor: MapActionHistoryCursor? = null,
        ): MapQueryResult<MapActionHistoryPage> = MapQueryResult.Success(MapActionHistoryPage(rows, nextCursor))
    }
}
