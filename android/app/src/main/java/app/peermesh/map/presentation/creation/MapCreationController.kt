package app.peermesh.map.presentation.creation

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.map.application.creation.MapCreationIntent
import app.peermesh.map.application.creation.MapCreationShape
import app.peermesh.map.application.creation.MapCreationSubmissionFailure
import app.peermesh.map.application.creation.MapCreationSubmissionResult
import app.peermesh.map.application.creation.SubmitMapCreation
import app.peermesh.map.application.scene.MapSceneAreaGeometry
import app.peermesh.map.application.scene.MapSceneCoordinate
import java.io.Closeable
import java.util.concurrent.Executor

fun interface MapCreationStateListener {
    fun onStateChanged(state: MapCreationState)
}

/**
 * Android-independent owner of one scoped ephemeral creation draft.
 * Runtime overlays are consequences rendered from state; they are not draft authority.
 */
interface MapCreationController : Closeable {
    fun currentState(): MapCreationState
    fun begin(groupId: String, category: MapItemCategory, shape: MapCreationShape)
    fun updateCategory(category: MapItemCategory)
    fun updateKind(kind: String?)
    fun updateLabel(label: String?)
    fun updateNote(note: String?)
    fun updateTtlSeconds(ttlSeconds: Long?)
    fun updateCreatorInformationSource(source: MapInformationSource?)
    fun updateMarker(latitude: Double, longitude: Double, radiusMeters: Double?)
    fun updateArea(geometry: MapSceneAreaGeometry)
    fun submit(onStarted: (MapCreationSubmissionId) -> Unit = {}): MapCreationSubmissionId?
    fun cancel()
    fun consumeSubmissionFeedback(submissionId: MapCreationSubmissionId)
    fun addListener(listener: MapCreationStateListener)
    fun removeListener(listener: MapCreationStateListener)
}

/** Thread-safe single-draft controller with request and generation ownership. */
class DefaultMapCreationController(
    private val submitter: SubmitMapCreation,
    private val backgroundExecutor: Executor,
    private val publicationExecutor: Executor = Executor(Runnable::run),
) : MapCreationController {
    private val lock = Any()
    private var state = MapCreationState()
    private var nextDraftId = 0L
    private var nextRequestId = 0L
    private var generation = 0L
    private var closed = false
    private val listeners = linkedSetOf<MapCreationStateListener>()

    override fun currentState(): MapCreationState = synchronized(lock) { state.snapshot() }

    override fun begin(groupId: String, category: MapItemCategory, shape: MapCreationShape) {
        update {
            generation += 1
            val geometry = when (shape) {
                MapCreationShape.MARKER -> MapCreationDraftGeometry.Marker(Double.NaN, Double.NaN)
                MapCreationShape.AREA -> MapCreationDraftGeometry.Area(MapSceneAreaGeometry.Polygon(emptyList()))
            }
            MapCreationState(
                draft = MapCreationDraft(++nextDraftId, groupId, category, shape, geometry = geometry),
            )
        }
    }

    override fun updateCategory(category: MapItemCategory) = updateDraft { it.copy(category = category) }
    override fun updateKind(kind: String?) = updateDraft { it.copy(kind = kind) }
    override fun updateLabel(label: String?) = updateDraft { it.copy(label = label) }
    override fun updateNote(note: String?) = updateDraft { it.copy(note = note) }
    override fun updateTtlSeconds(ttlSeconds: Long?) = updateDraft { it.copy(ttlSeconds = ttlSeconds) }
    override fun updateCreatorInformationSource(source: MapInformationSource?) =
        updateDraft { it.copy(creatorInformationSource = source) }

    override fun updateMarker(latitude: Double, longitude: Double, radiusMeters: Double?) =
        updateDraft(MapCreationShape.MARKER) {
            it.copy(geometry = MapCreationDraftGeometry.Marker(latitude, longitude, radiusMeters))
        }

    override fun updateArea(geometry: MapSceneAreaGeometry) =
        updateDraft(MapCreationShape.AREA) {
            it.copy(geometry = MapCreationDraftGeometry.Area(geometry.copyGeometry()))
        }

    override fun submit(onStarted: (MapCreationSubmissionId) -> Unit): MapCreationSubmissionId? {
        val request = synchronized(lock) {
            if (closed || state.operation is MapCreationOperationState.Submitting) return null
            val draft = state.draft ?: return null
            val intent = draft.toIntent()
            if (intent == null) {
                state = state.copy(
                    draft = draft.copy(
                        validation = MapCreationDraftValidation.Invalid(MapCreationDraftFailure.INCOMPLETE_FORM),
                    ),
                    feedback = MapCreationFeedback.DraftRejected(MapCreationDraftFailure.INCOMPLETE_FORM),
                )
                null
            } else {
                val submissionId = MapCreationSubmissionId(draft.draftId, ++nextRequestId)
                val token = SubmissionToken(submissionId, generation, intent)
                state = state.copy(
                    draft = draft.copy(validation = MapCreationDraftValidation.Valid),
                    operation = MapCreationOperationState.Submitting(submissionId),
                    feedback = null,
                )
                token
            }
        }
        if (request == null) {
            publish()
            return null
        }
        onStarted(request.submissionId)
        publish()
        try {
            backgroundExecutor.execute {
                val result = try {
                    submitter.submit(request.intent)
                } catch (_: Exception) {
                    internalFailureResult()
                }
                complete(request, result)
            }
        } catch (_: Exception) {
            complete(request, internalFailureResult())
        }
        return request.submissionId
    }

    override fun cancel() {
        update {
            generation += 1
            MapCreationState()
        }
    }

    override fun consumeSubmissionFeedback(submissionId: MapCreationSubmissionId) = update { old ->
        val feedback = old.feedback as? MapCreationFeedback.Submission
        if (feedback?.submissionId == submissionId) old.copy(feedback = null) else old
    }

    override fun addListener(listener: MapCreationStateListener) {
        val snapshot = synchronized(lock) {
            if (closed) return
            listeners += listener
            state.snapshot()
        }
        publicationExecutor.execute { listener.onStateChanged(snapshot) }
    }

    override fun removeListener(listener: MapCreationStateListener) {
        synchronized(lock) { listeners -= listener }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation += 1
            listeners.clear()
            state = MapCreationState()
        }
    }

    private fun complete(token: SubmissionToken, result: MapCreationSubmissionResult) {
        val shouldPublish = synchronized(lock) {
            if (closed || token.generation != generation) return@synchronized false
            val operation = state.operation as? MapCreationOperationState.Submitting
                ?: return@synchronized false
            if (operation.submissionId != token.submissionId ||
                state.draft?.draftId != token.submissionId.draftId
            ) {
                return@synchronized false
            }
            val accepted = result is MapCreationSubmissionResult.Accepted ||
                result is MapCreationSubmissionResult.AlreadyRecorded
            state = state.copy(
                draft = if (accepted) null else state.draft,
                operation = MapCreationOperationState.Idle,
                feedback = MapCreationFeedback.Submission(token.submissionId, result),
            )
            true
        }
        if (shouldPublish) publish()
    }

    private fun internalFailureResult() = MapCreationSubmissionResult.Failed(
        itemId = null,
        reason = MapCreationSubmissionFailure.INTERNAL_FAILURE,
    )

    private fun update(transform: (MapCreationState) -> MapCreationState) {
        val changed = synchronized(lock) {
            if (closed) return
            state = transform(state).snapshot()
            true
        }
        if (changed) publish()
    }

    private fun updateDraft(
        expectedShape: MapCreationShape? = null,
        transform: (MapCreationDraft) -> MapCreationDraft,
    ) = update { old ->
        val draft = old.draft ?: return@update old
        if (expectedShape != null && draft.shape != expectedShape) return@update old
        old.copy(
            draft = transform(draft).copy(validation = MapCreationDraftValidation.NotValidated).snapshot(),
            feedback = null,
        )
    }

    private fun publish() {
        val publication = synchronized(lock) {
            if (closed) return
            state.snapshot() to listeners.toList()
        }
        publicationExecutor.execute {
            publication.second.forEach { listener -> listener.onStateChanged(publication.first) }
        }
    }

    private fun MapCreationDraft.toIntent(): MapCreationIntent? {
        val exactKind = kind ?: return null
        return when (val value = geometry) {
            is MapCreationDraftGeometry.Marker -> MapCreationIntent.Marker(
                groupId, category, exactKind, label, note, ttlSeconds, creatorInformationSource,
                value.latitude, value.longitude, value.radiusMeters,
            )
            is MapCreationDraftGeometry.Area -> MapCreationIntent.Area(
                groupId, category, exactKind, label, note, ttlSeconds, creatorInformationSource,
                value.geometry.copyGeometry(),
            )
        }
    }

    private fun MapCreationState.snapshot(): MapCreationState = copy(draft = draft?.snapshot())
    private fun MapCreationDraft.snapshot(): MapCreationDraft = copy(
        geometry = when (val value = geometry) {
            is MapCreationDraftGeometry.Marker -> value.copy()
            is MapCreationDraftGeometry.Area -> value.copy(geometry = value.geometry.copyGeometry())
        },
    )

    private fun MapSceneAreaGeometry.copyGeometry(): MapSceneAreaGeometry = when (this) {
        is MapSceneAreaGeometry.Polygon -> MapSceneAreaGeometry.Polygon(
            rings.map { ring -> ring.map(MapSceneCoordinate::copy) },
        )
        is MapSceneAreaGeometry.MultiPolygon -> MapSceneAreaGeometry.MultiPolygon(
            polygons.map { polygon -> polygon.map { ring -> ring.map(MapSceneCoordinate::copy) } },
        )
    }

    private data class SubmissionToken(
        val submissionId: MapCreationSubmissionId,
        val generation: Long,
        val intent: MapCreationIntent,
    )
}
