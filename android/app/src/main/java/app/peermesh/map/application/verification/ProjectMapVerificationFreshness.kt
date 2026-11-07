package app.peermesh.map.application.verification

/** Pure M3C2 projection over one accepted M3C1 result. */
fun interface ProjectMapVerificationFreshness {
    fun project(
        verificationResult: MapItemVerificationProjectionResult,
    ): MapVerificationFreshnessProjectionResult
}
