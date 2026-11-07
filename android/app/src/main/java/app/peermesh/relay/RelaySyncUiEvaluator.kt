package app.peermesh.relay

enum class RelayIndicatorState {
    OFF,
    PAUSED,
    ON,
}

object RelaySyncUiEvaluator {
    fun hasConfiguredPath(
        directRelayCount: Int,
        managedConfigured: Boolean,
    ): Boolean {
        return directRelayCount > 0 || managedConfigured
    }

    fun indicatorState(
        relayAvailable: Boolean,
        relayEnabled: Boolean,
        internetOnline: Boolean,
        hasRecentSuccess: Boolean,
        directRelayCount: Int,
        managedConfigured: Boolean,
    ): RelayIndicatorState {
        if (!relayAvailable || !relayEnabled || !internetOnline) {
            return RelayIndicatorState.OFF
        }
        if (hasRecentSuccess) {
            return RelayIndicatorState.ON
        }
        return if (hasConfiguredPath(directRelayCount, managedConfigured)) {
            RelayIndicatorState.ON
        } else {
            RelayIndicatorState.PAUSED
        }
    }
}
