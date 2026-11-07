package app.peermesh.data.map.replay.m2d4

import app.peermesh.data.map.processing.MapEventProcessingRequest

/**
 * Fresh-database deterministic-replay verifier. It delegates every event to the production
 * processor and never writes canonical, action, or domain state itself.
 */
internal class M2D4DeterministicReplayHarness(
    private val environmentFactory: () -> M2D4ReplayEnvironment,
    private val failureHook: M2D4FailureHook = M2D4FailureHook.None,
) {
    fun replay(fixtures: List<DurableMapReplayFixture>): M2D4ReplayEvidence =
        environmentFactory().use { environment ->
            val results = fixtures.map { fixture ->
                failureHook.beforeProcessor(fixture)
                environment.processor.process(
                    MapEventProcessingRequest(
                        gid = fixture.gid,
                        core = fixture.core,
                        source = fixture.originalSource,
                        insertedAtSeconds = fixture.originalInsertedAtSeconds,
                    ),
                )
            }
            val snapshots = fixtures.map { it.gid }.distinct().sorted().associateWith {
                environment.snapshotReader.read(it)
            }
            M2D4ReplayEvidence(results, snapshots)
        }
}
