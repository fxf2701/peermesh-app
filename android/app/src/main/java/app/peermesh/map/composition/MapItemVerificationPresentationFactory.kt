package app.peermesh.map.composition

import app.peermesh.map.application.verification.DefaultMapItemVerificationProjector
import app.peermesh.map.application.verification.ProjectMapItemVerification

/** M3C1 composition boundary; intentionally requires no persistence dependency. */
object MapItemVerificationPresentationFactory {
    fun create(): ProjectMapItemVerification = DefaultMapItemVerificationProjector()
}
