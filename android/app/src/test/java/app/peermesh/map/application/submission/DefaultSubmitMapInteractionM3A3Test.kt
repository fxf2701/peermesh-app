package app.peermesh.map.application.submission

import app.peermesh.core.map.MapItemType
import app.peermesh.core.model.EventCore
import app.peermesh.data.map.local.LocalMapEventWriter
import app.peermesh.data.map.local.LocalMapWriteOutcome
import app.peermesh.data.map.local.LocalMapWriteRequest
import app.peermesh.data.map.processing.MapEventRejectionReason
import app.peermesh.map.application.detail.MapItemIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSubmitMapInteractionM3A3Test {
    private val command = ConfirmSituationCommand(MapItemIdentity("group", MapItemType.MARKER, "item"))
    private val context = MapEventConstructionContext("trusted", "event", 10L, 11L, 12L)
    private val productionFactory = DefaultMapInteractionEventFactoryAdapter()

    @Test fun `accepted submission follows exact order and preserves final event`() {
        val calls = mutableListOf<String>()
        var contextCount = 0
        var factoryCount = 0
        lateinit var written: LocalMapWriteRequest
        lateinit var transported: EventCore
        val useCase = DefaultSubmitMapInteraction(
            inputValidator = MapInteractionInputValidator { calls += "validate"; MapInteractionInputValidationResult.Valid },
            constructionContextProvider = MapEventConstructionContextProvider {
                calls += "context"; contextCount++; context
            },
            eventFactory = MapInteractionEventFactoryAdapter { submitted, trusted ->
                calls += "factory"; factoryCount++; productionFactory.create(submitted, trusted)
            },
            keyContextAttacher = MapEventKeyContextAttacher { _, core -> calls += "attach"; core.copy() },
            localWriter = LocalMapEventWriter { request ->
                calls += "write"; written = request; LocalMapWriteOutcome.AcceptedNew(request.core.id)
            },
            postCommitTransport = MapInteractionPostCommitTransport { groupId, outcome, core ->
                calls += "transport"
                assertEquals("group", groupId)
                assertTrue(outcome is LocalMapWriteOutcome.AcceptedNew)
                transported = core
                MapInteractionPostCommitResult.Enqueued
            },
            outcomeMapper = DefaultMapInteractionOutcomeMapper(),
        )

        assertEquals(MapInteractionSubmissionResult.Accepted("event"), useCase.submit(command))
        assertEquals(listOf("validate", "context", "factory", "attach", "write", "transport"), calls)
        assertEquals(1, contextCount)
        assertEquals(1, factoryCount)
        assertEquals("group", written.gid)
        assertEquals("trusted", written.localActorDid)
        assertEquals(12L, written.insertedAtSeconds)
        assertSame(written.core, transported)
    }

    @Test fun `canonical root is attached before validation write and transport`() {
        lateinit var written: EventCore
        val useCase = DefaultSubmitMapInteraction(
            inputValidator = DefaultMapInteractionInputValidator(),
            constructionContextProvider = MapEventConstructionContextProvider { context },
            eventFactory = productionFactory,
            keyContextAttacher = MapEventKeyContextAttacher { _, core -> core },
            localWriter = LocalMapEventWriter {
                written = it.core
                LocalMapWriteOutcome.AcceptedNew(it.core.id)
            },
            postCommitTransport = MapInteractionPostCommitTransport { _, _, _ ->
                MapInteractionPostCommitResult.Enqueued
            },
            outcomeMapper = DefaultMapInteractionOutcomeMapper(),
            canonicalRootResolver = MapCanonicalRootResolver { "canonical-add" },
        )

        assertTrue(useCase.submit(command) is MapInteractionSubmissionResult.Accepted)
        assertEquals("canonical-add", written.ref.root)
    }

    @Test fun `invalid input stops all downstream work`() {
        val calls = mutableListOf<String>()
        val useCase = useCase(
            validator = MapInteractionInputValidator {
                calls += "validate"
                MapInteractionInputValidationResult.Invalid(MapInteractionInputFailure.BLANK_ITEM_ID)
            },
            contextProvider = MapEventConstructionContextProvider { calls += "context"; context },
            factory = MapInteractionEventFactoryAdapter { submitted, trusted ->
                calls += "factory"; productionFactory.create(submitted, trusted)
            },
            attacher = MapEventKeyContextAttacher { _, core -> calls += "attach"; core },
            writer = LocalMapEventWriter { calls += "write"; LocalMapWriteOutcome.AcceptedNew("event") },
            transport = MapInteractionPostCommitTransport { _, _, _ ->
                calls += "transport"; MapInteractionPostCommitResult.Enqueued
            },
        )

        assertEquals(
            MapInteractionSubmissionResult.InvalidInput(MapInteractionInputFailure.BLANK_ITEM_ID),
            useCase.submit(command),
        )
        assertEquals(listOf("validate"), calls)
    }

    @Test fun `structurally invalid factory event never reaches key attachment or writer`() {
        var attached = false
        var written = false
        val valid = productionFactory.create(command, context)
        val result = useCase(
            factory = MapInteractionEventFactoryAdapter { _, _ -> valid.copy(id = "") },
            attacher = MapEventKeyContextAttacher { _, core -> attached = true; core },
            writer = LocalMapEventWriter { written = true; LocalMapWriteOutcome.AcceptedNew("event") },
        ).submit(command)

        assertEquals(MapInteractionSubmissionFailure.INVALID_CONSTRUCTED_EVENT,
            (result as MapInteractionSubmissionResult.Failed).reason)
        assertTrue(!attached && !written)
    }

    @Test fun `invalid trusted context is safe local-state failure`() {
        val result = useCase(contextProvider = MapEventConstructionContextProvider {
            context.copy(actorDid = "")
        }).submit(command)
        assertEquals(MapInteractionSubmissionFailure.INVALID_LOCAL_STATE,
            (result as MapInteractionSubmissionResult.Failed).reason)
    }

    @Test fun `factory exception is safe and never reaches writer`() {
        var written = false
        val result = useCase(
            factory = MapInteractionEventFactoryAdapter { _, _ -> throw IllegalArgumentException("codec") },
            writer = LocalMapEventWriter { written = true; LocalMapWriteOutcome.AcceptedNew("event") },
        ).submit(command)
        assertEquals(MapInteractionSubmissionFailure.INVALID_CONSTRUCTED_EVENT,
            (result as MapInteractionSubmissionResult.Failed).reason)
        assertTrue(!written)
    }

    @Test(expected = FatalTestError::class)
    fun `fatal construction error is not swallowed`() {
        useCase(
            contextProvider = MapEventConstructionContextProvider { throw FatalTestError() },
        ).submit(command)
    }

    @Test fun `transport runs only for newly accepted local write`() {
        val nonNew = listOf<LocalMapWriteOutcome>(
            LocalMapWriteOutcome.AcceptedNoChange("event"),
            LocalMapWriteOutcome.ExactDuplicate("event"),
            LocalMapWriteOutcome.ConflictingDuplicate("event"),
            LocalMapWriteOutcome.SemanticRejected("event", MapEventRejectionReason.TARGET_DELETED),
            LocalMapWriteOutcome.InvalidPersistedState("event", "private"),
            LocalMapWriteOutcome.PersistenceFailure("event", IllegalStateException()),
        )
        nonNew.forEach { outcome ->
            var transports = 0
            useCase(
                writer = LocalMapEventWriter { outcome },
                transport = MapInteractionPostCommitTransport { _, _, _ ->
                    transports++; MapInteractionPostCommitResult.Enqueued
                },
            ).submit(command)
            assertEquals(outcome::class.java.simpleName, 0, transports)
        }
    }

    @Test fun `post-commit failure remains accepted and does not regenerate identity`() {
        var contexts = 0
        var factories = 0
        val result = useCase(
            contextProvider = MapEventConstructionContextProvider { contexts++; context },
            factory = MapInteractionEventFactoryAdapter { submitted, trusted ->
                factories++; productionFactory.create(submitted, trusted)
            },
            transport = MapInteractionPostCommitTransport { _, _, _ -> throw IllegalStateException("outbox") },
        ).submit(command)

        assertEquals(1, contexts)
        assertEquals(1, factories)
        assertEquals("event", (result as MapInteractionSubmissionResult.Accepted).eventId)
        assertEquals(MapInteractionDeliveryWarning.ENQUEUE_FAILED, result.deliveryWarning)
    }

    @Test fun `writer exception maps separately from semantic rejection`() {
        val result = useCase(writer = LocalMapEventWriter { throw IllegalStateException("db") }).submit(command)
        assertEquals(MapInteractionSubmissionFailure.PERSISTENCE_FAILURE,
            (result as MapInteractionSubmissionResult.Failed).reason)
    }

    @Test(expected = FatalTestError::class)
    fun `fatal writer error is not mapped to persistence failure`() {
        useCase(writer = LocalMapEventWriter { throw FatalTestError() }).submit(command)
    }

    @Test(expected = FatalTestError::class)
    fun `fatal transport error is not converted into enqueue warning`() {
        useCase(
            transport = MapInteractionPostCommitTransport { _, _, _ -> throw FatalTestError() },
        ).submit(command)
    }

    private fun useCase(
        validator: MapInteractionInputValidator = DefaultMapInteractionInputValidator(),
        contextProvider: MapEventConstructionContextProvider = MapEventConstructionContextProvider { context },
        factory: MapInteractionEventFactoryAdapter = productionFactory,
        attacher: MapEventKeyContextAttacher = MapEventKeyContextAttacher { _, core -> core },
        writer: LocalMapEventWriter = LocalMapEventWriter { LocalMapWriteOutcome.AcceptedNew(it.core.id) },
        transport: MapInteractionPostCommitTransport = MapInteractionPostCommitTransport { _, _, _ ->
            MapInteractionPostCommitResult.Enqueued
        },
    ) = DefaultSubmitMapInteraction(
        validator,
        contextProvider,
        factory,
        attacher,
        writer,
        transport,
        DefaultMapInteractionOutcomeMapper(),
    )

    private class FatalTestError : Error()
}
