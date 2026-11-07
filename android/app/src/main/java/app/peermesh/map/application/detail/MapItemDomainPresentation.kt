package app.peermesh.map.application.detail

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.data.db.MapItemTemporalOrigin
import app.peermesh.data.map.MapItemWindows
import app.peermesh.data.map.MapSituationConfirmationFreshnessBasis

/** Thin presentation copy of the accepted M2 domain read model. */
data class MapItemDomainPresentation(
    val category: MapItemCategory?,
    val categoryOrigin: MapItemCategoryOrigin,
    val creatorInformationSource: MapInformationSource?,
    val lifecycle: MapSituationLifecycleStatus?,
    val windows: MapItemWindows,
    val temporalOrigin: MapItemTemporalOrigin,
    val confirmationActorCount: Int,
    val acknowledgementActorCount: Int,
    /** Safe classified metadata only; never an M2 ordering tuple. */
    val confirmationFreshnessBasis: MapSituationConfirmationFreshnessBasis =
        MapSituationConfirmationFreshnessBasis.Unknown,
) {
    val isUnresolvedLegacyCategory: Boolean
        get() = categoryOrigin == MapItemCategoryOrigin.UNRESOLVED
}
