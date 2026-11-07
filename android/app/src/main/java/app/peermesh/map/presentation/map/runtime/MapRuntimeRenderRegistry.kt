package app.peermesh.map.presentation.map.runtime

import app.peermesh.map.application.detail.MapItemIdentity

/** Exact-identity SDK artifact cache. It is never semantic state authority. */
class MapRuntimeRenderRegistry {
    private val artifacts = mutableMapOf<MapItemIdentity, MapRuntimeArtifactBundle>()

    fun get(identity: MapItemIdentity): MapRuntimeArtifactBundle? = artifacts[identity]

    fun put(
        identity: MapItemIdentity,
        bundle: MapRuntimeArtifactBundle,
    ): MapRuntimeArtifactBundle? {
        require(identity == bundle.identity) { "registry key must match bundle identity" }
        return artifacts.put(identity, bundle)
    }

    fun remove(identity: MapItemIdentity): MapRuntimeArtifactBundle? = artifacts.remove(identity)

    fun identities(): Set<MapItemIdentity> = artifacts.keys.toSet()

    fun clear(): List<MapRuntimeArtifactBundle> {
        val removed = artifacts.values.toList()
        artifacts.clear()
        return removed
    }

    fun size(): Int = artifacts.size
}
