package app.peermesh.map.application.timeline

sealed interface MapItemActivityLoadResult {
    data class Loaded(val page: MapItemActivityPage) : MapItemActivityLoadResult
    data class InvalidIdentity(val reason: InvalidMapItemActivityIdentityReason) : MapItemActivityLoadResult
    data object InvalidPersistedHistory : MapItemActivityLoadResult
    data object InternalReadFailure : MapItemActivityLoadResult
}

enum class InvalidMapItemActivityIdentityReason {
    BLANK_GROUP_ID,
    BLANK_ITEM_ID,
    NON_POSITIVE_LIMIT,
    INVALID_CURSOR,
}
