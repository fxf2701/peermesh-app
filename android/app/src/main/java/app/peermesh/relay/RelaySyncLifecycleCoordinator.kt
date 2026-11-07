package app.peermesh.relay

import android.content.Context
import app.peermesh.PeerMeshCapabilities

object RelaySyncLifecycleCoordinator {
    private enum class DesiredState {
        STOPPED,
        FOREGROUND_SERVICE,
        PERIODIC_ONLY,
    }

    @Volatile
    private var lastAppliedState: DesiredState? = null

    fun onEntitlementOrSettingsChanged(
        context: Context,
        lifecycleKnown: Boolean,
        appInForeground: Boolean,
    ) {
        applyDesiredState(
            context = context,
            desiredState = desiredStateForProcess(
                context = context,
                lifecycleKnown = lifecycleKnown,
                appInForeground = appInForeground,
            ),
        )
    }

    fun onProcessForegroundChanged(
        context: Context,
        lifecycleKnown: Boolean,
        appInForeground: Boolean,
    ) {
        applyDesiredState(
            context = context,
            desiredState = desiredStateForProcess(
                context = context,
                lifecycleKnown = lifecycleKnown,
                appInForeground = appInForeground,
            ),
        )
    }

    fun onUserRequestedImmediateSync(context: Context) {
        val appContext = context.applicationContext
        if (!PeerMeshCapabilities.canUseRelaySync || !RelaySyncSettings.isEnabled(appContext)) {
            applyDesiredState(appContext, DesiredState.STOPPED)
            return
        }
        RelaySyncSettings.markSyncAttempt(appContext)
        applyDesiredState(appContext, DesiredState.FOREGROUND_SERVICE)
    }

    fun onResumeWorkerTriggered(context: Context) {
        val appContext = context.applicationContext
        if (!PeerMeshCapabilities.canUseRelaySync || !RelaySyncSettings.isEnabled(appContext)) {
            applyDesiredState(appContext, DesiredState.STOPPED)
            return
        }
        val desiredState = when (RelaySyncSettings.load(appContext)) {
            SyncMode.BATTERY_SAVER -> DesiredState.PERIODIC_ONLY
            SyncMode.NORMAL -> DesiredState.FOREGROUND_SERVICE
        }
        applyDesiredState(appContext, desiredState)
    }

    fun onUserDisabledRelay(context: Context) {
        applyDesiredState(context.applicationContext, DesiredState.STOPPED)
    }

    fun onCapabilityUnavailable(context: Context) {
        applyDesiredState(context.applicationContext, DesiredState.STOPPED)
    }

    fun onBackgroundModeRequested(context: Context) {
        val appContext = context.applicationContext
        if (!PeerMeshCapabilities.canUseRelaySync || !RelaySyncSettings.isEnabled(appContext)) {
            applyDesiredState(appContext, DesiredState.STOPPED)
            return
        }
        applyDesiredState(appContext, DesiredState.PERIODIC_ONLY)
    }

    fun resetAppliedState() {
        lastAppliedState = null
    }

    private fun desiredStateForProcess(
        context: Context,
        lifecycleKnown: Boolean,
        appInForeground: Boolean,
    ): DesiredState {
        if (!PeerMeshCapabilities.canUseRelaySync) return DesiredState.STOPPED
        if (!lifecycleKnown) return lastAppliedState ?: DesiredState.STOPPED
        val appContext = context.applicationContext
        if (!RelaySyncSettings.isEnabled(appContext)) return DesiredState.STOPPED
        if (appInForeground) return DesiredState.FOREGROUND_SERVICE
        return when (RelaySyncSettings.load(appContext)) {
            SyncMode.BATTERY_SAVER -> DesiredState.PERIODIC_ONLY
            SyncMode.NORMAL -> DesiredState.FOREGROUND_SERVICE
        }
    }

    private fun applyDesiredState(
        context: Context,
        desiredState: DesiredState,
    ) {
        val appContext = context.applicationContext
        if (!shouldApplyDesiredState(desiredState)) return
        when (desiredState) {
            DesiredState.STOPPED -> {
                RelaySyncWorkManager.cancel(appContext)
                RelaySyncService.stop(appContext)
            }
            DesiredState.FOREGROUND_SERVICE -> {
                RelaySyncWorkManager.cancel(appContext)
                RelaySyncService.start(appContext, forceForeground = true)
            }
            DesiredState.PERIODIC_ONLY -> {
                RelaySyncService.stop(appContext)
                RelaySyncWorkManager.enqueue(appContext)
            }
        }
        lastAppliedState = desiredState
    }

    private fun shouldApplyDesiredState(desiredState: DesiredState): Boolean {
        val last = lastAppliedState
        val syncRunning = RelaySyncService.isSyncRunning()
        return when (desiredState) {
            DesiredState.STOPPED -> last != desiredState || syncRunning
            DesiredState.FOREGROUND_SERVICE -> last != desiredState || !syncRunning
            DesiredState.PERIODIC_ONLY -> last != desiredState || syncRunning
        }
    }
}
