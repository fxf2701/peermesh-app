package app.peermesh

import app.peermesh.data.db.entity.MapItemVoteEntity
import app.peermesh.data.db.entity.TrailPointEntity

/** Narrow deterministic seams used only by GroupMapActivity instrumentation. */
internal object GroupMapActivityTestSeams {
    @Volatile
    var bypassFeatureGate: Boolean = false

    @Volatile
    var suppressBackgroundMapLoads: Boolean = false

    @Volatile
    var voteLoader: ((String, String, String) -> MapItemVoteEntity?)? = null

    @Volatile
    var onVoteApplied: (() -> Unit)? = null

    @Volatile
    var onVoteResultDelivered: (() -> Unit)? = null

    @Volatile
    var trailPointLoader: ((String) -> List<TrailPointEntity>)? = null

    @Volatile
    var delayedScheduler: ((Runnable, Long) -> Unit)? = null

    @Volatile
    var onTrailPointResultDelivered: (() -> Unit)? = null

    fun reset() {
        bypassFeatureGate = false
        suppressBackgroundMapLoads = false
        voteLoader = null
        onVoteApplied = null
        onVoteResultDelivered = null
        trailPointLoader = null
        delayedScheduler = null
        onTrailPointResultDelivered = null
    }
}
