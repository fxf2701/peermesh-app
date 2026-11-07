package app.peermesh.core.map

/** TTL, owner editing, and member interaction are independent domain windows. */
data class MapItemWindows(
    val expiresAtSeconds: Long?,
    val ownerEditUntilSeconds: Long?,
    val interactionUntilSeconds: Long?,
)

object MapItemTemporalPolicy {
    fun areWindowsValid(windows: MapItemWindows): Boolean {
        if (windows.expiresAtSeconds != null && windows.expiresAtSeconds <= 0L) return false
        if (windows.ownerEditUntilSeconds != null && windows.ownerEditUntilSeconds <= 0L) return false
        if (windows.interactionUntilSeconds != null && windows.interactionUntilSeconds <= 0L) return false
        val interactionEnd = windows.interactionUntilSeconds ?: return true
        val expiry = windows.expiresAtSeconds ?: return false
        return interactionEnd >= expiry
    }

    fun isWithinActiveTime(windows: MapItemWindows, nowSeconds: Long): Boolean =
        windows.expiresAtSeconds == null || windows.expiresAtSeconds > nowSeconds

    private fun isWithinOwnerEditWindow(windows: MapItemWindows, nowSeconds: Long): Boolean =
        windows.ownerEditUntilSeconds == null || windows.ownerEditUntilSeconds > nowSeconds

    private fun isWithinInteractionWindow(windows: MapItemWindows, nowSeconds: Long): Boolean =
        windows.interactionUntilSeconds == null || windows.interactionUntilSeconds > nowSeconds

    fun canOwnerEditSituation(
        windows: MapItemWindows,
        lifecycleStatus: MapSituationLifecycleStatus,
        nowSeconds: Long,
    ): Boolean = areWindowsValid(windows) &&
        lifecycleStatus == MapSituationLifecycleStatus.ACTIVE &&
        isWithinActiveTime(windows, nowSeconds) &&
        isWithinOwnerEditWindow(windows, nowSeconds)

    fun canInteractWithSituation(
        windows: MapItemWindows,
        lifecycleStatus: MapSituationLifecycleStatus,
        nowSeconds: Long,
    ): Boolean = areWindowsValid(windows) &&
        lifecycleStatus == MapSituationLifecycleStatus.ACTIVE &&
        isWithinActiveTime(windows, nowSeconds) &&
        isWithinInteractionWindow(windows, nowSeconds)

    fun canOwnerEditCoordination(windows: MapItemWindows, nowSeconds: Long): Boolean =
        areWindowsValid(windows) &&
            isWithinActiveTime(windows, nowSeconds) &&
            isWithinOwnerEditWindow(windows, nowSeconds)

    fun canInteractWithCoordination(windows: MapItemWindows, nowSeconds: Long): Boolean =
        areWindowsValid(windows) &&
            isWithinActiveTime(windows, nowSeconds) &&
            isWithinInteractionWindow(windows, nowSeconds)
}
