package app.peermesh.map.application.scene

import app.peermesh.map.application.detail.MapItemIdentity

sealed interface MapSceneItemLoadResult {
    val identity: MapItemIdentity

    data class Found(val item: MapSceneItem) : MapSceneItemLoadResult {
        override val identity: MapItemIdentity = item.identity
    }

    data class CanonicalOnly(val item: MapSceneItem) : MapSceneItemLoadResult {
        override val identity: MapItemIdentity = item.identity
    }

    data class UnresolvedLegacy(val item: MapSceneItem) : MapSceneItemLoadResult {
        override val identity: MapItemIdentity = item.identity
    }

    data class Missing(override val identity: MapItemIdentity) : MapSceneItemLoadResult
    data class InvalidCanonical(override val identity: MapItemIdentity) : MapSceneItemLoadResult
    data class InvalidDomain(override val identity: MapItemIdentity) : MapSceneItemLoadResult
}

sealed interface MapSceneLoadResult {
    data class Success(val items: List<MapSceneItemLoadResult>) : MapSceneLoadResult
    data class InvalidRequest(val reason: MapSceneInvalidRequestReason) : MapSceneLoadResult
    data class InternalFailure(val failure: MapSceneLoadFailure) : MapSceneLoadResult
}

sealed interface MapSceneInvalidRequestReason {
    data object BlankGroupId : MapSceneInvalidRequestReason
    data object InvalidIdentity : MapSceneInvalidRequestReason
    data object GroupMismatch : MapSceneInvalidRequestReason
    data object TooManyIdentities : MapSceneInvalidRequestReason
}

sealed interface MapSceneLoadFailure {
    data object CanonicalReadUnavailable : MapSceneLoadFailure
    data object DomainStateReadUnavailable : MapSceneLoadFailure
    data object ClockUnavailable : MapSceneLoadFailure
    data object InvalidRepositoryResult : MapSceneLoadFailure
}
