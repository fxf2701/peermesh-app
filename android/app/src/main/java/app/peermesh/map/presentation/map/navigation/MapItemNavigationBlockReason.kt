package app.peermesh.map.presentation.map.navigation

/**
 * Presentation-orchestration reasons only.
 *
 * These values are not semantic processor rejections and must not be presented
 * as persistence, reducer, verification, or transport outcomes.
 */
enum class MapItemNavigationBlockReason {
    INVALID_IDENTITY,
    WRONG_GROUP,
    ITEM_NOT_SELECTABLE,
    CREATION_DRAFT_ACTIVE,
    ACTIVITY_NOT_ACTIVE,
    NAVIGATION_CLOSED,
}
