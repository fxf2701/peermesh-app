package app.peermesh.data.map.processing

import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemKindCatalog
import app.peermesh.core.map.MapItemType
import app.peermesh.data.map.MapItemMaterializationSnapshot

/**
 * Pure canonical/domain consistency guard shared by live action processing and pending replay.
 * It deliberately preserves unresolved legacy semantics instead of inferring a category or lifecycle.
 */
internal object DedicatedMapActionTargetSnapshotValidator {
    fun isConsistent(
        gid: String,
        itemType: MapItemType,
        itemId: String,
        canonicalOwnerDid: String,
        canonicalKind: String?,
        canonicalExpires: Long?,
        snapshot: MapItemMaterializationSnapshot,
    ): Boolean {
        if (snapshot.gid != gid || snapshot.identity.itemType != itemType ||
            snapshot.identity.itemId != itemId || snapshot.ownerDid != canonicalOwnerDid ||
            snapshot.windows.expiresAt != canonicalExpires
        ) return false
        val categoryFromKind = MapItemKindCatalog.categoryFor(canonicalKind)
        if (snapshot.category != null && snapshot.category != categoryFromKind) return false
        if (snapshot.category == MapItemCategory.SITUATION && snapshot.lifecycle == null) return false
        if (snapshot.category == MapItemCategory.COORDINATION && snapshot.lifecycle != null) return false
        return true
    }
}
