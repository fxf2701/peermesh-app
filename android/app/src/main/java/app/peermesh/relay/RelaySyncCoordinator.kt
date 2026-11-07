package app.peermesh.relay

import android.content.Context
import app.peermesh.config.EnvironmentLoader
import app.peermesh.data.relay.RelaySyncScheduler

class RelaySyncCoordinator private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val runner = RelaySyncRunner(appContext)
    private var scheduler = RelaySyncScheduler(
        runOnce = { runner.runOnce().success }
    )
    private var intervalMs: Long = DEFAULT_INTERVAL_MS

    fun start(successIntervalMs: Long = DEFAULT_INTERVAL_MS) {
        val environment = EnvironmentLoader.load()
        val relayBaseUrls = RelaySyncSettings.resolveRelayBaseUrls(
            appContext,
            environment.relayBaseUrl,
        )
        val managedConfigured = ManagedRelayAccessCoordinator(appContext, environment).isConfigured()
        if (!RelaySyncUiEvaluator.hasConfiguredPath(relayBaseUrls.size, managedConfigured)) return
        if (intervalMs != successIntervalMs) {
            scheduler.shutdown()
            scheduler = RelaySyncScheduler(
                runOnce = { runner.runOnce().success },
                successIntervalMs = successIntervalMs,
            )
            intervalMs = successIntervalMs
        }
        scheduler.start()
    }

    fun stop() {
        scheduler.stop()
    }

    fun requestImmediateRun(): Boolean = scheduler.requestImmediateRun()

    companion object {
        @Volatile
        private var instance: RelaySyncCoordinator? = null
        private const val DEFAULT_INTERVAL_MS = 30_000L

        fun get(context: Context): RelaySyncCoordinator {
            return instance ?: synchronized(this) {
                instance ?: RelaySyncCoordinator(context).also { instance = it }
            }
        }
    }
}
