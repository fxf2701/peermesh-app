package app.peermesh.map.application.verification

import app.peermesh.map.application.detail.MapItemDetailLoadResult

/** Pure M3C1 projection over one accepted M3A detail-load snapshot. */
fun interface ProjectMapItemVerification {
    fun project(detailResult: MapItemDetailLoadResult): MapItemVerificationProjectionResult
}
