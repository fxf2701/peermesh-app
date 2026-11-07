package app.peermesh.map.application.creation

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.data.map.local.LocalMapEventWriter
import app.peermesh.data.map.local.LocalMapWriteOutcome
import app.peermesh.data.map.processing.MapEventRejectionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmitMapCreationM3D3Test {
    private val intent = MapCreationIntent.Marker(
        "group-a", MapItemCategory.SITUATION, "risk", "R", "note", 60L,
        MapInformationSource.FIRSTHAND, 10.0, 20.0, null,
    )
    private val productionFactory = DefaultMapCreationEventFactoryAdapter()

    @Test fun `accepted flow preserves captured group and delivery success`() {
        val calls = mutableListOf<String>()
        var writtenGroup = ""
        val result = useCase(
            contextProvider = MapCreationConstructionContextProvider { group, _ ->
                calls += "context:$group"
                context("item-1")
            },
            writer = LocalMapEventWriter {
                calls += "write"
                writtenGroup = it.gid
                LocalMapWriteOutcome.AcceptedNew(it.core.id)
            },
            transport = MapCreationPostCommitTransport { group, _, _ ->
                calls += "transport:$group"
                MapCreationDeliveryState.ENQUEUED
            },
        ).submit(intent)
        assertEquals("group-a", writtenGroup)
        assertEquals(
            MapCreationSubmissionResult.Accepted("item-1", MapCreationDeliveryState.ENQUEUED),
            result,
        )
        assertEquals(listOf("context:group-a", "write", "transport:group-a"), calls)
    }

    @Test fun `post commit delivery warnings preserve exact reason and local acceptance`() {
        val expected = listOf(
            MapCreationDeliveryState.MISSING_GROUP_KEY,
            MapCreationDeliveryState.ENQUEUE_REJECTED,
        )
        expected.forEach { delivery ->
            val result = useCase(
                transport = MapCreationPostCommitTransport { _, _, _ -> delivery },
            ).submit(intent)
            assertEquals(
                MapCreationSubmissionResult.Accepted("item", delivery),
                result,
            )
        }

        val transportFailure = useCase(
            transport = MapCreationPostCommitTransport { _, _, _ ->
                throw IllegalStateException("outbox")
            },
        ).submit(intent)
        assertEquals(
            MapCreationSubmissionResult.Accepted("item", MapCreationDeliveryState.TRANSPORT_FAILURE),
            transportFailure,
        )
    }

    @Test fun `invalid input never creates context or calls writer`() {
        var contextCalled = false
        var written = false
        val invalid = MapCreationIntent.Marker(
            "", MapItemCategory.SITUATION, "risk", "R", null, null,
            MapInformationSource.FIRSTHAND, 0.0, 0.0, null,
        )
        val result = useCase(
            contextProvider = MapCreationConstructionContextProvider { _, _ ->
                contextCalled = true
                context("item")
            },
            writer = LocalMapEventWriter {
                written = true
                LocalMapWriteOutcome.AcceptedNew("event")
            },
        ).submit(invalid)
        assertTrue(result is MapCreationSubmissionResult.InvalidInput)
        assertFalse(contextCalled)
        assertFalse(written)
    }

    @Test fun `writer outcomes remain typed and transport only follows accepted new`() {
        val outcomes = listOf(
            LocalMapWriteOutcome.AcceptedNoChange("event"),
            LocalMapWriteOutcome.ExactDuplicate("event"),
            LocalMapWriteOutcome.ConflictingDuplicate("event"),
            LocalMapWriteOutcome.SemanticRejected("event", MapEventRejectionReason.ACTOR_NOT_MEMBER),
            LocalMapWriteOutcome.PersistenceFailure("event", IllegalStateException("db")),
        )
        outcomes.forEach { outcome ->
            var transported = false
            val result = useCase(
                writer = LocalMapEventWriter { outcome },
                transport = MapCreationPostCommitTransport { _, _, _ ->
                    transported = true
                    MapCreationDeliveryState.ENQUEUED
                },
            ).submit(intent)
            assertFalse(outcome::class.java.simpleName, transported)
            when (outcome) {
                is LocalMapWriteOutcome.AcceptedNoChange,
                is LocalMapWriteOutcome.ExactDuplicate,
                -> assertTrue(result is MapCreationSubmissionResult.AlreadyRecorded)
                is LocalMapWriteOutcome.ConflictingDuplicate,
                is LocalMapWriteOutcome.SemanticRejected,
                -> assertTrue(result is MapCreationSubmissionResult.Rejected)
                is LocalMapWriteOutcome.PersistenceFailure ->
                    assertEquals(
                        MapCreationSubmissionFailure.PERSISTENCE_FAILURE,
                        (result as MapCreationSubmissionResult.Failed).reason,
                    )
                else -> error("unexpected")
            }
        }
    }

    @Test fun `ordinary exceptions map by boundary and fresh context is used per attempt`() {
        var sequence = 0
        val ids = mutableListOf<String>()
        val useCase = useCase(
            contextProvider = MapCreationConstructionContextProvider { _, _ ->
                context("item-${++sequence}")
            },
            writer = LocalMapEventWriter {
                ids += it.core.ref.target!!
                LocalMapWriteOutcome.AcceptedNew(it.core.id)
            },
        )
        useCase.submit(intent)
        useCase.submit(intent)
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])

        val factoryFailure = useCase(factory = MapCreationEventFactoryAdapter { _, _ ->
            throw IllegalArgumentException("factory")
        }).submit(intent)
        assertEquals(
            MapCreationSubmissionFailure.INVALID_CONSTRUCTED_EVENT,
            (factoryFailure as MapCreationSubmissionResult.Failed).reason,
        )
        val writerFailure = useCase(writer = LocalMapEventWriter {
            throw IllegalStateException("db")
        }).submit(intent)
        assertEquals(
            MapCreationSubmissionFailure.PERSISTENCE_FAILURE,
            (writerFailure as MapCreationSubmissionResult.Failed).reason,
        )
    }

    @Test(expected = FatalTestError::class)
    fun `fatal error propagates`() {
        useCase(writer = LocalMapEventWriter { throw FatalTestError() }).submit(intent)
    }

    @Test(expected = FatalTestError::class)
    fun `fatal post commit error propagates`() {
        useCase(
            transport = MapCreationPostCommitTransport { _, _, _ -> throw FatalTestError() },
        ).submit(intent)
    }

    private fun useCase(
        validator: ValidateMapCreationIntent =
            DefaultValidateMapCreationIntent(DefaultMapCreationCompatibilityPolicy()),
        contextProvider: MapCreationConstructionContextProvider =
            MapCreationConstructionContextProvider { _, _ -> context("item") },
        factory: MapCreationEventFactoryAdapter = productionFactory,
        writer: LocalMapEventWriter =
            LocalMapEventWriter { LocalMapWriteOutcome.AcceptedNew(it.core.id) },
        transport: MapCreationPostCommitTransport =
            MapCreationPostCommitTransport { _, _, _ -> MapCreationDeliveryState.ENQUEUED },
    ) = DefaultSubmitMapCreation(
        validator,
        contextProvider,
        factory,
        MapCreationKeyContextAttacher { _, core -> core },
        writer,
        transport,
    )

    private fun context(itemId: String) =
        MapCreationConstructionContext("actor", "event-$itemId", itemId, 10L, 11L, 12L)

    private class FatalTestError : Error()
}
