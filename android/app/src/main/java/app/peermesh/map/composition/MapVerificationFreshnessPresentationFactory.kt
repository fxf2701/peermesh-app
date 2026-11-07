package app.peermesh.map.composition

import app.peermesh.map.application.verification.DefaultMapVerificationFreshnessProjector
import app.peermesh.map.application.verification.ProjectMapVerificationFreshness

/** M3C2 composition boundary; intentionally has no persistence or clock dependency. */
object MapVerificationFreshnessPresentationFactory {
    fun create(): ProjectMapVerificationFreshness = DefaultMapVerificationFreshnessProjector()
}
