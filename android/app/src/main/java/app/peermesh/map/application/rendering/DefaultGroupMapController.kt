package app.peermesh.map.application.rendering

import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.scene.LoadMapSceneItems
import app.peermesh.map.application.scene.MapSceneInvalidRequestReason
import app.peermesh.map.application.scene.MapSceneItem
import app.peermesh.map.application.scene.MapSceneItemLoadResult
import app.peermesh.map.application.scene.MapSceneLoadResult
import java.util.concurrent.Executor

/**
 * M3D2 owner of viewport-scoped scene loading and ephemeral map presentation state.
 *
 * Implementation constraints:
 * - M3D1 LoadMapSceneItems is the only semantic read dependency;
 * - protect publication with both request-generation and closed-state guards;
 * - retain the last accepted content on ordinary refresh failure;
 * - selection/focus changes rebuild render specs without repository reads;
 * - cancellation is best effort and is never the only stale-result protection;
 * - catch ordinary Exception only; fatal Error subclasses must propagate.
 */
class DefaultGroupMapController(
    private val loadMapSceneItems: LoadMapSceneItems,
    private val visualPolicy: MapItemVisualPolicy,
    private val backgroundExecutor: Executor,
    private val presentationExecutor: Executor,
) : GroupMapController {
    private val lock = Any()
    private var state = GroupMapState.initial()
    private var latestRequestedId = 0L
    private var renderRevision = 0L
    private var closed = false
    private val listeners = linkedSetOf<GroupMapStateListener>()

    override fun currentState(): GroupMapState = synchronized(lock) { state }

    override fun refresh(request: GroupMapViewportRequest) {
        val requestCopy = request.copy(identities = request.identities.toSet())
        val requestId: Long
        synchronized(lock) {
            if (closed) return
            requestId = ++latestRequestedId
        }
        val validation = validate(requestCopy)
        if (validation != null) {
            publishFailure(
                requestId,
                GroupMapFailure.InvalidRequest(validation),
                isLatestRequired = true,
            )
            return
        }
        publishLoading(requestId)
        backgroundExecutor.execute {
            val result = try {
                loadMapSceneItems.load(requestCopy.groupId, requestCopy.identities)
            } catch (_: Exception) {
                null
            }
            presentationExecutor.execute presentation@{
                if (!isLatestOpen(requestId)) return@presentation
                if (result == null) {
                    acceptFailure(requestId, GroupMapFailure.ExecutionFailure(GroupMapOperation.LOAD_SCENE))
                } else {
                    acceptLoadResult(requestId, requestCopy, result)
                }
            }
        }
    }

    override fun select(identity: MapItemIdentity?) = updateSelection(identity, selected = true)

    override fun focus(identity: MapItemIdentity?) = updateSelection(identity, selected = false)

    override fun reset() {
        presentationExecutor.execute {
            val next = synchronized(lock) {
                if (closed) return@execute
                ++latestRequestedId
                acceptedEnvironment = MapRenderEnvironment(MapZoomBand.FAR, MapDensityBand.SPARSE)
                GroupMapState.initial().also { state = it }
            }
            notifyListeners(next)
        }
    }

    override fun addListener(listener: GroupMapStateListener) {
        synchronized(lock) {
            if (!closed) listeners += listener
        }
    }

    override fun removeListener(listener: GroupMapStateListener) {
        synchronized(lock) { listeners -= listener }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            listeners.clear()
        }
    }

    private fun validate(request: GroupMapViewportRequest): MapSceneInvalidRequestReason? = when {
        request.groupId.isBlank() -> MapSceneInvalidRequestReason.BlankGroupId
        request.identities.size > MAX_IDENTITIES -> MapSceneInvalidRequestReason.TooManyIdentities
        request.identities.any { it.groupId.isBlank() || it.itemId.isBlank() } ->
            MapSceneInvalidRequestReason.InvalidIdentity
        request.identities.any { it.groupId != request.groupId } ->
            MapSceneInvalidRequestReason.GroupMismatch
        else -> null
    }

    private fun publishLoading(requestId: Long) {
        presentationExecutor.execute presentation@{
            val next = synchronized(lock) {
                if (closed || requestId != latestRequestedId) return@presentation
                state.copy(
                    activeRequestId = requestId,
                    operation = GroupMapOperationState.Loading(requestId),
                ).also { state = it }
            }
            notifyListeners(next)
        }
    }

    private fun publishFailure(
        requestId: Long,
        failure: GroupMapFailure,
        isLatestRequired: Boolean,
    ) {
        presentationExecutor.execute presentation@{
            if (isLatestRequired && !isLatestOpen(requestId)) return@presentation
            acceptFailure(requestId, failure)
        }
    }

    private fun acceptFailure(requestId: Long, failure: GroupMapFailure) {
        val next = synchronized(lock) {
            if (closed || requestId != latestRequestedId) return
            val retained = state.content is GroupMapContentState.Accepted
            state.copy(
                activeRequestId = null,
                operation = GroupMapOperationState.Failed(requestId, failure, retained),
            ).also { state = it }
        }
        notifyListeners(next)
    }

    private fun acceptLoadResult(
        requestId: Long,
        request: GroupMapViewportRequest,
        result: MapSceneLoadResult,
    ) {
        when (result) {
            is MapSceneLoadResult.InvalidRequest ->
                acceptFailure(requestId, GroupMapFailure.InvalidRequest(result.reason))
            is MapSceneLoadResult.InternalFailure ->
                acceptFailure(requestId, GroupMapFailure.ReadFailure(result.failure))
            is MapSceneLoadResult.Success -> acceptSuccess(requestId, request, result)
        }
    }

    private fun acceptSuccess(
        requestId: Long,
        request: GroupMapViewportRequest,
        result: MapSceneLoadResult.Success,
    ) {
        if (!isLatestOpen(requestId)) return
        val previousSelection = synchronized(lock) { state.selection }
        val (normalizedItems, normalizedOutcomes, rebuilt, selection) = try {
            val normalized = normalize(request.identities, result.items)
            val built = buildSnapshot(
                request.groupId, request.environment, normalized.first, normalized.second, previousSelection,
            )
            val renderable = built.snapshot.items.mapTo(hashSetOf()) { it.identity }
            val selection = GroupMapSelectionState(
                selectedIdentity = previousSelection.selectedIdentity?.takeIf { it in renderable },
                focusedIdentity = previousSelection.focusedIdentity?.takeIf { it in renderable },
            )
            val rebuilt = if (selection == previousSelection) built else buildSnapshot(
                request.groupId, request.environment, normalized.first, normalized.second, selection,
            )
            Quadruple(normalized.first, normalized.second, rebuilt, selection)
        } catch (_: Exception) {
            acceptFailure(requestId, GroupMapFailure.ExecutionFailure(GroupMapOperation.BUILD_RENDER_SNAPSHOT))
            return
        }
        val failure = rebuilt.failures.takeIf { it.isNotEmpty() }?.let {
            GroupMapFailure.VisualPolicyFailure(it)
        }
        val next = synchronized(lock) {
            if (closed || requestId != latestRequestedId) return
            acceptedEnvironment = request.environment
            state = GroupMapState(
                acceptedRequestId = requestId,
                activeRequestId = null,
                content = GroupMapContentState.Accepted(
                    request.groupId,
                    normalizedItems,
                    normalizedOutcomes,
                ),
                operation = if (failure == null) GroupMapOperationState.Idle else {
                    GroupMapOperationState.Failed(requestId, failure, retainedContent = true)
                },
                selection = selection,
                renderSnapshot = rebuilt.snapshot,
            )
            state
        }
        notifyListeners(next)
    }

    private fun normalize(
        requested: Set<MapItemIdentity>,
        results: List<MapSceneItemLoadResult>,
    ): Pair<Map<MapItemIdentity, MapSceneItem>, Map<MapItemIdentity, MapSceneItemOutcome>> {
        val items = linkedMapOf<MapItemIdentity, MapSceneItem>()
        val outcomes = requested.associateWithTo(linkedMapOf()) { MapSceneItemOutcome.MISSING }
        val seen = mutableSetOf<MapItemIdentity>()
        results.forEach { result ->
            if (result.identity !in requested) return@forEach
            require(seen.add(result.identity)) { "duplicate scene result for ${result.identity}" }
            when (result) {
                is MapSceneItemLoadResult.Found -> {
                    items[result.identity] = result.item
                    outcomes[result.identity] = MapSceneItemOutcome.FOUND
                }
                is MapSceneItemLoadResult.CanonicalOnly -> {
                    items[result.identity] = result.item
                    outcomes[result.identity] = MapSceneItemOutcome.CANONICAL_ONLY
                }
                is MapSceneItemLoadResult.UnresolvedLegacy -> {
                    items[result.identity] = result.item
                    outcomes[result.identity] = MapSceneItemOutcome.UNRESOLVED_LEGACY
                }
                is MapSceneItemLoadResult.Missing -> outcomes[result.identity] = MapSceneItemOutcome.MISSING
                is MapSceneItemLoadResult.InvalidCanonical ->
                    outcomes[result.identity] = MapSceneItemOutcome.INVALID_CANONICAL
                is MapSceneItemLoadResult.InvalidDomain ->
                    outcomes[result.identity] = MapSceneItemOutcome.INVALID_DOMAIN
            }
        }
        return items.toMap() to outcomes.toMap()
    }

    private data class BuiltSnapshot(
        val snapshot: MapRenderSnapshot,
        val failures: Map<MapItemIdentity, MapVisualPolicyFailure>,
    )

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun buildSnapshot(
        groupId: String,
        environment: MapRenderEnvironment,
        items: Map<MapItemIdentity, MapSceneItem>,
        outcomes: Map<MapItemIdentity, MapSceneItemOutcome>,
        selection: GroupMapSelectionState,
    ): BuiltSnapshot {
        val specs = mutableListOf<MapItemRenderSpec>()
        val failures = linkedMapOf<MapItemIdentity, MapVisualPolicyFailure>()
        items.keys.sortedWith(IDENTITY_COMPARATOR).forEach { identity ->
            val emphasis = when {
                identity == selection.selectedIdentity && identity == selection.focusedIdentity ->
                    MapItemEmphasis.SELECTED_AND_FOCUSED
                identity == selection.focusedIdentity -> MapItemEmphasis.FOCUSED
                identity == selection.selectedIdentity -> MapItemEmphasis.SELECTED
                else -> MapItemEmphasis.NORMAL
            }
            when (val evaluated = visualPolicy.evaluate(
                items.getValue(identity),
                emphasis,
                environment,
                outcomes.getValue(identity),
            )) {
                is MapItemVisualPolicyResult.Renderable -> specs += evaluated.spec
                is MapItemVisualPolicyResult.Unsupported -> failures[identity] = evaluated.reason
            }
        }
        return BuiltSnapshot(
            MapRenderSnapshot(++renderRevision, groupId, specs.toList()),
            failures.toMap(),
        )
    }

    private fun updateSelection(identity: MapItemIdentity?, selected: Boolean) {
        presentationExecutor.execute presentation@{
            val captured = synchronized(lock) {
                if (closed) return@presentation
                val content = state.content as? GroupMapContentState.Accepted ?: return@presentation
                val currentSnapshot = state.renderSnapshot ?: return@presentation
                if (identity != null && currentSnapshot.items.none { it.identity == identity }) {
                    return@presentation
                }
                val nextSelection = if (selected) {
                    state.selection.copy(selectedIdentity = identity)
                } else {
                    state.selection.copy(focusedIdentity = identity)
                }
                if (nextSelection == state.selection) return@presentation
                SelectionCapture(content, acceptedEnvironment, nextSelection, currentSnapshot, state.acceptedRequestId)
            }
            val built = try {
                buildSnapshot(
                    captured.content.groupId, captured.environment, captured.content.items,
                    captured.content.itemOutcomes, captured.selection,
                )
            } catch (_: Exception) {
                val failed = synchronized(lock) {
                    if (closed || state.renderSnapshot !== captured.snapshot || state.acceptedRequestId != captured.requestId) return@presentation
                    state.copy(operation = GroupMapOperationState.Failed(
                        captured.requestId ?: 0L,
                        GroupMapFailure.ExecutionFailure(GroupMapOperation.BUILD_RENDER_SNAPSHOT), true,
                    )).also { state = it }
                }
                notifyListeners(failed)
                return@presentation
            }
            val next = synchronized(lock) {
                if (closed || state.renderSnapshot !== captured.snapshot || state.acceptedRequestId != captured.requestId) return@presentation
                state.copy(
                    selection = captured.selection,
                    renderSnapshot = built.snapshot,
                    operation = if (built.failures.isEmpty()) state.operation else GroupMapOperationState.Failed(
                        captured.requestId ?: 0L, GroupMapFailure.VisualPolicyFailure(built.failures), true,
                    ),
                ).also { state = it }
            }
            notifyListeners(next)
        }
    }

    private data class SelectionCapture(
        val content: GroupMapContentState.Accepted,
        val environment: MapRenderEnvironment,
        val selection: GroupMapSelectionState,
        val snapshot: MapRenderSnapshot,
        val requestId: Long?,
    )

    private var acceptedEnvironment = MapRenderEnvironment(MapZoomBand.FAR, MapDensityBand.SPARSE)

    private fun isLatestOpen(requestId: Long): Boolean =
        synchronized(lock) { !closed && requestId == latestRequestedId }

    private fun notifyListeners(next: GroupMapState) {
        val targets = synchronized(lock) {
            if (closed) return
            listeners.toList()
        }
        targets.forEach { listener ->
            try {
                listener.onStateChanged(next)
            } catch (_: Exception) {
                // One UI observer cannot prevent delivery to the other observers.
            }
        }
    }

    private companion object {
        const val MAX_IDENTITIES = 1_000
        val IDENTITY_COMPARATOR = compareBy<MapItemIdentity>(
            { it.groupId },
            { it.itemType.wireValue },
            { it.itemId },
        )
    }
}
